# Android Agent Runtime v2 实施计划

> 日期：2026-10-26
>
> 前置设计：[`agent_brain_design.md`](agent_brain_design.md)
>
> 原则：先统一状态与执行语义，再接入模型闭环，最后建设长链轨迹数据集

## 0. 当前实施状态

| Phase | 状态 | 说明 |
|---|---|---|
| 0 文档与基线 | 完成 | 基线为 `master@3d15ebe` 上的既有混合工作区；生产参数保持 baseline/static |
| 1 状态与 ToolSpec | 完成 | 新增 Artifact/InputRef/RuntimeState/StateDelta；候选上限单测通过 |
| 2 单工具执行与数据流 | 完成 | 31/31 注册 executor 真机执行通过；拼贴产物→滤镜→发布数据流已验证 |
| 3 搜索一等化 | 完成首版 | `search_assets` 进入标准 TOOL_CALL/RESULT/STATE_DELTA；replacement 共用搜索服务 |
| 4 Kotlin AgentLoop | 完成首版 | 8 步、2 次修订、singleton/idempotency 门禁和 checkpoint 已实现 |
| 5 路线/绑定/修订 | 完成 | 28个模型选择与2个唯一候选自动绑定共30/30通过；九拼触发 revise/ASK |
| 6 UI/审计/恢复 | 部分完成 | 全链审计和步骤 checkpoint 已有；进程中断明确提示，不自动恢复进行中的 SDK 调用 |
| 7 真机验收/轨迹集 | 切换验收完成 | 逐工具、绑定、长链、倒放重复性均通过；正式扩展轨迹集继续迭代 |

详细真机证据见 [`agent_runtime_v2_validation.md`](agent_runtime_v2_validation.md)。

## 1. 改造策略

本次不是在现有 prompt 上继续打补丁，而是自底向上改造运行时。实施期间遵循：

1. **static baseline 保留回滚能力**：loop 已成为默认，出现回归时可显式构建 static；
2. **每阶段单独可验证**：不把状态、executor、planner、常驻进程一次性重写；
3. **先保持行为等价，再引入动态行为**：先让旧计划跑在新执行内核上；
4. **模型协议最后修改**：底层状态和工具契约稳定前，不调整 Qwen prompt；
5. **单元测试不是评测集**：架构阶段可以并且必须测试状态转换与工具过滤；正式长链数据集后置；
6. **不顺手扩能力**：本轮不新增媒体工具，不更换模型，不改 VLM 索引策略。

## 2. 目标依赖关系

```text
AgentArtifact / InputRef / AgentRuntimeState
                    ↓
              ToolSpec / Validator
                    ↓
       MobileToolExecutor / StateReducer
                    ↓
      search_assets 一等化 / 显式 publish
                    ↓
             MobileAgentLoop 骨架
                    ↓
      Route Planner / Binder / Replanner
                    ↓
          UI、恢复、真机长链验收
                    ↓
              正式轨迹数据集
```

任何上层阶段不得绕过尚未完成的下层契约。

---

## 3. Phase 0 — 文档与基线冻结

### 工作

- [x] 将 `agent_brain_design.md` 更新为 v2 权威设计；
- [x] 区分 static baseline 与 loop runtime；
- [x] 确定单轮具体候选工具上限为 10；
- [x] 确定采用事件驱动的 Plan-Execute-Replan，而非每步 ReAct；
- [x] 确定正式长链数据集后置；
- [x] 记录改造前的 Git 状态、APK 构建参数和手机生产配置；
- [x] 为新运行时确定独立 `agentRuntime=static|loop` 构建参数设计，但本阶段不改变默认值。

### Gate

- 架构规则、corner cases、迁移阶段无互相冲突；
- 文档明确哪些是当前能力、哪些是目标能力；
- 不产生运行代码行为变化。

---

## 4. Phase 1 — 状态、引用与工具契约

### 4.1 新增运行时模型

建议新增文件：

```text
AgentRuntimeState.kt
AgentArtifact.kt
AgentPlanModels.kt
MobileToolSpec.kt
```

核心类型：

- `AgentRuntimeState`：当前素材、选择、布局、artifact、计划版本和终态；
- `AgentArtifact`：图片、视频、Live Photo、布局、JSON 等显式产物；
- `AgentInputRef`：`AssetSetRef` / `ArtifactRef` / `LayoutRef`；
- `RuntimeStep`：step id、family、具体 tool、input refs、status；
- `StateDelta`：executor 唯一允许的状态修改方式；
- `ToolSpec` / `ToolAvailability`：统一能力与不可用原因。

### 4.2 改造 ToolRegistry

`MobileToolRegistry.kt` 从 `id → description` 升级为 `id → ToolSpec`，但保留现有
`supports()` 和 prompt 输出适配器，使 static baseline 暂时无需改 prompt。

首批规格覆盖全部当前 Android 工具，至少补齐：

- 图片/视频/Live Photo 要求；
- 素材及视频轨道 min/max；
- selection arity；
- layout/artifact 前置条件；
- family；
- destructive/idempotent/singleton；
- observation barrier。

### 4.3 候选过滤测试

必须覆盖：

- 三张普通图片不出现视频/Live Photo 工具；
- 三段视频可见三视频工具，一段视频不可见；
- 选中一个素材才可见 replace；
- 选中两个且存在 layout 才可见 swap；
- 没有 artifact 时不可见 publish；
- 每个代表状态的候选数在 1～10；
- 超过 10 时返回明确架构错误，不能静默截断。

### Gate

- 当前项目 assemble/lint 通过；
- ToolSpec 与 executor 的真实前置条件完成一次人工对照；
- static baseline 的 prompt catalog 内容除错误工具被修正外不发生无关变化；
- 尚不调用新 AgentLoop。

---

## 5. Phase 2 — 单工具执行器与显式数据流

### 5.1 拆分 Runner

新增 `MobileToolExecutor.kt`，将 `MobileAgentRunner.kt` 的巨大 `when(call.tool)` 抽成：

```kotlin
execute(
    step: RuntimeStep,
    state: AgentRuntimeState,
    environment: ExecutionEnvironment
): ToolExecutionResult
```

`MobileAgentRunner` 暂时仍按旧计划顺序调用 executor，确保这一阶段是执行内核重构而非规划行为改变。

### 5.2 引入 StateReducer

新增 `AgentStateReducer.kt`，统一处理：

- artifact 增加与验证；
- 视频轨道 writeback；
- asset replace 与 selection remap；
- layout swap/update；
- publish URI；
- step status 和错误。

executor 不得直接修改共享 plan；传入的 JSON 参数先深拷贝。

### 5.3 修复真正的工具串联

优先打通三类数据流：

```text
video_filter.output → video_reverse.input → publish.input
image edit.output   → next image edit.input
per-asset writeback → template_collage.inputs
```

明确区分：

- `transform`：生成新 artifact，供后续步骤消费；
- `writeback`：以稳定 asset ID 更新指定轨道；
- `publish`：通过统一 `publish_result` 把已验证 artifact 写入 MediaStore；static 路径的
  `export_mp4` 和尾部图片/Live Photo 发布先作为兼容适配器保留。

### Gate

- 同一静态计划迁移前后生成相同类型的最终结果；
- 两个连续编辑效果都作用在同一条产物链上；
- 失败 artifact 不会被发布；
- static baseline 仍可完整执行现有工具；
- 不引入模型额外调用。

---

## 6. Phase 3 — `search_assets` 一等化

### 6.1 统一搜索服务

将 BGE/SQLite 搜索抽为 `MobileAssetSearch.kt`，供以下路径复用：

- 普通 `search_assets`；
- `asset_replace` 的候选搜索；
- 后续补搜或排除现有素材。

搜索参数支持：

```text
query / requested_count / min_score
required_media / required_tracks
exclude_asset_ids
```

### 6.2 搜索进入 executor

`search_assets` 必须产生标准：

```text
TOOL_CALL
TOOL_RESULT
STATE_DELTA(AddAssets)
```

结果记录候选分数、过滤原因、实际返回数量和媒体能力。Runner 不再通过
`firstOrNull { tool == "search_assets" }` 在循环外消费它。

### 6.3 处理手选素材

- 有 URI/选中素材：初始化 state 后可直接跳过 RETRIEVE；
- 无素材：路线中的首个可执行步骤为 search；
- 历史素材与最新 UI 选择冲突时，以 UI 状态为准。

### Gate

- “给我的猫做个三拼”产生一条连续计划血缘，不再丢弃 intent plan；
- 搜索不足不会进入不满足数量的工具；
- replacement 搜索仍保留最低相似度与排除当前阵容的保护；
- 搜索结果中的失效 URI 被剔除并反映到返回数量。

---

## 7. Phase 4 — Kotlin AgentLoop 骨架

### 7.1 独立运行时开关

在 `android/app/build.gradle.kts` 增加：

```text
agentRuntime=static|loop
```

默认 `loop`。该开关与 `plannerExperiment`、`plannerEvalOnly` 完全独立；`static` 为回滚值。

### 7.2 新增 `MobileAgentLoop.kt`

首版先使用确定性/预置 RuntimePlan 验证循环，不立即修改 Qwen prompt。它负责：

- next-ready step；
- ToolSpec 前置条件；
- executor 调用；
- StateDelta 应用；
- observation barrier；
- executed/replan/retry 上限；
- terminal gate；
- cancel 和 checkpoint。

### 7.3 计划版本

每次修订生成新版本：

```text
plan_id
version
parent_version
revision_reason
completed_prefix
pending_suffix
```

测试必须证明 completed prefix 不能被替换或再次执行。

### Gate

- 不接模型修订时，预置的 `search → edit → publish` 可以由 loop 完整驱动；
- 达到步数/重规划/重试上限时确定终止；
- 取消后不再启动新步骤；
- 重复 publish/replace 被 idempotency 门禁阻止；
- 步骤边界 checkpoint 可读取并验证。

---

## 8. Phase 5 — 路线规划、绑定与局部修订

### 8.1 Planner API

在 `GenieTextPlanner.kt` 增加相互独立的接口：

```text
planRoute(query, state, semanticFamilies)
bindNext(stateSnapshot, remainingRoute, availableTools)
reviseSuffix(stateSnapshot, failedOrBarrierResult, remainingRoute, availableTools)
```

不复用 2-Turn 的 free/formatter 协议；每个接口都直接返回短结构结果。

### 8.2 路线协议

首版优先使用 P2a 已验证的短行格式，而不是嵌套大 JSON。协议必须支持：

- 完整能力路线；
- 搜索内容、数量与必要媒体约束；
- 每步用户效果原文；
- ASK/CHAT；
- 不包含文件路径或虚构 asset ID。

### 8.3 具体绑定

绑定模型只看到：

- 原始用户请求；
- 尚未完成的语义路线；
- 紧凑状态摘要；
- 1～10 个当前可执行具体工具；
- 最近一个 observation/error。

parser 必须拒绝目录外工具，并记录模型实际看到的 `TOOL_CATALOG`。

### 8.4 修订策略

只在以下情况调用 `reviseSuffix`：

- 搜索结果使路线需要具体绑定或无法满足；
- ToolResult 失败；
- 素材/布局变化使 pending step 失效；
- 模型请求用户确认。

成功且仍可执行的连续步骤不再调用模型。

### Gate

- 单次具体候选始终 ≤10；
- 搜索前能保留完整语义目标，搜索后只绑定/修改 pending 后缀；
- 模型不能修改 completed prefix；
- 模型超时/解析失败只重试一次，随后明确失败而非隐式关键词救回；
- loop 模式的计划、工具选择与修订全部可审计。

---

## 9. Phase 6 — UI、审计与恢复

### 工作

- `AgentActivity` 展示“规划路线 / 检索 / 执行 / 修订 / 发布”阶段，但不显示冗长内部思考；
- 增加 `ROUTE_BRAIN`、`PLAN_VERSION`、`TOOL_CATALOG`、`STATE_DELTA`、`TERMINAL`；
- session state 增加 `runtime_state_version` 和可选 checkpoint；
- 新旧会话恢复兼容；
- 步骤执行期间 Activity 重建不启动第二个 loop；
- 前台 Service 继续只负责长任务保活，不在本阶段持有常驻模型。

### Gate

- 一次请求只存在一个 active loop；
- Activity 重建后不会重复发布或替换；
- 审计可以从用户原话回放到每个 plan version、候选目录、调用、结果和最终 URI；
- 日志中不出现后续 mutation 污染历史参数。

---

## 10. Phase 7 — 真机架构验收，然后建设轨迹集

### 10.1 架构验收用例

这一步先做少量人工/脚本 smoke，不立即扩成正式数据集：

1. 无选中：`search → collage → publish`；
2. 多效果：`filter → reverse → publish`，确认效果真正串联；
3. 数量不足：搜索仅返回 2 个但路线要求 3 个；
4. 混合媒体：不可执行工具不进入候选目录；
5. 替换后重拼：旧 ID 在 state 和 pending plan 中全部迁移；
6. SDK 失败：未验证产物不发布，并在上限内修订或失败；
7. 取消/Activity 重建：不重复执行破坏性步骤。

### 10.2 正式数据集后置

上述 Gate 通过后，再将现有 20 条单轮集保留为 L1 planner regression，并新增 L2 真机轨迹集。
轨迹集结构和评分标准届时单独设计，避免在 runtime contract 尚未稳定时固化错误字段。

---

## 11. 文件级改动清单

| 文件 | 计划变更 |
|---|---|
| `AgentModels.kt` | 保留现有 API；必要时增加兼容转换，不塞入全部 runtime 类型 |
| `AgentRuntimeState.kt` | 新增状态、checkpoint 与状态摘要 |
| `AgentArtifact.kt` | 新增 artifact、input ref 和媒体验证信息 |
| `AgentPlanModels.kt` | 新增 route、runtime step、plan version、decision |
| `MobileToolSpec.kt` | 新增工具契约与 availability 结果 |
| `MobileToolRegistry.kt` | 以 ToolSpec 为事实源，提供 ≤10 候选目录 |
| `AgentStateReducer.kt` | 新增唯一状态修改入口 |
| `MobileToolExecutor.kt` | 从 Runner 抽出单工具执行 |
| `MobileAssetSearch.kt` | 统一普通检索和 replacement 检索 |
| `MobileAgentLoop.kt` | 新增有界 Kotlin 状态机 |
| `MobileAgentRunner.kt` | 缩为 facade；按 runtime 开关进入 static/loop |
| `GenieTextPlanner.kt` | 后期新增 route/bind/revise，不先修改 baseline 协议 |
| `MobilePlanner.kt` | static 路径保留；loop 稳定后再决定删除哪些 fallback |
| `LibraryDatabase.kt` | 增加可选 runtime checkpoint 与新审计事件支持 |
| `AgentActivity.kt` | 展示 loop 阶段、写审计、避免重复 worker |
| `build.gradle.kts` | `agentRuntime` 默认 `loop`，显式 `static` 回滚 |

SDK adapter、VLM 索引和 BGE 模型本身不在本轮重构范围内。

---

## 12. 风险与控制

| 风险 | 控制 |
|---|---|
| 大范围拆 Runner 引入 SDK 回归 | 先做行为等价 executor 抽取；static 路径持续构建和真机 smoke |
| ToolSpec 与真实 executor 漂移 | parser/executor 共用同一规格；添加代表状态测试 |
| 新计划协议再次过重 | 复用短行协议原则；不使用自由草稿和 formatter |
| 模型调用次数失控 | 只在 observation/失败/失效时调用；硬上限与审计 |
| 状态 checkpoint 引用临时文件失效 | 恢复时逐个验证；首版只保证步骤边界恢复 |
| 旧会话不兼容 | 新字段可选并带版本；无法升级时回到 static 或新建 loop 状态 |
| 重复副作用 | step/idempotency key + completed prefix 不可改写 |
| loop 回归影响演示 | 保留并持续构建 `-PagentRuntime=static` 回滚 APK |

---

## 13. 明确暂缓事项

以下工作不与本轮底层改造并行：

- 常驻 `genie-app`、JNI BrainService；
- 原生 Thinking 模型；
- scratchpad/2-Turn 继续实验；
- 新媒体 SDK 工具；
- MediaStore ContentObserver 热更新；
- 正式 20 条以上长链轨迹集；
- prompt 自动优化或强模型 meta-agent。

它们只有在 loop runtime 完成 Phase 7 架构验收后重新排期。

---

## 14. 开工点

代码修改从 **Phase 1** 开始，第一批只提交：

1. `AgentArtifact` / `AgentInputRef`；
2. `AgentRuntimeState` / `StateDelta`；
3. `ToolSpec` 与 `MobileToolRegistry` 适配层；
4. 对工具数量、媒体、selection、layout、artifact 的单元测试。

第一批不改 Qwen prompt、不改默认 runtime、不移动 SDK 执行分支。通过 Gate 后再进入
`MobileToolExecutor` 抽取，避免架构和行为同时变化导致问题无法归因。
