# Agent Brain 设计：常驻大脑 + 执行闭环

> 本文档记录"大脑主导"架构的设计共识（2026-10-13 定稿）。
> 背景：现状中 VLM 已落 SQLite、BGE 仅挂 search_assets、意图规划实际由
> `MobilePlanner` 硬编码关键词完成，Qwen 文本模型被架空。本设计把 Qwen
> 文本模型恢复为唯一大脑：意图识别 → 工具挑选 → 链路规划 → 执行监督。

## 1. 三个模型的职责划分

| 模型 | 定位 | 调用时机 | 现状/目标 |
|---|---|---|---|
| Qwen3-VL-4B (VLM) | 素材内容识别 | 素材入库/指纹变化时分析关键帧 | 已实现：结果落 SQLite `assets` 表 |
| BGE-small-zh-v1.5 | 素材语义检索 | `search_assets` 工具内部 | 已实现；历史遗留，大脑够强时可不用 |
| Qwen 文本模型 | **唯一大脑** | 交互时：意图识别→工具挑选→链路规划→每步执行监督 | **本设计重构对象** |

硬约束：大脑不做素材内容识别（那是 VLM 的活），BGE 不做意图映射
（那是大脑的活）。

## 2. 规划方式：首轮完整链 + 逐步监督

- **首轮**：大脑根据 用户输入 + SQLite 会话历史 + 素材语义摘要 + 工具目录，
  输出完整工具链 `tool_calls`（JSON 数组，含 `tool/reason/arguments`）。
- **逐步执行**：每执行完一个工具，把结果摘要回喂大脑，大脑裁决下一步：
  `tool_call`（继续）/ `revise`（修正参数重试）/ `finish`（完成）/ `fail`（失败）。
- **完成信号**：模型输出 `action=finish/fail` 为唯一完成依据；
  另有兜底：全局步数上限 8、单步重试上限 2（超限强制终止并汇报），保证任务有界。

## 3. 运行方式

- **阶段 1**：常驻 `genie-app` 子进程，CLI dialog 协议多轮交互。
  进程保持存活，通过 stdin 逐条写 dialog 命令（`dialog config create` →
  `dialog create` → `dialog query` → …），不写 `exit`。
- **阶段 2**（后续优化）：JNI 进程内常驻（`GenieDialog_query/save/restore`），
  消除 IPC 与模型加载延迟。阶段 1 先验证闭环正确性。

## 4. 生命周期与保活

- 前台服务（`AgentBrainService`）持有 genie-app 子进程，通知兜底保活。
- 会话闲置 5 分钟：历史已落 SQLite → `dialog free` → 杀子进程 → 状态"休眠"。
- 继续对话：spawn 子进程 → 重放 SQLite 历史恢复 dialog → 继续。
- 内存预算：qwen 权重 ~3.2GB 常驻；VLM 按 job 冷启动，不与常驻大脑共存。

## 5. 状态存储（仅 SQLite）

- 扩展 `LibraryDatabase` 的 session 表：
  - `messages[]`：user/assistant 消息
  - `plan_json`：最近一次完整计划
  - `steps[]`：每步的 tool/arguments/成功标志/output_path/模型裁决
- 恢复流程：继续会话时重放 messages 恢复上下文。

## 6. 降级策略

- 大脑不可用（`qwenReady()`=false 或 spawn 失败）→ UI 明确提示
  "大脑不可用，仅支持基础浏览"，禁用需要大脑的功能。
- **不再用 MobilePlanner 硬编码兜底**（避免"时灵时不灵"的不可预测体验）。
- MobilePlanner 仅保留 capability 应答（"能做什么"）。

## 7. 组件与接口

### 7.1 AgentBrainService（前台服务，新增）

- `start(sessionId)`：spawn 子进程 → 初始化 dialog
- `plan(query, assets, history)` → JSON 工具链
- `supervise(query, plan, results, step)` → JSON 裁决
- `onIdle(5min)`：unload 子进程
- `resume(sessionId)`：重放历史恢复

### 7.2 BrainClient（改造 GenieTextPlanner.kt）

plan 输出（沿现有格式）：

```json
{
  "user_goal": "...",
  "intent": "...",
  "tool_calls": [
    {"tool": "search_assets", "arguments": {"query": "...", "k": 3}, "reason": "..."},
    {"tool": "template_collage", "arguments": {}, "reason": "..."}
  ]
}
```

supervise 输出（新增协议）：

```json
{"action": "tool_call|revise|finish|fail", "tool": "...", "arguments": {}, "reason": "...", "message": "..."}
```

### 7.3 AgentLoop（改造 MobileAgentRunner.run）

```
plan = brain.plan(...)                 # 首轮完整链
for step in plan.tool_calls:           # 全局上限 8
    retry = 0
    loop:
        result = executor.run(call)    # try/catch 捕获失败，不崩链
        decision = brain.supervise(...) # 结果回喂
        action=tool_call  → 进入下一步
        action=revise     → retry++（>=2 强制终止汇报）
        action=finish/fail → 结束并汇报
```

关键变化：

- 失败不再是 throw 崩链，而是变成回喂材料。
- 导出/发布从硬编码尾处理改为显式工具调用（`export_mp4`）。

### 7.4 UI（AgentActivity）

- 大脑状态指示：加载中 / 就绪 / 休眠 / 不可用
- 每步决策可视化：工具名 + reason + 结果

## 8. 阶段 1 改动文件

1. `GenieTextPlanner.kt` → 重写为 BrainClient（常驻协议 + supervise）
2. 新增 `AgentBrainService.kt`
3. `MobileAgentRunner.kt` → AgentLoop
4. `MobilePlanner.kt` → 删除硬编码（仅保留 capability 应答）
5. `LibraryDatabase.kt` → session 表扩展
6. `AgentActivity.kt` → 状态 UI
7. `AndroidManifest.xml` → 注册 BrainService

## 9. 阶段 1 内待验证风险

1. **genie-app 常驻交互**：现有代码每次 spawn 后写 `exit`；常驻要求 stdin
   管道逐条写 dialog 命令，需真机验证协议支持。
2. **采样参数**：genie_config temp=0.8 偏高；JSON 解析失败重试一次兜底；
   CLI 能否运行时改采样待验证。
3. **context 4096**：多轮历史裁剪沿用 `messageLimit=8`。
4. **内存**：qwen 常驻 + VLM 冷启动不共存。

## 10. 决策记录（grill 共识）

| # | 决策 |
|---|---|
| Q1 | 模型分工：VLM=素材识别，BGE=遗留检索，Qwen=唯一大脑 |
| Q2 | 意图识别=大脑职责 |
| Q5 | 完成信号=模型 finish/fail + 步数/重试上限双保险 |
| Q6 | 阶段 1 常驻 genie-app CLI |
| Q7 | 前台服务保活 |
| Q8 | 状态仅存 SQLite |
| Q9 | 恢复仅重放历史，UI 恢复留二版 |
| Q11 | 首轮完整链 + 每步回喂修正（混合式） |
| Q12 | 会话状态落 SQLite |
| Q13 | 单步重试上限 2 |
| Q14 | 首轮完整链 + 监督修正 |
| Q15 | 不写 JNI 探针，直接用 CLI dialog 协议 |
| Q16 | 会话活跃度管理生命周期（闲置 5 分钟 unload） |
| Q17 | 大脑不可用→UI 提示+禁用功能，无规则兜底 |
| Q18 | 两阶段：先闭环跑通，再 JNI 优化 |
