# Agent Runtime v3：Content Graph 与类型化执行 DAG

> 状态：实验架构，shadow compiler 已接入；不得替代生产 Runtime v2
>
> 建立日期：2026-10-29
>
> 冻结基线：`runtime-v2-linear-prod-20261029`（commit `691ebef`）
>
> 生产默认：`agentRuntime=loop`；实验构建：`agentRuntime=dag`
>
> Live内容模型候选：[`video_content_model_candidates.md`](video_content_model_candidates.md)
>
> AI Hub远程验证：[`aihub_remote_grounding_validation.md`](aihub_remote_grounding_validation.md)

## 1. 决策

Runtime v2 已完成31/31 executor、30/30动态绑定与代表性长链验收，作为线性生产基线冻结。
下一阶段不继续扩展自由文本Tool Calling，而是建设领域通用、无环、有界、可恢复的类型化DAG，
为Live内容的增加、变化和移除提供执行基础。

核心边界：

1. Qwen负责生成Goal IR，选择用户语义、目标、约束和操作顺序；
2. Qwen不生成物理节点ID、文件、URI、SDK backend或DAG连线；
3. 编译器只根据Goal IR、Content Graph、Live状态和ActionSpec生成合法物理图；
4. 确定性代码可以排除不可能实现和选择语义等价backend，不能替用户发明意图；
5. 多个合法方案产生不同用户可见结果时，必须由模型进一步消歧或暂停询问用户；
6. Live内容target首版只接受语言，不引入点选/框选；mask是Grounding observation，不是用户输入。

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
产生`NeedUser`，缺观察时产生`NeedObservation`。修改节点还必须依赖一个`ScopeCertificate`，记录
候选唯一性、时间覆盖率、mask连续性、保护对象重叠和所用Content Graph版本；证书不充分时节点
不得ready。

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
target = TargetPredicate(
  concept=person,
  attributes=[red clothing],
  spatial=[right, background],
  quantifier=one,
  exclude=[foreground child]
)
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
  image object   → language segment + image inpaint
  video object   → SAM3 keyframe text masks + EdgeTAM track + temporal inpaint
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

### 6.1 语言Grounding子图

复杂描述先由Qwen编译成TargetPredicate；Qwen不输出mask或坐标。物理图按scene展开：

```text
SampleKeyframes
→ SAM3TextSegment(prompt primitives)           # 关键帧fan-out
→ EvaluateAttributesRelationsDepthExclusions
→ AssociateCandidateTracks
→ EdgeTAMPropagate
→ ScopeCoverageValidate
→ Ready(mask certificate) | NeedObservation(more frames) | NeedUser(language clarification)
```

SAM3只直接处理短概念词组；“右后方穿红衣的人”应拆成`person`、红衣属性、右侧几何关系和背景深度，
再在候选mask上求值。量词`all/one/exact(n)`、排除条件和时间范围都是DSL一等字段。用户修正也只用
语言，例如“不是左边的，是后面的”；系统重新编译谓词，不要求用户点或框。

## 7. 设备端调度

DAG表达可并行依赖，不代表设备必须并行执行。每个节点声明CPU/GPU/NPU、codec、vendor SDK、
峰值内存和thermal weight；调度器按资源租约串行化互斥节点。Qwen、VLM、BGE和媒体SDK不得因
图并行而同时常驻并造成内存或热失控。

所有外部副作用推迟到COMMIT节点。中间结果只写临时artifact；发布、替换等操作使用幂等键，
失败时执行显式compensation或保留原Content Graph版本。

### 7.1 AI Hub远程验证backend

Qualcomm AI Hub Workbench只执行编译、量化和模型张量推理作业，不承载任意Android APK、
MediaStore、vivo SDK或完整Kotlin Agent。远程验证因此采用节点级backend：

```text
host Qwen / Goal IR / DAG / journal
              ↓ typed model input
AI Hub Galaxy S26 model job(s)
              ↓ boxes / masks / scores
host validator / Content Graph / next DAG decision
```

远程backend只用于`OBSERVE`和无副作用`VALIDATE`节点；`TRANSFORM`首测也只能写临时预览。
`STATE_MUTATION`和`COMMIT`不得远程执行。每次作业必须记录模型ID、job ID、设备/OS、输入哈希、
输出哈希、阈值和失败原因；缺少输出、类型不符或job失败都按节点失败处理，不能回退成猜测。

迁移顺序固定为：远程float语义正确性 → 远程量化数值对照 → 下载制品 → 本机隔离runtime单节点
验证 → Android node bridge差分。AI Hub通过只是必要条件，不代表vivo本机runtime、内存、热或SDK
生命周期已经通过。Workbench支持显式`--qairt_version`；需要与生产2.45共存的模型应优先重编译
为2.45，或继续使用独立2.50进程，禁止直接混载两套QAIRT库。

截至2026-11-02，EdgeTAM已补齐公开recipe遗漏的memory attention并完成vivo只读probe。远程S26上
通过的7-memory/16-pointer动态validity图，在vivo SM8850的3/3真实状态只达到28.05dB；等价固定
3/3图恢复到75.48dB。因此bounded图只保留为回归fixture，部署候选改为精确固定计数图银行：
`1/1...7/7`和`7/8...7/16`均已通过。图选择只依赖已验证state cardinality，是host
可替换backend选择，不交给Qwen，也不暴露给Goal IR。

固定图银行已在vivo完成合成遮挡、自然遮挡、快速镜头、出画重入和第16个真实pointer；attention
accelerator为14.65–19.24ms。17帧真实链最低host IoU为0.9536。一个候选时序覆盖validator会把
面积突然低于上一个可信mask 10%的
观测标为`UNCERTAIN_TEMPORAL_COVERAGE`并fail-closed。它尚未写入Android或生产阈值。上述结果只把
`MASK_TRACK`模型backend推进为“可设计Android只读bridge”；它不自动生成`ScopeCertificate`，也不
授权`TRANSFORM`、`STATE_MUTATION`或`COMMIT`。底层TERM取消、10次context重载和3000次短时soak
已通过；OWLv2 QAIRT 2.45 contexts及Android tokenizer/图像输入均已通过差分，但Android QNN JNI、
逐帧覆盖、多分钟完整pipeline soak、总host/DSP内存和Android生命周期恢复仍是前置门禁。

固定图银行的单图性能不等于包体已通过：16个独立DLC约560MiB，vivo O3 contexts约234MiB；
QAIRT 2.45对两个独立DLC的weight-sharing compose在序列化阶段返回`0x138f`。Android bridge必须
保持backend可替换，并在单一多图导出、其他共享权重路径或显式包体预算完成前继续shadow-only。

### 7.2 首个交互MVP：局部确定性修改预览

首版产品方向固定为局部确定性修改，而不是inpaint或生成。新增shadow-only action：

```text
source media ───────────────┐
MASK_TRACK ─────────────────┼→ local_mask_change_preview_shadow → MEDIA_ARTIFACT(preview)
SCOPE_CERTIFICATE ──────────┘
```

当前只接受两类可逆效果：`color_tint`与`blur`。扁平照片/视频中的“对象透明度”会暴露未知背景，实际
需要独立图层或inpaint，因此不在首版伪装成确定性能力。preview节点只有`ADD_ARTIFACT` effect，固定
`preview_only=true / writeback=false`，不含`CHANGE_CONTENT`、`REPLACE_ASSET`、`PUBLISH`或
`COMMIT`。

候选10%时序coverage policy可以授权临时preview，但`approvedForMutation=false`，所以不能授权
写回。Goal compiler在grounded object后按`MASK_TRACK → SCOPE_CERTIFICATE`继续门禁；只有证书新鲜、
目标唯一、保护检查通过、时间覆盖完整且Qwen给出合法typed effect参数时，才生成单节点preview DAG。
证书的全部观测事实会生成SHA-256 fingerprint并进入DAG external binding，避免只复用certificate ID
掩盖内容变化。纯Kotlin `LocalMaskChangeReferenceRenderer`定义ARGB调色、软mask混合和mask内box blur
的CPU oracle，mask外像素必须逐位不变。隔离的`AndroidLocalMaskPreviewRenderer`现已实现GLES局部调色
和整数域两遍box blur，并在vivo真机对CPU oracle完成单帧差分；GL不可用时才回退CPU。它尚未注册为
DAG或生产executor，也不接收媒体路径或执行写回。

随后加入`AgentShadowObservationBridge`：语言target只有唯一候选时才物化`OBJECT_TRACK`，内部灰度
帧物化为`MASK_TRACK`，同批typed observations签发fresh certificate；多候选返回`NeedUser`，不把
box/point交给用户。`AndroidLocalPreviewShadowExecutor`仅接受完整evidence fingerprint及
`preview_only=true / writeback=false`的单节点DAG，产物进入process-local store。UI review状态只有
待确认、已确认预览、取消和撤销，不存在commit。该executor只在显式shadow probe实例化，仍未进入
生产registry。

`OwlV2GroundingPostprocessor`进一步把locator边界固定为host事实代码：score threshold、per-label NMS、
原图坐标映射和0/多/唯一候选分支均不交给Qwen；候选box只参与内部SHA，bridge只接收candidate count
与`locatorEvidenceFingerprint`。任一物理候选变化都会使object grounding和certificate失效。

17帧vivo设备track现已增加高对比调色显色Gate：无损输出mask外变化0、最低mask IoU 0.9536、额外
保护区泄漏0。这证明同一`MASK_TRACK`能够稳定驱动确定性transform；它仍是抽样frame fixture，不能
代替完整Live Photo全帧解码、时间戳保持、编码与repack。

同一运动轨道随后完成PyTorch全181帧track/tint，并由vivo LivePhotoSDK在应用私有目录repack和decode：
封面像素、181帧framemd5、AAC bitstream和1,000,000µs photo timestamp均保持。该里程碑证明完整
效果/容器契约，但模型全帧执行仍是PyTorch oracle，生产迁移必须替换成Android QNN与持久GLES。

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
- backend-independent DAG value store/execution engine；输出端口类型错误或缺失时fail-closed；
- EdgeTAM固定图拓扑选择器：只接受已验证的`1/1...7/16`基数组合，未知组合不回退；
- typed track observation、候选时序覆盖policy与`AgentScopeCertificate`；候选policy、旧state、
  非唯一目标、保护重叠或覆盖缺口都不能授权mutation；
- 局部调色/模糊的preview-only typed contract与单节点shadow compiler；源媒体、mask和证书都是
  必需端口，输出只能是临时`MEDIA_ARTIFACT`；
- 隔离的Android单帧preview renderer：GLES调色与box blur、CPU oracle fallback、显式backend ID；
- staged Android shadow observation bridge、证书绑定preview executor、process-local artifact store与
  confirm/cancel/undo UI lifecycle；所有review状态固定`writebackAuthorized=false`；
- OWLv2纯Kotlin后处理：deterministic NMS/cardinality、内部candidate evidence SHA、无box/point Goal输入；
- Runtime v2动作顺序、节点状态与DAG拓扑的逐快照差分；
- `agentRuntime=dag`实验构建；
- 每次Runtime v2计划快照同步生成`PLAN_GRAPH`与`CONTENT_GRAPH`审计；
- DAG仍由Runtime v2执行，shadow结果不控制任何SDK或发布行为。

首轮host验证结果：

```text
unit tests = 51, failures = 0, errors = 0
agentRuntime=dag    test + lint + assemble = PASS
agentRuntime=loop   test + lint + assemble = PASS
agentRuntime=static test + lint + assemble = PASS
```

实验APK只在受控shadow探针中临时安装；测试后恢复冻结的loop APK，从未切换生产默认：

```text
/home/jiangrui/.cache/lpa-final/app-runtime-v3-dag-shadow-20261029.apk
SHA-256 c19752bfc262c86e1431eb59fa928f819522a86c301a64e2b651a2b026eb7645
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
grounded OBJECT_TRACK无mask     → NeedObservation(MASK_TRACK)
grounded OBJECT_TRACK + mask    → NeedObservation(SCOPE_CERTIFICATE)
fresh certificate + typed effect → Ready(local preview-only DAG)
publish但无validated artifact  → NeedObservation(MEDIA_ARTIFACT)
旧state fingerprint            → Unsupported(stale state)
当前已支持语义操作             → NeedBinding(状态过滤候选<=10)
```

固定图拓扑选择、track observation、`AgentScopeCertificate`和局部preview compiler加入后，首轮
debug/release单测为66/66。renderer在1080×1440真实mask及非对称软mask、半径1/6/64上共6组通过：
mask外变化0、alpha差0、RGB最大差≤1；真实r=6 blur与CPU oracle逐字节一致。加入shadow bridge与
review lifecycle、locator evidence hash及OWLv2输入后单测为87/87，真机完整链达到`FINISHED`，确认后undo且
源帧SHA不变。证据见
`local_deterministic_preview_validation.md`。真实生产DAG SDK执行仍关闭，未注册内容修改executor。

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

Phase 3前半段增加与Android SDK无关的执行内核：typed runtime value、external/data-edge
物化、ready wave执行、输出端口校验、event journal和最终commit。fake backend已覆盖：

```text
decode ─┐
        ├→ inpaint → publish
segment ┘
```

两条独立前置节点可同wave执行，fan-in只有在VIDEO与MASK_TRACK都物化后才ready；错误输出
IMAGE代替VIDEO或漏掉required output时，节点和整图均FAILED，不允许继续publish。

真机仍由Runtime v2执行，只比较每个计划快照。F002四个快照结果：

```text
structural validation         = 4/4
Runtime v2 differential match = 4/4
expected actions              = search_assets → video_reverse
graph actions                 = search_assets → video_reverse
issues                        = []
real DAG SDK execution        = disabled
```

证据：

```text
/home/jiangrui/.cache/lpa-final/runtime-v3-dag-differential-phone-acceptance.json
SHA-256 ea549c189562412064822584f17d73d5352cff7e9e0c76baf4da3673da15131d
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
