# Agent Runtime v2 完整切换验收记录

> 验证日期：2026-10-26
>
> 设备：vivo PD2547 / Android 16 / arm64-v8a
>
> 生产构建：`plannerExperiment=baseline`、`plannerEvalOnly=false`、`agentRuntime=loop`
>
> 回滚构建：`plannerExperiment=baseline`、`plannerEvalOnly=false`、`agentRuntime=static`

## 1. 验证结论

Runtime v2 的首个纵向版本已经在真机完成以下闭环：

```text
语义路线
→ 按状态生成 ≤10 个具体候选
→ search_assets 标准工具调用
→ 真实素材进入 AgentRuntimeState
→ 逐路线步骤绑定具体工具
→ ArtifactRef 串联 SDK 产物
→ publish_result 显式发布
→ TERMINAL 完成门禁
```

架构 smoke、static 兼容回归、Android 构建和 lint 均通过；随后完成 **31/31 注册 executor**
真实执行验收与 **30/30 动态绑定**验收。最终长链和 HEVC 倒放再次通过，生产默认已切换为
`agentRuntime=loop`，static 继续作为显式回滚路径。

---

## 2. 自动化检查

### 2.1 Android 构建

以下两套构建均通过：

```text
agentRuntime=loop   + plannerExperiment=baseline + plannerEvalOnly=false
agentRuntime=static + plannerExperiment=baseline + plannerEvalOnly=false
```

两套构建均执行：

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
```

结果：`BUILD SUCCESSFUL`。仅保留两条已有 Android deprecated API warning，无新增 lint 错误。

### 2.2 ToolSpec 单元测试

`MobileToolRegistryTest`：**6/6 通过，0 failures，0 errors**。

覆盖：

- 图片上下文隐藏视频和 Live Photo 工具；
- 三视频竖向堆叠要求精确 3 条视频轨道；
- `layout_swap` 同时要求两个选择和已有 layout；
- publish 要求已验证 artifact，loop 只暴露 `publish_result`；
- 单视频 artifact 不暴露双输入动态贴纸；
- 各代表性 family/context 的具体候选均不超过 10。

### 2.3 Harness 自检

`tests/evals/test_harness_p3.py`：`ALL P3 ASSERTIONS PASSED`。

`device_harness.py` 已兼容读取 `RUNTIME_BRAIN`，可分别统计 route 与 bind/revise 的模型延迟，
同时保持旧 static 报告兼容。

### 2.4 Static 20 条回归

在真实 APK、真实 QNN 模型和真实素材检索上下文中，以 `plannerEvalOnly=true` 跑
`device_eval_set.jsonl` 20×1：

```text
pass_rate             = 1.00
brain_pass_rate       = 1.00
rescued_by_tables     = 0
chat_swallowed_edit   = 0
keyword_fallback      = 0
protocol_clean_rate   = 1.00
errors                = 0
```

报告保存在本机：

```text
/home/jiangrui/.cache/lpa-final/static-20-regression.json
```

这证明 Runtime v2 代码接入没有破坏当前生产 static planner。该 20 条仍是单轮 planner
回归，不把它表述为长链端到端成功率。

### 2.5 注册工具逐一执行验收

使用只存在于测试 APK 的 `RuntimeToolAcceptanceTest`，复用手机真实 Live Photo、真实
LivePhotoSDK/VideoEditorSDK/ImageProcessSDK/BGE，逐一执行 `MobileToolRegistry` 的全部31项：

```text
registered_count = 31
attempted_count  = 31
passed_count     = 31
failed_count     = 0
```

覆盖：

- 检索/分析：`search_assets`、`asset_summarize`、`color_analysis`、`live_xdr_process`；
- 单视频：裁剪、镜像、虚化、时间线、动感、画布适配、边框、滤镜、变速、倒放；
- 多视频：转场、动态贴纸、三视频竖排、模板拼贴；
- 图片：提亮、锐化、降噪、色调、人像美颜；
- Live Photo：滤镜、动感、换封面；
- 状态操作：交换、语义替换；
- 发布：legacy `export_mp4` 与 Runtime v2 `publish_result`。

完整报告：

```text
/home/jiangrui/.cache/lpa-final/runtime-tool-acceptance-final.json
```

### 2.6 动态候选绑定验收

对28个需要模型选择的具体工具，分别构造正确的媒体、数量、selection、layout 和 artifact
状态，让真实 QNN Qwen 在不超过10个的动态目录中选择；`search_assets` 与 `publish_result`
因各自只有一个候选自动绑定：

```text
case_count   = 30
passed_count = 30
failed_count = 0
```

legacy `export_mp4` 已完成 executor 验收，但设计上不进入 loop 模型目录，由 `publish_result`
取代。完整报告：

```text
/home/jiangrui/.cache/lpa-final/runtime-tool-binding-acceptance-final.json
```

### 2.7 最终默认构建端到端回归

默认值切到 loop 后重新安装正常 APK，真实 UI/QNN/SDK 跑：

```text
F001 search_assets → template_collage → video_filter → publish_result
F002 search_assets → video_reverse → publish_result
```

结果：2/2 pass、2/2 brain pass、0 fallback、协议 clean 1.0。报告：

```text
/home/jiangrui/.cache/lpa-final/runtime-loop-final-e2e-report.json
```

### 2.8 测试基础设施事故与恢复

首次使用 Gradle `connectedDebugAndroidTest` 时，该 task 在测试结束后自动卸载 target APK，
同时删除了 App 私有目录。恢复过程：

1. 从 `/data/local/tmp/live_photo_agent.db.bak` 恢复 101 条历史 VLM 索引；
2. 从保留的 7.3GB `/data/local/tmp/lpa-models` 恢复 Qwen/VLM/BGE 模型；
3. 重新启动增量索引，依据仍在 MediaStore 的原始素材补算到 **114 条**；
4. 停止 App 后重新备份完整数据库到设备与主机；
5. 后续 instrumentation 全部改成手动安装/运行，仅卸载 `com.vivo.lpa.test`。

恢复后的数据库备份：

```text
/home/jiangrui/.cache/lpa-final/live_photo_agent-114-restored.db
/data/local/tmp/live_photo_agent.db.bak
```

素材及114条语义索引已恢复；旧备份生成后新增的测试会话/审计行无法恢复。该事故不影响原始
相册媒体，但属于本轮测试的真实负面结果，必须保留记录。

### 2.9 语义检索纠偏复验

切换后用户实测“给我的猫做个三拼”曾返回完全不相关素材。审计证明根因不是 embedding 与
asset ID 串位，而是 route 输出：

```text
RETRIEVE=三拼
STYLE=暖色调
```

Runtime 因而用编辑词“三拼”搜索，返回锅、狗、柠檬，并执行了用户没有要求的暖色滤镜。
修复包括：

- STYLE/TEMPORAL/COMPOSE 等 family 必须在本轮原话中有动作证据，只做减法、不用规则加工具；
- RETRIEVE 查询剥离相册、数量、拼图、滤镜、导出等非画面词；
- 查询只剩编辑词或与原话主体冲突时，调用独立 Qwen retrieval extractor；
- 修复后的查询再次做 grounded 校验，不可靠则停止，禁止随机素材继续执行；
- BGE/SQLite/ONNX helper 在每轮后显式 close，修复长期运行连接泄漏。

同一句新会话 3× 重复结果稳定为：

```text
query = 猫
kitten_133587.jpg / cat_179212.jpg / kitten_4216.jpg
tools = search_assets → template_collage → publish_result
无 STYLE、无 fallback
```

猫、狗、风景、美食、海边五类端到端检索均通过；其中海边查询被规范为纯“海边”，Top-3
语义为夕阳海岸线、海浪翻滚、岩石海滩。设备端固定索引审计：

```text
猫 / 狗 / 海边 / 美食：4/4 case，Top-3 全部具备对应语义证据
```

报告：

```text
/home/jiangrui/.cache/lpa-final/retrieval-fix-cat-repeat3.json
/home/jiangrui/.cache/lpa-final/retrieval-semantic-5-report.json
/home/jiangrui/.cache/lpa-final/retrieval-fix-beach-report.json
/home/jiangrui/.cache/lpa-final/semantic-index-acceptance-final.json
```

数据库共114条素材：113条带标准512维/2048字节 embedding；唯一无向量的
`sports_81945.jpg` 被 VLM 明确标记 `vlm_no_signal, settled=true`，按设计不可检索，不属于
向量错配。

---

## 3. 真机 Runtime v2 场景

### L001：检索并三拼

用户原话：

```text
给我的猫做个三拼
```

实际链路：

```text
search_assets → template_collage → publish_result
```

结果：通过；零 fallback；最终发布为有效 vivo Live Photo。该场景同时证明：

- `search_assets` 已进入标准 TOOL_CALL/TOOL_RESULT；
- 明确数量“三拼”覆盖模型错误数量；
- COMPOSE 目录根据 3 条真实运动轨道生成；
- 通用“三拼”选择 `template_collage`，未误选仅限明确竖排的 `video_vertical_stack`。

### L002：多工具真实串联

用户原话：

```text
给我的猫做个三拼，再调成黑白并导出
```

实际链路：

```text
search_assets
→ template_collage
→ video_filter
→ publish_result
```

关键输入引用：

```text
template_collage.output = artifact-step-1-2-compose
video_filter.input       = artifact-step-1-2-compose
video_filter.output      = artifact-step-1-3-style
publish_result.input     = artifact-step-1-3-style
```

SDK 实际参数审计：

```json
{
  "effect": "调成黑白",
  "saturation": -1,
  "contrast": 0.1,
  "temperature": 0,
  "filter_level": 0.8
}
```

最终结果：`content://media/external/images/media/2055`。

MediaStore 验证：

```text
mime_type     = image/jpeg
size          = 7,397,766 bytes
relative_path = Pictures/LivePhotoAgent/Generated/
live_photo    = motionphoto00010000000000000
```

从封装文件提取运动轨道后，`ffprobe` 验证：

```text
video = H.264 / 1080x1440 / yuv420p
audio = AAC
duration = 6.066s
```

抽取同一时间附近画面做颜色通道差异检查：

| 产物 | RGB 平均通道差 | 近灰像素比例 |
|---|---:|---:|
| 未调色拼贴 | 42.925 | 4.73% |
| 黑白链路结果 | 2.787 | 73.75% |

这证明黑白滤镜不是只出现在计划中，而是真实作用于拼贴 artifact 后再发布。

该场景模型调用延迟统计约 19.1 秒（route 8.0 秒，两个需要选择的 bind 合计约 11.0 秒）；
SDK 导出时间另计。

### L003：普通聊天

用户原话：`你好`

结果：直接 `REPLY`，无检索、无工具调用、无素材污染，通过。

### L004：检索视频并倒放

用户原话：

```text
找一段猫的视频并倒放后导出
```

实际链路：

```text
search_assets(required_media=video, k=1)
→ video_reverse
→ publish_result
```

结果：通过；TEMPORAL 候选目录为 6 个，模型选择 `video_reverse`。切换默认后的最终复测发布
URI 为 `content://media/external/images/media/2095`。

### L005：不支持的九拼与动态修订

用户原话：`给我的猫做个九拼`

执行过程：

1. 模型规划 RETRIEVE → COMPOSE → PUBLISH；
2. `search_assets` 实际返回 9 条素材；
3. ToolSpec 根据真实数量过滤：模板拼贴仅支持 2～6，竖向堆叠要求精确 3；
4. COMPOSE 具体候选数变为 0；
5. Runtime 把结构化不可执行原因回喂模型；
6. 模型返回 `QUESTION=是否需要调整素材数量或类型以支持九拼布局？`；
7. 状态进入 `waiting_user`，未调用错误 SDK，未发布结果。

结果：动态修订/追问通过，brain attribution 正确，无关键词兜底。

---

## 4. 测试中发现并修复的问题

| 真机现象 | 根因 | 修复 |
|---|---|---|
| route 同时输出 REPLY、QUESTION、ROUTE | 4B 模型混合了三种通道 | 有有效路线时路线优先；无路线才处理聊天/追问 |
| “三拼”检索数量输出 1 | 模型数字槽漂移 | 用户原话中的明确数量作为事实门禁，覆盖错误 k |
| 未明确媒体却限制 IMAGE | 模型臆测媒体形态 | 只有用户明确说视频/Live Photo/静态图片时才成为硬检索约束 |
| 三拼路线凭空加入 REPLACE | 初始语义目录暴露了当前不可能能力 | REPLACE 仅在已有素材且恰好单选时可见/可解析 |
| 通用三拼选中 video_vertical_stack | 工具描述边界不清 | 模板工具标明“几拼优先”；竖堆叠仅限明确上下竖排 |
| binder 把 asset ID 抄成 `#序号` 并生成多段时间 | prompt 暴露原始 ID且通用后缀规则过宽 | binder 隐藏 ID；非裁剪步骤禁止后缀；parser 拒绝多重/非法后缀 |
| 九拼路线额外生成裁剪与倒放 | 模型过度规划 | 增加只做减法的显式 TEMPORAL 动作 guard，不用规则添加工具 |
| UI 保存会覆盖 loop checkpoint | Activity 重写整个 session JSON | UI 持久化继承 `runtime_state_version/runtime_checkpoint` |
| fit/crop 显式输入一个 artifact 却按3个源素材拒绝 | executor 校验忽略步骤 InputRef | 执行前 ToolContext 优先由显式 ArtifactRef/AssetSetRef 构造 |
| HEVC 倒放无回调并等待240秒 | vendor VideoReverser 对该 HEVC 轨道不稳定，且受 codec 状态影响 | HEVC 先经已验收 fit 后端规范化为 H.264；倒放置于一次性独立进程并使用有界重试 |
| `connectedDebugAndroidTest` 卸载目标 App | Gradle managed task 测后清理 target package | 改为手动安装 test APK + `am instrument`，只卸载 test package，不再清理目标 App |
| “猫三拼”返回锅/狗/柠檬并自动暖色 | route 把“三拼”当检索词并重复历史/臆测 STYLE | grounded 查询规范化 + 独立 retrieval repair + family 负向门禁 |

这些失败均发生在实验 loop APK 中，修复后重新真机验证；生产 static 路径始终由独立构建保留。

---

## 5. 当前限制

1. 31个 executor 已逐一真实执行，但不是31种工具的所有参数组合；后续轨迹集仍需覆盖组合顺序、
   否定表达和用户中途更改目标。
2. 黑白等风格数值仍由可审计 preset 填充。模型决定“黑白”和 `video_filter`，但具体数值不是
   模型生成；报告中仍标记为 `hollow_params`，不冒充 brain-authored 参数。
3. 首版在进程中断后只恢复 checkpoint 并明确提示，不自动重放进行中的 SDK 操作。
4. 模型在真机上出现过 `RETRY=` 代替 `ROUTE=`；parser 将其作为已审计别名接受，但不会放宽
   family、数量、媒体或工具目录校验。
5. 已有逐工具 acceptance 和代表性长链，正式扩展 L2 轨迹集仍需继续建设。
6. loop 多工具路线会增加模型调用次数；代表性四步链模型时间约 19 秒，后续仍需在正确性稳定后
   评估常驻进程或减少 bind 调用。

---

## 6. 最终设备状态

验收完成后手机已切换：

```text
plannerExperiment = baseline
plannerEvalOnly   = false
agentRuntime      = loop
default IME       = com.baidu.input_vivo/.ImeVivoService
ADBKeyboard       = disabled
test stay-awake   = disabled
```

static 回滚方式：

```text
./gradlew :app:assembleDebug -PagentRuntime=static \
  -PplannerExperiment=baseline -PplannerEvalOnly=false
```

本轮保留的可安装制品：

```text
/home/jiangrui/.cache/lpa-final/app-runtime-loop-production.apk
/home/jiangrui/.cache/lpa-final/app-runtime-static-rollback.apk
```
