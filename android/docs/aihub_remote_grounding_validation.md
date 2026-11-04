# AI Hub远程语言Grounding验证

> 日期：2026-10-29；decoder回归、远程时序链与vivo固定图银行更新：2026-11-02
>
> 状态：OWLv2语言消歧通过；EdgeTAM QAIRT 2.45固定计数attention图银行已在vivo通过合成遮挡、
> 自然遮挡、快速镜头、出画重入和第16个真实pointer；bounded validity图在vivo多槽状态失败；
> 2.50/2.51确认存在runtime回归
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

该轮两帧闭环当时仍是**混合执行**：三个公开模型组件在S26 NPU，`sam2.memory_attention`由host
PyTorch CPU执行。公开AI Hub recipe没有导出这个约2.96M参数的attention模块，因此这份早期证据
本身不等于Android端完整视频pipeline已经可部署。下一小节记录后续补齐该缺口后的全NPU结果。

### 5.3 远程固定有界memory attention与8帧全NPU闭环

没有把动态Python状态机直接搬进模型，也没有为每个帧数复制一份权重。新增
`android/tools/export_edgetam_memory_attention.py`，把当前EdgeTAM checkpoint改写成一个固定上限图：

```text
current_embeddings       [1, 256, 64, 64]
maskmem_features         [1, 7, 512, 64]
object_pointers          [1, 16, 256]
spatial_memory_valid     [1, 7]
object_pointer_valid     [1, 16]
→ pix_feat               [1, 256, 64, 64]
```

7个空间槽对应时间位置编码`[6, 5, 4, 3, 2, 1, 0]`：条件帧固定在首槽，可用的最近帧填充尾部；
16个pointer槽按“条件帧、最近非条件帧到更早帧”排列。无效槽由显式attention bias排除，不能用零值
padding冒充有效memory。导出同时把上游complex RoPE逐元素等价改写为实数cos/sin运算，避开ONNX
对complex `expand`的限制。

导出前后分别验证启动、增长和满载状态：

| 空间memory / pointer | 对原动态PyTorch最大绝对误差 | 远程QNN对host PSNR | cosine |
|---:|---:|---:|---:|
| 1 / 1 | 9.54e-7 | 73.86dB | 0.9999991 |
| 2 / 2 | 8.34e-7 | 74.89dB | 0.9999992 |
| 7 / 16 | 0 | 80.51dB | 0.9999998 |

固定上限图成功编译为QAIRT 2.45 QNN DLC：

```text
compile job = jp8r6keqg  model = mqy3321w9
profile job = jp37xr3zg  19.000ms，全NPU
profile peak memory = 262,537,216B
DLC size = 37,558,572B
DLC SHA-256 = 8da1a72248b4be97d322feeed5a7f05722a9786bbdf4783b8c74e7d017a091b5
```

随后从6秒真实猫视频抽取`[0, 30, 60, 90, 120, 150, 165, 180]`帧；第90和120帧用草地patch
完全遮住语言选中的中段橘猫，构成确定性遮挡fixture。Host动态实现先验证该fixture：第一帧重现时
对无干扰基线IoU为0.9970。远程链的encoder、bounded memory attention、decoder和memory encoder
全部在S26 NPU执行；host只编排固定槽、执行确定性object-score门控、验证和记录journal：

| 门禁 | 结果 |
|---|---:|
| 遮挡首帧 | `object_score_logit=-1.5537`，正确判定object absent并输出空mask |
| 首次重现帧对host IoU | 0.9864 |
| 遮挡后最低对host IoU | 0.9731 |
| 遮挡后最大质心误差 | 4.16px |
| 跨上/下拼图区泄漏 | 0 |
| 实际执行的最大空间memory数 | 7 / 7 |
| 实际执行的最大pointer数 | 7 / 16 |

该轮证明`sam2.memory_attention`可以从host PyTorch移到NPU，并在AI Hub S26上支持单一bounded图。
但它仍只是远程结果：16个pointer的满载路径只用随机张量通过，真实序列只走到7个pointer；遮挡是
合成fixture。后续vivo验证发现动态validity mask存在输入相关分叉，因此本节的bounded图不再是部署
首选，只保留为远程基线和最小回归样本。该结果不授权Android executor、内容修改或发布。

关键证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-memory-attention-bounded-export.json
/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-memory-attention-bounded-qnn245-differential.json
/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-memory-attention-bounded-profile.json
/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-video-probe/eight-frame-bounded-occlusion-qnn245/
  all-npu-eight-frame-bounded-occlusion-evidence.json
```

### 5.4 vivo QAIRT 2.45固定计数图银行

在vivo V2547A / SM8850上使用官方QAIRT `2.45.0.260326154327`的`qnn-net-run`与
`qnn-context-binary-generator`，全部放在`/data/local/tmp/lpa-edgetam-qnn245-probe-c94fd89`独立
目录。没有替换APK、接Runtime executor、修改或发布媒体。

首先复现bounded图的真实多槽输入。完全相同的3-memory/3-pointer状态在AI Hub对PyTorch为
75.85dB，但vivo只剩**28.05dB / MAE 0.10747**。换成无动态validity mask的等价固定3/3图后，
vivo恢复为**75.48dB对PyTorch、87.54dB对AI Hub**。因此失败不在权重、slot packing或通用
attention，而在vivo执行动态validity attention bias的路径。

部署候选改为由确定性host按已验证状态基数选择精确图。Qwen仍只决定语义target，不接触物理图：

```text
(1 memory, 1 pointer) ... (7 memories, 7 pointers)
(7 memories, 8 pointers) ... (7 memories, 16 pointers)
```

当前完整固定图银行均已验证：

| 拓扑 | vivo对PyTorch最低/代表PSNR | vivo accelerator | 结果 |
|---|---:|---:|---|
| 1/1 ... 7/7 | 最低73.86dB | 14.65–19.24ms | PASS |
| 7/8真实状态 | 75.45dB | 18.39ms | PASS，超过7个真实pointer |
| 7/9 ... 7/16真实状态 | 最低75.03dB | 18.18–18.61ms | PASS，走满16个pointer |

所有图使用`O=3 + VTCM 8MB + soc_id 87 + v81`在vivo生成context。context输出与在线prepare输出
逐字节相同。加载context时仍必须传HTP backend extension；只传`--perf_profile burst`不会施加所需
power vote，实测会从约15–19ms退到约80–112ms。这是runtime契约，不是可忽略的benchmark参数。

完整只读时序门禁：

| fixture | 结果 |
|---|---|
| 8帧两帧合成遮挡 | 遮挡帧fail-closed；恢复后最低IoU 0.9729；最大质心误差0.17px；零泄漏 |
| 9帧加密猫序列 | 最低host IoU 0.9572；7个空间memory + 8个真实pointer；全部可见状态通过 |
| `12_hideandseek.mp4`自然遮挡 | 完全遮挡判空；部分重现IoU 0.9019；完整恢复0.9789；出画末帧fail-closed |
| `19_cyclist.mp4`快速镜头 | 两帧出画均判空；重入IoU 0.9333；可比帧最低0.8858；第8个pointer通过 |
| 17帧加密猫序列 | 最低host IoU 0.9536；最大质心误差5.21px；真实状态走满7 memory / 16 pointers |

17帧preservation初检曾因第15帧有1个像素落到拼图区边界外而拒绝；独立PyTorch参考在同一主连通域
有5个边界像素。最终验证规则是不裁剪mask、且设备额外泄漏不得超过独立参考；最大新增泄漏为0。

为把数值mask变成用户可理解的跟踪证据，又对这17帧设备mask逐帧应用高对比绿色调色。无损PNG上
mask外改变像素总数为0，mask内改变1,190,825像素，最低mask IoU仍为0.9536，额外保护区泄漏为0。
并排MP4只用于人工查看，不参与像素Gate。它清楚显示仅中间面板的目标猫随运动持续变绿，其他猫和
背景不变。该结果证明“已跟踪区域可稳定驱动确定性transform”，但输入仍是17帧抽样fixture，不等于
完整Live Photo逐帧解码、编码和容器重组已经通过。

进一步从真实Live Photo拆出同一181帧原始运动轨道并运行EdgeTAM PyTorch全帧oracle；17个vivo抽样
mask与全帧对应时刻最低IoU为0.9513。181帧全部通过10%coverage和保护区Gate，调色后mask外变化0。
生成的视频保留181帧、AAC音频和6.034秒时长；以第30帧作为1,000,000µs封面后，vivo LivePhotoSDK在应用私有目录完成
pack/decode，封面像素、所有解码视频framemd5和AAC bitstream均保持一致。该证明仍是“PyTorch全帧+
vivo抽样差分”，不是Android QNN全帧执行。

7/16 context还通过了生命周期和短时热门禁：执行中TERM取消后无残留进程，连续10次重载输出SHA
全部一致；3000次连续推理耗时77.99秒，NPU传感器峰值86.5°C，系统thermal status和CDSP cooling
保持0，输出哈希不变。该结果不替代多分钟完整pipeline soak，也不代表DSP总峰值内存已量准。

完整图银行当前约560MiB DLC或234MiB独立context。尝试把7/8与7/16合成一个
`weight_sharing_enabled` context时，两图都完成finalize，但2.45在序列化阶段明确返回
`Weight sharing is unavailable / error 0x138f`。因此尚不能用共享权重宣称包体已解决；Android打包前
必须找到单一多图source导出、其他2.45支持路径，或显式接受并评审context包体。

合成遮挡还暴露了接近阈值的小伪mask会在等价数值路径间前后移动。候选validator因此在model
object score之外增加确定性时序覆盖检查：raw mask面积若突然低于上一个可信可见mask的10%，标记
`UNCERTAIN_TEMPORAL_COVERAGE`，用空mask和`no_obj_ptr`写memory。该策略在上述三类fixture通过，
但仍是**候选验证策略**，尚未进入production或`ScopeCertificate`。

关键证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-bounded-vivo-multislot-failure.json
/mnt/ssd/lpa-aihub-probe/artifacts/vivo-qnn245-fixed-bank/fixed-count-1-to-7-vivo-o3-summary.json
/mnt/ssd/lpa-aihub-probe/artifacts/vivo-qnn245-fixed7-pointer8-frame180/fixture-manifest.json
/mnt/ssd/lpa-aihub-probe/artifacts/vivo-qnn245-real-pointer-counts/real-pointer-9-to-16-differential-summary.json
/mnt/ssd/lpa-aihub-probe/artifacts/vivo-qnn245-dense-seventeen-frame/device-fixed-bank-run/
  vivo-dense-seventeen-frame-fixed-bank-evidence.json
/mnt/ssd/lpa-aihub-probe/artifacts/vivo-qnn245-dense-seventeen-frame/tracked-tint-proof/
  tracked-tint-evidence.json
/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-full-frame-tracked-tint/
  livephoto-tracked-tint-summary.json
/mnt/ssd/lpa-aihub-probe/artifacts/vivo-qnn245-fixed-bank/cancel-reload-lifecycle-summary.json
/mnt/ssd/lpa-aihub-probe/artifacts/vivo-qnn245-fixed-bank/soak-7-memory-16-pointer-summary.json
/mnt/ssd/lpa-aihub-probe/artifacts/vivo-qnn245-fixed-bank/weight-sharing-probe.json
/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-vivo-qnn245-fixed-bank-validation-summary.json
```

### 5.5 OWLv2本地QAIRT 2.45语言locator张量backend

原远程OWLv2 contexts的metadata明确是QAIRT `2.50.0.260828221209`，不能进入本项目2.45进程。
本轮复用同一官方recipe上传的Torch Exported Program，不重新下载或改变权重，分别显式指定
`--qairt_version 2.45`编译、link vision/text contexts：

| 组件 | Compile / Link | Context model | 大小 |
|---|---|---|---:|
| vision | `jgzym3nop` / `jp2dwkoq5` | `mnjxxvewx` | 323,055,616B |
| text/head | `j5wz7e435` / `jgk04e6n5` | `mn0xx6103` | 131,280,896B |

两者metadata均为`QAIRT 2.45.0.260326154327 / soc_id 87 / v81 / HTP / O3`。远程S26使用显式
`--qairt_version 2.45 --compute_unit npu`对同一真实1080×1440猫拼图验证：vision对PyTorch为
72.36dB；端到端boxes最低53.85dB，scores最低70.98dB，labels完全一致。宽泛`a cat`得到9个候选并
返回`NeedUser`；澄清为`an orange cat walking on green grass`后得到唯一候选。

同一contexts和raw输入随后在vivo V2547A的官方2.45 `qnn-net-run`执行。vivo vision/text全部输出与
远程2.45逐字节一致；3次独立vision context加载SHA一致。验证运行显式使用HTP backend extension、
`O3 + VTCM 8MB + soc_id 87 + v81 + burst`及ADSP library path。性能与仅host进程内存：

| 组件 | context加载 | accelerator执行 | host peak RSS / PSS |
|---|---:|---:|---:|
| vision | 639.05ms | 616.66ms | 331,764 / 326,569KiB |
| text/head | 6.96ms | 平均3.07ms | 144,880 / 139,577KiB |

该结果关闭“可获得且能在vivo正确执行的2.45 locator张量backend”Gate。2026-11-03先增加默认关闭的
Android App-UID `qnn-net-run`子进程bridge，随后又在第三层`persistentQnn`开关下接入QNN core API
2.34/System API 1.9的持久JNI runner。JNI从context反射图和tensor contract，以内容SHA为key维护最多
4项LRU，支持QNN graph abort、取消后重载、HTP burst vote及结束释放；子进程继续作为回退。真实cover的
vision/text已接入host后处理。两个locator context合计454,336,512B（433.29MiB），包体Gate明确不通过。
RSS也不包含DSP allocations，不能冒充总内存；短时运行thermal status为0，不替代多分钟soak。

Android Kotlin输入侧已单独闭环。`OwlV2ClipTokenizer`加载官方vocab/merges，宽泛和澄清query的16个
token ID与Hugging Face逐个一致；`OwlV2ImagePreprocessor`复现0.5右/下补边、Gaussian antialias、
bilinear resize和CLIP normalize，1080×1440真实帧对Python raw达到**133.93dB**，最大绝对误差
`1.91e-6`，且源帧SHA不变。2.70秒是包含冷tokenizer解析、预处理、两次源SHA和reference读取的诊断
总时间，不作为稳态性能结论。tokenizer文件仍是外部模型包制品，尚未并入APK。

Android侧纯Kotlin `OwlV2GroundingPostprocessor`固定执行score gate、per-label NMS、box映射、候选数量
决策和证据SHA；交给track bridge的只有canonical language、candidate count和SHA，不把box放入Goal IR。
它现已接到App-UID真实QNN输出：精确target得到唯一候选，宽泛`a cat`得到多个候选并返回`NeedUser`。

汇总证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/owlv2-qnn245/summary.json
SHA-256 88e651805309bd2ad5884d1bc81b3a9026ffdbb23cb697854fb6d9ef2ff2ba78

/mnt/ssd/lpa-aihub-probe/artifacts/owlv2-qnn245/android-input-validation.json
SHA-256 f5edb69c6ad06b6fd6aa9c47ba297facf83099595cccbf5f7351b0a564533e4e
```

## 6. 只读闭环工具

已增加六个host工具：

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
- `android/tools/export_edgetam_memory_attention.py`
  - `--num-memories N --num-object-pointers M`导出固定计数部署候选图；
  - 固定图银行按状态基数覆盖`1/1...7/7`，长序列继续覆盖`7/8...7/16`；
  - `--bounded`只导出动态validity回归图，不得用于vivo多槽部署；
  - 在保存图前自动对照原complex RoPE，任一状态不等价即失败；
  - 只生成模型图，不提交远程作业、不修改媒体。
- `android/tools/prepare_owlv2_qnn245.py`
  - submit/status/link/download严格分阶段，查询状态不会重复提交；
  - compile、link和metadata均强制2.45，任何其他版本直接失败；
  - 下载context后记录SHA和大小，不运行媒体操作。
- `android/tools/validate_owlv2_qnn245.py`
  - 对固定PyTorch raw tensors执行远程2.45 vision + 两轮text；
  - host执行PSNR、NMS、候选数量和`NeedUser/CandidateSetReady`决策；
  - 任一数值低于50dB、labels不等或语义分支错误即失败。
- `android/tools/render_tracked_tint_validation.py`
  - 消费已通过的source frame、设备mask及跟踪evidence，不重新推断或修正mask；
  - 逐帧输出无损调色PNG，强制mask外逐像素不变；
  - 可生成并排MP4供人工观察，但编码视频不参与preservation判定。

EdgeTAM grounding探针首选float模型验证语义，随后才比较W8A8；这样可以把prompt/后处理错误与
量化误差分开定位。

## 7. 当前门禁

- OWLv2公开、Apache-2.0；QAIRT 2.45 contexts已在S26和vivo通过数值对照，Android tokenizer/图像
  输入也已差分通过；App-UID持久JNI context已完成精确唯一候选，子进程回退继续覆盖宽泛`NeedUser`。
- EdgeTAM float QAIRT 2.45 QNN/TFLite单帧mask和memory encoder通过；vivo固定计数attention图银行
  已覆盖1/1到7/16，合成/自然遮挡、快速镜头、出画重入、取消恢复与短时soak通过；
  QAIRT 2.50/2.51明确禁用。
- SAM3不是当前优先路径；OWLv2 box + EdgeTAM 2.45 mask已形成首个通过远程Gate的单帧backend。
- 2.45 encoder、memory encoder、固定计数attention图银行、decoder、object-absent pointer常量与
  manifest均已下载；bounded图只保留失败回归样本。
- 真实语言→OWLv2→动态PTS时间线→EdgeTAM双向传播→完整certificate→GLES区域工具已在5帧和53帧
  Live Photo上进入`WAITING_USER`；`N=53`和现有长序列基准`N=181`均来自MediaCodec EOS而非固定
  计数，并已完成完整调色/编码/repack。持久JNI在`N=53`把总墙钟由220.481秒降至73.464秒、跟踪由
  190.393秒降至45.821秒；`N=181`也由684.405秒降至185.957秒。总DSP峰值内存及locator 433MiB
  与固定图银行包体方案仍未通过，任何Live Photo写回或发布节点仍不可ready。
- 下游Android GLES renderer及真实App-UID逐帧`MASK_TRACK` → content-bound certificate → temporary
  artifact → confirm/undo shadow链已通过；预览优先使用Live Photo封面对应condition frame。
- 17个既有QNN状态点decoder raw mask逐字节复现；动态Android解码连续3帧最低oracle IoU 0.9523，
  中间anchor双向5帧coverage通过。
- PyTorch全帧oracle与Android QNN/GLES/MediaCodec私有Live Photo repack均已通过；生产写回仍未授权。

## 8. 后续Gate

1. 持久Android QNN JNI/context生命周期已通过17帧固定图银行、`N=5`、`N=53`、graph abort与取消后
   重载；继续消除JNI与Kotlin之间的大tensor文件I/O，并保留子进程回退。
2. 持久JNI动态全序列现已覆盖`N=5`、真VFR `N=7`、`N=53`和`N=181`；继续增加遮挡/重入fixture
   与总host/DSP峰值观测，子进程约684秒只保留为旧基线。
3. 待确认/已确认预览的Activity旋转、触屏撤销、进程终止、会话恢复和孤儿清理已通过；Android
   bridge级context重载与底层QNN abort/重载也已通过。
4. 把10%时序覆盖阈值扩展到更多尺度变化fixture；不充分时返回`NeedObservation`，不能静默接受。
5. 联合评审OWLv2 433MiB与EdgeTAM 234MiB context包体；当前单独DLC compose的weight sharing不可用。
6. 按实际PTS的持久GLES调色、MediaCodec编码、AAC sample保留与Live Photo repack已在`N=5`、
   `N=53`和`N=181`素材通过；两阶段整数box blur已在`N=5`、真VFR `N=7`、`N=53`与`N=181`
   通过完整Agent review闭环。真实发布仍需第二次用户授权；2.50/2.51继续禁用。

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

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-qairt245-bounded-temporal-validation-summary.json
SHA-256 f4d72e82dd9ede8bddd44f3d6958dd444b597cc38c290736ee57668a3073f28d

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-vivo-qnn245-fixed-bank-validation-summary.json
SHA-256 7ce67e2128d0212609cb5ae1bd88612ebd0d48448463bcd6b055401c87ab6b16

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-qnn245/manifest.json
SHA-256 a8118e3bdb58d85426845d19d33da9f2877334387e1aec71101b2cc2fae7c6e1

/mnt/ssd/lpa-aihub-probe/artifacts/owlv2-qnn245/summary.json
SHA-256 88e651805309bd2ad5884d1bc81b3a9026ffdbb23cb697854fb6d9ef2ff2ba78

/mnt/ssd/lpa-aihub-probe/artifacts/vivo-qnn245-dense-seventeen-frame/tracked-tint-proof/
  tracked-tint-evidence.json
SHA-256 bbe69a034c77cdf1f1a2ac3c3a052d11506b5dca506f09d394bdcd6d7c1e80dc

/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-full-frame-tracked-tint/
  livephoto-tracked-tint-summary.json
SHA-256 7a06a42544df53f947c621f45e50f620d6107bfc97fd146f587378e1267f929d

/mnt/ssd/lpa-aihub-probe/artifacts/local-deterministic-preview-vivo-gles/summary.json
SHA-256 73da947f37d8cdb3b59b5e774de342d955e20eb107f94fc25fe22d0ce762da71

/mnt/ssd/lpa-aihub-probe/artifacts/local-preview-shadow-bridge-vivo/summary.json
SHA-256 daf8238cb553fcff6473f9c47bff5a3da0f7a17c87dd9e2130c38d63d6310688
```
