# AI Hub远程语言Grounding验证

> 日期：2026-10-29；decoder回归归因更新：2026-10-30
>
> 状态：OWLv2语言消歧通过；EdgeTAM QAIRT 2.45两帧memory传播通过，2.50/2.51确认存在runtime回归
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

为判断是否仅QNN DLC有问题，又编译并运行了float TFLite（当时使用Workbench默认QAIRT 2.50）：

```text
encoder compile job = jgl8784jp  model = mqkklpw1k
decoder compile job = j56mvm26p  model = mmx7pjw0e
encoder inference   = jg9282jwp
decoder inference   = j56mvme6p
```

默认2.50 TFLite结果与QNN错误形态一致：coverage 0.049027、扩展框外泄漏0.501423，并出现完全
相同的半幅复制；仅score从0.8125变为0.809082。证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/cat-precise-mask-tflite-float/mask-evidence.json
```

### 5.1 decoder回归归因与2.45修复

下载同一TFLite并在host CPU/XNNPACK执行后，转换图与PyTorch几乎逐数值一致：

| 对照 | PSNR / score |
|---|---:|
| TFLite encoder `pix_feat` vs PyTorch | 116.11dB |
| TFLite E2E mask vs PyTorch | 120.13dB |
| PyTorch / local TFLite score | 0.940316 / 0.940317 |

远程S26显式使用XNNPACK也产生正确单猫mask，coverage 0.037906、扩框外泄漏0.049379、score
0.940122。由此排除模型、导出图和host后处理，回归只存在于NPU runtime/delegate。

固定正确的XNNPACK encoder输出，只切换decoder QAIRT版本：

| QAIRT | Decoder Job | mask PSNR vs XNNPACK | 上下半幅MAE | 结论 |
|---|---|---:|---:|---|
| 2.45 | `jgowo30kp` | 64.16dB | 11.50 | PASS |
| 2.50 | 默认路径 | 约8.02dB | 0.0 | FAIL，完全复制 |
| 2.51 | `jpvmevorp` | 15.48dB | 0.0 | FAIL，回归仍存在 |

Workbench当前只开放这三个QAIRT版本。随后对每个版本用完全相同的模型、输入和options各重复3次：

| QAIRT | 3次结果 | PSNR vs XNNPACK | 二值mask IoU | 半幅MAE |
|---|---|---:|---:|---:|
| 2.45 | 3/3逐字节相同 | 64.16dB | 0.9996 | 11.50 |
| 2.50 | 3/3逐字节相同 | 15.48dB | 0.3831 | 0.0 |
| 2.51 | 3/3逐字节相同 | 15.48dB | 0.3831 | 0.0 |

2.50与2.51不仅都失败，而且三个重复job产生完全相同的错误mask SHA-256；因此可排除偶发设备、
队列、输入上传或非确定性误差。

关闭`qnn_htp_use_conv_hmx`不能修复2.50，因此不是单一short-conv HMX开关。完整TFLite + QNN
delegate在QAIRT 2.45上随后通过，二值mask对XNNPACK IoU为0.9678：

```text
encoder inference = jg92kw18p
decoder inference = jg92kwe8p
encoder profile   = j5q1z26eg  8.953ms，全NPU
decoder profile   = jgl8okv2p  2.737ms，全NPU
```

最后从相同source model显式重编译QAIRT 2.45 QNN DLC，也得到相同正确mask：

```text
encoder compile = jp44mwn2p  model = mq24jp5xd
decoder compile = jpxr3198p  model = mmr6dj0ex
encoder profile = jgl8ok2ep  8.925ms，全NPU
decoder profile = j56mr1zvp  2.725ms，全NPU
QNN mask vs XNNPACK IoU       = 0.9678
QNN mask vs TFLite-QNN-2.45   = 1.0
```

又把**同一个由2.45编译的decoder DLC**分别交给2.45、2.50和2.51 runtime加载。2.45正常；2.50和
2.51再次产生同一个半幅复制mask，二者对2.45正确结果均只有7.47dB、IoU 0.3853。该实验保持DLC
字节、输入数据和设备型号不变，只切换runtime版本，进一步证明回归位于QAIRT执行层而非编译产物。

可供下一阶段本机隔离probe的制品：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-qnn245/encoder.dlc
SHA-256 6ad2c1dd547f59633cf4cb51e6fac9128830d7c18fdad60ee4f0451a31e4c225

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-qnn245/video_decoder.dlc
SHA-256 b830a52684bd03d078e45b4d2d81230f9d85de87315f7cb4091fba6bcbb95d87

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-qnn245/memory_encoder.dlc
SHA-256 7985600ba858ff475a9c4b4d9a70fab66a48c949df31a6b8c9b04a159aa98a23

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-qnn245/maskmem_pos_enc.npy
SHA-256 16ae91334554dace33db3259641ffa5248e1f58a3f378d0aa80f1212921d3752
```

因此最终结论是：**EdgeTAM recipe与导出图正确；QAIRT 2.50引入且2.51仍存在NPU decoder回归；
项目当前固定的QAIRT 2.45反而是已验证正确路径。** 2.50/2.51制品继续禁用，2.45 float DLC只获准
进入本机隔离、只读probe，尚未获准接Android executor或修改媒体。

### 5.2 memory encoder与两帧时序闭环

memory encoder显式编译到QAIRT 2.45：

```text
compile job   = jgzy8xj4p  model = mqv409yvl
inference job = jg92k3yqp
profile job   = jgl8o03ep  9.220ms，全NPU
```

使用2.45 encoder/decoder产生的真实feature与mask，remote memory输出对host为55.22dB，cosine
0.999972，mean absolute error 0.00411，单组件数值通过。

随后从真实6秒猫视频选择第0帧与第30帧。只有第0帧接收由语言box生成的两个内部正点；第30帧
不再提供目标提示，只通过首帧memory传播：

| 帧 | remote vs host mask IoU | 质心误差 | 跨拼图泄漏 |
|---:|---:|---:|---:|
| 0 | 0.9741 | 0.31px | 0 |
| 30（约1秒） | 0.9760 | 2.90px | 0 |

这说明远程QAIRT 2.45的encoder → decoder → memory encoder → 下一帧decoder数值闭环成立，且目标
没有漂到上下两段的其他猫。Host连续10帧基线的coverage保持0.0368–0.0390，跨拼图泄漏也始终为0。

但当前远程闭环仍是**混合执行**：三个模型组件在S26 NPU，`sam2.memory_attention`由host PyTorch
CPU执行。公开AI Hub recipe没有导出这个约2.96M参数的attention模块。因此两帧通过不等于Android
端完整视频pipeline已经可部署；下一架构门禁是把固定有界memory attention导出为设备组件，或实现
等价的本机执行层。

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
  - 默认固定`QAIRT 2.45 + NPU`；禁止同时传`compute_unit`与`tflite_delegates`；
  - 禁止上游自动重试，因为其重试会遗漏inference options并静默改变delegate；
  - 输出单帧mask/overlay后仍返回`NeedObservation`，等待视频传播与`ScopeCertificate`。

第二个探针首选float EdgeTAM验证语义，随后才比较W8A8；这样可以把prompt/后处理错误与量化误差
分开定位。

## 7. 当前门禁

- OWLv2公开、Apache-2.0；S26 float编译、数值对照、宽泛语言`NeedUser`和澄清后唯一候选均通过。
- EdgeTAM float QAIRT 2.45 QNN/TFLite单帧mask、memory encoder数值及两帧传播均已通过；
  QAIRT 2.50/2.51明确禁用。
- SAM3不是当前优先路径；OWLv2 box + EdgeTAM 2.45 mask已形成首个通过远程Gate的单帧backend。
- 2.45三组件DLC与mask positional encoding已下载，但memory attention尚无Android制品；在补齐并
  完成vivo本机隔离验证前不接Android executor。
- 两帧无显著遮挡，不构成完整`ScopeCertificate`；任何内容移除/填充/发布节点仍不可ready。

## 8. 后续Gate

1. 将固定上限的memory attention导出为设备组件，或实现等价本机执行层。
2. 扩大到更多连续帧、快速运动和遮挡恢复，不只验证两帧。
3. 在vivo上以独立、只读进程运行完整2.45时序链，不修改或发布媒体。
4. 通过本机范围门禁后，才比较W8A8和完整时序覆盖。
5. QAIRT 2.50/2.51保持禁用；vivo闭环通过后才建设Android DAG node bridge。

本轮汇总证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/aihub-remote-grounding-acceptance.json
SHA-256 a19cc6bf95784b3bc2832b5f174cb886b8abcd5ec338a8af0a792ca7b44bedea

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-decoder-regression-resolution.json
SHA-256 85f453936201d3f8746b5de0ab82121c0d943080ce879dbc8140b54c1ee4bf30

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-decoder-qairt-version-repeat-matrix.json
SHA-256 1ea53e817681f7134f817f5b279e4bc6eab095c375779539ab5bda201ddf28b9

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-qnn245-dlc-runtime-version-differential.json
SHA-256 2be28a9957014c4135e74a074335e4bfb3cddacf6c215b59e82e126d657e922f

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-memory-qnn245-differential.json
SHA-256 1ba8582eb8d2be40338bcc9534be2332591bdf63e9daf6d64a4b732fe66ae2a4

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-video-probe/two-frame-qnn245/two-frame-temporal-evidence.json
SHA-256 e9eb56cd006d690e7256bd13d40576414b6d4769bce5257621dc03a2623d8ef8

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-qairt245-temporal-validation-summary.json
SHA-256 68b79eacf1eda03a42e13aa13aa854cdf5c3b7ed43c556f35766739442800d68
```
