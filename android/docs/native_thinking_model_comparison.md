# 端侧原生 Thinking 模型对比报告

> 日期：2026-10-27
>
> 设备：vivo PD2547 / SM8850 / HTP V81
>
> 结论：本轮原生 Thinking 候选显著回归，不进入生产；手机已恢复 Instruct-2507

## 1. 候选选择

### 1.1 理想候选：Qwen3-4B-Thinking-2507

官方模型明确是 thinking-only，Agent benchmark 也优于旧 Qwen3-4B。但没有找到可直接下载的
SM8850/QAIRT 2.45 QNN binary；若使用官方 BF16 权重，需要自行经过 Qualcomm AI Hub 编译。

它官方建议普通任务提供 32K 输出、复杂任务提供 81K 输出，而当前静态 Genie 图只有 4096
context。即使完成转换，也不能直接假定手机上的截断版本保留官方收益。

### 1.2 本轮可部署候选：Qualcomm Qwen3-4B

最终选择 Qualcomm 官方预导出的 hybrid Qwen3-4B：

```text
model        = Qwen3-4B
release      = Qualcomm v0.64.0
precision    = W4A16
target       = Snapdragon 8 Elite Gen 5 For Galaxy
QAIRT        = 2.45.0.260326154327
context      = 4096
package      = 2,540,040,444 bytes
extracted    ≈ 3.0 GB
```

QAIRT、HTP版本和当前APK运行库完全一致，因此不存在“拿不兼容运行库测模型”的混淆。

模型放在独立目录：

```text
/data/local/tmp/lpa-models/qwen-hybrid
/data/user/0/com.vivo.lpa/files/models/qwen-hybrid
```

生产 `qwen` 目录从未被覆盖。

---

## 2. 实验臂

| 实验臂 | 权重 | 模式 | 用途 |
|---|---|---|---|
| A | Qwen3-4B-Instruct-2507 | non-thinking | 当前生产基线 |
| B | Qwen3-4B hybrid | no-thinking | 分离“换权重”影响 |
| C | Qwen3-4B hybrid | Route/Retrieve/Revise thinking，Bind no-thinking | 测原生thinking增益 |

构建参数：

```text
qwenModelPack=instruct|hybrid
qwenThinking=off|route|all
qwenThinkingTokens=1024
```

Qwen3官方模板中，non-thinking通过预填空块实现：

```text
<think>

</think>
```

thinking臂则预填 `<think>` 起始并增加 `/think`，输出按最后一个 `</think>` 拆成
`reasoning_output` 与最终结构；未闭合的thinking只记录审计，不能进入parser。

---

## 3. 评测口径

### 3.1 24条Agent规划集

`tests/evals/device_thinking_eval_set.jsonl` 覆盖：

- 普通聊天和能力询问；
- 猫、狗、海边、美食检索；
- 拼贴、滤镜、倒放、镜像、变速、竖排；
- 多动作、否定约束、英文请求；
- 歧义与不支持数量。

断言不仅包含工具名，还检查：

- Route必须/禁止的family；
- 检索词必须保留主体；
- 检索词不得包含拼图、倒放等编辑动作；
- brain attribution、协议和延迟。

### 3.2 12条无UI Route探针

为排除Activity、IME和会话管理影响，instrumentation直接调用真实 `GenieTextPlanner.tryRoute()`，
保存reasoning、最终输出、family、检索词与延迟。

### 3.3 30条动态Binder探针

在真实媒体、数量、selection、layout和artifact状态下，测试模型从≤10个目录中选择具体工具。

---

## 4. 结果

### 4.1 24条规划集

| 模型 | 有效样本 | pass | brain pass | 说明 |
|---|---:|---:|---:|---|
| Instruct-2507 | 23（1条设备会话错误） | **82.6%** | **73.9%** | 当前基线 |
| Qwen3-4B no-thinking | 23（1条输入错误） | 26.1% | 17.4% | 大量格式复读、JSON漂移、漏步骤 |
| Qwen3-4B thinking | 长UI运行因连续会话失败中止 | — | — | 不用不完整结果伪造汇总分数 |

Instruct主要困难项是加速请求、否定裁剪、英文三拼和部分澄清归因；hybrid no-thinking则对绝大
多数媒体任务无法形成可执行Route。

### 4.2 同口径12条Route探针

| 模型 | 通过 | reasoning出现 | 平均延迟 | 中位延迟 | 最大延迟 |
|---|---:|---:|---:|---:|---:|
| Instruct-2507 | 4/12 | 0/12 | **7.45s** | **6.20s** | 13.57s |
| Qwen3-4B native thinking | **1/12** | 8/12 | 27.54s | 24.33s | **58.92s** |

Route探针是严格原始Route评分，没有把后续检索修复算回模型成绩；绝对通过率不是生产
end-to-end成功率，但两臂使用完全相同口径，可以衡量thinking对结构化Route的影响。

### 4.3 Binder

| 模型 | 结果 |
|---|---:|
| Instruct-2507 | **30/30** |
| Qwen3-4B hybrid no-thinking（thinking臂Binder同样关闭thinking） | 27/30 |

hybrid错误包括：背景虚化输出无协议、边框水印无 `STEP=`、暖色误选 `brighten`。

---

## 5. 典型样本

### 5.1 Retrieval：推理正确，最终格式错误

请求：`给我的猫做个三拼`

原生thinking明确推理出：

```text
主体是猫
三拼是编辑动作，应删除
数量是3
```

但旧三槽提示的最终输出是：

```text
SEARCH=内容查询|数量|媒体类型
```

说明thinking没有解决schema echo。协议简化为只输出 `SEARCH=主体` 后，该探针输出
`SEARCH=猫`，但简单任务选择了空thinking；收益来自协议减法，不是reasoning本身。

### 5.2 复杂Route：推理正确，结构落地失败

请求：`找三张猫做三拼再倒放，别加滤镜`

reasoning正确识别：

```text
RETRIEVE → COMPOSE → TEMPORAL → PUBLISH
禁止STYLE
```

最终却输出：

```text
ROUTE=RETRIEVE|COMPOSE|TEMPORAL|PUBLISH
ROUTE=COMPOSE|TEMPORAL|PUBLISH
ROUTE=COMPOSE|PUBLISH
```

parser只能得到重复COMPOSE，漏掉TEMPORAL，因此任务失败。

### 5.3 长思考退化

部分样本thinking持续生成近千token后，最终出现：

- 重复单字符直到token上限；
- 改写成未要求的JSON；
- 把历史session JSON复制进reasoning；
- 正确推理否定条件，最终仍输出STYLE；
- thinking未闭合或闭合后无可解析答案。

---

## 6. 稳定性观察

- hybrid模型初始化期间，日志观察到约6.7GB的QNN/ION占用，并触发系统LMK清理其他进程；
- 长UI评测出现连续会话创建失败，无法形成可信全量报告；
- 两轮长压测期间ADB USB接口掉线，需要重新插拔恢复；
- 一次无UI bounded-loop实验留下未被普通30秒超时立即清理的 `libgenie_app.so` phantom，
  最终由系统约30分钟后回收。该轮数据作废。

这些现象不能证明模型必然导致USB掉线，但足以说明当前4K/QNN运行方式不适合作为稳定生产臂。

---

## 7. 结论

本轮不是“thinking没有发生”：原生reasoning已经被成功捕获，复杂样本可达上千字符。问题是：

1. 旧hybrid权重的结构化指令遵循远弱于Instruct-2507；
2. thinking显著增加串行token与延迟；
3. 4096静态context与官方建议的长thinking预算严重不匹配；
4. reasoning语义正确不保证最终协议正确，Format Tax仍然存在；
5. 稳定性和进程回收风险不满足手机交互要求。

因此：

> **Qwen3-4B hybrid原生thinking不进入生产，保留Qwen3-4B-Instruct-2507。**

> 后续已把真正的 `Qwen3-4B-Thinking-2507` 通过GGUF/llama.cpp部署到目标手机；它不能用
> 本文旧hybrid结论代替。见 `qwen3_4b_thinking_2507_phone_validation.md`。

生产手机已恢复：

```text
qwenModelPack   = instruct
qwenThinking    = off
plannerEvalOnly = false
agentRuntime    = loop
```

如后续获取真正的 `Qwen3-4B-Thinking-2507` QNN包，应至少满足更大context、可靠进程终止和独立
最终答案约束后重新测试，不能把本轮旧hybrid结果外推为所有Thinking模型都无效。

---

## 8. 证据路径

```text
/home/jiangrui/.cache/lpa-final/thinking-eval-instruct.json
/home/jiangrui/.cache/lpa-final/thinking-eval-hybrid-nothink.json
/home/jiangrui/.cache/lpa-final/instruct-route-acceptance-12.json
/home/jiangrui/.cache/lpa-final/thinking-route-acceptance-12.json
/home/jiangrui/.cache/lpa-final/runtime-tool-binding-acceptance-final.json
/home/jiangrui/.cache/lpa-final/thinking-hybrid-bind-acceptance.json
/home/jiangrui/.cache/lpa-final/thinking-retrieval-probe-forced-1024.json
/home/jiangrui/.cache/lpa-final/thinking-retrieval-probe-simple-1024.json
```

官方参考：

- <https://huggingface.co/Qwen/Qwen3-4B-Thinking-2507>
- <https://huggingface.co/Qwen/Qwen3-4B>
- <https://huggingface.co/qualcomm/Qwen3-4B>
