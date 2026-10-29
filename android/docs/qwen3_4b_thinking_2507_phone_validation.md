# Qwen3-4B-Thinking-2507 真机部署与验证

> 日期：2026-10-27
>
> 最近更新：2026-10-29（本地W4A16量化停止并归档）
>
> 设备：vivo PD2547 / SM8850 / Android 36 / 16GB RAM
>
> 生产配置始终保持 `Qwen3-4B-Instruct-2507 + Runtime v2 loop`；本实验使用独立目录和进程。

## 1. 目标与范围

本轮优先回答两个问题：

1. 真正的 `Qwen3-4B-Thinking-2507`（不是旧 Qwen3-4B hybrid）能否在目标手机运行；
2. 在手机可承受的有界思考预算下，它对Agent语义Route是否有实际收益。

扩大旧QNN图的context是低优先级，不与2507模型效果混为一个变量。
GGUF的context可在运行时设为8192，因此本轮无需重新导出；这不代表静态QNN图也能通过改
JSON扩容。

## 2. 部署路径

### 2.1 QNN/HTP路径

截至实验时，Qualcomm没有发布 `Qwen3-4B-Thinking-2507` 的SM8850预编译包。
`qwen3_4b` recipe支持自定义checkpoint，但流程需要：

```text
原始2507权重
→ 本地AIMET W4A16量化
→ Qualcomm AI Hub Workbench云编译
→ Genie/GenieX QAIRT包
```

实验开始时机器没有AI Hub凭据；随后由用户在本机配置Token，权限已收紧为 `0600`，并已通过
`qai-hub list-devices` 验证可访问 **Samsung Galaxy S26 / Android 16 / SM8850**。这只证明
云服务与目标可用，尚不等同于2507量化或QNN编译成功。

官方recipe的4B结构常量与2507一致（36层、hidden 2560、32/8个Q/KV heads、head dim
128、vocab 151936），从结构上可复用；但本地AIMET说明推荐40–80GB VRAM，当前RTX 5090
只有24GB。即使获得Token，仍需把“量化可完成”和“云端能编译”分别验证。
recipe还显式声明 `supports_thinking=True`，并包含tied embedding/lm_head修复；官方2507的
`tie_word_embeddings=true` 不会被当作未知结构静默忽略。

### 2.2 已完成的GGUF/llama.cpp路径

为了先隔离验证2507权重本身，使用真实2507的Q4_K_M GGUF和手机CPU：

```text
base model     Qwen/Qwen3-4B-Thinking-2507
quant          bartowski Q4_K_M, 2,497,280,736 bytes
SHA-256        64ce5838a76b4d63071289c17ed04525265a47abf51546cd747a30e41b7687fa
llama.cpp      v0.7.0, commit 620d2225cc436ab0b001e3200e590e3b286ef9ab
Android NDK    28.2.13676358
CPU kernels    KleidiAI enabled; runtime reports NEON/KLEIDIAI/REPACK
runtime ctx    8192
threads        6
```

设备独立目录：

```text
/data/local/tmp/lpa-models/qwen-thinking-2507-gguf
```

模型、runner和localhost server均未覆盖生产 `qwen` 模型目录。

### 2.3 本地W4A16量化结论

真实 `Qwen3-4B-Thinking-2507` 官方权重已完整校验，但当前主机无法稳定完成AI Hub
Models 0.64.0 / AIMET-ONNX 2.28的AdaScale W4A16流程。该结论是**量化工具链和峰值
内存受阻**，不是模型效果否定，也不构成生产切换依据。

量化阶段并非按8GB BF16推理权重常驻。实测内存构成为：

| 项目 | 实测 |
|---|---:|
| 官方BF16 safetensors | 8,045,591,552 bytes |
| 动态ONNX FP32 initializer | 4,411,424,268 elements / 17,645,697,120 bytes |
| ONNX外部 `model.data` | 17,645,660,160 bytes |
| 2048-token完整FP32 logits | 1,244,659,712 bytes |
| 校准临时文件峰值 | 约95GB（约17GB ORT模型和约78GB prefill mmap） |

ONNX比源权重大，是因为QuantSim保留FP32 master weights，同时QNN导出把源模型共享的
embedding / `lm_head` 拆开；两个 `[151936, 2560]` FP32矩阵各占1,555,824,640 bytes。
W4A16是最终编码目标，不是校准期间的实际常驻格式。

主要尝试及停止点：

| 校准context / sequence | 结果 |
|---|---|
| 4096 / 2048 | QuantSim重建和Wikitext prefill均出现24GB CUDA OOM |
| 3072 / 2048 | 128/128 prefill完成；CUDA完整 `lm_head` 申请1,244,659,712 bytes失败 |
| 2048 / 1536 | 同一 `lm_head` 申请933,494,784 bytes失败 |
| 2048 / 1024 | 同一节点出现CUBLAS内部错误；关闭TF32无改善，未继续降至512牺牲校准质量 |
| 3072 / 2048混合CPU/GPU | 参数编码和最终全图校准移至CPU，AdaScale留在GPU；曾进入首个block并达到1699/2048，但无可恢复checkpoint |
| 独立systemd复跑 | 128/128 prefill完成，CPU计算1873个参数编码时被OOM killer终止 |

最后一次systemd复跑时，主机125GiB RAM仅余约0.8GiB、约10GiB swap已满；GPU仅使用
约1.3GiB且仍有约22.7GiB空闲，因此该次是明确的**主机RAM OOM**。更早的纯CUDA尝试则是
明确的**24GB VRAM不足**。两者均是容量/实现峰值问题，不表示CPU或GPU硬件故障。

截至停止时：

- `model.encodings` 不存在，云端QNN编译没有启动；
- 不原样重试，不把校准sequence继续降至512；
- 当前没有新增主机内存或更换40GB+ GPU的计划，本地Thinking-2507 W4A16工作停止并归档；
- 若未来出现官方预量化Thinking-2507 checkpoint或可用的大内存主机，再重新打开QNN路径；
- 生产仍为 `Qwen3-4B-Instruct-2507`，没有覆盖、切换或降级。

## 3. 推理控制

2507是thinking-only模型。无限制探针在生成2048 token后仍未闭合思考，因此手机实验使用
llama.cpp的token级reasoning budget：

```text
reasoning budget = 512 tokens
final reserve    = 256 tokens
temperature      = 0.6
top-k            = 20
top-p            = 0.95
min-p            = 0
```

达到预算时runner强制注入结束思考标记，再让模型生成最终答案。`reasoning_content`与最终
`content`分开保存；未把思考写进下一轮历史。

## 4. 性能与资源

短基准：

| 后端 | pp64 | tg16 | 状态 |
|---|---:|---:|---|
| CPU / KleidiAI | 14.59 token/s | 11.70 token/s | 稳定 |
| **OpenCL / Adreno 840** | **149.55 token/s** | **17.94 token/s** | 短基准稳定 |
| Vulkan / Adreno 840 | — | — | 枚举GPU后、加载模型前SIGSEGV |

OpenCL把短prompt吞吐提高约10.3倍、短decode提高约1.53倍。它有望把512-token Route的
推理时间降到约30秒量级。随后在手机上用原生tool-call实测两条难例：P09为35秒，P12为
44秒，均成功；温度32.2°C → 33.7°C。Vulkan路径已经明确失败并停止，不进入后续评测。

12条真实Route连续运行：

| 指标 | 结果 |
|---|---:|
| 请求成功 | 12/12 |
| 平均单条墙钟 | 67.0s |
| 平均纯生成 | 64.64s |
| 平均输出 | 554.8 token |
| 连续运行总时长 | 804s |
| 电池温度 | 33.0°C → 44.4°C |
| 最低 MemAvailable | 5.83GiB |
| server RSS观察值 | 约4.8–5.4GB |

随着温度上升，实际解码从约9.2 token/s降至约7.9 token/s。该CPU路径可用于模型效果验证，
不满足交互式生产延迟与热负载要求。

## 5. 512-token Route结果

同一组12条无UI Route oracle，结果如下：

这是**原始Route诊断集**：不把后续 `RetrievalQueryGuard`、专用检索修复、Binder或Replan的
补救计入成绩。它可与同一12条的4/12基线比较，但不能与24条完整loop的82.6%直接换算。
两种模型使用相同语义规则和输出schema；thinking-only模型只做了必要的“最终答案”措辞和
有界思考控制。

| 模型 | App兼容通过 | 协议干净 | 说明 |
|---|---:|---:|---|
| 生产 Instruct-2507 / QNN | 4/12 | 原报告另计 | 平均Route约7.45s |
| 旧 Qwen3-4B hybrid thinking / QNN | 1/12 | — | 平均Route约27.54s |
| **Thinking-2507 Q4_K_M / CPU / budget 512** | **8/12** | **12/12** | 平均墙钟67s |

通过：普通聊天、能力询问、海边拼图、视频黑白、猫视频倒放、海边视频加速、海边视频
竖排、英文猫三拼。

失败：

| Case | 失败原因 |
|---|---|
| P03 猫三拼 | 检索内容退化为“本地相册”，并漏PUBLISH |
| P08 猫三拼+否定滤镜贴纸 | family和检索正确，但漏PUBLISH |
| P09 猫三拼+黑白 | 过度思考后错误追问用户提供素材 |
| P12 猫三拼+倒放+否定滤镜 | family正确且没有STYLE，但漏PUBLISH |

12条最终输出全部符合短协议；失败不是旧hybrid的JSON漂移/字符复读，而主要是512 token截断
时尚未完成语义核对，导致末尾PUBLISH丢失或回到错误澄清。

## 6. 当前结论

### 6.1 思考预算扫参

在同一Q4_K_M权重上，取P03/P05/P08/P12四条难例做主机同实现扫参，并把强制收尾语句
改为“核对全部明确要求，媒体路线必须以PUBLISH结束”：

| reasoning budget | App兼容通过 | 平均生成时延 |
|---:|---:|---:|
| 0 | 1/4 | 4.21s |
| 128 | 2/4 | 13.06s |
| 256 | 2/4 | 22.56s |
| **512** | **3/4** | **41.43s** |
| 1024 | 3/4 | 80.69s |

1024没有带来增益；0–256会更频繁漏掉检索内容或末尾步骤。512是本轮可见的Pareto点。
改进的强制收尾语句修复了P08/P12漏PUBLISH，但P03仍把检索主体写成空值或“本地相册”，
说明它不是纯格式问题。

### 6.2 原生 tool-call 对照

2507官方chat template原生支持工具调用。把同一语义Route由多行文本改成单个
`plan_media_route(steps)` function call，schema只约束字段和family枚举，不替模型决定步骤：

| 输出协议 | 12条通过 | 协议干净 |
|---|---:|---:|
| 原多行 `ROUTE=`，手机，旧收尾提示 | 8/12 | 12/12 |
| 原多行 `ROUTE=`，主机，改进收尾提示 | 9/12 | 12/12 |
| **原生 `plan_media_route` tool call，主机** | **12/12** | **12/12** |
| 原生tool call，手机OpenCL难例P09/P12 | **2/2** | **2/2** |

原生调用正确覆盖了聊天分流、猫/海边检索主体、拼贴、黑白、变速、倒放、否定滤镜和最终
PUBLISH。媒体任务每条恰好一个tool call，聊天不调用工具。说明旧hybrid实验中的Format Tax
不能外推给2507；2507应按官方tool协议接入，而不是继续优化脆弱的自定义行协议。

模型仍会在未明示时推测 `media=IMAGE`。生产接入必须保留现有负向安全归一化：只有用户原话
明确媒体类型时该字段才生效；这只拒绝模型臆测，不替模型选择路线。

手机OpenCL复核使用的是 **4096 context**。P09/P12总token分别为1181和1232，没有发生context
截断；将reasoning budget从512提高到1024也没有提高难例通过数。因此当前Agent Route的主要
矛盾是输出协议、预算利用和运行速度，而不是4096容量。低优先级8192 QNN重导出没有正向证据，
按用户预期暂缓。

### 6.3 专用检索词预算

猫、狗、海边、美食、猫视频、英文cat共6条专用 `SEARCH=视觉主体` 提取：

| budget | 通过 | 主机平均时延 | 平均输出token |
|---:|---:|---:|---:|
| **0** | **6/6** | **1.50s** | **18** |
| 64 | 6/6 | 5.79s | 82 |
| 128 | 6/6 | 10.32s | 146.2 |

因此简单检索规范化不应开启长思考；强制立即结束thinking即可保留2507权重的直接分类能力。
这也避免让模型在简单任务上自我怀疑并重新引入“照片/视频/拼图”等噪声。

### 6.4 具体工具Binder预算

用真实ToolSpec描述构造3–6个state-filtered工具目录（始终≤10），覆盖Analyze、Temporal、
Compose、视频Style、图片Style和Live Photo Style共26条非平凡绑定：

| 配置 | 通过 | 说明 |
|---|---:|---|
| budget 0 / max output 128 | 24/26 | 两条已说出正确工具，但在tool call前截断 |
| budget 64 / max output 128 | 25/26 | 动态贴纸在tool call前截断 |
| **budget 64 / max output 256** | **26/26** | 失败项扩大最终余量后通过 |

失败输出都在自然语言中明确选中了正确ID，没有选择错误工具；问题是tool-call落地余量。Binder
不需要512-token长思考，但thinking-only 2507用0预算时在部分Live Photo任务会把分析泄漏到
最终区，因此选择64-token思考、256-token总输出上限。

阶段预算由此收敛为：

```text
Route / Revise       512 thinking + 256 final reserve
Retrieval extraction  0 thinking + short final
Concrete Binder       64 thinking + 192 final reserve（总上限256）
```

### 6.5 阶段结论

1. **部署可行性已确认**：真正2507 Thinking已经在目标手机离线运行并完成批量推理；
2. **权重效果明显优于旧hybrid**：原文本Route由1/12提高到8/12，协议12/12干净；
3. **使用正确协议后语义显著改善**：原生tool-call为12/12；相同模型的改进行协议为9/12；
4. **CPU部署不可生产**：约67秒/Route、连续13分钟升温11.4°C；
5. **OpenCL可作为测试后端**：P09/P12为35/44秒且2/2通过，但仍明显慢于生产QNN；
6. **512 token是有界最优而非充分思考**：初始手机行协议12/12均被强制闭合，不能等同
   官方完整推理质量；原生tool-call中7/12达到预算上限；
7. **原生tool-call仍值得保留**，但Thinking-2507本地W4A16路径已被24GB VRAM和主机RAM
   峰值共同阻断；在没有官方预量化checkpoint或更大内存主机前停止重试，8192重导出继续暂缓；
8. 具体Binder已在26条非平凡目录上达到26/26，但仍未测试媒体执行与长链Replan；不能把
   组件通过率宣称为生产闭环通过率。

### 6.6 后续对照模型

在引入新模型前，应先让现有 `Qwen3-4B-Instruct-2507` 使用与Thinking-2507完全相同的
`plan_media_route` 原生tool schema重跑12条Route。当前4/12基线使用旧输出路径，而12/12
Thinking结果使用原生tool call，不能把全部差异都归因于thinking。

独立模型对照按以下顺序考虑：

| 模型 | 价值 | 当前部署条件 |
|---|---|---|
| **Phi-4-Mini-Instruct 3.8B** | MIT、支持中文和function calling、参数量最接近；适合作为首选独立语义对照 | AI Hub 0.64已有2.33GB Q4_0/GenieX llama.cpp资产，并有Galaxy S26数据；不是QNN W4A16 |
| **Gemma-4-E4B-it** | Apache-2.0、有效4B、多语言、原生function calling，独立于Qwen | AI Hub有QAIRT 2.45 W4A16现成资产；QNN仍为Developer Preview且已知有复读/格式问题 |
| **Llama-3.2-3B-Instruct** | 成熟的独立架构和tool calling基线 | AI Hub有发布的W4A16 checkpoint；官方支持语言不含中文，中文Route不宜作为主对照 |
| **Qwen3-1.7B** | 同家族、同thinking协议，可分离“模型规模”影响 | AI Hub有现成W4A16和Q4_0；不是独立架构对照 |

质量比较先统一为4096 context、同一tool schema、同一Route/Retrieval/Binder集合和同一输出预算；
模型语义成绩与GGUF/QNN后端性能必须分开报告。

## 7. 证据

```text
/home/jiangrui/.cache/lpa-final/2507-phone-route-report-b512.json
/home/jiangrui/.cache/lpa-final/2507-phone-route-progress-b512.log
/home/jiangrui/.cache/lpa-final/2507-phone-route-results-b512/
/home/jiangrui/.cache/lpa-final/2507-phone-server-route-complex.json
/home/jiangrui/.cache/lpa-final/2507-thinking-budget-sweep-report.json
/home/jiangrui/.cache/lpa-final/2507-host-line-route-improved-b512.json
/home/jiangrui/.cache/lpa-final/2507-host-tool-route-report-b512.json
/home/jiangrui/.cache/lpa-final/2507-phone-opencl-tool-report.json
/home/jiangrui/.cache/lpa-final/2507-phone-opencl-tool-progress.log
/home/jiangrui/.cache/lpa-final/2507-phone-opencl-bench.txt
/home/jiangrui/.cache/lpa-final/2507-phone-vulkan-tombstone.txt
/home/jiangrui/.cache/lpa-final/2507-host-retrieval-budget-sweep.json
/home/jiangrui/.cache/lpa-final/2507-host-binder-budget-sweep.json
/home/jiangrui/.cache/lpa-final/2507-thinking-eval-prompts.tar.gz
/home/jiangrui/.cache/lpa-final/qai-hub-sm8850-device.txt
/home/jiangrui/.cache/lpa-final/qwen3-4b-thinking-2507-phone-manifest.json
/home/jiangrui/.cache/lpa-final/2507-hf-weight-verification.json
/home/jiangrui/.cache/lpa-final/2507-qnn-w4a16-default-attempt-oom.json
/home/jiangrui/.cache/lpa-final/2507-qnn-w4a16-context3072-seq2048-lmhead-oom.json
/home/jiangrui/.cache/lpa-final/2507-qnn-w4a16-block0-external-interruption.json
/home/jiangrui/.cache/lpa-final/2507-qnn-w4a16-systemd-host-oom.json
/home/jiangrui/.cache/lpa-final/2507-qnn-w4a16-systemd-host-oom.log
/home/jiangrui/.cache/lpa-final/2507-qnn-w4a16-systemd-host-oom-gpu-trace.csv
/home/jiangrui/.cache/lpa-final/2507-qnn-w4a16-systemd-host-oom-memory-trace.txt
/home/jiangrui/.cache/lpa-final/llama-v070-android-arm64-kleidiai-runner.tar.gz
/home/jiangrui/.cache/lpa-final/llama-v070-android-arm64-opencl-kleidiai-runner.tar.gz
tests/evals/score_llama_route_responses.py
```

## 8. 收尾状态

实验server、bench与测试包均已停止；GGUF及runner保留在独立模型目录以便复现。手机当前为：

```text
agentRuntime    = loop
plannerEvalOnly = false
qwenModelPack   = instruct
qwenThinking    = off
默认输入法       = com.baidu.input_vivo/.ImeVivoService
ADBKeyboard     = disabled
stay-awake      = false
```

生产数据库仍为114条素材、113条有效2048-byte embedding。
