# 局部确定性预览 Renderer 验证

> 验证日期：2026-11-04
> 设备：vivo V2547A / PD2547，SM8850，Android 16（SDK 36）  
> 状态：**真实语言→OWLv2→EdgeTAM全时序mask→私有Live Photo预览已通过显式实验路径；写回仍禁用**

## 1. 边界

首个局部修改MVP只实现`color_tint`和`blur`。输入是host已验证的原帧与灰度mask，输出是内存中的
临时`Bitmap`：

```text
source Bitmap + mask Bitmap + LocalDeterministicChange
    → AndroidLocalMaskPreviewRenderer
        ├→ GLES 2.0 mask color tint
        ├→ GLES 2.0 separable integer-domain box blur + mask blend
        └→ CPU oracle fallback（仅GL路径不可用时）
    → LocalMaskPreviewFrame(bitmap, backendId)
```

renderer本身不接收路径或MediaStore URI，也不写文件、替换素材、提交或发布。隔离的
`AndroidLocalPreviewShadowExecutor`只在显式shadow probe中实例化，未加入生产tool/executor registry。
`SdkProbeService`的H/I探针只把结果保存到应用私有诊断目录以便拉取验证。Runtime v2 loop和生产APK
均未改变。

## 2. 等价语义

`LocalMaskChangeReferenceRenderer`仍是CPU oracle：

- mask值为0时，ARGB必须逐位保持原值；
- `color_tint`按`mask / 255 × intensity`混合，保留source alpha；
- `blur`先做水平box blur并整数截断，再做垂直box blur并整数截断，最后按软mask混合；
- blur半径使用Kotlin `roundToInt()`后限制到1–64。

GLES blur在每次采样时把RGBA8 UNORM恢复为整数，显式复现水平截断、垂直截断和最终四舍五入，避免
普通浮点Gaussian或一次性二维平均悄悄改变契约。边界窗口只统计有效像素，不使用重复clamp像素。

vendored SDK没有被强行套用：

- `GPUMattingFilter`是固定overlay blend且mask语义反向，不等价于调色或局部RGB模糊；
- `GPUGaussianBlurFilter`随包shader只采样红通道，不等价于RGB box blur。

因此选择自有GLES shader；这仍是vivo真机GPU路径，但不是伪称为等价的vendor effect。

## 3. 真机差分

验收阈值：必须命中预期GLES backend，mask外改变0像素，alpha最大差0，RGB相对CPU oracle最大通道
误差不超过1。97×73 fixture使用非对称source、软mask、左边界与下边界覆盖，可检测上下翻转、mask
错位、边界计数和半径取整。

| Case | 尺寸 | Backend | 最大RGB差 | 平均RGB绝对差 | mask外改变 | Renderer时间 |
|---|---:|---|---:|---:|---:|---:|
| 真实猫mask调色 | 1080×1440 | `gles_mask_color_tint` | 1 | 0.002479 | 0 | 52.51ms |
| 真实猫mask blur r=6 | 1080×1440 | `gles_mask_box_blur` | 0 | 0 | 0 | 43.86ms |
| 非对称软mask调色 | 97×73 | `gles_mask_color_tint` | 1 | 0.015676 | 0 | 23.88ms |
| 非对称软mask blur 0.5→1 | 97×73 | `gles_mask_box_blur` | 0 | 0 | 0 | 8.34ms |
| 非对称软mask blur 6.4→6 | 97×73 | `gles_mask_box_blur` | 0 | 0 | 0 | 9.38ms |
| 非对称软mask blur 63.6→64 | 97×73 | `gles_mask_box_blur` | 1 | 0.001648 | 0 | 8.00ms |

六组alpha最大差均为0。真实1080×1440 blur产物与CPU oracle PNG逐字节相同。表中时间包含单次
EGL context、shader编译、纹理上传与`glReadPixels`，不是复用context后的稳态视频性能结论。

独立host脚本重新读取PNG并复算全部差分，与设备JSON逐项一致。证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/local-deterministic-preview-vivo-gles/summary.json
SHA-256 73da947f37d8cdb3b59b5e774de342d955e20eb107f94fc25fe22d0ce762da71

/mnt/ssd/lpa-aihub-probe/artifacts/local-deterministic-preview-vivo-gles/h_local_preview_evidence.json
SHA-256 078c8d595e964350c9815d1bc6f37b799a7b50ef589880bb354a5bd882f93c23

test APK
SHA-256 22d58087ab6cfaf30ad9287d0488eacb517ed8e3051a0221af958a43c51010ca
```

## 4. Shadow bridge与交互生命周期

`AgentShadowObservationBridge`现已把host观测按三阶段物化：

```text
language target + deterministic candidate count
  → unique OBJECT_TRACK（0个继续观察，>1个NeedUser，不选择box/point）
  → internal grayscale frames → MASK_TRACK + typed observations
  → fresh AgentScopeCertificate → preview-only DAG
  → AndroidLocalPreviewShadowExecutor → process-local MEDIA_ARTIFACT
```

OWLv2 host NMS只把candidate count和候选集合SHA-256交给bridge，box不进入Goal IR；该SHA继续进入
object grounding fingerprint与certificate。mask字节只保存在`ShadowMaskFrame`内部，Content Graph与
DAG不含mask、box、point、媒体路径或URI。
bridge拒绝`approvedForMutation=true`的policy；executor再次核对source state fingerprint、graph版本、
mask node、certificate ID及完整evidence fingerprint，并强制`preview_only=true / writeback=false`。

临时artifact只有四个review状态：`PENDING_REVIEW → ACCEPTED_FOR_REVIEW → UNDONE`，或
`PENDING_REVIEW → CANCELLED`；不存在`COMMITTED`状态，所有状态的`writebackAuthorized=false`。
`AgentActivity`已加入原图/预览切换、确认、取消和撤销panel；切换会话或Activity销毁时自动取消/撤销
并释放preview bitmap，不持久化临时artifact。

vivo H/I真机probe完成一条1080×1440链：语言target → graph v2 → fresh certificate → 单节点DAG
`FINISHED` → GLES preview → 确认 → undo → 临时store清空。源帧像素SHA在执行前后相同，host复算
mask外变化0、mask内变化57,643像素，序列化Content Graph/DAG不含路径、box或point。

```text
/mnt/ssd/lpa-aihub-probe/artifacts/local-preview-shadow-bridge-vivo/summary.json
SHA-256 daf8238cb553fcff6473f9c47bff5a3da0f7a17c87dd9e2130c38d63d6310688

/mnt/ssd/lpa-aihub-probe/artifacts/local-preview-shadow-bridge-vivo/i_shadow_bridge_evidence.json
SHA-256 9bac1bd20e8f3994e35b42076ee15b2a1167f5f0eda0a409ab2f67c06a8e930e

test APK
SHA-256 89e93a44495e7f02bcbc9b75f057be755322398b9efde7a6c98dbec83299e99a
```

debug/release单测均为87/87，`lintDebug`、debug/release assemble通过。headless probe验证了与UI相同的
interaction/store状态机，但尚未做触屏自动化或Activity重建测试。

### 4.1 真实Agent语言到关键帧区域工具

2026-11-03新增默认关闭的`localTrackedPreview`实验入口；它强制要求
`-PagentRuntime=dag -PlocalTrackedPreview=true`，默认生产仍为`agentRuntime=loop`。Qwen只生成严格
`CHANGE_CONTENT` Goal IR和ASCII canonical target，box、points、mask、context及DAG连线仍由host持有。
任何Goal格式、唯一性或观察门禁失败都会停止，不回退为全局`apply_style`。

初始QAIRT执行层使用随实验APK提取的官方2.45 `qnn-net-run`，由App UID启动可取消子进程；context和
DSP skel来自外部模型包并逐个校验SHA-256。它关闭了“应用UID能否拥有并取消2.45 context执行”的
Gate，并继续作为回退实现；后续持久JNI结果见4.4。EdgeTAM encoder固定fixture的五个输出与此前
vivo张量逐字节一致，App-UID执行总时长703ms：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-app-qnn245-context/
  edgetam-encoder-evidence.json
SHA-256 d8c8dc3fe96b86d1a4e0118ef7131d6202536f3b7690da440c5f5719a19adc78
```

真实Live Photo cover随后完成：

```text
中文请求
  → Qwen CHANGE_CONTENT（orange cat walking on green grass）
  → OWLv2 vision/text QAIRT 2.45（唯一候选）
  → EdgeTAM encoder/decoder QAIRT 2.45（关键帧mask）
  → OBJECT_TRACK + 单关键帧preview certificate
  → local_mask_change_preview_shadow
  → gles_mask_color_tint
  → WAITING_USER
```

Android EdgeTAM预处理复现Pillow UINT8双线性两阶段卷积；对同一1080×1440 cover的Python
`Image.resize(..., BILINEAR)`输入达到58.51dB、最大绝对误差1/255，高于50dB门槛。真实mask通过覆盖率
与扩框外泄漏门禁；区域工具改变mask内像素且mask外严格0变化。该证书明确标记`KEY_FRAME_ONLY`，
`temporal_mask_track_complete=false`，不能用于Live Photo写回或发布。

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-app-qnn245-context/
  agent-language-to-mask-gate.json
SHA-256 3436d65d906745620c7deee4ba1be84632076d072bee88dce214c6a4ce7c9723

  language-mask/android-input-differential.json
SHA-256 218887a9bb164fdfeada8f7154b1b8b75c1a1d6db05ad83f2e2f273ebc513399

  agent-real-keyframe-preview/evidence.json
SHA-256 a89b6adfdfc0409ba058b8a0db916bbdd4afd282fcdea14ea81d8702fb37d038

  agent-real-keyframe-preview/preview.png
SHA-256 438a3d13bf0de46c9730fee89199ac0a104eefdd1cdade9fcbbd917120fc3dd4
```

宽泛`a cat`在同一cover返回多个候选并进入`NeedUser`；host未按score、面积或位置擅自挑选。目标颜色、
动作和场景限定由Qwen保留，目的颜色不得污染编辑前主体词；场景中的同色背景可保留，例如
`orange cat ... on green grass`不会被误判为`green cat`。

实验入口还必须先输出`plan_live_edit`或`skip_local_preview`二选一。真实Qwen对能力问答和拼图请求均
输出严格`skip_local_preview`，随后仍由原Runtime v2 loop处理，局部实验分支不劫持无关请求：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-app-qnn245-context/eligibility/
  local-goal.json     SHA-256 a7abdf6d8950aa0a64afb853b96c126e785c5385a8aecc9ae51adf93f2535c8e
  chat-skip.json      SHA-256 35f83cb5cc3e1fe212b2d47cd65ba43a9ed553bab9a1c42761b03d5baf8c9a67
  collage-skip.json   SHA-256 aac516243fd5652c5f33b99bf013a88bc50fff1caf651b5ca563d5c9504f6c3a
  chat-loop-fallthrough.json
                      SHA-256 4691654241a483d9c249f5300870daa124a96effd4cd0d0d0079147f9be6cec0
```

当前debug/release单测均为161/161，`lintDebug`、`lintRelease`及debug/release assemble通过。默认
debug/release APK都不包含`libqnn_net_run.so`；默认debug也不含演示Provider。只有显式实验Debug
构建加入只读Provider与实验native：

```text
default debug APK          724e5c78d4b0bebe25c2820c26e7be39e895c80c3a582bd5e86ebd0034e084bd
default release APK        58de012f339337e2255d9aa54e69462189d8c9f895b122544aee53aafdff4b60
full-timeline fallback     86858f6cc5451f289d6263c06783bd500075ff8432ee904393f4080010d03408
persistent-QNN debug APK   902f151532522ce1098ef45bc48679176e44d3994ddc000b76fb60f246b47a2b
generation-fixture APK     3c231fdb209bf690d1486e04c79c527d67e2ab2f64776e589f735070bf01e555
```

所有自定义Gradle属性已注册为task inputs；同一worktree按“clean默认→实验→默认”构建，前后两个默认
APK逐字节相同，避免实验BuildConfig或native source set被增量缓存带入生产包。

实验模型包固定在应用私有`files/models/local-tracked-preview/`，不进入APK；必需布局与SHA如下：

```text
contexts/owlv2_vision_qnn245.bin    6bd5393673e57190fa95d9af770fe470ef7b0b038f2edf42da4c6dae0e4bb006
contexts/owlv2_text_qnn245.bin      831c23acd211f29189860e0a9dbb5fb5544aca068835abd5d002ffec3a00a28a
contexts/edgetam_encoder_qnn245.bin 0edaed8a468ab003dff72fd57574b3ffc95bf2ac9fbb89fba94070b12811ddc0
contexts/edgetam_decoder_qnn245.bin 0fbc23c5fd70760bf9aeb369f88936b112e6a54cbc03155ff7a3ee4067fd08dc
contexts/edgetam_memory_encoder_qnn245.bin
                                      bf13e61cbb873da162fbd9d28008b6587bc5a7fbcb6998f12ab6297feed9b3f1
contexts/memory_attention_{1..7}_o3.bin
contexts/memory_attention_7_{8..16}_o3.bin
                                      SHA集合固定在AndroidEdgeTamTemporalQnnRunner
tokenizer/vocab.json                e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349
tokenizer/merges.txt                9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a
runtime/dsp/libQnnHtpV81Skel.so     57330ed3f7846b94181b25b7a87bc3fe5dc71033766f2d5630520a1400477c97
supplementary/no_obj_ptr.npy         1eaf62836fa7a7afb592bea5b2c043fee463cfd9a488c59440bded15982679fd
```

任一文件缺失或SHA不符都返回`NeedObservation`/失败，不会切换QAIRT版本或CPU伪结果。

### 4.2 动态帧时间线与时序QNN

帧数不由时长或名义fps推算。`AndroidVideoFrameTimelineDecoder`使用`MediaCodec`解码到EOS，保存每个
输出帧的真实`presentationTimeUs`；封面帧通过`photoTimestampUs`在该时间线上取最近项。相同代码在
两份私有Live Photo上分别得到：

| 素材 | 实际解码帧 | PTS范围 | 封面映射 |
|---|---:|---:|---:|
| 原始fixture | 181 | 0–6,000,000µs | 1,500,000µs → frame 45 |
| 24fps / 2.2s变长验证 | 53 | 0–2,166,666µs | 1,000,000µs → frame 24 |

`expectedFrameCount`直接取时间线长度。封面不在边界时，tracker以封面为condition frame分别向前、向后
传播，再按原始frame index/PTS合并；不假设condition frame为0。

Android host状态银行也已按真实处理进度实现：condition memory固定保留，最近6个非condition memory
组成最多7个空间memory；condition pointer加最近15个pointer组成最多16个pointer。17帧fixture的16次
状态转移与此前生成的raw输入逐字节一致，并正确选择`1/1…7/7、7/8…7/16`context。随后App UID真实
执行全部17个QAIRT 2.45时序点，17个decoder raw mask均与此前vivo结果逐字节一致。

动态Android视频解码相对OpenCV fixture输入为43.17dB，但下游首帧mask IoU为0.9962；连续3个实际
解码帧全部通过coverage，和PyTorch oracle最低IoU为0.9523。中间frame 2作为condition的5帧双向测试
覆盖5/5帧；因Android/OpenCV颜色转换与conditioning变化，same-anchor oracle最低IoU为0.9212，超过
当前0.90候选门槛，但仍需更多尺度、遮挡和VFR素材后才能开放正常入口。

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/
  timeline-181-frame.json
    SHA-256 aee1ca7e9aa3d1fc50fe71589af796502c4bc03b6598611c96a658ac754d2894
  timeline-53-frame.json
    SHA-256 38e84b9f04e5c7b1cbd7a58331effdfd5d4479501e4a24c46c0bf83e5c913729
  edgetam-temporal-state-evidence.json
    SHA-256 5a806f440db7ac48340fef5250e30e9c6206b29d864ac2f024d7b263d166fde6
  qnn-edgetam-temporal-17-evidence.json
    SHA-256 bd1208064b418dd56bade2e3be14b4d4e1cc5183a4f1e29e78e476c9a28074b3
  qnn-dynamic-frame-mask-evidence.json
    SHA-256 af184808a6b41569aaa1e368feae2ffcd57f5b0995a5aae0b4a2d7eeef277e13
  qnn-dynamic-temporal-prefix-evidence.json
    SHA-256 449b93a6b2c0ce699a9b73d6d9a131ce0d90af85d9176d23aedf24e9f0f72d1a
  qnn-dynamic-bidirectional-5-evidence.json
    SHA-256 8b6d30d65a3210109a4a31f1d3025eeb8773ca3f720f3f97bd441b18b1e5ea3a
```

全帧mask在处理中写入应用私有临时文件，物化到shadow bridge时使用1-bit packed binary表示，避免
1080×1440×N字节常驻内存。完整动态执行仍由独立开关保护：

```bash
./gradlew assembleDebug \
  -PagentRuntime=dag \
  -PlocalTrackedPreview=true \
  -PfullTimelineTrackedPreview=true \
  -PpersistentQnn=true
```

随后对53帧Live Photo执行真实Agent全链：Qwen Goal IR → OWLv2 → 以frame 24为condition双向传播 →
53/53 `MASK_TRACK` → 内容绑定certificate → DAG区域工具 → GLES preview → `WAITING_USER`。总墙钟约
217秒（含Qwen、locator、逐context子进程加载和preview）；中途App host进程`TOTAL PSS=80,425KiB /`
`RSS=163,988KiB`，系统thermal status为0，但该数字不含DSP总分配，不能当作完整内存Gate。

certificate现在把每帧mask内容SHA继续绑定到mask-track node和certificate ID；同面积但形状不同的mask
不能复用证书。最终代码又用5帧、condition frame 2重跑，5/5帧进入`ready`并生成预览，证明内容绑定
没有破坏动态闭环；preview也从旧的“首个可见帧”修正为condition frame 2（66ms）。两轮都没有媒体
写回或发布：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/
  full-53-agent/evidence.json
    SHA-256 21aab8f27f535d410777f8eb24b4e645e419874f460524d11aa18056565e096b
  full-53-agent/preview.png
    SHA-256 b1938b99e67a2d622843a66e796457fbda19f6f30644bdcbc8a3af9332a6269e
  full-53-agent/midrun-memory-thermal.txt
    SHA-256 bce299603ac233684fd1e1002b4561a8b56025c16e642a08457d6976d350b8d2
  full-5-agent-final/evidence.json
    SHA-256 a739a22704bb39638ecef0ad36f2f9e8d7b2d2d12e4c4d6a40aafdbcf725f908
```

### 4.3 持久GLES、MediaCodec与临时Live Photo

调色时间线不再为每帧创建EGL环境。`AndroidPersistentTintRenderer`在整条时间线上复用一个context、一个
program和固定texture/FBO；5个动态帧对CPU oracle均为mask外0变化、最大通道误差0，总渲染2.278秒：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/persistent-gles/
  evidence.json
    SHA-256 710ae0da70f8824a8024b87fe090aa6dd9d2a9566fcc7f20e1e363129ad3718b
```

同一shader随后直接绘制到`MediaCodec` AVC encoder surface；encoder surface使用独立纵向纹理坐标，
避免pbuffer readback与window surface原点差异导致的垂直翻转。5/5输出PTS与输入逐项相同，anchor帧
编码后PSNR 37.24dB，垂直翻转对照只有11.48dB。带AAC样本的9个audio sample payload与PTS/flags/
size时间线均逐项保持；首个负PTS priming sample没有被夹到0。LivePhotoSDK repack/decode后timestamp仍为
66,000µs：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/persistent-encode-audio/
  evidence.json
    SHA-256 9c48b48c7e142681320dfc6776029e1ebd447f65a50a9966b2da7f3c4b340c2a
  tinted-livephoto.jpg
    SHA-256 db4dbf7afb5d46e06a64d0c06555979141fdbee181b430b4b6da72212ee5222f
```

编码器已挂入完整Agent临时artifact：真实语言请求完成5帧双向QNN后自动生成私有动态Live Photo，再进入
`WAITING_USER`。取消/撤销会删除artifact store拥有的完整中间目录；确认仍只接受预览，不产生发布授权：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/full-5-agent-encoded/
  evidence.json
    SHA-256 e56942966da86643909bd5e9b6817910a373399c6cc6ed074c65c256dbd90d60
  preview-livephoto.jpg
    SHA-256 eb39cdb7cd704f901008a91416ac552f9e4f419005a05f7a309a0d27ed2bb1ba
```

最终又在53帧/24fps/带音轨素材上完成同一Agent整链：53/53 mask observation、condition frame 24
（1,000ms）预览、持久GLES逐帧调色、PTS精确保留、AAC逐sample保持、Live Photo repack和私有artifact
清理全部通过；源文件未修改，未发布：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/full-53-agent-encoded/
  evidence.json
    SHA-256 9dd0eaa023760ba22c311cc47ea11b1e3fd1fd83892017a6720b4d174335ce4d
  preview-livephoto.jpg
    SHA-256 d208e207de3519ad0656b33a8a60d0859edf6b116bdbcdb36e6273588b3a3cbc
```

现有长序列基准也已用同一动态实现完成：MediaCodec解码至EOS得到`N=181`，condition frame 45
（1,500ms），181/181 mask observation通过，持久GLES、AVC编码、281个AAC sample、Live Photo repack
和私有artifact全部成功。视频PTS/DTS/sample duration与源轨逐项一致；AAC payload SHA仍为
`efba4de...5782`。总墙钟约684.4秒；抽样App PSS为72,868→85,668→97,445→100,403KiB，thermal
status始终为0。该内存不含DSP总分配，且11分钟墙钟明确说明子进程context加载只关闭功能Gate，不能
作为生产性能结果：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/full-181-agent-encoded/
  summary.json
    SHA-256 46f87266c7f5737d1abfc75044bbdea93a24766bc130e4efdc7d8a7e5f38da8a
  evidence.json
    SHA-256 4ae1001c9dd4ea0ebbf75381702a2a76e6b86b39e61dbfa835cc9a49472def5d
  preview-livephoto.jpg
    SHA-256 064e5a59059d9a2218af1d03ce929e7a1805bb21e381ee9f8c56a7003f63cc10
```

QNN encoder子进程启动后100ms触发取消的真机probe也已通过：Agent终态为`CANCELLED`，未产生preview，
partial track目录和`qnn-context-runs`均为0，源媒体未改变：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/cancel-probe/evidence.json
SHA-256 e6f45e11d8b32877ad42808927d84567f331ad3f8e0c5ee3f33f3e58b885a6a7
```

### 4.4 QAIRT 2.45持久JNI context

`-PpersistentQnn=true`现在增加第三层显式开关；未设置时仍使用上述子进程回退，默认`loop` APK既不
编译也不打包JNI runner。JNI只接受已由Kotlin完成路径、大小和SHA校验的context；再用QAIRT 2.45
System API反射图名、tensor名称、类型和维度，逐项核对contract，禁止仅按文件字节数猜ABI。运行时
同时校验QNN core API `2.34.0`、System API `1.9.0`及backend build ID
`2.45.0.260326154327`，2.50/2.51仍不可加载。

context按“canonical path + content SHA”进入最多4项的确定性LRU，适配每帧
encoder→current-attention→decoder→memory的访问序列；结束/取消时释放全部context及HTP burst power
vote。V81 transport通过manifest声明设备公开的`libcdsprpc.so`，不复制vendor库。QNN abort signal已在
真实graph dispatch中触发：context被逐出、partial输出清理，随后重新加载同一context仍产生五个逐字节
一致的encoder输出。双线性mask resize还预计算横纵采样轴；单测证明与原逐像素floor实现float-bit/
binary-bit一致。

17帧固定图银行再次通过17/17 decoder raw mask逐字节复现；完成时LRU恰为4项，release后为0且burst
vote关闭。完整5帧与53帧Live Photo也通过certificate、持久GLES、MediaCodec、AAC sample copy和
repack。53帧总墙钟从子进程基线220.481秒降至73.464秒（3.001×），跟踪阶段从190.393秒降至
45.821秒（4.155×）；`7/16`稳定attention的20次热执行为35–47ms、中位41ms。53帧输出保留53个
实际PTS和104个AAC packet完整时间线/payload；封面preview SHA与子进程结果相同。结束后App
PSS/RSS为52,872/151,320KiB，thermal status为0；该数字仍不含DSP总分配。

现有长序列也由同一代码再次动态解码至EOS得到`N=181`，condition frame 45；181/181 mask、完整
编码、181帧PTS/DTS/duration、281个AAC packet及payload、Live Photo repack全部保持。总墙钟由
684.405秒降至185.957秒（3.680×），跟踪由647.859秒降至155.914秒（4.155×）；150次`7/16`
执行中148次热执行为38–58ms、中位47ms。结束后App PSS/RSS为58,967/153,752KiB，thermal status
仍为0，源媒体未修改且未发布。

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/
  persistent-jni-context/encoder-evidence.json
    SHA-256 7f9dec26f146afbc6bad4648f3b82d9e1488dc9a4fd915a224ad71d35408ddfe
  persistent-jni-17-temporal/evidence.json
    SHA-256 22176a45b32b7ff7a91666a6986f94594c24a88ff04b6dbb612748a04eca56ef
  persistent-jni-5-agent-encoded/summary.json
    SHA-256 632352453fbda5ea70c72543c65ee1b3e6de9bb4e701ca64d553bef3a6263488
  persistent-jni-53-agent-encoded/summary.json
    SHA-256 1285a6bace4391c91b22d11352a08466e022afff34853ddd55d2ad2440e07b50
  persistent-jni-181-agent-encoded/summary.json
    SHA-256 c6d2a875d438cb5726ddf78b068f8f05f6b507dfc544d4a59797cba9c80019bf
  persistent-jni-cancel/evidence.json
    SHA-256 2285c2244077bb3d4f804e7ea6d3a29d1bee5602550fa2ea14bf1ee52c1ee2b2
```

### 4.5 手机总内存与FastRPC/DMA Gate

在15,523,340KiB物理内存的V2547A上，以root诊断接口同步采样`smaps_rollup`、同UID全部进程、
DMA-BUF、FastRPC map/unmap、系统`MemAvailable`、swap、PSI、LMKD和thermal。完整`N=53`整链仍成功
进入`WAITING_USER`，但**非干扰式内存Gate失败**：Qwen规划子进程单次FastRPC映射峰值约
5,562.5MiB，LMKD记录该子进程ION最高6,880,403,456B、系统ION最高7,691,677,696B；系统可用内存
从9,484,244KiB最低降至3,671,656KiB。LMKD以`critical pressure`杀掉6个后台进程，共释放
699,720KiB RSS和220,948KiB swap。LPA自身没有被杀、kernel OOM计数仍为0，但“能跑完”不能视为
生产内存通过。

主App在完整整链中的采样峰值为PSS 377,731KiB、RSS 467,508KiB、`VmHWM` 509,592KiB、持有
DMA-BUF 1,047,896KiB；结束后回落到PSS 58,702KiB、RSS 159,196KiB、DMA-BUF 12,824KiB。
FastRPC全局映射峰值约6,640.1MiB，结束后约12.5MiB。thermal status始终为0，skin最高31.934°C。

为把planner问题与tracking分开，又在同一App进程连续运行5轮17帧固定图银行。五轮mask均逐字节
一致，单轮峰值稳定在PSS 146–158MiB、RSS 235–247MiB、DMA-BUF 265,588KiB；每轮release后
DMA-BUF都回到12,824KiB，最终PSS/RSS为43,914/131,772KiB。期间`MemAvailable`最低
9,379,980KiB、PSI avg10始终0、无LMKD、context计数为0、burst vote关闭，说明持久QNN跟踪本身
没有随轮次增长的泄漏。当前阻塞项明确收敛到Qwen planner模型/context峰值，而不是动态`N`或LRU。

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/
  memory-gate-53-run1/summary.json
    SHA-256 d356bc5b516c080ef103db0ebd0ad31474300c6dd4bb1a5a8358341d29a2eed4
  memory-gate-53-run1/lmkd-log.txt
    SHA-256 6083688717075c4c0fd26b001a8b38290dfc6f83534627e22e1d38b9aa708564
  memory-gate-tracking-soak/summary.json
    SHA-256 5d78fd6d48049cb4c53332913cc9bf9d6a3f2893cd87272d323d365d6b6af83a
```

### 4.6 完整动态预览的确认、撤销与可选发布

5帧真实Agent整链又执行了完整review生命周期：`PENDING_REVIEW → ACCEPTED_FOR_REVIEW → UNDONE`。
确认只接受视觉结果，`writeback_authorized`始终为false；确认后同一packed Live Photo通过私有根目录、
完整时间线、certificate fingerprint和JPEG contract检查，进入`publish_ready`，但发布仍要求第二次独立
用户授权；同一发布器在`PENDING_REVIEW`状态被真机probe明确拒绝。撤销后artifact拥有的完整动态目录
已删除，源Live Photo SHA前后一致，相册输出目录行数
保持37→37，本轮没有发布。

UI现只在完整Live Photo已确认后显示“发布副本”；用户点击后还必须通过“不会覆盖原片”的确认对话框。
artifact在创建时即记录packed输出SHA；发布器先验证该内容绑定，再重新decode、核对帧数、写入新的
pending MediaStore行、设置vivo `live_photo`标记，并以SHA-256
重读验证；任一步失败都删除pending行。成功后可单独“删除已发布副本”，删除策略只接受本发布器在
固定输出目录创建的URI。该入口不会把“确认预览”静默升级为发布授权。本轮只验证到`publish_ready`
并确认相册零变化，未替用户执行第二次发布授权。

进程死亡清理也使用真实完整artifact验证：先故意保留一个`PENDING_REVIEW`的5帧Live Photo目录
（1,073,325B、3个文件），确认源SHA和相册行数未改变；随后`force-stop`模拟进程被杀。新主进程的
Application janitor启动时扫描1个孤儿根并删除1个，失败0、剩余0。janitor只在主进程启动执行且只
处理`cache/full-timeline-previews/`的直接子目录，不会由`:reverse_worker`误删活跃artifact。

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/
  persistent-jni-5-review-loop/evidence.json
    SHA-256 fa16518e0d441a820b60f43852c82042637bfce5d3e6c24cd13455554ed9964f
  process-restart-preview-cleanup/pending-before-process-death.json
    SHA-256 53711601d1eb33854704d7f8949df496d1f195eb5bf74d99be45715ce1368814
  process-restart-preview-cleanup/cleanup-after-restart.json
    SHA-256 5fdf577c3a361d8a33a7db85439df11dd5ce47e5fddf013d8c930c6ec59008ec
```

完整时间线`blur`现与单帧renderer共享同一组整数域两阶段box-blur shader。5帧pbuffer差分中，单EGL
context/双program处理全部帧，mask外变化0，mask内变化277,302像素，对CPU oracle最大通道误差0。
同一renderer随后直接输出到MediaCodec surface：5/5 PTS保持，9个AAC sample payload与时间线完全
一致，anchor帧对无损GLES结果PSNR为39.30dB，垂直翻转对照仅11.39dB；repack后photo timestamp仍为
66,000µs。最终自然语言“请把草地上正在走的橘猫做局部模糊，模糊半径6像素”完成5/5双向mask、
完整动态blur、私有Live Photo、确认→publish-ready→撤销与清理，源媒体及相册均未改变：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/
  persistent-blur-5/timeline-evidence.json
    SHA-256 7a6be4888fa946f353ebebc81d7b5ac3d0a35f320d77bc4dd0a1e45adea4d580
  persistent-blur-5/encode-evidence.json
    SHA-256 1212ee01374d3e6bbf7ec259a79e99f729b453035a402922756e236a977c546d
  persistent-blur-5-agent/evidence.json
    SHA-256 e8e8732be2f074fd306b8f8e48f47497c82e1a96a253f8bb3485ab4d7cac61b5
```

随后把相同自然语言blur闭环扩展到`N=53`、真正VFR的`N=7`以及长时间线`N=181`。VFR输入的实际
PTS为`0/40000/160000/240000/440000/520000/680000µs`，输出逐项相同，证明帧数与时间线都来自
MediaCodec EOS而不是duration×fps。`N=181`完成181/181双向mask、blur编码与Live Photo repack；
181个video packet PTS完全保持，281个AAC packet时间线及payload完全一致。三者都到达
`publish_ready`后撤销，源SHA不变且未发布：

```text
  persistent-blur-53-agent/evidence.json
    SHA-256 9b00547dcd0da59e403b47e6cd45ea6b40337ce04eb941b5ad39d23f39407fbc
  persistent-blur-vfr-7/evidence.json
    SHA-256 cc02d79950126554a8106a00fcbe5f5fcead64a51cbfdc4c5b930f2cd3c1a4fa
  persistent-blur-vfr-7/pts.json
    SHA-256 2b9175b0d0cb9f94138542e5f60638d345d85302a90ba039208412924e267f5a
  persistent-blur-181-agent/evidence.json
    SHA-256 a173b6dfc4f53399579d3ca7dc8e36e90f684e0171a61338adce747a9599c379
  persistent-blur-181-agent/packet-validation.json
    SHA-256 0a02d29f5ccf89422270065aa78db80fb8728e06cbf50eccb83a71a44c0e9899
```

真实Activity触屏Gate也已通过。待确认与已确认预览分别跨越一次`portrait→landscape→portrait`，
同一PID内保留process-local interaction；确认后仍显示独立“发布副本”门禁。撤销前先将ImageView与
Store拥有的Bitmap解绑定，修复了自动化发现的recycled-bitmap崩溃；撤销后进程存活、临时根为0。
另一次测试在`waiting_local_preview_review`时强杀进程：重启同一会话后1,606,752B临时根被清为0，
状态变为`local_preview_expired_after_restart`并明确显示“未写回、未发布”；源SHA不变，MediaStore
Live Photo查询为151→151：

```text
  activity-rotation-vfr-blur/evidence.json
    SHA-256 af68edafe0beb71cd62fdd56438d34d5a0bb8b62ed014170edb415b095deff80
  ui-process-restart-pending/evidence.json
    SHA-256 94baffc07c061ea7aff559e7a837effe15592bb66dc997af35351b4575109967
```

可直接展示的35秒UI精剪、8秒动态局部对比、四阶段看板和未发布私有Live Photo位于：

```text
  demo-success-vfr-blur/ui-demo-highlight.mp4
    SHA-256 44e3badf7fff4848f05137c2a8c6f58d367e23637d581054d0217f64ab7688bc
  demo-success-vfr-blur/before-after-blur-zoom.mp4
    SHA-256 12fe8a418ff802e47ee13f8fbd5d538dae6729d465867ab6060a3c8398212bda
  demo-success-vfr-blur/success-board.png
    SHA-256 64426ec92f32af0cb30e3675579906be22c949bb93802590badd9604487b116a
```

### 4.7 真实用户滑板素材的局部红色换色回归

真实UI输入“帮我把live中滑滑板的人调成红色么”最初失败。审计记录表明手机当时是关闭局部跟踪的
默认loop APK，旧Route Planner又把请求错误收敛成了单独`PUBLISH`。切换到实验构建后还发现三个
素材泛化问题，并逐项fail-closed修复：

1. Goal IR曾把结果颜色泄漏进定位词`a person skateboarding on red grass`；host现拒绝非原始目标中的
   destination color并要求Qwen重试，最终得到`a person sliding on a skateboard`与
   `color_tint/#FF0000`；
2. Photo Picker复制出的JPEG由fallback拆轨，vendor SDK未返回封面时间戳；现从XMP
   `GCamera:MotionPhotoPresentationTimestampUs=1500000`恢复，并在复用旧PreparedAsset时补齐；
3. OWLv2返回一个完整人物框和两个低分滑板碎片框；只有最高候选同时满足最低分0.20、分差0.08和
   倍率1.40时才由host收敛为唯一候选，近分多目标仍返回`NeedUser`。人物EdgeTAM提示改为上身35%与
   下身75%两个正点，单帧mask覆盖率4.856%、检测框外泄漏0，覆盖头、躯干、双臂和双腿。

最终同一句输入在同一素材上按MediaCodec EOS得到`N=339`，完成339/339双向跟踪、完整动态红色换色、
私有Live Photo repack、`PENDING_REVIEW→ACCEPTED_FOR_REVIEW→UNDONE`。源SHA前后一致，相册输出目录
37→37，未发布：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/
  user-skateboarder-red-339/evidence.json
    SHA-256 c3c615cd87bb562c46e873ab862b84a4a9c2ab605bec53e9e2bdcf8cf42e6d03
  user-skateboarder-red-339/preview-cover.png
    SHA-256 b086ac306944ad4d23a47794de3f24c3201fa1dbe0354433d658394bd45c55d4
  user-skateboarder-red-339/private-preview-copy.jpg
    SHA-256 b671a28b6f8ec938d25af0a9367e110c3334317f0bb7744ee29089bc02bfc77e
  user-skateboarder-red-339/language-mask-evidence.json
    SHA-256 c5ededb6a98d5f055382d1836a6e449a49e07e023badf31407871f3a0add04e0
```

### 4.8 运行中Activity重建与坐姿女人蓝色换色回归

第二轮真实UI输入“帮我把画面中的女人调成蓝色”在素材预处理后被取消。Runtime checkpoint显示
`OWLv2 locator cancelled before preprocessing`：Activity在worker运行时重建，`onDestroy()`按旧策略取消
worker；新Activity只恢复Photo Picker URI，并错误提示点击不存在的“分析”按钮。修复后worker期间锁定
当前方向，结束后解锁；中断会话会预填原指令并提示重新发送自动前处理。VFR `N=7`真机回归中，worker
运行时请求横屏仍保持rotation 0且未取消，完成后切到rotation 1并保留预览，取消后临时根为0：

```text
  worker-rotation-lock-vfr7/evidence.json
    SHA-256 1df892090782e6151a3a6a7760610c20cfd7ce7d8a0e9c1d87d620b9f53b41b9
```

同一“女人→蓝色”请求还暴露两个独立泛化问题：Qwen输出过“操作间多一个`}`、外层少一个`}`、重复
operation”的组合格式，并把结果颜色写进目标；窄结构修复只移动括号和删除逐字节重复项，语义validator
仍拒绝`女人调成蓝色/a woman in blue clothing`，模型提示最终稳定输出`woman/画面中的女人`与
`#0000FF`。更关键的是，该Motion Photo静态JPEG与1,500,000µs视频条件帧构图不同；旧链在JPEG上
定位后把点直接用于视频，导致背景染蓝。现改为先将photo timestamp映射最近实际PTS，再从同一视频条件
帧运行OWLv2和EdgeTAM。

最终素材按EOS得到`N=270`，完成270/270双向跟踪、完整动态蓝色换色、私有Live Photo、确认门禁与
撤销；窗帘、墙面和沙发不再染色，源SHA不变，相册37→37。固定双点图对坐姿人物覆盖头发、上身和
双臂主区域，但不同材质裤子未合并，仍作为已知分割精度边界：

```text
  user-woman-blue-270/evidence.json
    SHA-256 1dfcfec38298ef0753ec8e97659fd6589a36167ac68681bc89482a303ef96786
  user-woman-blue-270/goal-ir-evidence.json
    SHA-256 1c9deef3fdd1232799963fdbee4ac069d18b6a76dcda680c3ab0678020ea1faf
  user-woman-blue-270/condition-mask-evidence.json
    SHA-256 25e6b8e7d3b963ade5fc48f538047ac0fda202e21a3546598a8a2f87cc3e8eb0
  user-woman-blue-270/preview-cover.png
    SHA-256 4751fbf68f76e1689a2f483b5de140a22b6194f70462c611b7515dc9fad2950a
  user-woman-blue-270/private-preview-copy.jpg
    SHA-256 f28cb4ba2e9a29f3d93e276005c0d0ce61c7185728068d9a1b522d29e5ae9815
```

### 4.9 可插拔局部生成fixture工具

生成能力与确定性调色/模糊分离，并由新增`-PlocalGeneratedPreview=true`第四层开关控制。Qwen可输出
`masked_replace`、编辑前目标和带原文provenance的ASCII generation prompt；Compiler将其降低为独立
`local_mask_generation_preview_shadow`节点。节点只接受当前素材、完整Mask Track和fresh
ScopeCertificate，输出单帧process-local artifact；`preview_only=true / writeback=false /
single_frame_only=true`均为强制参数。

首个`fixture_masked_generation_v1`实现可插拔backend接口，用prompt SHA生成确定性棋盘图案；它不是
生成模型。纯Kotlin测试要求Mask外ARGB逐位不变、alpha不变、prompt改变输出且取消生效；backend输出
再绑定source、mask、prompt、certificate和output SHA。低于画面0.1%的Mask被拒绝，避免把不可审查的
小碎片当作生成成功。

真机以5帧滑板fixture输入“把女人脚下的滑板变成悬浮板”：Qwen产生
`skateboard under a woman's feet → hoverboard`，5/5时序Mask完成后执行生成节点，图案落在滑板区域，
改变1,662像素且alpha变化0。结果无packed Live Photo；pending与accepted状态均被发布器拒绝，随后
`ACCEPTED_FOR_REVIEW→UNDONE`，源SHA不变、相册38→38：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/
  generation-fixture-hoverboard-5/evidence.json
    SHA-256 f913e30dc5dc3c2dbff82b132aa6a3857a6507d2f4195d106a25111f93440ecb
  generation-fixture-hoverboard-5/goal-ir-evidence.json
    SHA-256 8623d36dab9240aadb96463ee686dfba5290b77e3af0b729b183b84d1f8c775d
  generation-fixture-hoverboard-5/preview.png
    SHA-256 7f8cd9a11263b77a1b78f2fa0f1f04803be449b05dc918e4945596a250a16179
  generation-fixture-hoverboard-5/ui-pending.png
    SHA-256 cd5b048ddbb571eaad3d8986067b32898212c7ce6222e8129131e36dcf4fa879
```

该闭环只证明生成工具的Planner/DAG/backend/review接口，不代表真实inpaint质量，也不生成动态时间线。
接入真实云端或端侧模型后仍需独立验证生成内容、边缘融合、保护区、时序一致性和商业许可。

随后加入`AndroidHttpMaskedGenerationBackend`：配置只从应用私有目录读取；远端只允许HTTPS，明文仅允许
localhost/127.0.0.1供`adb reverse`；multipart请求绑定source PNG、mask、prompt、model、seed与
certificate SHA，响应必须回显request SHA并提供同尺寸PNG与output SHA。网络调用支持超时和取消，
即使远端改写整图，手机仍以源像素为准重新按认证mask合成，保证mask外不被远端修改。

N=5滑板fixture通过本地主机合同服务完成真实HTTP往返，backend为
`http_masked_generation_v1/contract_fixture`，改变1,662像素、alpha变化0，确认后仍不可发布：

```text
  generation-http-contract-hoverboard-5/evidence.json
    SHA-256 ba84c24496fda832e40993bc7b87f02189b39432b440937850a5113defece38d
  generation-http-contract-hoverboard-5/preview.png
    SHA-256 a8c9cf9134cd6bb8a7fad7fdba2b4779a9c40780476ccbb4ab9b68224ada15b9
```

主机研究服务入口为`android/tools/masked_inpaint_server.py`，权重与Python环境固定放在仓库外部
`/mnt/ssd/lpa-generation-backend/`；首个候选为OpenRAIL++的SD2 inpainting。

真实SD2 fp16权重在RTX 5090主机加载后，先以诊断区域生成出可辨识的金属悬浮板。原对象Mask仅有
1,662像素，严格按原Mask合成会裁掉新物体轮廓；因此Compiler加入固定24px
`generation_mask_padding_px`，executor从证书绑定Mask确定性派生扩展区域，padding参数进入DAG且
backend证据绑定派生Mask。扩展区域仍仅用于单帧私有preview，不能发布。

手机端同一请求最终通过真实`http_masked_generation_v1/sd2_diffusers_inpaint`：N=5跟踪完整，SD2生成
清晰悬浮板外形与发光边缘，手机重合成后改变20,116像素、alpha变化0；pending/accepted发布均被拒绝，
确认后撤销，源SHA不变、相册38→38：

```text
  generation-sd2-hoverboard-5/evidence.json
    SHA-256 5078b4c3f3d30d300a08ee3bd819aa0f4fc4c25662a92c179eac2d32f31e179f
  generation-sd2-hoverboard-5/preview.png
    SHA-256 132bed7e63e4bc93864859c37c8dc968d00e80e59c7875fac60ece0b152bea43
  generation-sd2-hoverboard-5/host-diagnostic-inpaint.png
    SHA-256 ac2a3211b3b094f76df2f691d77a3dc16c13b9a5c9d464b0887fcb50071ef5ec
```

这关闭了真实单帧masked inpaint backend Gate；完整动态生成、padding保护区语义、跨帧一致性、模型安全
和生产许可仍未关闭。

## 5. 逐帧跟踪调色显色证据

在已经通过vivo QAIRT 2.45 Gate的17帧固定图跟踪链上，使用每帧`device-mask.png`对中间面板目标猫
应用`#00FF66 / intensity=0.65`调色。17张无损输出均满足mask外逐像素不变：

```text
frames                              = 17
total outside-mask changed pixels   = 0
total inside-mask changed pixels    = 1,190,825
minimum mask IoU vs host reference  = 0.953569
maximum excess protection leakage   = 0
```

证据与人工查看视频：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/vivo-qnn245-dense-seventeen-frame/tracked-tint-proof/
  tracked-tint-evidence.json
SHA-256 bbe69a034c77cdf1f1a2ac3c3a052d11506b5dca506f09d394bdcd6d7c1e80dc

  tracked-tint-proof.mp4
SHA-256 b3f28bb6a6ebcb7e41c88849e100013537dfb4d2a441b5612756ab962b817491
```

MP4是并排人工验收产物，像素Gate只依据无损PNG。该fixture覆盖17个抽样时刻和16个真实pointer，
尚不是完整Live Photo的所有解码帧，也没有执行Live Photo repack或写回。

随后从真实`loop-chain-color.jpg` Live Photo拆出完全一致的1080×1440 / 181帧 / 6.034秒运动轨道，
运行EdgeTAM PyTorch全帧oracle，并与上述17个vivo QAIRT 2.45抽样mask交叉验证：

```text
full frames tracked                         = 181/181
all frame observations                     = VISIBLE_VALIDATED
outside-mask changed pixels                 = 0
maximum protected-panel outside fraction   = 0.0000813
sampled vivo masks compared                 = 17
minimum full-oracle vs vivo sampled IoU     = 0.951296
```

全帧调色视频保留181帧、原AAC音频及6.034秒时长，并使用第30帧生成1,000,000µs对应封面。vivo
LivePhotoSDK在应用私有目录完成pack后又成功decode；解包封面像素完全一致，181帧framemd5完全一致，
AAC bitstream SHA一致，photo timestamp仍为1,000,000µs。没有写入MediaStore：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-full-frame-tracked-tint/
  livephoto-tracked-tint-summary.json
SHA-256 7a06a42544df53f947c621f45e50f620d6107bfc97fd146f587378e1267f929d

  full-frame-tracked-tint-preview.mp4
SHA-256 1722cf4d68a0060c7e4a90137454e32ab853e1f2fe91c94cdb559d7d80ba8f6e

  vivo-livephoto-pack/tracked-tint-livephoto.jpg
SHA-256 d18ba66d98923f1a42fdc41f0d76e760ca3c2edd819aaefd107c3ee7d1359b2e
```

这里完整181帧由PyTorch oracle生成，vivo QNN只在17个时刻做差分，因此仍不能宣称Android端全帧
tracking已经闭环；但“全时间线mask驱动调色并repack为可解码Live Photo”的效果契约已经跑通。

### 5.1 用户明确授权后的相册发布

上述阶段均保持preview-only。2026-11-03用户随后明确要求把结果放入相册，因此只发布一份、使用新
文件名且不覆盖原片。发布前再次通过LivePhotoSDK decode；发布后MediaStore核对通过：

```text
uri           = content://media/external/images/media/2202
display name  = LPA_TrackedTint_Cat_20261103.jpg
relative path = Pictures/LivePhotoAgent/Generated/
size          = 5,752,408 bytes
live_photo    = motionphoto00010000000000000
is_pending    = 0
gallery SHA   = d18ba66d98923f1a42fdc41f0d76e760ca3c2edd819aaefd107c3ee7d1359b2e
```

相册文件与私有验证产物逐字节一致；原始Live Photo未覆盖。诊断发布入口只允许debug build且要求显式
`user_authorized_publish=true`，生产发布仍走已有validated artifact门禁。证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/edgetam-full-frame-tracked-tint/gallery-publish/
  gallery-publish-evidence.json
SHA-256 e2189af7efdaacc1a97e88c2d67e0246c04bcd74e8fa74707974fdccfeb2b653
```

## 6. 复现入口

```bash
adb shell am start-foreground-service \
  -n com.vivo.lpa/.SdkProbeService \
  --es cover_path /data/local/tmp/lpa-local-preview/source.png \
  --es mask_path /data/local/tmp/lpa-local-preview/mask.png \
  --ez local_preview_only true
adb logcat -d -s LPA_PROBE2:I '*:S'
```

探针会生成六个renderer case、CPU/GLES成对PNG、`h_local_preview_evidence.json`，并继续生成完整
shadow链的`i_shadow_bridge_evidence.json`与临时preview。任一renderer case使用fallback、mask外变化、
alpha变化或RGB误差大于1都会返回FAIL。

## 7. 尚未通过的Gate

本结果关闭“单帧renderer数值等价”“真实语言到真实关键帧mask”“动态N双向时序QNN”和“完整track
certificate到临时区域工具”Gate；以下仍阻止mutation：

1. App-UID QAIRT 2.45持久JNI已在实际解码`N=5`、VFR `N=7`、`N=53`和长序列`N=181`完成全时间线
   传播，子进程仍是回退；遮挡/重入等更多困难素材仍属于后续硬化，因此路径继续由第三层实验开关保护；
2. Android持久GLES→MediaCodec→AAC sample copy→Live Photo repack的`color_tint`已在`N=5`、
   `N=53`和`N=181`通过；`blur`已在`N=5`、VFR `N=7`、`N=53`和`N=181`通过完整Agent闭环，
   QNN执行中取消也已通过；
3. 完整动态artifact的确认→publish-ready→撤销→清理、Activity旋转、UI触屏和进程/会话恢复Gate均已
   通过。实际新副本发布/删除仍必须等待用户第二次明确授权，本轮未代替用户执行；
4. 持久QNN tracking五轮soak与释放已通过；完整pipeline的Qwen FastRPC/ION峰值及6次后台LMK证据
   保留，但产品决策接受16GB目标机上的该资源代价，不再把它列为当前功能闭环阻塞项；
5. 局部生成已完成fixture、HTTP合同与主机SD2真实单帧inpaint闭环；该OpenRAIL++研究后端尚未完成
   生产许可/安全评审，完整动态替换、padding保护语义和时序一致性仍未通过，生成预览不能发布；
6. 固定context银行包体仍未解决；任何确认仍只接受临时预览，不能写回。

常规验收结束时仍应恢复默认APK；本轮为继续验证可插拔生成backend，设备暂时保留显式实验
`dag/localTrackedPreview/fullTimeline/persistentQnn/localGeneratedPreview` Debug APK。该包不是生产构建，
其中生成结果只来自不可发布fixture；设备base.apk与host逐字节一致：

```text
SHA-256 3c231fdb209bf690d1486e04c79c527d67e2ab2f64776e589f735070bf01e555
```

vivo百度输入法保持启用，ADBKeyboard为disabled-user，stay-awake为0，LPA进程已停止。
