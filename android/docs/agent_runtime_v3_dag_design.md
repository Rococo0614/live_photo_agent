# Agent Runtime v3：Content Graph 与类型化执行 DAG

> 状态：实验架构，shadow compiler 已接入；不得替代生产 Runtime v2
>
> 建立日期：2026-10-29
>
> 冻结基线：`runtime-v2-linear-prod-20261029`（commit `691ebef`）
>
> 生产默认：`agentRuntime=loop`；实验构建：`agentRuntime=dag`

## 1. 决策

Runtime v2 已完成31/31 executor、30/30动态绑定与代表性长链验收，作为线性生产基线冻结。
下一阶段不继续扩展自由文本Tool Calling，而是建设领域通用、无环、有界、可恢复的类型化DAG，
为Live内容的增加、变化和移除提供执行基础。

核心边界：

1. Qwen负责生成Goal IR，选择用户语义、目标、约束和操作顺序；
2. Qwen不生成物理节点ID、文件、URI、SDK backend或DAG连线；
3. 编译器只根据Goal IR、Content Graph、Live状态和ActionSpec生成合法物理图；
4. 确定性代码可以排除不可能实现和选择语义等价backend，不能替用户发明意图；
5. 多个合法方案产生不同用户可见结果时，必须由模型进一步消歧或暂停询问用户。

## 2. 冻结基线

冻结制品与证据：

```text
git tag: runtime-v2-linear-prod-20261029
/home/jiangrui/.cache/lpa-final/app-runtime-v2-linear-loop-20261029.apk
/home/jiangrui/.cache/lpa-final/app-runtime-v2-linear-static-20261029.apk
/home/jiangrui/.cache/lpa-final/runtime-v2-linear-prod-20261029-manifest.json
```

冻结前，`loop`和`static`分别通过：

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
```

v3开发期间不得删除`loop`或改变默认值；`static`继续作为第二级回滚。

## 3. 两张图

### 3.1 Content Graph

Content Graph描述当前Live内容，而不是如何处理它。首版节点类型：

```text
LIVE_ASSET / IMAGE_TRACK / VIDEO_TRACK / AUDIO_TRACK
OBJECT_TRACK / MASK_TRACK / REGION / LAYER / METADATA
```

边表示：

```text
CONTAINS / DERIVED_FROM / SYNCHRONIZED_WITH
TARGETS / MASKS / OVERLAYS / ANCHORED_TO
```

所有节点使用稳定ID和revision；图使用乐观并发版本。增加、修改、移除通过原子Patch完成：

```text
AddNode / UpdateNode / RemoveNode / AddEdge / RemoveEdge
```

模型不得编造ContentNode ID。自然语言目标必须先经Grounding解析为带图版本的引用；目标不唯一时
产生`NeedUser`，缺观察时产生`NeedObservation`。

### 3.2 Execution DAG

Execution DAG描述怎样从当前Content Graph得到新版本和最终artifact。节点快照必须包含：

```text
action id / contract version / typed ports / typed parameters
resources / effects / destructive / idempotent / status
```

边分为：

```text
DATA        显式值流，必须通过端口类型检查
CONTROL     只表达顺序或副作用边界
CONSTRAINT  表达必须先满足的验证或确认关系
```

DAG必须无环、节点数有上限。帧级循环、分块推理和有界重试封装在executor内部，模型和DSL均
不能生成任意`if/while`。

## 4. 三层程序表示

### 4.1 Goal IR（模型输出）

Goal IR只包含领域语义：

```text
operation = REMOVE_CONTENT
target = "右后方的人"
scope = entire motion track
preserve = [main subject, audio, duration]
fill_policy = background reconstruction
```

当前Runtime v2的`SemanticRoute`通过兼容适配器转换为Goal IR；后续原生tool-call直接生成
强类型Goal IR，逐步淘汰自由`instruction: String`。

### 4.2 Logical Method / HTN（编译器展开）

同一目标按内容状态展开：

```text
REMOVE_CONTENT
  overlay layer  → remove layer
  image object   → segment + image inpaint
  video object   → segment + track + temporal inpaint
  no backend     → Unsupported / NeedUser
```

### 4.3 Physical DAG（运行时执行）

例如视频内容移除：

```text
DecodeVideo ───────────────┐
SegmentTarget → TrackMask ─┼→ TemporalInpaint → TemporalValidate
EstimateMotion/Background ─┘                         │
DecodeAudio ─────────────────────────────────────────┼→ RepackLivePhoto
CoverFrame ──────────────────────────────────────────┘       │
                                                        Validate → Publish
```

## 5. ActionSpec

Runtime v2的ToolSpec继续负责真实可执行性，同时增加编译器契约：

```text
phase
typed input/output ports
parameter schema、单位、范围
state effects
resource claims
postcondition validators
destructive / idempotent / singleton
```

后续必须补齐：

```text
compensation action
semantic equivalence class
strict parameter validation
estimated latency / memory / thermal cost
content graph preconditions and effects
```

参数必须保留来源：`USER_EXPLICIT / MODEL_INFERRED / PRESET / RUNTIME_FACT`。模型选择
`MONOCHROME`等领域值，Preset Catalog按媒体/backend转换成SDK数值；编译器不得通过关键词表
替用户决定风格。

## 6. 编译结果与人机协作

编译器只返回五种结果：

```text
Ready(planGraph)
NeedBinding(operation, candidates<=10)
NeedObservation(observationGoal)
NeedUser(questionSpec, continuationToken)
Unsupported(reason, alternatives)
```

歧义处理：

| 类型 | 处理 |
|---|---|
| 语言映射，如“黑白” | Qwen映射领域值，编译器校验 |
| 运行事实，如目标轨道/权限 | observation/runtime，模型不得猜 |
| 用户偏好，如裁剪或留边 | 结果非等价时询问或展示预览 |
| 能力不足，如九拼/无inpaint | 编译器给合法替代，用户选择 |

`WAITING_USER`必须保存question id、缺失slot、graph version和continuation token；用户回答后恢复同一
图，不把答案当成一条全新无状态请求。

## 7. 设备端调度

DAG表达可并行依赖，不代表设备必须并行执行。每个节点声明CPU/GPU/NPU、codec、vendor SDK、
峰值内存和thermal weight；调度器按资源租约串行化互斥节点。Qwen、VLM、BGE和媒体SDK不得因
图并行而同时常驻并造成内存或热失控。

所有外部副作用推迟到COMMIT节点。中间结果只写临时artifact；发布、替换等操作使用幂等键，
失败时执行显式compensation或保留原Content Graph版本。

## 8. 当前shadow实现

已加入：

- `AgentGoalIr`与Runtime v2 route适配；
- 版本化`AgentContentGraph`及原子Patch reducer；
- `AgentActionContract`、类型端口、资源和effects；
- `AgentPlanGraph`、外部值绑定、DATA/CONTROL/CONSTRAINT边；
- DAG无环、端口、类型、必需输入和节点上限校验；
- 不调用executor的ready-node scheduler、资源互斥、fan-out/fan-in与commit门禁；
- Runtime素材、轨道、artifact和layout到Content Graph的无路径投影；
- 严格模型侧`plan_live_edit` Goal IR codec；工具ID、图连线、文件、URI和runtime ID全部拒绝；
- `Ready / NeedBinding / NeedObservation / NeedUser / Unsupported` preflight；
- `agentRuntime=dag`实验构建；
- 每次Runtime v2计划快照同步生成`PLAN_GRAPH`与`CONTENT_GRAPH`审计；
- DAG仍由Runtime v2执行，shadow结果不控制任何SDK或发布行为。

首轮host验证结果：

```text
unit tests = 48, failures = 0, errors = 0
agentRuntime=dag    test + lint + assemble = PASS
agentRuntime=loop   test + lint + assemble = PASS
agentRuntime=static test + lint + assemble = PASS
```

实验APK只在受控shadow探针中临时安装；测试后恢复冻结的loop APK，从未切换生产默认：

```text
/home/jiangrui/.cache/lpa-final/app-runtime-v3-dag-shadow-20261029.apk
SHA-256 48258bde5c467b52a3fd4b70592c1289d391c44fb5e675617cf811a153321abb
```

首次真机shadow使用旧Instruct已知弱例“给我的猫做个三拼”，在Runtime v2 route解析阶段失败，
尚未产生任何图；该失败不能归因于DAG。随后改用已通过生产闭环的F002：

```text
query                 = 找一段猫的视频并倒放后导出
graph snapshots       = 4
structurally valid    = 4/4
physical actions      = search_assets → video_reverse
topological order     = retrieve → temporal
media edit executed   = false（plannerEvalOnly）
publish executed      = false
```

首轮F002 shadow同时暴露出现有数量事实缺口：“一段视频”未被旧正则识别，检索默认返回3个素材，
倒放executor再隐式取第一个。现已抽出纯`RequestFactExtractor`，支持图片/视频单位，并在DAG端口
加入ASSET_SET item cardinality；复跑后Goal IR的`requested_count=1`、倒放输入绑定恰好1个素材，
图无诊断错误。证据：

```text
/home/jiangrui/.cache/lpa-final/runtime-v3-dag-shadow-phone-acceptance.json
SHA-256 13d0c2740bea18081c6926147c614df9d93ca47be7ebd4f75c166506935ada2d
```

第二阶段增加不调用executor的确定性DAG scheduler：ready-node、fan-out/fan-in、资源互斥、
内存/thermal预算、失败阻断、commit全图门禁、取消和不可变event journal。真实状态同时投影为
Content Graph，且审计不包含本地文件路径。

同一F002再次真机shadow：

```text
execution graph snapshots = 4，valid 4/4
content graph snapshots   = 4，valid 4/4
content source assets     = 1
content nodes             = LIVE_ASSET + IMAGE_TRACK + VIDEO_TRACK + METADATA
next scheduler wave       = [video_reverse]
media edit / publish      = 均未执行
```

证据：

```text
/home/jiangrui/.cache/lpa-final/runtime-v3-dag-scheduler-content-phone-acceptance.json
SHA-256 af16a626ac4aa6fbd935762ea8a2f781a3854c9c8ed2c6d93e4d9958a4cadc27
```

第三阶段加入严格Goal IR边界。模型只能输出领域操作、语义target、scope、typed parameter、
preserve/forbid/require约束和未决slot；host分配operation ID。若模型输出`tool/action_id/node_id/
edge_id/asset_id/file/path/uri/sdk/backend`，codec直接拒绝。用户原话中的明确数量和媒体类型仍由
事实提取覆盖模型错误值。

内容操作preflight行为已由host测试覆盖：

```text
remove_content但无target       → NeedUser + continuation token
remove_content语义target       → NeedObservation(OBJECT_TRACK)
publish但无validated artifact  → NeedObservation(MEDIA_ARTIFACT)
旧state fingerprint            → Unsupported(stale state)
当前已支持语义操作             → NeedBinding(状态过滤候选<=10)
```

真机F002最后一个shadow快照返回：

```text
status      = need_binding
operation   = route-temporal-27
family      = TEMPORAL
candidates  = clip_trim / video_mirror / video_timeline_effect /
              video_speed / video_reverse / set_display_frame
next wave   = video_reverse
```

这保持了架构边界：编译器过滤事实候选，Qwen仍负责从候选中选择`video_reverse`。当前生产QNN仍
使用Runtime v2短行route；严格`plan_live_edit` codec已经可用但尚未替换生产模型协议。

证据：

```text
/home/jiangrui/.cache/lpa-final/runtime-v3-goal-preflight-phone-acceptance.json
SHA-256 139c52242b51d74da7b00f23550e360f38c17084b530e83e1048ec0843dbd7f7
```

测试结束后已重新安装冻结的Runtime v2 loop APK；vivo输入法启用、ADBKeyboard禁用、stay-awake
为0，数据库仍为114条素材/113条有效embedding。

## 9. 迁移阶段

| Phase | 内容 | Gate |
|---|---|---|
| 0 | 冻结Runtime v2 | tag、APK、manifest和两套构建通过 |
| 1 | 类型、Content Graph、DAG与shadow compiler | 单元测试、loop行为零变化 |
| 2 | Goal IR原生协议与严格参数schema | 语义/格式双轴评测，目录外动作全拒绝 |
| 3 | DAG executor驱动现有31工具 | 与Runtime v2逐事件差分一致 |
| 4 | Suspend/Resume、checkpoint和reconcile | 进程终止、Activity重建、重复发布故障注入通过 |
| 5 | Live内容增加/变化/移除 | Grounding、时序一致性、保留约束和回滚验收 |
| 6 | 资源成本调度与有限并行 | 内存、温度、延迟和中断压力测试通过 |
| 7 | 生产切换 | 独立真机闭环通过，`loop`仍可回滚 |

## 10. 当前非目标

- 不让模型直接生成物理DAG；
- 不在Phase 1改动31个executor行为；
- 不把DAG做成图灵完备脚本语言；
- 不因shadow graph通过就宣称Live内容编辑已实现；
- 不在没有Grounding/时序验证/回滚前开放内容移除或生成式修改。
