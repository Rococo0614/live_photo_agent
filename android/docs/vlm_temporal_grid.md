# VLM 时序拼图（Temporal Grid）方案

记录端侧 Live Photo 动作语义分析的关键约束、当前取舍与待验证实验。

## 1. 要解决的问题

Live Photo 是「静态封面 + 1.5~3 秒短视频」。用户需要的是**动作语义**（谁在做什么、运动趋势），
而不是单帧的内容描述。

早期实现对每帧各跑一次 VLM，再把多段 summary 拼接。这个做法有根本缺陷：
**每次推理只看到一张静止图，模型在物理上不可能推断出动作变化**。
实测同一场景输出 `motion_tags: ["running","walking","dancing"]`，属于纯幻觉。

## 2. 硬约束：QNN 视觉图的 patch 预算是编译期固定的

部署的 `vision_encoder.bin` 输入形状写死在图里：

```
pixel_values:     [1024, 1536]   uint16
position_ids_cos: [1024, 32]     uint16
vision-param:     height 32, width 32
```

1024 = 32×32 patch 网格，对应单张 **512×512** 输入（Qwen3-VL `patch_size=16`）。
QNN HTP 是静态图，运行时不能改维度。

更关键的是 `position_ids_cos/sin` 与 `window/full_attention_mask` 都是
**为 32×32 网格预计算的固定 RoPE 位置编码**（1024×32×float32 = 131072 字节）。
一旦改变画布尺寸或长宽比，位置编码就会与实际 patch 错位。

> 结论：曾考虑过 864×288 的横向三帧拼接，**该方案不可行**——它会让预计算的
> RoPE 位置编码错位。画布必须保持 512×512 / 32×32 网格。

## 3. 当前方案：2×2 网格拼图，单次推理

在 1024 patch 预算内塞进 4 帧：

```
512×512 画布 → 2×2 网格，每格 256×256
每格 256/16 = 16×16 = 256 patch
4 格 × 256 = 1024 patch  ✓ 正好用满预算
```

- 抽帧时间点：`0% / 33% / 66% / 100%`（`LivePhotoPreprocessor.extractFrames`）
- 阅读顺序即时间顺序：左上 → 右上 → 左下 → 右下
- prompt 中的布局描述由**实际帧数推导**，不写死

### 相比多帧多次推理的收益

| | 旧（3 次隔离推理） | 新（1 次拼图推理） |
|---|---|---|
| 时序感知 | 无，每次只见静止图 | 有，可直接对比姿态变化 |
| 索引耗时 | ×3 | ×1 |
| 单素材耗时 | 45~60 秒 | 15~20 秒 |

### 实测效果对比

同一「猫躺沙发」素材，grid-v1/v2（3 帧拼图）实测：

| 字段 | 旧单帧（实测） | 3 帧拼图（实测） | 4 帧拼图（预期） |
|---|---|---|---|
| `motion_tags` | `["running","walking","dancing"]` 幻觉 | `["静止","无运动"]` 正确 | 主体绑定双语对 |
| `frames_analyzed` | 1 | 3 | 4 |
| 第四格 | — | 留黑，曾致「左下角缺失」幻觉 | 填满，无空格 |

模型已能区分「真静止」与「真运动」，这是动作语义生效的直接证据。
4 帧方案已在 grid-v3 实测：102/102 条 `frames_analyzed=4`，无空格幻觉。

## 4. 已知代价：分辨率损失（创新点 / 待实验）

拼图的代价是**单帧有效分辨率从 512×512 降到 256×256**，即每帧像素面积只剩 1/4。

这是 patch 预算固定下的必然取舍：

```
时序信息 ↑  ←→  单帧分辨率 ↓      （总量受 1024 patch 约束）
```

### 待验证的实验方向

1. **不同分辨率下的动作识别准确率**
   256×256 是否足以识别细粒度动作（手指动作、表情变化）？
   对大幅度动作（跑跳、转身）预计影响小。

2. **重新导出更大 patch 预算的视觉图**
   用 QAIRT 把 `pixel_values` 第一维从 1024 提到 4096（4×1024），
   即可 4 帧各保持 512×512 全分辨率。代价：
   - `vision_encoder.bin` 体积约 430MB → 1.3GB
   - 单次推理时间约 ×3~4
   - 需重新走完整的 QNN 导出与验证流程

3. **帧数与分辨率的其他组合**
   2 帧 × 512×256（横向对半）、8 帧 × 128×128 等，
   在时序密度与空间细节之间寻找更优点。

4. **采样点密度的影响**
   Live Photo 仅 1.5~3 秒，4 帧是否足够捕捉关键动作瞬间？
   可对比 4 帧 vs 8 帧（需配合方案 2 扩大 patch 预算）。

### 当前决策

**先跑通闭环，暂不扩大 patch 预算。** 256×256 对「识别动作趋势」这一目标已验证可用，
而重新导出图的成本（体积 ×3、推理 ×3、验证流程）应在闭环稳定后再评估。

## 5. 实现要点与踩坑记录

### 5.1 布局描述必须由帧数推导

早期 prompt 写死「右下为空白」，3 帧时正确，改 4 帧后就成了错误信息，
主动误导模型。更严重的是：**prompt 与画面不一致时，模型会去描述排版而非内容**，
实测输出过「该图像由四帧构成，但左下角缺失…左上为第1帧，右上为第2帧…」，
把布局说明原样复述进了 `summary`。

由于 `summary` 是 BGE 检索文本的主体，这类污染会直接破坏语义检索。

修复：`gridLayoutSentence(frameCount)` 按实际帧数生成描述，
并在 prompt 中显式禁止 `summary` 提及拼图、网格、帧序号、方位词。

### 5.2 100% 时刻抽帧需 clamp

`getFrameAtTime(durationUs)` 在部分设备上正好越界返回 null，
导致第 4 格空白、重新引入「缺失」幻觉。实现中 clamp 为 `durationUs - 1`。

### 5.3 帧解码失败时的行为

若某帧 `getFrameAtTime` 返回 null，该帧被丢弃，调用方收到不足 4 帧。
这是**安全的**而非静默错误：幸存帧保持相对顺序，`stitchFrames` 按列表顺序填格，
而 `gridLayoutSentence(frameCount)` 会根据实际帧数生成布局描述，
因此空格总是被如实告知模型，不会被误认为画面内容。

> 更正记录：本节初稿曾声称"压缩索引会导致时序错乱、动作方向反转"，
> 该论断有误。`filterNotNull` 保持相对顺序，且 `stitchFrames` 不读文件名，
> 时序不会反转。此错误结论未经实测即写入文档，现已修正。

### 5.4 frameLimit 与 MAX_GRID_FRAMES 必须同源

曾出现 `extractFrames` 已改为抽 4 帧，但 `LibraryIndexer` 仍传 `frameLimit = 3`，
导致第 4 帧被 `.take(3)` 截断、右下格空白，实测 `frames_analyzed` 恒为 3。

修复方式不是把两处数字都改成 4，而是让 `prepare()` 的默认值直接派生自
`MobileVlmPreprocessor.MAX_GRID_FRAMES`，从结构上消除再次脱节的可能。

**教训：同一个约束在两个文件里各写一遍字面量，迟早会漂移。**

### 5.5 audio_tags 是纯幻觉字段，已移除

pipeline 只有 `imageEncoder + lutEncoder + textGenerator`，**没有任何音频输入**。
但旧 prompt 要求输出 `audio_tags`，模型便凭空编造，实测出现
`audio_tags: ["wind","waves","birds","sunset"]`（`sunset` 出现在音频标签里）。

危害在于 `library.py` 会把 semantic 的所有 value 拼进检索文本，
这些幻觉值**直接污染 BGE 向量**。已从 Android 与 Python 两侧彻底移除，
并修正 Python 侧「包括画面动态和声音」这一误导性 prompt 描述。

### 5.6 quality_score 已移除：量化小模型不适合做数值回归

旧 schema 要求模型输出 `quality_score`，实测分布高度聚簇且与内容质量无关：

| 分数 | 条数 |
|---|---|
| NULL | 10 |
| 0 | 49 |
| 0.8 | 35 |
| 0.85 / 0.9 / 0.95 | 8 |

「水流倾泻」得 0 分，「猫躺着不动」得 0.8 分，排序完全反向。

根本原因：LLM 输出层是词表上的分类，`0.8` 是 `0`/`.`/`8` 三个 token 的序列，
不存在连续标量机制；训练用交叉熵而非 MSE，**没有任何误差信号校准过这个数值**；
BPE 分词下数字 token 之间也没有序数结构。叠加 W4A16 量化优先牺牲细粒度判别力、
prompt 从未定义评分维度、`temp=0.8` 采样不可复现，该字段不具备可操作性。

**通用原则：让量化小模型做描述与分类，不要让它做测量。**
离散档位（明显 / 轻微 / 静止）是小闭集分类，属于 next-token prediction 的强项。

### 5.7 版本标记与旧数据清理

`analysis_version` 写入每条 semantic，`isFresh()` 与 `readySemantic()` 都校验它，
`purgeStaleAnalysis()` 在同步前物理删除版本不匹配的旧行。

两个必须同时改的地方（曾漏改 `readySemantic()` 导致死循环）：
每次启动重新拷贝全部素材却永不更新，`cache/agent-inputs` 堆积到 1.3GB。

**任何改变 semantic 内容的改动都必须 bump 版本号**，否则旧记录会被永久跳过。

### 5.8 占位符复读：确定性采样下重试无效

prompt 用 `"..."` 表示待填槽位，4B 模型在 `temp=0.8` 下有一定概率不填充、
直接复读占位符，产出语法合法但内容为空的 JSON：

```json
{"summary":"...","theme":"...","scene_tags":["..."],"motion_tags":["..."]}
```

grid-v3 实测泄漏率（102 条全量）：
- `summary` 字段泄漏 **2/102 ≈ 2.0%**
- 任意字段含占位符 **4/102 ≈ 3.9%**

grid-v2 抽样 14 条中 `summary` 泄漏 1 条（≈7%），样本量小仅作参考。
两个口径需分开统计：只查 `summary` 会低估污染面，
因为 `theme` 同样参与 BGE 检索文本拼接（见 5.11）。
危害在于 `extractSemanticJson()` 只校验能否解析，不校验是否填充，
于是这类输出被标记为 `status: ready`，其 BGE 向量由 `"... ..."` 算出，
语义上是噪声却带着 ready 标记参与召回排序。

**关键约束：`text-generator.json` 里 `seed: 42` 是固定值。**
相同图片 + 相同 prompt + 固定 seed = 逐字节相同的输出。
因此对占位符复读**重试必然再次失败**，任何重试逻辑都是无效开销。

处理方式（区分失败性质，而非一刀切）：

| reason | 性质 | 是否重试 |
|---|---|---|
| `vlm_placeholder_echo` | 永久（确定性输出） | 否，标记 `settled: true` |
| `vlm_inference_failed` | 瞬时（DSP 超时等） | 是 |
| `vlm_model_pack_missing` | 配置问题 | 是，部署后可恢复 |

`isFresh()` 同时接受 `status=ready` 与 `settled=true`，
否则 settled 记录会在每次启动时被反复重解析，`pending` 永远无法归零。
bump `ANALYSIS_VERSION` 可让 settled 记录重新参与解析。

配套三处防线：
- `tagArray()` 过滤等于占位符的标签项
- `LibraryIndexer` 只为 `ready` 的记录写入嵌入。
  失败记录的 `summary` 是文件名（如 `spring_376279.jpg`），
  而 `searchEmbedding()` 按向量排序时不再复查 status，
  若照常嵌入，该噪声向量会被真实查询召回。
- `theme` 同样须经 `isFilledIn()` 校验（见 5.11）

#### grid-v4 实测结果（102 条全量）

| 指标 | grid-v3 | grid-v4 |
|---|---|---|
| `summary` 泄漏 | 2 | **0** |
| 占位符残留（任意字段） | 4 | **0** |
| 拦截为 `unavailable` | 0 | **7** |
| 其中 `settled`（不再重试） | — | 7 |
| 进入检索池 | 102 | **95** |
| 有 BGE 嵌入 | 102 | **95** |

复读率本身约 7/102 ≈ 6.9%（被拦截数），高于 grid-v3 观测到的 4/102。
差异源于 grid-v3 只统计了"已入库且带 ready 标记"的泄漏，
而 grid-v4 在写库前就拦截，统计口径更完整；
且 seed 固定使同一批素材的复读行为稳定，两者本就不宜直接相减比较。
**B 方案不降低复读率，它保证复读结果不进检索池** —— 这正是其设计目标。
降低复读率需依靠 A 方案（更换不易复读的占位符）。

检索池完整性校验通过：`ready` 而无嵌入 = 0，`unavailable` 而有嵌入 = 0。

### 5.9 占位符字面量必须单点定义

prompt 里的占位符与校验逻辑要比对的字面量，若各写一遍必然漂移
（与 5.4 的 `frameLimit` 同源问题）。现统一引用 `SCHEMA_PLACEHOLDER` 常量。

### 5.10 遗留数据：路径过滤曾依赖文件名前缀

`isGeneratedOutput()` 原用 `startsWith("live-result-")` 等 4 条前缀匹配。
改为纯路径判断（引用 `MobileAgentRunner.OUTPUT_IMAGE_PATH` /
`OUTPUT_VIDEO_PATH`，由写入方单点定义）后，发现 2 个
2026-02 旧版本遗留在 `Pictures/LivePhotoAgent/` 根目录的
`template_collage-*.jpg` 失去拦截。

这两个文件是旧版产物（当前代码只写 `Generated/` 子目录），
已连同 MediaStore 记录一并删除，路径规则此后完备。

> 注意 `cleanupIntermediateVideos()` 中的
> `startsWith("template_collage")` 前缀判断**予以保留**：
> 它区分的是同一目录下的「遗留中间产物」与「用户显式导出」，
> 承载语义，改成纯路径匹配会误删用户成果。
### 5.11 校验必须覆盖所有会进入检索文本的字段

B 方案首版只校验了 `summary`，遗漏 `theme`。实测 2 条 `ready` 记录：

```json
"summary": "夜间骑行者在昏黄灯光下骑行，镜头连续捕捉其侧脸与头部微调..."
"theme": "..."          ← 占位符，却因 isNotBlank 通过
"scene_tags": [], "motion_tags": [], "search_keywords": []
```

根因是 `theme` 的降级写法用了 `takeIf(String::isNotBlank)`：
`"..."` 并非空白字符串，于是直接入库。
而 BGE 检索文本由 `summary + theme + tags` 拼接而成，
该占位符**确实进入了向量**。

修复：`takeIf { isFilledIn(it) }`。

**教训：校验范围必须由"哪些字段会流向下游"决定，而非由"哪个字段最显眼"决定。**
`summary` 最直观所以先被校验，但检索文本是拼接的，
任何一个参与拼接的字段漏校验，污染照样发生。

### 5.12 全量索引被杀：死因是系统守护服务，不是代码 bug

grid-v4/v5 全量重跑反复卡在同一进度（37/102 或 24/102）后进程消失。
排查方向一度跑偏（怀疑 OOM、native crash、特定素材），实测数据逐一排除：

| 假设 | 证据 | 结论 |
|---|---|---|
| OOM | `PRESSURE_IDLE_ABUNDANT`，10.5GB 可用 | 排除 |
| native crash | 无 tombstone（最近一个是 6 月的） | 排除 |
| Java 崩溃 | 无 FATAL、无 ANR | 排除（中间一轮例外，见 5.14） |
| 特定素材 | 死亡时间恒为启动后约 13 分钟，与进度无关 | 排除 |

决定性证据来自 `ActivityManager` 日志——同一毫秒内两个 App 被批量 force-stop：

```
17:24:34.822  nsphvx-3 temperature: 99.7°C          ← NPU 触及杀死点
17:24:39.082  Force stopping com.vivo.ai.copilot ... stop by com.vivo.daemonService
17:24:39.089  Force stopping com.vivo.lpa        ... stop by com.vivo.daemonService
17:24:39.090  Killing 2654:com.vivo.lpa (adj 0)      ← adj 0 = 前台最高优先级，仍被杀
17:24:39.226  Sending broadcast android.thermal.WARNING_WINDOW_STATE
```

`com.vivo.daemonService` 在 NPU（Hexagon `nsphmx`/`nsphvx` 传感器）持续 ~99.7°C 时
批量清理进程，7 毫秒内连杀两个不相干 App，`adj 0` 前台服务也照杀。

**"约 13 分钟死亡"与"卡在 37 条"是同一件事的两种表述**：13 分钟是设备从冷态
加热到阈值所需时间，37 只是那一刻的进度快照（后续某轮跑到了 38）。
把进度数字当成"第 38 条素材有毒"去查，方向就错了。

**教训：进程被杀先查 `ActivityManager` 的 force-stop 来源与热区温度，
再查应用自身。`SYSTEM-OTHER` + SIGKILL + 无 tombstone + 内存充足，
基本可锁定为系统级温控而非应用 bug。**

### 5.13 三个修复各治一个失败模式（grid-v5 全量跑通）

确定温控死因后，grid-v5 落地三个互相独立的修复，缺一不能跑完全量：

| 失败模式 | 修复 | 位置 |
|---|---|---|
| burst 下 NPU 100–106°C → 守护服务杀进程 | `perf_profile: burst → balanced` | `MobileVlmPreprocessor.kt` `NPU_PERF_PROFILE` |
| 模型包无生成上限 → 失控生成跑满 240s 超时 | `max-num-tokens: 256` | 同上 `MAX_GENERATED_TOKENS` |
| 超时 `destroyForcibly()` 关流 → `readLine` 抛未捕获异常 → App 崩溃 | `outputThread` 用 `runCatching` 兜住 | 同上 `runGrid` |

grid-v5 全量实测（102 条，pid 25000 全程存活，约 22 分钟）：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 完成度 | 卡在 24–37 条 | **102/102** |
| NPU 峰值 | 100–106°C | **67°C** |
| 温控杀进程 | 每轮必死 | **0 次** |
| 推理超时挂死 | 240s（`kitten_133587`） | **0 次** |
| App 崩溃 | JavaCrash | **0 次** |
| 推理次数 | — | 111（102 素材 + 9 次 seed 重试） |

质量：101 `ready` / 1 正确拦截（`sports_81945` 四个 seed 全答"无" → `vlm_no_signal`），
占位符泄漏 **0**，101 条有 BGE 嵌入，101 条 `frames_analyzed=4`。
此前失败的 5 条救回 4 条（`kitten_133587`、`people_6387`、`spring_376279`、`winter_377043`）。

### 5.14 反直觉：降频会把慢输出推过超时线

`balanced` **单独用并没有跑通**——中间那一轮就是反例：

| 轮次 | 配置 | 结果 | 死因 |
|---|---|---|---|
| 09:38 | burst | 卡在 37/102 | `thermal.WARNING`，NPU 101°C |
| 10:41 | **balanced（仅此一项）** | **卡在 24/102** | **JavaCrash**，`thermal.WARNING` = 0 |
| 11:09 | balanced + token 上限 + 异常保护 | **102/102** | 无 |

中间那轮 NPU 峰值只有 68°C、温控一次没触发，进程却照样死了。死因是：

```
inference 240000ms timedOut=true        ← 挂死
FATAL EXCEPTION: Thread-59
java.io.InterruptedIOException          ← 崩溃
```

同一个复读循环素材（`kitten_133587`），**生成量由 token 数决定，与频率无关**：

| 模式 | 该次推理耗时 | 结果 |
|---|---|---|
| burst | 161s | 勉强在 240s 内跑完 |
| balanced | >240s | 超时 → 关流 → 崩溃 |

降频让同样的失控生成**慢到越过超时线**。所以 `balanced` 不只是"没单独解决问题"，
它把一个原本能容忍完成的慢输出变成了致命的超时 + 崩溃。

**教训：降低时钟会等比拉长所有推理的墙钟时间，包括失控生成。
任何"靠超时兜底"的逻辑，在降频后都必须重新核对超时余量，
否则降频本身会制造新的超时。**

### 5.15 反应式温控不可靠，降频才是真杠杆

`ThermalGuard` 的反应式路径（`shouldThrottle()` 读温度再决定休息）在 grid-v5 全程
**0 次触发**。41 个样本证明 `before` 温度对 `peak` 无预测力——实测出现过
`before=50°C → peak=103°C`：单次推理 15–20s 内传感器就能从安全区冲到杀死点，
而生成无法中途打断，"等温度高了再停"结构性地来不及。

真正有效的是 **`balanced` 降频（砍热源）**：单次温升从 +22°C 降到 +10°C
（burst 8s/peak 66°C → balanced 12s/peak 54°C，输出逐字节相同——
该参数只改 NPU 时钟不改算术，质量由构造保证不受影响）。

`ThermalGuard` 现在的定位是**保险而非主力**：按推理次数固定节奏休息
（`DEFAULT_BATCH_SIZE=6`，约 +13°C/批），温度只用来判断"休息够了没"。
它 0 次触发恰恰说明降频已把热量压在阈值之下。

**教训：温控的根本手段是减少热源（降频），不是事后补救（反应式节流）。
后者受限于"单次推理不可打断 + 温升快于采样"，只能当保险。**

### 5.16 生成上限标定：max-num-tokens 真实生效，repetition-penalty 被静默忽略

模型包原本**没有任何生成上限**，复读循环会一直跑到 240s 进程超时。
标定前先验证参数真实生效——`repetition-penalty` 曾被静默忽略，不能重蹈覆辙：
设 `max-num-tokens=5` 时输出正好截断为 `The image shows a fluffy`，确认生效。

用真实 gridPrompt（从源码提取，638 字符，与 App 运行时一致）二分标定：

| max-num-tokens | 输出 | 状态 |
|---|---|---|
| 64 | 280 字符 | **TRUNCATED**（切在数组中间，无闭合 `}`） |
| **96** | 348 字符 | 完整 ✓ |
| 112–384 | 348 字符 | 完整 ✓ |
| 无上限 | 348 字符 | 完整 ✓（7–8s 自然 EOS） |

锚点：348 字符输出需 65–96 token（3.6–5.4 字符/token）。
库里最长合法输出 453 字符 → 需 84–126 token；实测最长单条 542 字符 → 需 160–192 token。
生产设 **256**：把失控生成从 240s 压到约 25s，对正常输出零影响
（正常输出在 192 token 前就自然 EOS，上限只在失控时起作用）。

> **待定（余量偏薄）**：256 对 542 字符最长输出仅 1.33× 余量。
> 若某图产出 700+ 字符详细描述会被截断 → 4 次 seed 重试（balanced 下约 80s）
> → 全截断 → 永久 `settled` 丢失该素材语义。提到 **384** 可把余量做到 2×，
> 代价仅是失控素材多花 10–20s（相比 burst 挂死 161–240s 仍好得多）。
> 此项尚未拍板，`MAX_GENERATED_TOKENS` 当前仍为 256。

### 5.17 截断与复读同归 vlm_unparseable，且截断会触发 seed 重试

`max-num-tokens` 截断的输出**不是"保留前半部分"，而是整条判废**。
`extractSemanticJson()` 靠 `indexOf('{')` 与 `lastIndexOf('}')` 定位边界，
截断输出没有 `}` → `lastIndexOf` 返回 -1 → 解析为 null → 归 `vlm_unparseable`。
**`summary` 里那段完整的好描述也被一起丢掉**，不会存半截进库污染检索。

但截断会先触发 seed 重试：`isDegenerate()` 对解析失败返回 true
（`extractSemanticJson(raw) ?: return true`），于是依次用 seed 7、999、20260101 重试，
4 次全截断才标记 `settled`。问题在于**截断是长度问题，换 seed 只是碰运气生成更短内容**，
不一定有效——这也是 5.16 建议把上限提到 384 的原因：从源头减少截断，而非靠重试赌。

> **待定（无法区分截断与复读）**：当前截断和复读循环都归 `vlm_unparseable`，
> 无法统计"有多少素材是被截断误杀的"。可加 `vlm_truncated` 分类，
> 判据是"有 `{` 无 `}`"——属格式判断而非业务规则。此项尚未拍板。

### 5.18 SQLite 连接泄漏：每素材 new 一个连接池

`LivePhotoPreprocessor.prepare()` 循环内**每条素材都 `LibraryDatabase(context)` 新建实例**，
且全代码无任何 `close()`。每个 `SQLiteOpenHelper` 自带连接池 → 102 条素材 = 102 个泄漏连接池
（日志大量 `SQLiteConnection was leaked`）。

修复：对照 `LibraryIndexer.kt` 的类级单例写法，把实例提升到循环外
（`private val database = LibraryDatabase(context)`）。

**但必须诚实说明：泄漏是真 bug（该修），却可能不是死因。**
37 个连接池约 37MB，远不足以让系统杀死一个 15GB 虚拟内存的进程；
且两次死亡都精确停在 37，是确定性阈值，而连接泄漏是渐进累积。
实测佐证：修复后某轮进度跑到 38，证明 37 不是硬性障碍——真正死因是 5.12 的温控。

**教训：发现真 bug 不等于找到死因。修了泄漏仍要实测验证进度能否跑过原卡点，
不能把"修了一个确定的 bug"直接当成"解决了崩溃"。**

### 5.19 标定仪表仍在代码里（待移除）

`runGrid` 中的 `MobileVlmThermal` 峰值采样线程（500ms 轮询最热 NPU 传感器）
是为标定单次温升、推导 `DEFAULT_PAUSE_C` 而加的，注释已标注
"Remove once the pause threshold has been derived from measured per-inference rise"。

标定已完成（+22°C/+10°C 数据见 5.15，阈值已定），该仪表属临时埋点。
> **待定**：删除还是保留以便后续调参，尚未拍板。当前仍在代码里。

