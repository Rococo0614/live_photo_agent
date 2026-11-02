# 视频内容生成、修改与填充模型调研

> 日期：2026-10-29；EdgeTAM vivo固定图银行更新：2026-11-02
>
> 目标设备：vivo PD2547 / SM8850 / 16GB RAM
>
> 当前生产：Runtime v2 loop；Runtime v3 DAG仅shadow，不执行新增模型
>
> 验证状态：OWLv2语言消歧通过；EdgeTAM QAIRT 2.45固定计数attention图银行在vivo通过合成/自然
> 遮挡、快速镜头、出画重入和第16个真实pointer；bounded validity图在vivo禁用；2.50/2.51回归已隔离

## 1. 结论摘要

当前最可行的端侧路线不是直接部署一个“全能视频生成模型”，而是把小模型组合进Runtime v3：

```text
Qwen把用户描述编译成TargetPredicate
→ SAM3在稀疏关键帧上用文本生成候选mask
→ EdgeTAM把确认的候选传播成OBJECT_TRACK + MASK_TRACK
→ ScopeCoverageValidator检查唯一性、时间覆盖与排除范围
→ LaMa-Dilated按帧/关键帧填充
→ 时序传播与一致性校验
→ 重封装视频或Live Photo
```

该路线适合先实现**目标移除和小区域填充MVP**。它的主要风险是LaMa本质上是图片模型，逐帧运行
会闪烁；AI Hub现成LaMa checkpoint还是Dilated CelebA-HQ，通用背景质量必须实测，必要时需要
把通用Big-LaMa权重重新导出。

内容添加的首版建议采用“生成/检索一个静态patch + 跟踪合成”，而不是完整视频扩散：

```text
Stable Diffusion / ControlNet生成对象patch
→ SAM3按语言定位锚点 + EdgeTAM跨帧跟踪
→ Depth Anything估计遮挡
→ 确定性composite与光照/透明度调整
```

真正的文本到视频和指令式视频编辑已有移动研究模型：Neodragon、MobileWan、MoViE。但公开仓库
目前没有可以直接装入本项目的Android/QNN资产：Neodragon/MobileWan公开的是PyTorch/CUDA
采样代码；MobileWan还明确未发布优化视频decoder；MoViE仓库只有项目页，没有推理代码与权重。

## 2. 设备与运行时门禁

本项目当前QNN/LLM栈固定在QAIRT 2.45。下面EdgeTAM、LaMa、Stable Diffusion等最新公开资产来自
AI Hub Models v0.65.0，并标注 **QAIRT 2.50**；本机0.64.0包中的最新Galaxy S26 perf记录也由
QAIRT 2.50生成。因此：

- 不得把2.50 QNN DLC/context直接混入当前2.45进程；
- 首次实验使用独立目录、独立进程和独立runtime库；
- 或用AI Hub把相同模型重新编译到已确认兼容的目标版本；
- 只有单模型probe通过后，才评估Qwen/VLM/BGE与视频模型的生命周期调度；
- 下表延迟是单组件模型延迟，不包含解码、缩放、mask后处理、内存拷贝和视频封装。

### 2.1 输入决策：首版只接受语言目标，不引入点选或框选

点选/框选会新增坐标映射、关键帧选择、触控误差、目标被遮挡时的重新输入和多实例判定，而且并不
天然保证跨帧范围正确。首版因此把语言作为唯一目标输入：

```text
“移除右后方穿红衣服的人，但保留前面的孩子”
             ↓ Qwen（只解析语义，不画mask）
TargetPredicate(
  concept=person,
  attributes=[red clothing],
  spatial=[right, background],
  quantifier=one,
  exclude=[child in foreground],
  temporal=whole_clip
)
```

mask是Grounding产生的**事实输出**，不是用户输入。系统可以显示overlay作为预览，但不要求用户
点击。若候选不唯一，继续使用语言追问，例如“是左侧红衣人物，还是右侧白衣人物？”。用户回答
后重新编译谓词和Grounding子图。

这也成为DAG范围是否充分的门禁：没有`ScopeCertificate`的内容修改节点不得ready。

## 3. 可在设备端优先验证的组件

### 3.1 SAM3：首选语言到关键帧mask

SAM3（Segment Anything with Concepts）可直接接受短自然语言概念，输出匹配实例的boxes、masks
和scores；上游还支持文本驱动的视频跟踪。Qualcomm已提供可导出的手机recipe：

| 项目 | 结果 |
|---|---|
| 输入 | 1008×1008图像 + 最长32 token文本 |
| 输出 | 候选boxes、scores、quarter-resolution mask logits |
| Galaxy S26 vision backbone | 约1099ms |
| Galaxy S26 text/head | 约215ms；同一关键帧特征可复用多个短prompt |
| 许可 | Meta SAM自定义许可；AI Hub因许可不提供预编译下载 |
| 运行时 | AI Hub recipe基于QAIRT 2.50，需自行export |

当前Qualcomm recipe是**单图文本分割**，没有导出上游SAM3 video predictor。因此建议每个scene只在
少量关键帧运行SAM3，再用EdgeTAM传播mask；不能逐帧跑约1.3秒的SAM3完整前向。

SAM3擅长短概念词组，不保证理解“顶层书架从右数倒数第二本书”等复杂关系。Qwen应把复杂描述
拆成概念、属性、相对位置、深度、数量和排除谓词；SAM3只负责感知候选，不能替代关系求值。

备选语言定位器：

| 模型 | Galaxy S26公开组件延迟 | 优缺点 |
|---|---:|---|
| **OWLv2** | text 2.789ms + vision 431.661ms | Apache-2.0、运行时自由文本、只输出box；可接EdgeTAM，速度快于SAM3 |
| **YOLO-World** | text约95–104ms + detector约31–47ms | 12.7M detector、运行时文本embedding、只输出box；GPL-3.0且text encoder profile内存较高 |
| **YOLOE-Seg** | W8A8约3.759ms | 直接输出mask但AI Hub recipe在export前`set_classes(prompt_text)`，更适合固定词表；AGPL-3.0 |

若SAM3许可或约1.3秒/关键帧延迟不可接受，优先用Apache-2.0 OWLv2定位box，再让EdgeTAM生成
精细mask。YOLOE仅适合预定义概念目录，不满足任意语言目标首选路径。

本项目已把OWLv2 float官方recipe编译到AI Hub真实Galaxy S26：vision 432.8ms、text 2.8ms，
均全NPU。真实猫拼图中，“移除橘猫”返回6个候选并正确触发`NeedUser`；语言澄清为“草地上行走
的橘猫”后复用同一vision embedding，只重跑text并收敛到正确的唯一box。

### 3.2 EdgeTAM：语言mask的跨帧传播器

| 项目 | 结果 |
|---|---|
| 任务 | 交互式视频任意目标分割与跟踪 |
| 许可 | Apache-2.0 |
| 结构 | encoder 8.3M / memory encoder 1.2M / memory attention约2.96M / video decoder 6.22M |
| 本项目2.45 DLC大小 | 19.0MB / 7.4MB / 33.6–37.5MB固定计数attention / 21.5MB |
| 输入 | encoder 1024×1024 |
| Galaxy S26 W8A8 QNN组件延迟 | 3.763ms / 2.797ms / 1.169ms |
| 最新公开资产 | Universal QNN DLC / ONNX / TFLite，QAIRT 2.50 |

本项目随后把公开W8A8 QNN DLC提交到AI Hub真实Galaxy S26，encoder / memory encoder /
video decoder实测为**3.741 / 2.798 / 1.163ms**，三者均全NPU；这仍只是随机输入组件烟测，
不等于mask语义验证。详见`aihub_remote_grounding_validation.md`。

首次默认QAIRT 2.50链式测试出现半幅mask复制；2.51仍复现，关闭short-conv HMX也无效。但下载的
TFLite在host CPU与PyTorch达到120.13dB，远程S26 XNNPACK也正确，证明导出图无误。显式切换
QAIRT 2.45后问题消失：decoder固定输入对XNNPACK为64.16dB，完整mask IoU为0.9678。重新编译的
2.45 QNN DLC在真实S26上全NPU运行，float encoder / decoder为**8.925 / 2.725ms**。因此必须固定
2.45；2.50/2.51禁用。三个版本各重复3次后，版本内输出均逐字节一致，且2.50与2.51产生相同错误
SHA；同一个2.45 DLC换到2.50/2.51 runtime也立即复现。当前单帧mask已过远程Gate，视频memory
encoder也达到55.22dB/cosine 0.999972。真实视频第30帧不再提供提示、只依赖首帧memory时，对host
mask IoU仍为0.9760且无跨拼图泄漏。

公开recipe遗漏的约2.96M参数memory attention已补齐。最初的单一7-memory/16-pointer validity图在
远程S26通过，但vivo SM8850多槽真实输入只有28.05dB；同一状态的固定3/3图为75.48dB。因此该
bounded图只保留为回归fixture，部署候选改为host按状态基数选择的固定图银行。当前`1/1...7/7`
和`7/8...7/16`已在vivo通过，对PyTorch最低73.86dB，accelerator为**14.65–19.24ms**；
9–16 pointer真实状态最低75.03dB。context retrieval必须同时加载HTP backend extension，否则
burst power vote不会生效。

完整NPU只读链已通过：两帧合成遮挡恢复后最低IoU 0.9729；自然`hideandseek`完全遮挡判空、完整
恢复0.9789、出画fail-closed；快速镜头`cyclist`两帧出画判空、重入0.9333；17帧链走满16个真实
pointer，最低host IoU 0.9536。TERM取消后10次重载和3000次短时soak通过。10%突然面积下降门禁
目前只是候选validator；逐帧覆盖、本地语言locator和Android `ScopeCertificate`仍待完成。

全银行当前约560MiB DLC / 234MiB独立context。2.45把两个独立DLC合成weight-shared context时在
序列化阶段返回`Weight sharing is unavailable`，所以包体仍是Android集成前置门禁，不能把单图
14–19ms性能等同于完整产品包已可接受。

用途（不要求用户点选）：

- 接收SAM3关键帧mask并传播成稳定`OBJECT_TRACK`；
- 为remove/change提供逐帧`MASK_TRACK`；
- 在用户确认前显示mask overlay，避免模型选错同类人物/物体；
- 作为内容增加时的锚点/遮挡目标跟踪器。

它只生成mask，不做内容填充。相比SAM2，EdgeTAM更适合手机：SAM2 Tiny的公开S26 encoder仍约
40–68ms，而EdgeTAM W8A8组件明显更轻。

### 3.3 LaMa-Dilated：首选端侧填充MVP

| 项目 | 结果 |
|---|---|
| 任务 | 单帧mask区域擦除与inpaint |
| 许可 | Apache-2.0 |
| 参数/大小 | 45.6M / 174MB float |
| 输入 | 512×512 |
| Galaxy S26 QNN DLC | 33.853ms，约4–360MB profile范围 |
| 最新公开资产 | Universal QNN DLC / ONNX / TFLite，QAIRT 2.50 |

优点是模型小、已有NPU资产、单帧速度足够。49帧逐帧核心模型耗时理论下限约1.66秒，但实际
还要加视频解码、缩放/切块、mask处理和封装。

限制：

- AI Hub现成checkpoint为Dilated CelebA-HQ，并非通用Big-LaMa；
- 没有时间维度，逐帧结果可能闪烁、纹理漂移；
- 512输入需要对高分辨率帧做ROI或overlap tile；
- 大面积运动目标、镜头快速移动和新暴露背景仍可能失败。

### 3.4 AOT-GAN：较小但不是首选

| 项目 | 结果 |
|---|---|
| 许可 | AI Hub标记MIT，但上游AOT-GAN仓库LICENSE为Apache-2.0；打包前需澄清 |
| 参数/大小 | 15.2M / 58MB float |
| 输入 | 512×512，CelebA-HQ checkpoint |
| Galaxy S26 QNN DLC | 44.782ms |

文件更小，但当前checkpoint同样偏人脸，且S26实测比LaMa慢。保留为人像移除/修补对照，不作为
通用场景首选。

### 3.5 Depth Anything V2：内容添加的遮挡辅助

| 项目 | 结果 |
|---|---|
| 许可 | MIT（AI Hub声明） |
| 参数/大小 | 24.7M / 94.3MB float |
| 输入 | 518×518 |
| Galaxy S26 W8A16 QNN DLC | 13.391ms |

可估计插入对象与原场景的前后关系，但深度不能替代三维重建；只用于遮挡顺序、落点和粗略尺度。

### 3.6 Stable Diffusion 1.5 / ControlNet-Canny：生成静态patch或关键帧

AI Hub已有SM8850同代目标的W8A16 QNN context：

| 组件 | Galaxy S26 QNN context延迟 |
|---|---:|
| SD1.5 text encoder | 1.561ms |
| SD1.5 UNet（每denoise step） | 45.391ms |
| SD1.5 VAE | 79.17ms |
| ControlNet（每step） | 23.397ms |
| ControlNet UNet（每step） | 49.24ms |

适合：

- 生成需要插入的静态对象或贴图；
- 生成少量关键帧；
- 以Canny/结构约束做局部风格或属性修改实验。

不适合直接逐帧生成完整视频：20步ControlNet仅核心网络就约1.5秒/帧，且各帧没有时间一致性。
若使用自定义inpaint/InstructPix2Pix权重，仍需重新量化与编译，不能假定现成T2I资产可直接替换。
SD1.5受CreativeML OpenRAIL-M及AI Hub使用限制约束，发布前需单独审核。

### 3.7 FOMM：专用图片动画

| 项目 | 结果 |
|---|---|
| 任务 | 用驱动视频动作动画化一张源图 |
| 许可 | MIT |
| 输入 | 256×256，vox-256 checkpoint |
| Galaxy S26 | detector 2.786ms + generator 10.409ms |

可以做头像/人脸驱动的专用工具，但不是通用文本到视频，也不能完成任意对象新增或移除。

## 4. 移动研究模型：能力强但当前不能直接集成

### 4.1 MoViE：最符合“指令修改”，但没有可用权重

MoViE基于移动版InstructPix2Pix，支持全局风格、背景、对象和属性修改。论文在Snapdragon 8 Gen 3
上报告512×384、120帧约9.6秒（约12fps），非常契合本Agent的`CHANGE_CONTENT`。

阻塞：官方GitHub仓库只有项目网页、图片和演示视频，没有推理实现、checkpoint或QNN资产；因此
现在只能作为目标架构和性能参考，不能排入集成计划。

### 4.2 Neodragon：当前最完整的公开移动T2V研究包

官方报告：

```text
2秒 / 49帧 / 24fps
原生320×512，2×超分后640×1024
Snapdragon Hexagon NPU端到端约6.7秒
峰值RAM约3.5GB
完整pipeline约4.945B参数
```

代码、模型和优化decoder均已公开，许可为BSD-3-Clause-Clear加Qualcomm Responsible AI条款。
但公开仓库的实际入口是PyTorch/CUDA脚本，不含QNN context、Android app、导出脚本或设备runtime。
所以它是最值得监控/尝试转换的**生成候选**，不是现成APK组件。

### 4.3 MobileWan：质量更强，但公开移动pipeline不完整

官方报告5B DiT可在手机生成5秒、81帧、480×832视频，约20秒，VBench 83.79。公开了代码和
transformer checkpoint，但模型卡明确写明**优化视频decoder未公开**；仓库仍是Diffusers/CUDA
采样器，没有QNN/Android制品。因此当前可用于主机质量实验，不能复现论文移动端E2E数字。

### 4.4 MobileVD：尚未发布

论文报告14帧、512×256、手机约1.7秒，但项目页仍标记code/checkpoint “soon”。没有权重就不进入
候选实施列表。

## 5. 主机/云端候选

| 模型 | 能力 | 许可 | 当前判断 |
|---|---|---|---|
| **Wan2.1-VACE-1.3B** | T2V、参考图生成、V2V、mask编辑、增加/变化/移除/扩图 | Apache-2.0 | 最佳主机语义基线；480P约81帧，24GB GPU值得先跑质量probe |
| **LTX-Video 2B distilled** | T2V/I2V、多关键帧、视频延长、V2V | 自定义Open Weights License | 主机生成/延长候选；非端侧，许可需审核 |
| **ProPainter** | 目标移除、视频completion | NTU S-Lab非商用 | 质量oracle；320×240 FP16约2GB、640×480约6GB、720p约19GB显存 |
| **E2FGVI/HQ** | flow-guided视频inpaint | CC BY-NC 4.0 | 较轻研究基线；432×240在Titan XP约0.12秒/帧，禁止商用 |
| **DiffuEraser** | diffusion视频inpaint | Apache代码但继承ProPainter限制 | 主机研究，不作为生产许可基线 |
| **VideoPainter** | 任意长度inpaint、文本编辑 | 明确非商用，依赖CogVideoX-5B | 仅研究/质量对照 |
| **VOID** | 删除对象及其阴影/物理交互副作用 | Apache代码，依赖CogVideoX许可 | 需要40GB+ VRAM，当前24GB主机也不适合 |
| **MiniMax-Remover** | 81帧480×832对象移除，少步DiT | 官方仓库未提供清晰LICENSE | 许可澄清前排除 |

VACE 1.3B是最适合作为Runtime v3远端ActionSpec的模型，因为它的输入天然对应：

```text
src_video + src_mask + reference_images + prompt
```

但本项目当前目标是端侧离线，远端/主机执行只能作为可选backend和质量oracle，不能成为默认。

## 6. 推荐的Runtime v3方法展开

### 6.1 REMOVE_CONTENT首版

```text
CompileTargetPredicate
→ SampleSceneKeyframes
→ SAM3TextSegment(keyframes)                 # fan-out候选mask
→ AssociateAndFilterCandidates               # 属性/位置/深度/排除
→ EdgeTAMTrackMask                           # OBJECT_TRACK + MASK_TRACK
→ ScopeCoverageValidate                      # 唯一性/漏帧/连续性
→ NeedUser(language clarification)           # 仍不唯一时暂停
→ DilateAndFeatherMask
→ DecodeVideoChunks
→ LaMaInpaintFrames/Keyframes
→ TemporalPropagateAndBlend
→ TemporalConsistencyValidate
→ RepackLivePhoto
→ Preview
→ Publish
```

必须保留的约束：`preserve audio / duration / main subject / frame rate`。大mask、快速镜头、严重遮挡
或temporal validator失败时，不得静默发布；可提示用户缩小范围，或转到VACE/ProPainter质量backend。

### 6.2 ADD_CONTENT首版

```text
CompileAnchorPredicate
├→ SAM3TextSegment + EdgeTAMTrackAnchor
├→ GenerateOrRetrieveImagePatch (SD1.5/ControlNet或素材库)
└→ DepthAnythingEstimate
        ↓
TrackedComposite
→ Occlusion/Scale/LightingValidate
→ Repack/Preview/Publish
```

该方法把“增加内容”定义为可追踪图层，而不是重新生成整段视频。它更容易撤销、审计和保持原素材。
需要真实动态生成时再选择FOMM专用方法或未来Neodragon/MobileWan backend。

### 6.3 CHANGE_CONTENT首版

优先级：

1. 可由现有SDK完成的全局颜色/滤镜/速度变化；
2. EdgeTAM mask限定的局部确定性变化（首版颜色、模糊；透明度只用于已有独立图层）；
3. SD/ControlNet关键帧实验，并要求时序验证；
4. MoViE公开权重后再评估完整指令式V2V；
5. VACE作为主机质量oracle。

## 7. 建议实施顺序

### P0：只读Grounding probe

1. AI Hub真实S26上的OWLv2语言box与EdgeTAM float QAIRT 2.45单帧mask均已通过；
2. 宽泛/充分两组语言已分别通过`NeedUser`与唯一候选分支，不接受点/框输入；
3. memory encoder与vivo固定计数attention图银行已通过；合成/自然遮挡、快速镜头、出画重入和
   第16个真实pointer均有只读证据；下一步补逐帧长序列、语言locator与完整pipeline soak；
4. QAIRT 2.50/2.51保持禁用并保留最小复现；SAM3只作为后续可选直接文本mask对照；
5. 计算候选唯一性、时间覆盖、连续性和排除重叠，只输出mask overlay，不修改媒体；
6. 模糊时只生成语言澄清问题；
7. 所有候选只允许生成并验证QAIRT 2.45制品；2.50/2.51在回归修复前不得进入本机。

### P0.5：局部确定性修改预览（当前首选）

1. 语言target经`OBJECT_TRACK → MASK_TRACK → SCOPE_CERTIFICATE`后，只生成临时preview；
2. 首版effect限定为局部调色与局部模糊，参数有界且由Qwen在Goal IR中选择；
3. 候选coverage policy可预览但不可写回；preview contract强制`writeback=false`；
4. 纯Kotlin CPU oracle已固定调色、软mask与mask内模糊语义，mask外像素逐位不变；
5. vivo单帧GLES调色/box blur已在真实mask与非对称软mask上通过CPU差分（最大RGB差≤1、mask外0）；
6. 仍需track/certificate bridge、用户确认/取消/undo、逐帧preservation与生命周期Gate，全部通过后才
   单独评审mutation权限。

### P1：单帧LaMa probe

1. 用真实Live Photo关键帧和人工mask验证LaMa；
2. 比较AI Hub CelebA-HQ checkpoint与通用Big-LaMa自定义导出；
3. 不接Runtime发布，只保存临时结果。

### P2：短视频移除MVP

1. 2秒、≤49帧、ROI或512内部处理；
2. 通过远程与vivo抽样语义Gate的跟踪mask + 每帧LaMa（完整逐帧覆盖、语言locator与Android bridge仍待验）；
3. 增加flicker/temporal warping/边界残留检查；
4. 与主机ProPainter或VACE结果做盲评，但非商用模型不进入产品包。

### P3：静态内容增加

SD/素材库patch + EdgeTAM + Depth Anything + deterministic composite，先支持可撤销图层，不做全视频重绘。

### P4：完整移动生成/编辑

只有在Neodragon、MobileWan或MoViE出现可复现Android/QNN导出与兼容runtime后再开启。不能把论文
中的移动演示等同于当前公开仓库已可部署。

## 8. 评测与完成门禁

必须分开报告：

- Grounding：J&F、mask IoU、ID switch、遮挡恢复；
- Fill：边界残留、LPIPS/SSIM（有真值时）、用户盲评；
- Temporal：warping error、flicker、纹理漂移；
- Preservation：主体、音频、duration、fps、Live Photo timestamp；
- Device：端到端延迟、峰值RAM、NPU/GPU占用、温升、取消与恢复；
- Safety：错误目标不得执行；生成内容标识、预览确认、不可逆发布门禁；
- License：代码、checkpoint、基础模型和训练数据条款分别审查。

任何单帧看起来正确但视频闪烁的结果都判失败；任何语言mask目标不唯一、覆盖不足或侵入保护对象，
但没有阻止修改并触发语言追问的结果也判失败。

## 9. 官方来源

```text
EdgeTAM:       https://github.com/facebookresearch/EdgeTAM
AI Hub EdgeTAM:https://huggingface.co/qualcomm/EdgeTAM
SAM3:          https://github.com/facebookresearch/sam3
AI Hub SAM3:   https://huggingface.co/qualcomm/Segment-Anything-Model-3
LaMa:          https://github.com/advimman/lama
AI Hub LaMa:   https://huggingface.co/qualcomm/LaMa-Dilated
AOT-GAN:       https://github.com/researchmm/AOT-GAN-for-Inpainting
Depth Anything:https://github.com/DepthAnything/Depth-Anything-V2
Stable Diff.:  https://huggingface.co/qualcomm/Stable-Diffusion-v1.5
ControlNet:    https://huggingface.co/qualcomm/ControlNet-Canny
FOMM:          https://github.com/AliaksandrSiarohin/first-order-model
MoViE:         https://qualcomm-ai-research.github.io/mobile-video-editing/
MobileVD:      https://qualcomm-ai-research.github.io/mobile-video-diffusion/
Neodragon:     https://github.com/qualcomm-ai-research/neodragon
MobileWan:     https://github.com/qualcomm-ai-research/mobilewan
VACE:          https://github.com/ali-vilab/VACE
LTX-Video:     https://github.com/Lightricks/LTX-Video
ProPainter:    https://github.com/sczhou/ProPainter
E2FGVI:        https://github.com/MCG-NKU/E2FGVI
DiffuEraser:   https://github.com/lixiaowen-xw/DiffuEraser
VideoPainter:  https://github.com/TencentARC/VideoPainter
VOID:          https://github.com/netflix/void-model
MiniMax:       https://github.com/zibojia/MiniMax-Remover
```
