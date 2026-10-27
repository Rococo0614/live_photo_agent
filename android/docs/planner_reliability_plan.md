# 端侧规划器可靠性改造计划（文献驱动）

> 定稿日期：2026-10-20
> 最近更新：2026-10-26（标明本文是单轮 planner 实验记录，不是 Agent Runtime v2 实施计划）
> 架构文档：`agent_brain_design.md`；实施顺序：`agent_runtime_migration_plan.md`
> 数据来源：`tests/evals/device_eval_report.json`（20 条端侧基线，2026-10-20 16:25）
>
> 本文档回答一个问题：**端侧 Qwen3-4B 在 Qualcomm Genie 栈上输出结构化规划，
> 为什么不可靠，以及按已有文献结论应该怎么改。**
>
> 范围说明：现有 20 条数据是独立单轮规划回归，不是多工具长链端到端成功率。
> 2-Turn 等结论继续作为历史证据保留；新的状态、工具数据流和有界闭环改造以 Runtime v2
> 文档为准，正式长链轨迹集在运行时架构落地后建设。
>
> 2026-10-27 已完成可部署 `Qwen3-4B` hybrid 原生thinking对照，结果显著回归；详见
> `native_thinking_model_comparison.md`。这不等同于测试了尚无兼容QNN包的
> `Qwen3-4B-Thinking-2507`。
>
> 同日后续已通过GGUF/llama.cpp把真正的 `Qwen3-4B-Thinking-2507` 部署到SM8850：自定义
> 行协议8/12，官方原生tool-call 12/12，OpenCL难例2/2；详见
> `qwen3_4b_thinking_2507_phone_validation.md`。生产模型尚未切换。
>
> 立场声明：本文档**不是**探索性实验设计。原计划的「散文约束 A vs 结构约束 B」
> 两臂 A/B 已废弃——文献表明该问题的基线设定本身有误（见 §2.1）。
> 本文档只做一件事：把已验证的文献结论映射到本仓库的具体代码位置。

---

## 1. 硬约束：Genie 栈没有 constrained decoding

这是所有方案的前提，已核实：

- `genie-app` 与 `libGenie.so` 的符号表中**不存在** `grammar`、`json_schema`、
  `response_format`、`ebnf` 任何一项。
- 可用能力仅：`GenieSamplerConfig_setParam`（`seed` / `random-seed` / `top-k` /
  `top-p` / temperature）、`GenieDialog_setStopSequence`、`GenieDialog_setMaxNumTokens`。

**结论：文献中「直接上 grammar / JSON-schema 约束解码」这条主路线在端侧不可用。**
可用的结构杠杆只有四个：prompt 措辞、输出格式设计、`setStopSequence`、
parser + 后置校验。

当前采样配置（`genie_config.json`）：`temp=0.8`、`top-k=40`、`top-p=0.95`、
`seed=42`、`context=4096`。`temp=0.8` 对结构化输出偏高，`agent_brain_design.md`
§9.2 已把它列为待验证风险，至今未处理。

---

## 2. 文献结论（已核实引用）

引用均已通过 arXiv API 核验存在性与标题。早期检索中出现的不可溯源百分比与
伪造引用已剔除，下表只保留核实过的条目。标 ★ 者已读全文，数字取自原文。

| arXiv | 标题 | 对本项目的作用 |
|---|---|---|
| 2604.14862 ★ | Schema-Key Wording as an Instruction Channel in Structured Generation under Constrained Decoding | 否掉了原 A/B 的基线设定 |
| 2604.03616 ★ | The Format Tax | 否掉了「加 few-shot」；给出 thinking / 2-Turn 的量化收益 |
| 2609.23742 ★ | Constrained Decoding Eliminates Structural Failures in Small LLMs but Reveals a Scale-Dependent Semantic Gap | 双轴评测；hollow rescue 陷阱 |
| 2605.02363 ★ | When Correct Isn't Usable: Improving Structured Output Reliability in Small Language Models | 迭代优化循环的可行性与收益 |
| 2603.15118 | VAREX | schema echo 现象命名与量级 |
| 2607.20492 | PhantomFill: When the Form Demands an Answer, Language Models Invent One | 必填槽诱发编造 |
| 2501.10868 | JSONSchemaBench | schema 复杂度与合规率关系 |
| 2408.02442 | Let Me Speak Freely? | 格式限制损害推理的早期证据 |
| 2311.07911 | IFEval | 指令遵循评测方法论 |
| 2307.09702 | Efficient Guided Generation for LLMs (Outlines) | 约束解码原理（本项目不可用，仅作对照） |

### 2.1 原 A/B 的基线设定是错的

Schema-Key Wording（2604.14862, AACL-IJCNLP 2026）把「结构约束 vs 语言约束」
形式化为 **multi-channel instruction problem**，四个设置：
`None` / `Key-only`（结构通道）/ `Prompt-only`（语言通道）/ `Both`。
7 个模型（含 **Qwen2.5-3B、Qwen2.5-7B**、Llama-3.2-1B/3B、Llama-3.1-8B），
GSM8K + Math500，20000 次 bootstrap：

| 模型 | Δkey（结构） | Δprompt（语言） | Δint（交互） |
|---|---|---|---|
| Qwen2.5-3B | +4.32\* | +2.58\* | **−8.87\*** |
| Qwen2.5-7B | +7.81\* | +2.88\* | **−3.87\*** |
| Llama-3.2-1B | +5.99\* | +4.25\* | +4.09\* |
| Llama-3.2-3B | **−12.05\*** | +2.96\* | **+17.06\*** |
| Llama-3.1-8B | −3.18\* | +2.65\* | +3.79\* |

（\* = 95% CI 不含 0）

三条结论：

1. **没有普适赢家**。结构通道在 Qwen 家族为正，在 Llama-3.2-3B 上大幅为负。
2. **语言通道更稳定**：7 个模型中 6 个为正效应。
3. **同一约束在两个通道同时表达会互相干扰**：交互项 6/7 显著，
   Qwen2.5-3B 上是 **−8.87\***（显著有害）。

**而本项目现状恰好就是那个有害的 `Both` 设置。**
`GenieTextPlanner.kt:244-259` 的 system prompt 里，同一条约束既写在散文里
（「输出 ACTION=PLAN 或 ACTION=CLARIFY；PLAN 时每个步骤一行」），
又写在结构模板里（`STEP=工具ID|effect|start_ms|end_ms|素材序号|用户原话证据`）。

> **所以真正的问题不是「结构 vs 语言选哪个」，而是「每条约束只在一个通道表达」。**
> 原 A/B 的两个臂都是 `Both` 的变体，测不出任何东西。这是废弃它的理由。

### 2.2 「加 few-shot 示例」被明确否掉

The Format Tax（2604.03616, UCSD）用 G/E/T/C 四因子 × 5 种生成策略，
6 开源 + 4 API 模型，144 个 cell，McNemar 配对检验：

> "Standard prompt-level mitigations—**few-shot examples and explicit schema
> descriptions—leave the majority of the degradation intact**."

> "of 39 cells with any statistically significant effect, **36 (92%) are already
> present under the format-requesting prompt alone** and only 15 (38%) involve GCD"

即：**代价产生在 prompt 层，不在解码器层；加示例救不回来。**

真正有效的两个手段是**解耦推理与格式化**：

| 手段 | 效果 | 恶化率 | 延迟 |
|---|---|---|---|
| **2-Turn**（先自由生成，再格式化） | 42/72 cell 显著改善，+6.8pp | **2/72 = 3%** | ~2× |
| **Thinking**（允许思考后再输出） | 43/72 cell 改善，+9.2pp | 11/72 = 15% | 增加 |

还有一个必须记住的反直觉数据：

> "GCD dramatically improves **format compliance from 55.7% to 92.2%** yet
> **accuracy is unchanged**—57.3% without GCD and 55.7% with, versus 61.5% freeform"

**格式合规率 ≠ 任务准确率。** 本项目 harness 的主指标 `pass_rate` 目前混着这两者，
方向本身有偏差（见 §4）。

### 2.3 我们的失败模式属于「约束解码也救不了」那一类

2609.23742 提出双轴评测（结构正确性 / 语义正确性），5 个 0.6B–4B 模型 ×
14 任务 × 3 解码条件：

| 失败原型 | 例子 | CD 可救？ |
|---|---|---|
| Type coercion | `"4.98"` vs `4.98` | ✓ 0.000 → 1.000 |
| Structural misplacement | 缺字段、字段错位 | ✓ |
| **Instruction-semantic** | 该发两个 call 只发一个 | **✗ 0.200 → 0.200** |

> "Token masking cannot inject task understanding."

本项目 prompt 里的这些规则**全部是 instruction-semantic 类**：

- 「每个动作只输出一行 STEP，不要重复输出同一动作」
- 「区分两类"换"：改变画面内容才用 asset_replace，改变颜色/色调用 color_tone」
- 「如果用户只是在闲聊…不要输出任何 STEP」
- 「只规划本轮用户原话中明确要求的动作，不要重复执行历史轮次」

即使 Genie 支持约束解码，这些也救不了。**这直接排除了「换解码栈」这条路，
把重心锁定在 prompt 结构 + 推理/格式化解耦 + 评测指标上。**

同一篇论文还警告了 **hollow rescue** 陷阱：Llama-1B 在 XGrammar 下
score=1.000（两个 call 都在、参数都在），但 email body 是 `"1. 2. 3. 4. 5."`。
**结构指标只数参数存在性，不数质量。** 本项目已经踩进去了（§4.1）。

### 2.4 工程上最可行的路径已被验证

When Correct Isn't Usable（2605.02363）提出
`output accuracy = task_accuracy × json_valid`，用 AloLab（黑盒迭代 prompt
优化器）：

| 策略 | GSM8K output acc | 延迟 |
|---|---|---|
| NAIVE（无 system prompt） | 0%（task acc 却有 77–85%） | 1× |
| REFERENCE（手写格式提示） | Qwen 74.3% / **Gemma 0%** / **GPT-4o 0%** | 1× |
| CONSTRAINED（约束解码） | 15–52% | **3.6×–8.2×** |
| **AloLab** | **84–87%**（29/30 McNemar p<0.05） | **0.71×–1.06×** |

关键洞察：

> "the optimizer must ***observe*** that this specific model systematically wraps
> JSON in markdown fences... That behavior is **not predictable from prior
> knowledge of the task**."

**优化信号来自观察到的执行轨迹，不是先验知识。**
本项目已经具备三个零件：`device_harness.py`（Evaluator）、
`genie-app` 直调（Solver，黑盒，路径 `/data/local/tmp/lpa-models/qwen/`，
SELinux Permissive，adb root，已核实可执行）、`interaction_events` 审计库（轨迹）。
缺的只是 Analyzer + Optimizer 循环。

顺带一个好消息：Gemma 和 GPT-4o 都栽在 markdown fence wrapping 上，
而本项目实测 `markdown_pollution = 0/20`——「不要输出 Markdown」这条散文规则
是**有效的**，属于应当保留的语言通道约束。

---

## 3. 现状诊断（20 条端侧基线，2026-10-20，P3 修复前）

> **本节数据已被 §7 取代。** 这里的所有数字都来自 P3 修复之前的 harness 与 APK，
> 其中 `keyword_fallback`、`intent_returned_tools`、`has_action_header` 三项
> 后来被证明是**测量错误**而非模型缺陷（见 §7.1）。
> 保留本节是为了记录「错误的指标会导出错误的结论」这一过程本身——
> §3.2 预测的 `brain_pass_rate ≈ 0.45` 实测是 **0.75**，偏差完全来自归因 bug。

### 3.1 汇总指标

> 右列是 P3 修复后的实测值（§7.2，60 轮）。**三个指标原来的读数是测量 bug。**

| 指标 | 修复前 (n=20,r=1) | 修复后 (n=60,r=3) | 说明 |
|---|---|---|---|
| `pass_rate` | 0.80 (16/20) | 0.80 (48/60) | 唯一从头到尾正确的数字 |
| `brain_pass_rate` | — | **0.75 (45/60)** | 新主指标 |
| `intent_called` | 1.00 | 1.00 | intent 阶段每轮都跑 |
| `intent_returned_tools` | 0.00 ✗ | **0.20 (12/60)** | 修复前受审计偏差污染（§3.4） |
| `chat_swallowed_edit` | 0.15 | 0.15 (9/60) | D005/D006/D007，未改善 |
| `keyword_fallback` | **0.55 ✗** | **0.10 (6/60)** | 修复前把大脑产出误记为兜底（§7.1） |
| `retrieved` | 0.55 | — | |
| `executed` | 0.55 | 0.55 (33/60) | |
| `has_action_header` | 0.05 ✗ | `action_header_rate` **0.167** | 指标名与实现不符（§4.3） |
| `has_duplicate_tools` | 0.00 | 0.00 | 去重逻辑生效 |
| `markdown_pollution` | 0.00 | 0.00 | 散文规则有效 |
| `vacuous` | — | **0** | D015 补期望后归零 |
| `sampler_variance` | 未测 (r=1) | **全部 =1** | seed=42 固定，零方差（§7.2） |

槽位回声（仅统计被 parser 接受的 6 行）：

| 槽 | echoed | filled | echo_rate |
|---|---|---|---|
| `effect` | 0 | 6 | 0.00 |
| `start_ms` | 3 | 3 | **0.50** |
| `end_ms` | 3 | 3 | **0.50** |
| `素材序号` | 3 | 3 | **0.50** |
| `用户原话证据` | 3 | 3 | **0.50** |

`sampler_variance` 为空——**本轮 `--repeat=1`，方差未测**。任何后续对比必须
≥3 重复，否则 temp=0.8 下的抽样噪声会淹没改动效果。

### 3.2 大脑真实产出率：~~45%~~ → 实测 75%（本节结论已被推翻）

> **本节的 45% 是错的。** 它建立在 `keyword_fallback=0.55` 之上，而那个数字来自
> `tryIntent` 缺 `parsed=true` 的归因 bug（§7.1）。修复后实测 `brain_pass_rate=0.75`。
> 保留推导过程，因为它示范了「错误指标如何导出错误优先级」——
> 若按 45% 排优先级，P0/P1/P2 的预期收益会被系统性高估一倍。

当时把 16 个 pass 按「谁产出的计划」拆开：

| 类别 | case | 数量 | 修正后 |
|---|---|---|---|
| 大脑产出且通过 | D008 D009 D012 D013 D016 D017 D018 D019 D020 | 9 | 仍成立 |
| 关键词兜底产出且通过 | D001 D002 D003 D004 D011 D014 D015 | 7 | **其中 6 个（除 D011）实为大脑产出** |
| 失败 | D005 D006 D007 D010 | 4 | 仍成立 |

- ~~大脑可归因通过率 = 9/20 = 45%~~ → **15/20 = 75%**
- ~~`pass_rate = 80%` 里有 7/16 = 44% 是兜底挣来的~~ → **只有 D011 一个（1/16 = 6%）**

D015 的空洞通过判断**仍然成立**，且已修复：`expect` 是空对象 `{}`，
`_score_expectations()` 四个分支全部不触发，`scored` 只剩 `not_swallowed` 和
`turn_recorded` 两个恒真项，`all([True, True])` → pass。它没有验证任何东西。
现已补 `{"outcome": "clarify"}`，`vacuous` 计数归零（§7.2）。

### 3.3 协议漂移：大脑的 STEP 输出 9 次里 3 次不可解析

> **样本量已被 §7.7 取代**（33 轮 step_class / 18 轮解析出 STEP / 3 轮完全合规）。
> 本节的 9 次是 `--repeat=1` 的单次观测，漂移类型判断仍然成立，但比例以 §7.7 为准。
> 最重要的修正：主导漂移是**丢 ACTION 头**（8/11 case），不是字段错位。

大脑实际吐出过 STEP 类内容的 9 个 case，逐条核对 `PLAN_BRAIN.raw_output`：

| case | raw_output（原文） | 判定 |
|---|---|---|
| D008 | 6 字段合规 | ✓ |
| D012 | `STEP=video_filter\|调暖一点\|start_ms\|end_ms\|素材序号\|用户原话证据` | ✓ 但 4 槽全回声 |
| D013 | `STEP=color_tone\|鲜艳\|1\|10000\|1\|用户原话证据：把颜色调鲜艳一些` | ✓ 但证据槽带前缀 |
| D016 | 6 字段合规 | ✓ 但 4 槽回声 |
| D019 | `STEP=video_speed\|2x\|0\|7900\|1\|用户原话` | ✓ 但证据槽截断 |
| D020 | 6 字段合规 | ✓ 但 4 槽回声 |
| **D009** | `STEP=template_collage\|布局类型=3x1\|布局参数=auto\|素材序号=1,2,3` | ✗ **自造字段名，4 段** |
| **D017** | `ACTION=STEP\|video_blur\|...` + `STEP=video_blur\|start_ms\|end_ms\|1\|视频背景虚化` | ✗ **ACTION 值写成 STEP，5 段** |
| **D018** | `ACTION=PLAN\r\nSTEP=video_reverse\|end_ms=30038\|start_ms=0\|素材序号=1` | ✗ **CRLF、字段序颠倒、4 段** |

**漂移率 3/9 = 33%。被接受的 6 行里 3 行有占位符回声 = 50%。**

D018 尤其说明问题：模型把 `start_ms` / `end_ms` 当成 `key=value` 写，
且顺序颠倒。它不是在填槽，是在**猜格式**——因为占位符本身就是合法字段名，
模型无法区分「这里该填值」和「这里的字面值就是 `start_ms`」（PhantomFill,
2607.20492 描述的正是这个机制）。

### 3.4 审计机制有幸存者偏差，`intent_returned_tools` 不可用

`GenieTextPlanner.lastTrace` 是**单字段**：

- `tryIntent()` 写入 `source=qnn_genie_intent`
- `tryPlan()` 随后**覆盖**为 `source=qnn_genie`
- `PLAN_BRAIN` 只在 `AgentActivity.onPlan` 时落库
- `intentPlan` 只有走 CHAT 快速通道时才会被 `onPlan` 报告

因此审计库里的 `qnn_genie_intent` 记录是**被 CHAT 通道筛过的子集**。
`intent_returned_tools = 0/20` 不能证明 intent 阶段产不出工具，
只能证明「产出工具的 intent 调用没被记录下来」。

历史 14 次 intent 调用的真实分布：6 次被 CHAT 快速通道吞掉、
7 次未产出可用 `search_assets`（后续用原话兜底检索）、1 次被 capability 关键词拦截。

**在修好审计之前，任何关于 intent 阶段的结论都不成立。**

> **已修复（§7.6 Bug 3）。** 新增 `INTENT_BRAIN` 审计行 + `Listener.onIntentTrace`，
> intent trace 不再被 `tryPlan` 覆盖，也不再依赖 CHAT 快速通道才落库。
> 修复后 60/60 轮次都有 intent trace，`intent_returned_tools = 0.20`（12/60）。
> 所以原诊断成立：真实的 intent 产出率不是 0，而是 20%——
> 修复前那个 0.00 确实是幸存者偏差的产物，不是模型能力。

### 3.5 三个已确认的执行层缺陷

| # | 缺陷 | 位置 | 证据 |
|---|---|---|---|
| A | 大脑 `ACTION=CLARIFY` 被关键词兜底覆盖 | `MobilePlanner.kt:65-73` `explicitAction` | D010 / D011 raw_output 只有 `\nACTION=CLARIFY`，兜底仍执行了 `live_photo_filter_effect` |
| B | `applyStylePreset` 枚举漏词 | `MobileAgentRunner.kt:797-818` | D012 `effect="调暖一点"` 未命中 `暖色/暖调/warm`，`temperature` 实际未设置 |
| C | CHAT 快速通道吞编辑请求 | `MobileAgentRunner.kt` `intentPlan?.takeIf { it.toolCalls.isEmpty() }?.clarification?.let` | D005 `做个狗的四拼` → intent 输出 `reply="用一句话直接回答用户"`（**逐字照抄 intentPrompt 占位符**） |

缺陷 A 与 `agent_brain_design.md` §6「不再用 MobilePlanner 硬编码兜底」的既定
决策**直接冲突**——设计文档说不兜底，代码在兜底，而且兜底掩盖了大脑的失败。

缺陷 C 是 PhantomFill 的教科书案例：`intentPrompt` 里写了
`{"intent":"chat","reply":"用一句话直接回答用户"}`，模型把**示例值当成了要输出的值**。
这和 §3.3 的槽位回声是同一个机制，只是发生在 JSON 通道。

---

## 4. 评测指标缺陷（必须最先修）

> **四项缺陷已全部修复**（2026-10-21，见 §7.9 交付物清单）。
> 本节保留原始诊断，行号指向修复前的代码。
> 修复过程中还发现三个**生产代码** bug（不属于评测层，见 §7.6），
> 其中一个正是 §4.4「缺参数质量轴」之所以测不出缺陷 B 的原因。

### 4.1 hollow rescue：pass 无法归因 ✅ 已修

`device_harness.py:402-430` `_score_expectations()` 的 `outcome_match`：

```python
elif want == "plan":
    checks["outcome_match"] = bool(tool_names) or executed
```

`tool_names` 取自 `PLAN_FINAL`，而 `PLAN_FINAL` **不区分计划来自大脑还是兜底**。
于是一个大脑完全没产出、靠关键词表救回来的轮次，判为 pass。

D011（`给这张加个复古滤镜`）就是活例：大脑只输出 `ACTION=CLARIFY`，
零 STEP，兜底补出 `live_photo_filter_effect`，`passed=True`。

**后果：P0–P2 的每一项改动，其"改善"都可能只是兜底路径的波动。
指标不修，后续所有对比都不可信。**

### 4.2 `all([]) == True` 型空洞通过 ✅ 已修

`checks["passed"] = all(scored) if scored else None`。
当 case 的 `expect` 缺少 `outcome` 且无 tool 约束时，`scored` 只剩
`not_swallowed` 和 `turn_recorded` 两个恒真项 → pass。D015 即此类。
`turn_recorded` 这个 guard（`device_harness.py:427`）只挡住了「完全没观测到」，
没挡住「观测到了但什么都没断言」。

### 4.3 指标名与实现不符 ✅ 已修

`aggregate()` 里：

```python
"has_action_header": rate(lambda m: m.get("has_step_output") and m.get("has_action_header")),
```

字段名叫 `has_action_header`，实际测的是「有合规 STEP 行 **且** 有 ACTION 头」
两个条件的**交集**，分母还是全部 20 条。逐条核对（11 条有非空 `step_raw`）：

- 出现任意 `ACTION=` 行的：5 条（D010 D011 D012 D017 D018）
  - 其中 D010 / D011 是 `ACTION=CLARIFY`（无 STEP），D017 是畸形的 `ACTION=STEP|...`，
    真正合规的 `ACTION=PLAN` 只有 D012 / D018 两条
- 产出可解析 STEP 行（`has_step_output=True`）的：6 条（D008 D012 D013 D016 D019 D020）
- 两者交集：仅 **D012** → 汇总报 `1/20 = 5%`

**这个 5% 同时误导两个方向**：它既不是 ACTION 头的出现率（那是 5/11 = 45%），
也不是 STEP 产出的合规率。真正该报的是——**在 6 条产出了 STEP 的轮次里，
只有 1 条同时保留了 ACTION 头（1/6 = 17%）**，即大脑一旦开始吐 STEP，
83% 的概率把 `ACTION=` 头丢掉。这与旧审计「`ACTION=PLAN` 头遵守率 21%」一致，
是 `parse()` 里 `hasSteps` 兜底逻辑（`GenieTextPlanner.kt:105`）存在的根本原因。

**修法见 P3 第 4 项**：拆成 `action_header_rate`（分母 = 有 STEP 类输出的轮次）
和 `protocol_clean_rate`（分母同上，分子 = 6 字段全合规），不要再用交集除以全集。

### 4.4 缺参数质量轴 ✅ 已修

harness 只检查「工具名对不对」，不检查「参数是大脑给的还是硬编码补的」。
2609.23742 的双轴评测要求分开报告结构正确性与语义正确性，本项目只有前者。

---

## 5. 改造计划

每步给出：依据 / 改动位置 / 风险 / 验证方法 / 回滚方式。
**所有验证统一用 `device_harness.py --repeat 3`。**

> `--repeat 3` 的作用是**确认确定性**而非测方差：§7.2 实测 `seed=42` 下
> 20 个 query 的 `distinct_outcomes` 全部为 1，三次重复零方差。
> 所以任何失败都是系统性的、可复现的，改 prompt 一定能改掉。

### P3 — 修评测指标（前置，其余全部依赖它）✅ 已完成 2026-10-21

> **实测结果见 §7。** 五项子任务全部落地，另加三个计划外发现的生产 bug（§7.6）。
> 最重要的结论：§3 里 `keyword_fallback=0.55` 和 `intent_returned_tools=0.00`
> **都是测量错误**，修复后分别是 0.10 和 0.20；`brain_pass_rate` 实测 0.75，
> 而 §3.2 基于错误归因预测的是 0.45。

**依据**：2609.23742 的 hollow rescue 警告；§4 的四项实测缺陷。

**改动**（全部在 `tests/evals/device_harness.py`，**不碰生产代码，风险最低**）：

1. **归因拆分**。`_score_expectations()` 增加 `brain_attributed` 检查：
   `expect.outcome == "plan"` 时，要求 `PLAN_FINAL.source != "fallback"`。
   汇总同时报 `pass_rate`（旧口径，保留以便对比）和
   `brain_pass_rate`（新口径，主指标）。
2. **参数质量轴**。比对 `PLAN_BRAIN.raw_output` 里的 `effect` 值与
   `TOOL_CALL.arguments` 的最终数值。若数值由 `applyStylePreset` 补出
   （大脑只给了风格名、没给 `saturation`/`temperature`），标记 `hollow_params=True`
   并单列统计。
3. **堵空洞通过**。`scored` 为空或只含恒真项时，`passed = None` 而非 `True`；
   汇总里单列 `vacuous` 计数。给 D015 补 `expect.outcome`。
4. **修指标名**。`has_action_header` 拆成两个字段：
   `action_header_rate`（分母 = 有 STEP 类输出的轮次）和
   `protocol_clean_rate`（分母同上，分子 = 6 字段全合规）。
5. **修审计偏差**。这条要碰生产代码：`GenieTextPlanner.lastTrace` 单字段改为
   `intentTrace` / `planTrace` 双字段，`AgentActivity` 分别落
   `INTENT_BRAIN` / `PLAN_BRAIN`。**在拆分之前，`intent_returned_tools`
   指标从报告中移除**，避免继续被误读。

**验证**：重跑基线，确认 `brain_pass_rate` 与手工归因一致。
> 原计划写的是「确认 `brain_pass_rate ≈ 0.45`（与 §3.2 手算一致）」——
> **这个预期本身是错的**，因为 §3.2 的手算建立在 `keyword_fallback=0.55` 之上，
> 而那个数字是 `tryIntent` 缺 `parsed=true` 造成的假象。实测 0.75（§7.1）。
> 教训：验证标准如果和被验证的指标共享同一个错误假设，就验证不出任何东西。

**回滚**：纯 harness 改动，git revert 即可；第 5 项独立提交。

---

### P0 — 原生 thinking（当前模型不可用；原「删 `/no_think`」计划作废）

> **2026-10-22 纠正**：本节此前把「tokenizer 中存在 `<think>` token」误判成
> 「模型权重支持 thinking」。设备实际部署的是
> `Qwen3-4B-Instruct-2507 W4A16`（`genie_config.json` 的四个
> `qwen3_4b_instruct_2507_w4a16` binary）。Qwen 官方模型卡明确写明：
> **“This model supports only non-thinking mode and does not generate
> `<think></think>` blocks.”** Thinking 是另一套独立权重
> `Qwen3-4B-Thinking-2507`，当前机器和仓库都没有对应 QNN binary。
> 模型卡：<https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507>；Qualcomm 端侧模型说明同样标注
> instruct-only：<https://aihub.qualcomm.com/models/qwen3_4b_instruct_2507>。

因此：

1. 删除 `/no_think` **不能开启原生 thinking**；intent prompt 没有 `/no_think`
   也不代表 intent 已经在思考。P2a 的 60/60 条 intent 原文中 `<think>` 与
   `</think>` 均为 0 次；此前所谓「对照组污染」并不存在。
2. tokenizer 能编码某个 special token，只证明词表兼容，不证明模型经过对应行为训练。
   `non-thinking` 也不等于模型完全不会推断；它表示没有可开启的专用 thinking 输出模式。
3. 真正测试原生 thinking 必须先转换并部署 `Qwen3-4B-Thinking-2507` 的 QNN 模型包；
   这是模型替换实验，不是一行 prompt 改动，而且 Thinking-2507 官方建议的输出长度远超
   当前 Genie 的 2K/4K 端侧上下文预算。
4. Format Tax 的 thinking 收益仍有参考价值，但本工程当前能测试的等价近似只能是：
   **单次调用中先输出自由草稿、再输出最终结构**（`scratchpad`），必须明确标为
   prompt-induced scratchpad，不能冒充模型原生 thinking。

当前改为四臂实测：`baseline` / `p1` / `scratchpad` / `two_turn`。
每臂写入审计字段 `strategy`、`reasoning_output`、分阶段延迟，避免只看最终格式猜测
模型是否真的做了额外串行计算。原生 thinking 能力另用最小探针复核，但官方模型规格已足以
否决「删 `/no_think` 即开启」这条工程路线。

---

### P1 — 消除双通道冗余（Schema-Key 论文的直接应用）

**依据**：Qwen2.5-3B 上 `Both` 设置 Δint = **−8.87\***（显著有害）；
本项目现状正是 `Both`。

**改动**：逐条梳理 `GenieTextPlanner.kt:244-259` system prompt 里的每条约束，
标注它属于哪个通道，**每条只保留一个**。

初步梳理（待 A/B 确认）：

| 约束 | 散文通道 | 结构通道 | 建议保留 |
|---|---|---|---|
| 输出 PLAN / CLARIFY / CHAT | ✓ 有 | ✓ `ACTION=` | 结构 |
| 每步一行、6 字段 | ✓ 有 | ✓ `STEP=a\|b\|c\|d\|e\|f` | 结构 |
| 不要 Markdown | ✓ 有 | ✗ | **散文**（实测有效，0/20 污染） |
| 闲聊不输出 STEP | ✓ 有 | ✓ `ACTION=CHAT` | 结构 |
| 不重复历史轮次动作 | ✓ 有 | ✗ | **散文**（纯语义，无结构可表达） |
| 换内容 vs 换色调 | ✓ 有（长段） | ✗ | **散文**（instruction-semantic） |
| 选中素材作用域 | ✓ 有 | ✓ `素材序号` 槽 | 结构 |

**这里的 A/B 是「删哪个通道」，不是「哪个通道更好」**——这是与原方案的关键区别。

**倾向**：Qwen 家族上 key（结构）通道为正（+4.32 / +7.81），
本项目是 Qwen3-4B，倾向保留结构通道、删重复散文。
但论文同时显示语言通道更稳定（6/7 模型为正），**必须实测，不能靠家族外推**。

**风险**：低。纯 prompt 改动，零延迟代价。
**验证**：`--repeat 3`，主指标 `brain_pass_rate` + `protocol_clean_rate`。
**回滚**：git revert。

---

### P2 — 修占位符设计（针对 schema echo）

> **状态：P2a 部分已执行并验证**（§8）。下面第 1、2、4 项（减槽位、时间改可选后缀、
> 删证据槽）已落地，STEP 协议占位符回声归零；第 3 项（`intentPrompt` 的 `reply`
> 示例值）属 intent 阶段，本轮未动，回声仍在（§8.6），归入 P1。

**依据**：VAREX（2603.15118）命名此现象，≤4B 模型压制 45–65pp；
PhantomFill（2607.20492）给出机制——**必填槽诱发编造**。
本项目实测：时间槽 / 序号槽 / 证据槽回声率 **50%**，
`intentPrompt` 的 `reply` 示例值被逐字照抄（§3.5 缺陷 C）。

**根因**：`STEP=工具ID|effect|start_ms|end_ms|素材序号|用户原话证据`
的占位符**本身就是合法字段名**。模型无法区分「待填槽」与「字面值」。
D018 把 `end_ms=30038|start_ms=0` 写成 key=value 且顺序颠倒，
正是这种歧义的直接后果。

**改动**：

1. **减少槽位**，而不是加说明。Format Tax 已证明「加 explicit schema
   description」无效，所以不能靠在 prompt 里解释「start_ms 是数字」来修。
2. 时间参数改为**可选后缀**：`工具ID|effect[@start-end]`。
   绝大多数工具（`video_mirror` / `video_blur` / `video_reverse` /
   `template_collage` / `color_tone`）根本不需要时间参数，
   强制填 4 个槽是在**制造**回声。
3. `intentPrompt` 的 JSON 示例里，把 `"reply":"用一句话直接回答用户"`
   改成不可能与真实输出混淆的形式，或直接去掉示例值只留 key。
4. `用户原话证据` 槽：D013 / D019 分别产出 `用户原话证据：把颜色调鲜艳一些`
   和 `用户原话`（截断）。`parse()` 只把它塞进 `MobileToolCall.reason`
   （`GenieTextPlanner.kt:153`），**对执行没有任何作用**——下游只用于
   UI 展示（`AgentActivity.kt:729`）和审计（`:740`）。
   它占了 1/6 的输出预算并贡献回声，而产出质量已经劣化到截断和带前缀。
   **考虑直接删除，UI 展示改为回显用户原话本身。**

**风险**：中。改格式要同步改 `parse()` 的正则与字段索引。
**验证**：`echoed_slots` 各槽计数应显著下降；`protocol_clean_rate` 上升。
**回滚**：格式与 parser 在同一 commit，一起 revert。

---

### P4 — AloLab 式迭代优化循环

**依据**：2605.02363——84–87% output accuracy，29/30 McNemar p<0.05，
**延迟不增反降（0.71×–1.06×）**。这是文献里唯一同时改善质量和延迟的方案。

**零件已齐**：

| AloLab 组件 | 本项目对应 | 状态 |
|---|---|---|
| Solver（黑盒） | `genie-app` 直调，`/data/local/tmp/lpa-models/qwen/` | ✓ 已核实可执行 |
| Evaluator | `device_harness.py` 确定性指标 | ✓ 需先做 P3 |
| 执行轨迹 | `interaction_events` 审计库 | ✓ 需先修 §3.4 偏差 |
| Analyzer | — | ✗ 缺 |
| Optimizer | — | ✗ 缺 |

**缺的是 Analyzer + Optimizer**：用强模型读失败轨迹、重写 prompt。

**成本**：20 query × 4 epoch × 5 run ≈ 400–2000 次调用 ≈ 1–6h，一次性投入。

**注意事项**：

- meta-agent 必须够强。论文消融显示弱 meta-agent（Haiku）只有
  61.0% ± 21.8pp，5 次里 3 次失败。
- 直调 `genie-app` 时 HTP/DSP 可能被独占，实验期间需
  `am force-stop com.vivo.lpa`。
- 优化目标必须是 P3 修好后的 `brain_pass_rate`，**不能是格式合规率**
  （§2.2：合规率 55.7%→92.2% 而 accuracy 不变）。

**风险**：中。产出的是 prompt 文本，需要人工 review 是否引入了
不可接受的语义偏移（优化器可能学会「总是输出 template_collage」这类退化解）。
**验证**：优化后的 prompt 必须在**留出集**上复测，不能只在优化用的 20 条上报数。

---

### P5 — 2-Turn 解耦（当前模型可用的主实验）

**依据**：Format Tax 中**最安全**的缓解手段——42/72 cell 改善（+6.8pp），
**仅 2/72 = 3% 恶化**（对比 thinking 的 15%）。

**代价**：两次推理，6–11s → 12–22s。端侧交互可能不可接受。

**2026-10-22 修订**：原生 P0 已被模型规格否决，P5 不再是备选，而是当前
`Qwen3-4B-Instruct-2507` 上真正实现「语义生成与格式化解耦」的主实验。
同时增加单调用 `scratchpad` 作为延迟较低的近似对照，但不能称为原生 thinking。

**改动**：第一次调用让模型自由输出自然语言计划（无格式要求），
第二次调用把自由文本 + 格式模板喂回去，只要求格式化。
第二次调用可以用更小的 `maxTokens`，且因为输入已确定，可以考虑降温。

> **实测结论（2026-10-22，见 §8.12）**：当前实现显著负收益，
> `brain_pass_rate=0.35`，turn p50=25.37s、p95=59.47s。问题不在 formatter
> 能否生成合法行，而在自由阶段已经误分类和过度规划；第二次调用只能忠实放大错误。
> 本方案不进入生产默认，也不做 20×3 确认。

---

## 6. 执行顺序

```
P3（修指标）✅ → P2a（删 ACTION 头 + 减槽位）✅ → 移除 Android 冗余 smart_collage
                                              → P1（消双通道 + 修占位符）
                                              → scratchpad vs 2-Turn
                                              → P4（迭代优化）
```

**P3 已完成**（2026-10-21，实测结果见 §7）。它的价值超出预期：不仅修好了指标，
还证明 §3 里三个最刺眼的数字中**有两个是测量 bug**（§7.1），
并顺带挖出三个生产 bug（§7.6）。

**P2a 已完成**（2026-10-21，实测结果见 §8）。协议层完全达成：
`protocol_clean_rate` 0.167→1.000，占位符回声从 4 槽各 50% 降到 0，
`rescued_by_tables` 3→0，`pass_rate` 与 `brain_pass_rate` 首次收敛。
代价是暴露出一个此前被兜底掩盖的 prompt/评测集冲突（§8.4，决策点 7）。

**P3 也改变了后续步骤的优先级判断：**

1. **原生 P0 已关闭。** §5 的 2026-10-22 纠正确认当前 Instruct-2507 只支持
   non-thinking；`/no_think` 有无不是实验变量。延迟与 token 预算改由
   `scratchpad` / `two_turn` 两个当前模型真正能执行的策略实测。
2. **§6 决策点 3（降温）可以直接关闭。** §7.2 实测 `sampler_variance` 全部
   `distinct_outcomes=1`——`seed=42` 固定，`temp=0.8` 根本没产生多样性。
   「高温放大格式漂移」的担忧不成立，所有漂移都是系统性的、可复现的。
   降温不会改善任何东西，**不必做**。
3. **P2a 的收益被上调，且已验证。** §7.7 显示主导漂移是「丢 ACTION 头」（8/11 case），
   而 `parse()` 的 `hasSteps` 兜底已经是主要工作路径。
   与其让模型遵守一个它 83% 概率丢掉的头，不如**直接删掉 ACTION 头要求**。
   §8.2 实测证实：`protocol_clean_rate` 0.167→1.000，退役探针三项全 0。
4. **P1 现在只需处理一处占位符。** §7.4 证实模型逐字照抄
   `"用一句话直接回答用户"`，原出现在 `GenieTextPlanner.kt:218`（JSON 示例）
   和 `:255`（散文）两处。**P2a 删 ACTION 头时已连带删掉 `:255` 的散文版**，
   只剩 intent prompt 的 JSON 示例一处（现 `:239`）。§8.6 实测该处回声仍在（D005 三轮全中）。

修订后的建议顺序：

```
P1（消双通道 + 修占位符）→ scratchpad vs 2-Turn
                        → P4（迭代优化）
```

P1 是零额外调用、确定性可复现的改动，直击 §7.4 / §8.6 已证实的占位符回声机制。
先取得 P1 基线，再比较 scratchpad / 2-Turn，避免把已知 prompt bug 的修复算成策略收益。

### 待定的决策点

| # | 决策 | 状态 |
|---|---|---|
| 1 | 端侧交互延迟预算 | **实测数据已就绪**（§7.8）：p50 13s / p95 20.5s / max 27s。后续以完整 turn 延迟比较 P1 / scratchpad / 2-Turn；每次 `runText` 仍维持 30s 上限，不为实验放宽 SLA |
| 2 | P1 删哪个通道 | **部分关闭**。ACTION 头（结构通道）已由 P2a 整体删除，§8.2 实测删除后合规率 1.000，不再需要 A/B 比较它与散文通道。P1 剩下的双通道问题是 intent prompt 内部（散文规则 vs JSON 示例），仍需实测 |
| 3 | ~~`temp=0.8` 是否降温~~ | **已关闭**。§7.2 实测零方差，seed 固定，降温无收益 |
| 4 | 缺陷 A（CLARIFY 被兜底覆盖） | **已由 P2a 修复**（§8.2/§8.3）。根因不是兜底覆盖，而是 prompt 从未提及 `QUESTION=`，而 `parse()` 要求它——23 轮 `ACTION=CLARIFY` 全部解析失败。P2a 让字段名本身成为类别判据并在 prompt 中明确要求，`rescued_by_tables` 3→0，D011 转为大脑自挣。与 `agent_brain_design.md` §6 的冲突随之消解（现已不再依赖硬编码兜底） |
| 5 | 缺陷 B（风格名解析失败） | §7.5 已用审计数据实锤（D012 无 temperature）。修法有两条：扩表（用户已明确反对硬编码兜底）或让大脑直接输出数值参数（P2 方向）。**建议走后者** |
| 6 | 设备副作用清理 | 默认输入法仍是 `com.android.adbkeyboard/.AdbIME`；测试会话与写入系统相册的结果仍在。未清理 |
| 7 | 拼接工具选 `template_collage` 还是 `smart_collage` | **已关闭**。Android 侧 `smart_collage` 没有消费 VLM 语义，只比 `template_collage` 多一个失败门；已按用户决策全量移除（保留历史文件清理前缀），Python 侧真实 VLM-scored 实现不动。正在做 20×3 复测 |

---

## 7. P3 实测结果（2026-10-21，20 cases × 3 repeats = 60 轮，0 errors）

P3 全部完成并已部署到设备。这一节是**实测数据**，取代 §3。
报告：`tests/evals/device_eval_report.json`；修复前基线存档：`device_eval_report_pre_p3.json`。

### 7.1 三个指标原来是测量错误，不是模型缺陷

这是 P3 最重要的发现：**§3 里三个最刺眼的数字，有两个是 harness/APK 的 bug 造成的假象。**

| 指标 | §3（修复前） | §7（修复后） | 差异根因 |
|---|---|---|---|
| `keyword_fallback` | **0.55** | **0.10** | `tryIntent` 从不设 `parsed=true`，导致**大脑产出的每一轮**都被记成 `source="fallback"` |
| `intent_returned_tools` | 0.00（判定不可用） | **0.20**（12/60，全部轮次有 INTENT_BRAIN） | intent 跑在一次性 planner 上，trace 只经 `intentPlan.brainTrace` 传出，检索路径上直接丢弃 |
| `has_action_header` | 0.05（名不符实） | `action_header_rate` **0.167** | 旧指标是「有 STEP 行 **且** 有 ACTION 头」的交集除以全部 20 条 |

**§3.2 预测 `brain_pass_rate ≈ 0.45`，实测 0.75。** 预测偏低的唯一原因是
`keyword_fallback=0.55` 本身是假的——D001–D004（闲聊）、D014/D015（歧义）
这 9 个 case 的回复其实都出自大脑，只是被错误归因给了关键词表。

> **教训（应写进方法论）**：`pass_rate=0.80` 从头到尾都是对的，
> 但「谁挣来的」这个归因在修复前完全不可信。
> 如果按 §3.2 的 45% 去排优先级，P0/P1/P2 的预期收益会被系统性高估。

被重新归因的 case：`D001 D002 D003 D004 D005 D006 D007 D014 D015`。
修复后仍然走兜底的只有 **D010 / D011**（两个都是 `ACTION=CLARIFY` 被兜底覆盖，即缺陷 A）。

### 7.2 headline 指标

| 指标 | 值 | 说明 |
|---|---|---|
| `pass_rate` | **0.80** (48/60) | 旧口径，保留用于对比 |
| `brain_pass_rate` | **0.75** (45/60) | **新主指标** |
| `rescued_by_tables` | **3** | 全部是 D011（3/3），hollow rescue 实锤 |
| `vacuous` | **0** | D015 补上 `clarify` 期望后，空洞通过归零 |
| `keyword_fallback` | 0.10 (6/60) | D010×3 + D011×3 |
| `chat_swallowed_edit` | **0.15** (9/60) | D005×3 D006×3 D007×3，未改善 |
| `executed` | 0.55 (33/60) | |
| `protocol_clean_rate` | **0.167** (3/18) | 18 轮解析出 STEP 行，只有 3 轮格式完全合规 |
| `sampler_variance` | **全部 `distinct_outcomes=1`** | seed=42 下采样完全确定，3 次重复零方差 |

**零方差是个重要结论**：`temp=0.8` 并没有带来输出多样性，因为 `seed=42` 固定。
这意味着（a）`--repeat 3` 的价值在于验证确定性而非测方差，
（b）§6 决策点 3 里「高温放大格式漂移」的担忧**在固定 seed 下不成立**，
（c）所有失败都是**系统性**的，不是抽样噪声——修 prompt 就一定能修掉。

### 7.3 逐 case 结果

| case | query | pass | brain | fallback | 判定 |
|---|---|---|---|---|---|
| D001–D004 | 闲聊 ×4 | 3/3 | **3/3** | 0 | ✓ 大脑（修复前误记为兜底） |
| **D005** | 做个狗的四拼 | **0/3** | 0/3 | 0 | ✗ 占位符照抄（§7.4） |
| **D006** | 给我的猫做个三拼 | **0/3** | 0/3 | 0 | ✗ 同上 |
| **D007** | 从相册中选择风景照做拼接 | **0/3** | 0/3 | 0 | ✗ 同上 |
| D008 D009 | 检索+拼接 ×2 | 3/3 | 3/3 | 0 | ✓ 但 D009 协议漂移 3/3 |
| **D010** | 把视频调成黑白的 | **0/3** | 0/3 | **3/3** | ✗ 大脑 CLARIFY 被兜底覆盖（缺陷 A） |
| **D011** | 给这张加个复古滤镜 | 3/3 | **0/3** | **3/3** | ⚠ **hollow rescue** |
| D012 | 画面调暖一点 | 3/3 | 3/3 | 0 | ⚠ 通过但**编辑无效**（缺陷 B，§7.5） |
| D013 | 把颜色调鲜艳一些 | 3/3 | 3/3 | 0 | ⚠ 通过但参数是查表来的（hollow） |
| D014 D015 | 歧义 ×2 | 3/3 | 3/3 | 0 | ✓ D015 走 chat 通道追问 |
| D016–D020 | 鲁棒性 ×5 | 3/3 | 3/3 | 0 | ✓ D017/D018 协议漂移但仍解析成功 |

### 7.4 缺陷 C 实测确认：模型逐字照抄 prompt 占位符

`intentPrompt`（`GenieTextPlanner.kt:218`）里写着示例：

```
输出 {"intent":"chat","reply":"用一句话直接回答用户"}，tool_calls 留空。
```

D005「做个狗的四拼」的实际输出，**3/3 完全一致**：

```json
{"intent":"chat","reply":"用一句话直接回答用户"}
```

模型把**示例值当成了要输出的值**，逐字照抄，`tool_calls` 为空 → 编辑请求被 CHAT 通道吞掉。
D006 / D007 同样。这是 PhantomFill（arXiv:2607.20492）的教科书案例，
也是 §3.3 里 STEP 槽位回声的同一机制在 JSON 通道上的表现。

**注意这个占位符出现在两个地方**：`GenieTextPlanner.kt:218`（JSON 示例）和
`:255`（散文「在 REPLY= 后用一句话直接回答用户」）。P1 消双通道时必须两处一起处理。

### 7.5 缺陷 B 实测确认：请求的编辑静默失效

`TOOL_CALL` 审计（修复审计保真后）对 D012「画面调暖一点」：

```
effect       = "调暖一点"
temperature  = <缺失>
contrast     = 0.12      ← video_filter 分支的通用默认值
saturation   = 0.08      ← 同上
filter_level = 0.8       ← 同上
```

`applyStylePreset` 的暖色分支检查 `contains("暖色")` / `contains("暖调")` / `contains("warm")`，
而模型给的是 **"调暖一点"** —— 三个都不匹配，走 `else -> return`。

**用户要求变暖，画面一寸都没变暖**，只套了通用对比度/饱和度。
`pass_rate` 判它通过，因为工具名 `video_filter` 对了。
这正是 arXiv:2604.03616 的核心论点：**格式合规 ≠ 任务正确**。

D012 同时也是唯一 `protocol_clean_rate` 达标的 case（3/3 格式完全合规）——
**格式最干净的那一轮，恰好是编辑无效的那一轮。**

### 7.6 顺带修掉的三个生产 bug

P3 实施过程中发现并修复，都不在原计划里：

**Bug 1 — `applyStylePreset` 别名污染审计**（`MobileAgentRunner.kt:811`）

`else -> return arguments` 返回的是**入参引用**，而 `video_filter` 分支用
`.apply { put("contrast", 0.12) ... }` **原地修改**它。由于 `logAudit` 经 `uiFor`
异步投递到 UI 线程，`PLAN_FINAL` 的序列化发生在修改**之后**，于是审计里记录了
大脑从未输出过的 `contrast/saturation/filter_level`。

后果有两层：审计不可信；**缺陷 B 被完全掩盖**——通用默认值让每次调用看起来都"有参数"，
`unresolved_style` 永远测不出来。修法：`applyStylePreset` 一律返回副本。

**Bug 2 — `TOOL_CALL` 审计不保真**（`MobileAgentRunner.kt:239`）

代码注释写着「so the TOOL_CALL audit records exactly the arguments each executor branch
receives」，但 `video_filter` 的默认值是在分支**内部**注入的，而 `onToolCall` 在分支
**之前**触发。Bug 1 修好后这个缺口立刻暴露：TOOL_CALL 里也看不到默认值了。
修法：把 `video_filter` 的默认值上提到循环前的 `styledPlan` 映射里，让注释成真。

**Bug 3 — `tryIntent` 缺 `parsed=true`**（`GenieTextPlanner.kt:64`）

即 §7.1 的归因 bug 根因。`tryPlan` 有 `lastTrace?.copy(parsed = true)`，`tryIntent` 没有。
修法：补上，并把 intent trace 拆成独立的 `INTENT_BRAIN` 审计行（新增
`Listener.onIntentTrace`），彻底消除幸存者偏差。

### 7.7 协议漂移明细（33 轮 step_class，18 轮解析出 STEP 行，3 轮完全合规）

| case | 实际输出 | 漂移类型 |
|---|---|---|
| D008 | `STEP=template_collage\|concatenate\|0\|20200\|1\|user原话:找几张美食照片拼起来` | 缺 ACTION 头；证据槽中英混写 |
| D009 | `STEP=template_collage\|布局类型=3x1\|布局参数=auto\|素材序号=1,2,3` | **自造字段名**，4 段 |
| D010/D011 | `ACTION=CLARIFY` | 无 QUESTION，被 `parse()` 判空返回 null |
| **D012** | `ACTION=PLAN` + 6 字段 | ✓ 唯一完全合规，但 4 槽全回声 |
| D013 | `STEP=color_tone\|鲜艳\|1\|10000\|1\|用户原话证据：把颜色调鲜艳一些` | 缺 ACTION 头；证据槽带前缀 |
| D016 | `STEP=video_mirror\|reversed\|start_ms\|end_ms\|素材序号\|用户原话证据` | 缺 ACTION 头；4 槽全回声 |
| D017 | `ACTION=STEP\|video_blur\|...` | **ACTION 的值写成 STEP** |
| D018 | `ACTION=PLAN\r\nSTEP=video_reverse\|end_ms=30038\|start_ms=0\|素材序号=1` | CRLF；字段序颠倒；4 段 |
| D019 | `STEP=video_speed\|2x\|0\|7900\|1\|用户原话` | 缺 ACTION 头；证据槽截断 |
| D020 | `STEP=template_collage\|concatenate\|start_ms\|end_ms\|素材序号\|用户原话证据` | 缺 ACTION 头；4 槽全回声 |

**主导漂移是「丢 ACTION 头」（8/11 case），不是字段错位。**
`parse()` 的 `hasSteps` 兜底（`GenieTextPlanner.kt:105`）因此成了主要工作路径——
它救了 D008/D013/D016/D019/D020，但也意味着 `ACTION=` 这个字段在实际运行中
基本不起作用。P1/P2 应该考虑**直接删掉 ACTION 头要求**，而不是想办法让模型遵守它。

槽位回声（18 轮解析行）：`effect` 0/16，`start_ms`/`end_ms`/`素材序号`/`用户原话证据` 各 **8/16 = 50%**。
与 §3 一致，P2 的判断成立。

### 7.8 延迟实测（P0 决策所需数据）

> **历史说明（2026-10-22）**：下面的延迟数据仍是有效基线，但据此讨论
> 「删除 `/no_think` 后的原生 thinking」已经失效；当前 Instruct-2507 不支持该模式。
> 后续只用这些数据比较 scratchpad / 2-Turn 的额外延迟。

| 阶段 | n | p50 | p95 | max | mean | 30s 超时余量（按 max） |
|---|---|---|---|---|---|---|
| intent | 60 | 6335 | 9225 | 10230 | 6721 | **2.9×** |
| plan | 33 | 6622 | **11323** | **16724** | 7436 | **1.8×** |

单位 ms。

**超时语义必须先说清楚**：`runText()` 的 30s 是**每次调用**的超时，不是一轮的总超时
（`GenieTextPlanner.kt:194` 的注释明确写了「two calls per turn stacked」）。
一轮完整交互调用两次 `runText()`（intent + plan），所以：

- 用户感知延迟 = intent + plan ≈ **13s（p50）到 20.5s（p95）**，最坏 27s
- 但**超时判定是分开算的**：intent max 10.2s（余量 2.9×），plan max 16.7s（余量 1.8×）

**对 P0 的含义（修正我最初的过度悲观判断）**：
thinking 只会加在 plan 阶段（`/no_think` 只出现在 `prompt()`，即 `tryPlan` 的模板），
所以要看的是 **plan 的 1.8× 余量**，不是整轮的 27s。

- 若 thinking 让 plan 阶段 token 数增加 ≤80%，仍在 30s 内，不会超时
- 若增加 >80%（plan max 16.7s → >30s），会撞超时并整轮失败
- Format Tax 报 thinking 通常多生成数百 token；当前 plan 输出仅 34–94 字符（§7.7），
  **基数很小，所以相对增幅可能很大**——这是真正的风险点，不是绝对秒数

另外注意：`intentPrompt` **没有** `/no_think`，而 intent p50 已达 6.3s、max 10.2s。
无法从现有数据判断 intent 阶段是否已经在思考（raw_output 里无 `think` 痕迹，
但 `maxTokens=256` 也可能把思考截断掉了）。**P0 之前应先确认 intent 阶段的思考状态**，
否则「删掉 plan 的 /no_think」这个改动的对照组本身是不干净的。

**结论：P0 不是「一行改动、低风险」，但也没有我最初判断的那么危险。**
真正的阻塞项是 `maxTokens=384` 是否够思考用——思考吃掉预算后可能截断在 STEP 之前，
那会比超时更隐蔽（输出看起来正常，只是少了步骤）。

### 7.9 P3 交付物

| 文件 | 改动 |
|---|---|
| `tests/evals/device_harness.py` | 归因拆分、参数质量轴、vacuous guard、protocol 指标拆分、latency 分位数、INTENT_BRAIN 读取 |
| `tests/evals/test_harness_p3.py` | **新增**，11 个合成用例离线验证全部新逻辑，无需设备 |
| `tests/evals/device_eval_set.jsonl` | D015 补 `{"outcome": "clarify"}` |
| `android/.../GenieTextPlanner.kt` | `tryIntent` 补 `parsed=true`（Bug 3） |
| `android/.../MobileAgentRunner.kt` | `onIntentTrace` 钩子；`applyStylePreset` 返回副本（Bug 1）；video_filter 默认值上提（Bug 2） |
| `android/.../AgentActivity.kt` | `INTENT_BRAIN` 审计行 |

新增 harness 指标：`brain_pass_rate`、`rescued_by_tables`、`vacuous`、
`intent_trace_complete`、`protocol.{step_class_turns,parsed_step_rows,action_header_rate,protocol_clean_rate,drifted}`、
`param_quality.{hollow,unresolved,brain_authored,table_authored}`、`latency_ms.{intent,plan}`、
per-case `final_source` / `clarified` / `table_authored_params`。

`intent_returned_tools` 现在**可用**（60/60 轮次有 INTENT_BRAIN），但注意
`_score_params` 里的 `_PRESET_KEYWORDS` 是 `applyStylePreset` 的**镜像副本**，
改 Kotlin 表时必须同步改 harness，否则 `unresolved_style` 会失真。

---

## 8. P2a 实测结果（2026-10-21，20 cases × 3 repeats = 60 轮，0 errors）

P2a 已部署实测。报告：`device_eval_report.json`；P3 基线存档：`device_eval_report_p3_baseline.json`。

### 8.1 改动内容

改的是 plan 阶段的 STEP 协议（`GenieTextPlanner.kt`），intent 阶段的 JSON 协议未动：

| 项 | 改前 | 改后 |
|---|---|---|
| 轮次类别 | `ACTION=PLAN/CLARIFY/CHAT` 头 | 无头，字段名即类别 |
| 追问 | `QUESTION=`（**prompt 从未提及**） | `QUESTION=`，prompt 明确要求 |
| 闲聊 | `ACTION=CHAT` + `REPLY=` | `REPLY=` |
| STEP 槽位 | 6 槽必填：`工具\|effect\|start_ms\|end_ms\|素材序号\|用户原话证据` | 2 槽：`工具\|effect` |
| 时间 | 必填槽 | 可选后缀 `@start-end`（仅 `clip_trim` 需要） |
| 序号 | 必填槽 | 可选后缀 `#n` |
| 证据槽 | 必填 | **删除**（只喂 UI 文案与审计，从不进执行器） |

parser 同步加固：`field()` 容忍 `=` / `:` / `：` 三种分隔符（设备实测两种冒号都出现过），
并接受 `CLARIFICATION=` 作为 `QUESTION=` 的别名（模型自发用过这个名字）。

### 8.2 协议层：完全达成

| 指标 | P3 基线 | P2a | 说明 |
|---|---|---|---|
| `protocol_clean_rate` | 0.167 | **1.000** | 33/33 轮全部合规 |
| `parsed_step_rows` | 18 | **33** | 分母翻倍：原先 15 轮解析失败 |
| `action_header_rate` | 0.167 | — | 头已删除，改为退役探针 |
| `legacy_header_rate` | — | **0.0** | 无一轮复活 `ACTION=` |
| `legacy_field_rows` | — | **0** | 无一轮复活 6 槽 |
| `legacy_placeholder_leak` | — | **0** | 无一轮复活旧占位符 |
| `drifted` | 10 个 case 共 30 轮 | **[]** | 空 |
| `slot_echo` | 4 槽各 50% 回声 | **effect 0.0，time_suffix 0.0** | 回声全部消失 |

设备审计独立复核（`interaction_events`，本次运行窗口内 22 条 plan 阶段记录）：
22/22 是纯 `STEP=` 形态，`ACTION=` 出现 0 次，旧占位符 0 次，`parsed=true` 22/22。

**这是 R11 的直接验证**：不是让模型遵守一个它 83% 概率丢掉的结构，而是删掉这个结构。

### 8.3 归因层：hollow rescue 归零

| 指标 | P3 基线 | P2a |
|---|---|---|
| `rescued_by_tables` | 3 | **0** |
| `keyword_fallback` | 0.10 (6/60) | **0.0 (0/60)** |
| `brain_pass_rate` | 0.75 | 0.70 |
| `pass_rate` | 0.80 | 0.70 |

`pass_rate` 与 `brain_pass_rate` **首次相等**——两者之差正是关键词表垫付的部分，
现在为 0，意味着每一个通过都是大脑自己挣的。

逐 case 归因变化：

| case | 基线 | P2a | 性质 |
|---|---|---|---|
| D011 | `brain_passed=False`（表兜底救） | **`True`** | ✅ 缺陷 A 修复，大脑自己产出 `STEP=video_filter\|复古滤镜` |
| D008 | `True` | `False` | ⚠️ 工具选择：`template_collage` → `smart_collage` |
| D020 | `True` | `False` | ⚠️ 同上 |

`brain_pass_rate` 0.75→0.70 是 **+1 −2 的净效果**，不是能力下降：D011 从"表垫付"变成"大脑自挣"，
D008/D020 因工具选择被扣分（见 8.4）。

### 8.4 两个回归：同一根因，且是既有的 prompt 冲突被暴露

D008/D020 都是 `smart_collage` 而非 `template_collage`，检索与执行均成功，
仅 `tools_must_include: ["template_collage"]` 未命中。

**根因不在 P2a 的改动里，而在 intent prompt 第 237 行**：

```
随后需要拼接时调用 smart_collage。
```

这句话一直在明确要求 `smart_collage`，与 6 个 case 期望 `template_collage` 直接冲突。
基线里大脑输出 6 槽格式、`parse()` 大量失败，于是关键词表接管并选中 `template_collage`，
**冲突被兜底掩盖了**。P2a 让大脑的输出第一次被真正解析，冲突随即显形。

两个工具在 `MobileAgentRunner.kt:471` 共用同一执行分支，行为等价，
唯一差别是 `smart_collage` 额外要求 VLM 语义就绪（`:472`）。因此这是**语义选择偏好**问题，
不是执行故障。

**待决策**（见 §6 决策点 7）：改 intent prompt 让拼接默认 `template_collage`，
还是改评测集接受两者之一。倾向前者——prompt 与评测集冲突时，
先确认哪个代表真实产品意图，而 `template_collage` 是已验证的稳定路径
（`mobile_agent_migration.md:23-24`：`smart_collage` 的 planner 仍走确定性兜底）。

### 8.5 未变的部分

| 指标 | 基线 | P2a | 说明 |
|---|---|---|---|
| `chat_swallowed_edit` | 0.15 (9/60) | 0.15 (9/60) | D005–D007 仍被吞，属 intent 阶段 |
| `intent_returned_tools` | 0.20 | 0.20 | intent prompt 未动，符合预期 |
| `executed` / `retrieved` | 0.55 / 0.55 | 0.55 / 0.55 | 无变化 |
| `markdown_pollution` | 0.0 | 0.0 | 散文约束仍 100% 有效 |
| plan p50 / max 延迟 | 6622 / 16724 ms | **5931 / 11135 ms** | 输出预算减少，略降 |
| `param_quality.unresolved` | D012 | **{}** | 缺陷 B 消失（见下） |
| `param_quality.hollow` | — | D010–D013 共 4 case | 数值仍由 `applyStylePreset` 表提供 |

**缺陷 B（风格名解析失败）本轮消失**：`unresolved` 从 D012 变为空集。
但 `hollow` 仍有 4 个 case——大脑给风格名、查表给数值，
这条路径本身没变，只是这轮的风格名都命中了词表。
**缺陷 B 未被修复，只是未复现**，仍需按 R13 走"让大脑直接输出数值参数"。

### 8.6 D005 占位符回声仍在（P1 的目标，本轮未动）

D005「做个狗的四拼」三轮全部被吞，intent 阶段输出：

```json
{"intent":"chat","reply":"用一句话直接回答用户"}
```

`reply` 逐字照抄 `GenieTextPlanner.kt:239` 的示例值，导致 App 把这句 prompt 文案
当成回答直接显示给用户。这正是 §7.4 缺陷 C，属 **P1** 范围（intent prompt 未改）。
注意此处只剩一个占位符位置：`:255` 的散文版已随 ACTION 头一起删除。

### 8.7 评测基础设施：两处修复

1. **休眠设备静默烧掉整轮评测。** 首次 20×3 运行全部 60 轮报「无法新建会话」，
   根因是 `mWakefulness=Asleep`、`mCurrentFocus=null`。新增 `ensure_awake()`
   （唤醒 + `svc power stayon true` + `dismiss-keyguard`，失败即抛异常），
   并在 `main()` 加连续 3 次会话失败即 `return 2` 的熔断，
   避免把基础设施故障写成 60 行看起来像模型全面崩溃的 error 记录。
2. **`clean` 规则误判 1 槽行为漂移。** `STEP=video_mirror` 是合法输出
   （该工具不需要 effect），原规则要求恰好 2 槽，把 D016 记成 drifted。
   改为 `len(parts) in (1, 2)`，字段名 `two_field_rows` → `current_shape_rows`。
   修正后 `protocol_clean_rate` 由 0.909 → **1.000**，`drifted` 由 `[D016×3]` → `[]`。

### 8.8 P2a 交付物

| 文件 | 改动 |
|---|---|
| `android/.../GenieTextPlanner.kt` | 删 ACTION 头与 6 槽模板；`QUESTION=`/`REPLY=` 成为类别判据；`@time`/`#ordinal` 可选后缀解析；`field()` 容忍三种分隔符；`CLARIFICATION=` 别名；`reason` 改带 effect |
| `tests/evals/device_harness.py` | `_score_step` 重写为 2 槽协议 + 3 个 `legacy_*` 退役探针；`action_header_rate` → `legacy_header_rate`；`slot_echo` 键改为动态；`clean` 规则修 1 槽误判；新增 `ensure_awake()` 与会话失败熔断 |
| `tests/evals/test_harness_p3.py` | 固件全部迁到新协议；新增 T10（旧模板复活探针）；12 例离线断言全通过 |
| `tests/evals/device_eval_report_p3_baseline.json` | **新增**，P3 基线存档 |
| `tests/evals/device_eval_failures_p3_baseline.jsonl` | **新增**，P3 基线失败明细 |

### 8.9 Android `smart_collage` 移除复测（2026-10-22，20×3，0 errors）

报告：`tests/evals/device_eval_report_smart_removal.json`；失败明细：
`device_eval_failures_smart_removal.jsonl`。

| 指标 | P2a | 移除后 |
|---|---:|---:|
| `pass_rate` | 0.70 | **0.90** |
| `brain_pass_rate` | 0.70 | **0.90** |
| `chat_swallowed_edit` | 0.15 | **0.10** |
| `keyword_fallback` | 0.0 | **0.0** |
| `protocol_clean_rate` | 1.0 | **1.0** |
| turn p50 / p95（intent+plan） | 11.26s / 17.46s | **11.37s / 17.34s** |

预期修复的 D008/D020 均从 `smart_collage` 收敛为 `template_collage`。但结果还揭示了
一个比工具等价性更重要的事实：**仅从 prompt 注册表删除一个工具，就改变了多个无关 query
的确定性输出**。D005、D007、D010 也由失败变通过，D011 则由通过变失败；固定 seed 下
三轮仍完全一致。净变化是 +5 case −1 case = +4/20，即 +20pp。

这不是采样噪声，而是小模型对候选集合长度/内容的系统性敏感。它支持后续增加候选工具路由，
也警告不能把本轮全部 +20pp 都归因于「删除错误工具语义」；其中一部分来自 prompt 分布变化。
当前仅剩两个系统性失败：

- D006「给我的猫做个三拼」：intent 判为 chat，要求用户补充不必要的风格/布局；
- D011「给这张加个复古滤镜」：intent 逐字回声 `用一句话直接回答用户`。

两者都是 intent 阶段，正好成为 P1 / scratchpad / 2-Turn 的主要观察点。

### 8.10 P1 intent 占位符/双通道清理筛选（2026-10-22，20×1，0 errors）

> 本轮为 planner-only 筛选：完整运行 intent、BGE 检索、素材准备和最终 plan，
> 在 `PLAN_FINAL` 后停止，不重复导出媒体。模型 seed 固定，前两次 20×3 已证明每个 query
> 三轮输出完全一致，因此劣于基线的实验臂不再浪费 3 倍执行量。

P1 删除了 intent JSON 中会被逐字照抄的 `"reply":"用一句话直接回答用户"`，
不再给具体示例值，只用文字说明字段和规则。

| 指标 | `smart_collage` 移除基线 | P1 |
|---|---:|---:|
| `pass_rate` / `brain_pass_rate` | **0.90 / 0.90** | 0.85 / 0.85 |
| `chat_swallowed_edit` | 0.10 | **0.05** |
| `protocol_clean_rate` | **1.00** | 0.929 |
| turn p50 / p95 | **11.37s / 17.34s** | 14.35s / 19.36s |

P1 修复 D006、D011，却新增三个系统性失败：

- D001：正确输出 `intent=chat`，但省略 `reply`；parser 丢弃 intent 结果，造成无意义检索；
- D013：把编辑请求判为 chat，并声称“已为您将颜色调鲜艳一些”，实际未执行；
- D015：intent 同样漏 `reply`，检索后 plan 静默猜成 `video_filter|樱花飘落水面`；
- D007 虽任务通过，但 STEP 自造多段字段，协议合规率下降。

所以「删除具体 JSON 示例」解决了 placeholder echo，却暴露 key omission 和虚假完成，
并非净收益。P1 不进入 20×3 确认，也不能直接设为生产默认。

### 8.11 单调用 scratchpad 筛选（2026-10-22，20×1，0 errors）

该臂不是原生 thinking，而是在一次生成中要求模型先写自由草稿，再输出
`FINAL_JSON` / `FINAL_STEPS` 后的结构结果。审计将草稿与最终文本分开保存，避免 parser
误读草稿。

| 指标 | 当前基线 | scratchpad |
|---|---:|---:|
| `pass_rate` | **0.90** | 0.70 |
| `brain_pass_rate` | **0.90** | **0.10** |
| `rescued_by_tables` | 0 | **12** |
| `keyword_fallback` | 0.0 | **0.90** |
| 解析出合法 STEP 的 plan | 36/36（20×3） | **0/18** |
| turn p50 / p95 | **11.37s / 17.34s** | 21.25s / 33.59s |

20/20 intent 确实生成了额外草稿，18 个 plan 中 14 个也有草稿；所以失败不能解释成
“模型没有照做”。真正的问题是同一次生成里两个目标仍在互相干扰：模型把说明文字中的
`编辑:`、`信息不足:`、`tool: effect` 等形态模仿成最终答案，反而不再输出 parser 要求的
`STEP=`。额外 token 提供了更多串行计算，却没有提供阶段隔离。

结论：在当前 Instruct-2507 + 简化 STEP 协议上，单调用 scratchpad 是显著负收益，
不能作为 thinking 替代，更不能靠关键词兜底后的表面 `pass_rate=0.70` 判断它可用。

### 8.12 真正 2-Turn 筛选（2026-10-22，20×1，0 errors）

该臂每个阶段都进行两次独立 QNN 调用：第一次不受 JSON/STEP 格式约束，自由理解和规划；
第二次只接收原始请求与自由规划，将其机械转换为最终协议。

| 指标 | 当前基线 | 2-Turn |
|---|---:|---:|
| `pass_rate` | **0.90** | 0.55 |
| `brain_pass_rate` | **0.90** | 0.35 |
| `rescued_by_tables` | 0 | 4 |
| `chat_swallowed_edit` | **0.10** | 0.30 |
| `keyword_fallback` | **0.0** | 0.20 |
| `protocol_clean_rate` | 1.00 | 1.00（但仅 6 个 plan 解析出 STEP） |
| turn p50 / p95 / max | **11.37s / 17.34s / 24.07s** | 25.37s / 59.47s / 62.57s |

20/20 intent 都生成了自由规划，说明 2-Turn 机制确实运行；失败发生在语义层：

- D016「左右翻转」、D018「倒放」、D019「加速两倍」在自由阶段就被判成普通对话；
- D006「猫三拼」把简单拼贴扩展成裁剪、贴纸、滤镜等多余动作，并选错
  `video_vertical_stack`；
- D007 风景拼接被扩展成 15 个摘要/动效/堆叠步骤；
- D002 能力询问的自由回答本来正确，但 formatter 将 intent 写成 `plan`，触发素材检索；
- 部分 style 请求在最终 plan 阶段被机械转换成 `QUESTION=追问`，丢失了第一阶段已有的意图。

因此 2-Turn 只解除了格式负担，**没有增强第一阶段的任务理解**。当自由规划本身错误时，
formatter 不但无法修复，还会把冗长幻觉结构化并合法化。条件合规率 1.00 不能掩盖
“只有 6 个 plan 产出可解析 STEP”的低覆盖，这再次印证格式正确不等于任务正确。

### 8.13 四臂最终结论

| 实验臂 | brain pass | turn p50 | 结论 |
|---|---:|---:|---|
| `baseline`（P2a + 移除 smart_collage） | **0.90** | **11.37s** | 当前生产候选 |
| `p1` | 0.85 | 14.35s | key omission / 虚假完成，拒绝 |
| `scratchpad` | 0.10 | 21.25s | 单调用阶段互扰，拒绝 |
| `two_turn` | 0.35 | 25.37s | 自由阶段语义错误被结构化放大，拒绝 |

当前结论不是「更多推理永远无效」，而是：对这套 non-thinking 4B 权重、约 30 个候选工具和
已经极简的 STEP 协议，主要瓶颈是**候选工具条件下的语义分类**，不是输出格式占用了推理能力。
下一步优先级应转向工具候选预路由/缩小 catalog、补充困难意图评测，而不是继续增加生成轮数。

### 8.14 演示前媒体兼容性修复（2026-10-22）

手工复测「给我的猫做个三拼」时捕获到一条完整失败链：BGE 正确找回 3 张 JPG，
final brain 却生成 `video_vertical_stack|...#1|...#2|...#3`；旧 parser 静默截断额外
字段并只保留 `#1`，最终把一张 JPEG 交给三视频堆叠 SDK，报
`SDK does not support video: null`。

修复不是为「三拼」硬编码工具，而是补齐三层通用边界：

1. **候选能力过滤**：final prompt 根据真实 scoped assets 隐藏不可执行工具；全 JPG cast
   不再展示视频工具，`video_vertical_stack` 仅在至少 3 个视频轨道时可见；
2. **parser 门禁**：P2a 协议只允许 1–2 段，额外 `|` 直接拒绝；解析作用域后再次检查
   工具/媒体兼容性，不兼容计划回到确定性 fallback；
3. **executor arity**：三视频堆叠、双视频转场等在进入 SDK 前检查真实视频数量，错误信息
   不再退化为 `null`。

同时将已有的 `isExplicitEditQuery` 保护前移到 intent CHAT 快速通道：模型仍先判断，但
「拼接/滤镜/倒放/镜像」等明确编辑请求不能被空工具 chat 回复吞掉；它仍会进入 BGE 和
post-retrieval brain，并非由关键词直接选工具。

真机端到端复测：

```text
给我的猫做个三拼
→ 检索 3 张猫图
→ STEP=template_collage|三拼
→ SDK 导出 100%
→ RESULT content://media/external/images/media/1832
```

随后 planner-only 回归覆盖 20 个 case：前 18 条全部 brain pass；宿主 15 分钟上限中止后
单独补跑 D019/D020，二者也全部 brain pass。合计 **20/20 通过、0 fallback、协议合规率
1.0**。最终手机重新安装 `baseline + plannerEvalOnly=false` 生产 APK，ADBKeyboard 已禁用。

---

## 9. 明确不做的事

| 不做 | 理由 |
|---|---|
| 上 constrained decoding / grammar | Genie 栈无此能力（§1）；且 2609.23742 证明它救不了 instruction-semantic 失败（§2.3） |
| 加 few-shot 示例 | Format Tax 明确否掉：「leave the majority of the degradation intact」（§2.2） |
| 加 explicit schema 描述 | 同上 |
| 原「散文 A vs 结构 B」两臂 A/B | 基线本身就是有害的 `Both`，两臂都测不出东西（§2.1） |
| 扩充滤镜名枚举硬编码 | 用户明确反对：硬编码只是兜底，不能包装成大脑能力。缺陷 B 的正确修法是让大脑输出数值参数，不是把 `applyStylePreset` 的词表加长 |
| 模型微调 | 用户决定：先把 agent 架构搞明白、框架完备后再考虑 tuning |
| 用 `intent_returned_tools` 做任何结论 | 审计有幸存者偏差（§3.4），修好前该指标无意义 |

---

## 10. 决策记录

| # | 决策 | 依据 |
|---|---|---|
| R1 | 废弃原两臂 A/B，改为「每条约束只在一个通道表达」 | 2604.14862，Qwen2.5-3B Δint=−8.87\* |
| R2 | P3（修指标）为所有改动的前置 | 2609.23742 hollow rescue。**实测证实且超出预期**：不仅抓到 D011 的 hollow rescue，还发现 §3 三个指标本身是测量 bug（§7.1） |
| R3 | 主指标从 `pass_rate` 改为 `brain_pass_rate` | 同上 |
| R4 | 不追求格式合规率，追求任务准确率 | 2604.03616：合规 55.7%→92.2% 而 accuracy 不变。**实测印证**：D012 是唯一格式 100% 合规的 case，却正是编辑无效的那个（§7.5） |
| R5 | ~~优先原生 thinking（P0）~~ → 比较 scratchpad 与 2-Turn | **2026-10-22 推翻原决策**：部署的是只支持 non-thinking 的 Qwen3-4B-Instruct-2507；tokenizer 含 `<think>` 不能证明权重支持。原生 thinking 需另一套 Thinking-2507 QNN binary |
| R6 | 延迟预算先实测再定 | 用户决策 2026-10-20 |
| R7 | 先写文档存档，再动代码 | 用户决策 2026-10-20 |
| R8 | 所有验证 `--repeat ≥ 3` 并报方差 | 当前基线 `--repeat=1`，temp=0.8 下噪声未测 |
| R9 | 修占位符靠减少槽位，不靠加说明 | 2604.03616 否掉 schema 描述；2607.20492 指出必填槽诱发编造 |
| R10 | ~~降温~~ **不做** | §7.2 实测 `sampler_variance` 全部 =1：`seed=42` 固定，`temp=0.8` 没产生任何多样性。降温零收益（2026-10-21） |
| R11 | P2 提前到 P0 之前，且直接删 ACTION 头 | §7.7：ACTION 头遵守率仅 17%，`parse()` 的 `hasSteps` 兜底已是主要工作路径。让模型遵守一个它 83% 概率丢掉的头没有意义（2026-10-21） |
| R12 | ~~P0 阻塞项是 `maxTokens`~~ → 原生 P0 不成立 | **2026-10-22 修正**：intent 没有 `/no_think` 也不会思考，模型规格已经关闭该问题。token/超时风险改在 scratchpad 与 2-Turn 实测中分别记录 |
| R13 | 缺陷 B 走「让大脑输出数值参数」而非扩表 | §7.5 实锤 D012 无 temperature；用户已明确反对硬编码兜底被包装成大脑能力（2026-10-21） |
| R14 | 验证标准不得与被验证指标共享假设 | §3.2 用 `keyword_fallback` 手算出 45% 作为 `brain_pass_rate` 的验收线，而两者共享同一个归因 bug，导致验收线本身就是错的（2026-10-21） |
| R15 | **P2a 已验证**：删结构比让模型遵守结构有效 | §8.2 实测 `protocol_clean_rate` 0.167→1.000，`legacy_*` 三项全 0，`drifted` 清空。R11 的判断成立（2026-10-21） |
| R16 | **P2a 已验证**：减槽位比加说明有效，占位符回声归零 | §8.2：4 个槽各 50% 回声 → effect 0.0 / time_suffix 0.0。印证 R9（2607.20492 必填槽诱发编造）（2026-10-21） |
| R17 | 修好归因后，`pass_rate` 与 `brain_pass_rate` 应当收敛 | §8.3：两者首次相等（0.70 = 0.70），`rescued_by_tables` 3→0。这是 R3 主指标切换的预期终态（2026-10-21） |
| R18 | 兜底会掩盖 prompt 与评测集的冲突，修好解析后必须复查工具选择 | §8.4：intent prompt `:237` 要求 `smart_collage` 与 6 个 case 期望 `template_collage` 长期冲突，基线因 `parse()` 失败走兜底而未暴露（2026-10-21） |
| R19 | 基础设施故障必须与模型失败可区分 | §8.7：休眠设备让 60/60 轮报「无法新建会话」，形态与模型全面崩溃无法区分。加 `ensure_awake()` + 连续 3 次会话失败熔断 `return 2`（2026-10-21） |
| R20 | 合规判据不得把合法变体记为漂移 | §8.7：`STEP=video_mirror`（1 槽，该工具无需 effect）被 `clean` 规则误判，虚报 `protocol_clean_rate` 0.909 而非 1.000。与 R14 同源——判据自身有 bug 时，结论方向会错（2026-10-21） |
