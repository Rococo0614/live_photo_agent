# AI Hub远程语言Grounding验证

> 日期：2026-10-29
>
> 状态：OWLv2语言消歧通过；EdgeTAM QNN/TFLite因真实输入数值回归停在远程Gate
>
> 生产边界：Runtime v2 loop不变；远程作业不得修改或发布媒体

## 1. 执行边界

Qualcomm AI Hub Workbench提供模型编译、量化和真实设备张量推理，不提供任意APK安装、Android
shell、MediaStore或vivo SDK环境。因此“远程测试Agent”采用以下拓扑：

```text
host Runtime v3 test orchestrator
├→ Goal/TargetPredicate与DAG决策
├→ 提交AI Hub模型节点
├→ 下载boxes/masks/scores
└→ 本地验证、journal与下一步分支

AI Hub Galaxy S26
└→ 只执行模型张量图
```

这能提前验证模型可编译性、端侧数值、NPU延迟和节点间张量契约；不能替代最终vivo真机上的
QAIRT加载、内存/温度、视频解码、Activity生命周期和SDK闭环。

## 2. 隔离环境

没有修改既有Qwen量化环境。候选依赖、模型缓存和临时制品均放在：

```text
/mnt/ssd/lpa-aihub-probe/
```

当前Workbench可选择：

```text
QAIRT 2.45 = 2.45.0.260326154327
QAIRT 2.50 = 2.50.0.260828221209（当前默认）
QAIRT 2.51 = 2.51.0.260929181845
```

所以远程语义验证通过后，可以用`--qairt_version 2.45`重新编译本地候选；或者把2.50放在
独立进程。不得把2.50库直接混入当前2.45生产进程。

## 3. EdgeTAM W8A8真实远程烟测

使用Qualcomm公开v0.65.0通用QNN DLC，上传encoder、video decoder和memory encoder后，在
`Samsung Galaxy S26 / Android 16`提交带profile的真实设备作业：

| 组件 | Job | 结果 | NPU延迟 | profile峰值内存 |
|---|---|---|---:|---:|
| encoder | `jpxrml1lp` | PASS | 3.741ms | 215,781,376B |
| video decoder | `jgnqxzeqg` | PASS | 1.163ms | 174,190,592B |
| memory encoder | `jp2djrmq5` | PASS | 2.798ms | 188,764,160B |

三个组件的profile明细均只出现NPU compute unit。证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-w8a8-remote-smoke.json
```

该轮使用Workbench随机输入，只证明DLC、QAIRT 2.50和S26执行路径有效；不证明mask质量。尤其
W8A8包对坐标、label和中间张量使用量化I/O，真实语义链必须验证量化/反量化与prompt范围，不能
仅依据profile通过就部署。

## 4. OWLv2真实语言消歧

OWLv2 float vision/text已由官方recipe编译为S26 QNN context：

| 组件 | 模型ID | Profile Job | 延迟 | Compute |
|---|---|---|---:|---|
| vision | `mm6vzjpe2` | `j56mv7m7p` | 432.8ms | 1694/1694 ops NPU |
| text | `mno3z4rep` | `jgowmrwdp` | 2.8ms | 1602/1602 ops NPU |

远程/host数值对照也通过：vision embedding PSNR 76.62dB；text boxes 73.02dB、scores 90.5dB、
labels 100dB。

在一张含多只橘猫的真实拼图上执行两轮纯语言Grounding：

本轮host探针手工提供与未来Qwen `TargetPredicate`等价的英文canonical query，同时保留中文用户原话；
它验证Grounding模型节点和DAG分支，不宣称生产QNN已经切换到Runtime v3 Goal IR协议。

| 用户语言 | 远程行为 | 候选 | 决策 |
|---|---|---:|---|
| “移除橘猫” | vision + text | 6 | `NeedUser`，禁止提交EdgeTAM |
| “移除草地上行走的橘猫” | 复用同一vision embedding，只重跑text | 1 | `CandidateSetReady`，仍禁止修改 |

第二轮正确定位草地中间的橘猫。它复用首轮vision job `j5q1w42og`，只新增text job
`jgl87w8ep`，证明澄清分支不需要重复约432.8ms的关键帧视觉编码。证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/cat-ambiguous/grounding-evidence.json
/mnt/ssd/lpa-aihub-probe/artifacts/cat-precise/grounding-evidence.json
```

## 5. EdgeTAM语言box到mask诊断

把唯一OWLv2 box交给重新上传的公开float QNN DLC后，模型作业本身成功，但mask未通过范围门禁：

```text
mask coverage                       = 0.049027
outside expanded language box       = 0.501423
model score                          = 0.8125
decision                             = MaskRejected → NeedObservation
content mutation                     = false
```

使用当前导出未声明支持的corner labels `2/3`更差，coverage达到0.42107；已停止该方法。改用recipe
明确支持的两个内部正点后，目标猫主体mask较清楚，但同一mask仍在另一拼图区域重复，因此范围门禁
正确拒绝。证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/cat-precise-mask-float-points/mask-evidence.json
```

为排除raw DLC通道适配问题，又用官方recipe重新编译float QNN DLC：

```text
encoder compile job = jpvm4mkjp  model = mqy325zv9
decoder compile job = jgjy1ynxg  model = mn7l65wxo
encoder inference   = jp2djdk45
decoder inference   = j5wzxzw65
```

带producer metadata并由`AsyncOnDeviceModel`自动转换NCHW/NHWC后，远程mask仍出现完全相同的
上下半幅重复。相同输入的host PyTorch EdgeTAM只分出草地中的目标猫，证明提示点和模型能力本身
正常。逐组件差分如下：

| 对照 | PSNR / 差异 | 结论 |
|---|---:|---|
| remote encoder `image_embeddings` vs host | 23.67dB | 低于常用30dB门槛 |
| remote encoder `pix_feat` vs host | 23.73dB | 主特征已明显退化 |
| high-res 1 / high-res 2 / sparse prompt | 46.98 / 39.85 / 62.57dB | 这些输出较稳定 |
| host decoder(remote encoder) vs host E2E mask | 20.98dB | encoder退化会伤害mask，但不会产生半幅复制 |
| remote decoder vs host E2E mask | 8.02dB | 严重错误 |
| remote mask上下半幅MAE | 0.0 | 两半完全复制，确定是错误结果 |
| host mask上下半幅MAE | 11.48 | host输出正常 |

证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/cat-precise-mask-compiled-float/mask-evidence.json
/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-float-component-differential.json
/mnt/ssd/lpa-aihub-probe/local-edgetam/build/edgetam_segmentation_output.png
```

为判断是否仅QNN DLC有问题，又编译并运行了float TFLite：

```text
encoder compile job = jgl8784jp  model = mqkklpw1k
decoder compile job = j56mvm26p  model = mmx7pjw0e
encoder inference   = jg9282jwp
decoder inference   = j56mvme6p
```

TFLite结果与QNN错误形态一致：coverage 0.049027、扩展框外泄漏0.501423，并出现完全相同的半幅
复制；仅score从0.8125变为0.809082。证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/cat-precise-mask-tflite-float/mask-evidence.json
```

结论不是“EdgeTAM原模型不可用”，而是**当前AI Hub EdgeTAM QNN和TFLite部署导出均不可作为语义
正确的Grounding backend**。性能profile只证明图能执行，不证明输出正确。按远程优先Gate，停止
这些制品的本地部署，不继续测试W8A8语义链。下一优先级是SAM3直接文本mask；ONNX只有在能证明
不复用同一错误转换路径时才值得测试。

## 6. 只读闭环工具

已增加两个host探针：

- `android/tools/aihub_owlv2_grounding_probe.py`
  - 用户目标仍是语言；
  - OWLv2 vision/text组件在远程S26执行；
  - 返回候选box、score、overlay和job journal；
  - 0个候选返回`NeedObservation`，数量不符返回`NeedUser`；
  - 即使唯一也只返回`CandidateSetReady`，不允许修改媒体。
- `android/tools/aihub_edgetam_grounding_probe.py`
  - 只接受上游唯一候选；
  - 多候选时在提交远程作业前fail-closed；
  - OWLv2 box转两个内部正点只是backend plumbing，不是用户点选；
  - 检查mask总覆盖率及扩框外泄漏，超限时返回`MaskRejected`而不裁剪伪装成功；
  - 输出单帧mask/overlay后仍返回`NeedObservation`，等待视频传播与`ScopeCertificate`。

第二个探针首选float EdgeTAM验证语义，随后才比较W8A8；这样可以把prompt/后处理错误与量化误差
分开定位。

## 7. 当前门禁

- OWLv2公开、Apache-2.0；S26 float编译、数值对照、宽泛语言`NeedUser`和澄清后唯一候选均通过。
- EdgeTAM W8A8组件profile通过，但float QNN和TFLite官方recipe在真实输入上都出现半幅复制；
  当前AI Hub部署backend不得进入本机。
- SAM3权重`facebook/sam3`为manual gated；本机尚无Hugging Face token。OWLv2 box与DAG消歧协议
  已验证，但当前没有通过远程语义Gate的mask backend。
- AI Hub成功后仍需下载同一制品，在vivo上以独立runtime只读运行；在那之前不接Android executor。
- 单关键帧mask不构成`ScopeCertificate`，任何内容移除/填充/发布节点仍不可ready。

## 8. 后续Gate

1. 固化最小复现并向Qualcomm/recipe维护方核对decoder导出回归。
2. SAM3 gated权重授权后，优先验证其直接文本mask，减少OWLv2→point桥接误差。
3. ONNX只有在确认采用不同转换路径时才测试；不重复消耗相同失败路径。
4. 只有float语义正确后才比较W8A8、视频memory path和时序覆盖。
5. 只有远程语义通过的backend才允许生成QAIRT 2.45或隔离2.50本机制品。
6. vivo独立进程只读probe通过后，才建设Android DAG node bridge。

本轮汇总证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/aihub-remote-grounding-acceptance.json
SHA-256 a19cc6bf95784b3bc2832b5f174cb886b8abcd5ec338a8af0a792ca7b44bedea
```
