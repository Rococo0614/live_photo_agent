# 局部确定性预览 Renderer 验证

> 验证日期：2026-11-03
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

当前debug/release单测均为127/127，`lintDebug`、`lintRelease`及debug/release assemble通过。默认
debug/release APK都不包含`libqnn_net_run.so`；只有显式实验构建包含：

```text
default debug APK        6bdccc048dc982fe7ccdbace21741748b845c36d7f8b0301e2ada0283e75a8a5
default release APK      4e6edfd6f776355ab9df8d720091856c4d74dc49400122c4b97529d884d92e40
full-timeline fallback   721b522a105b0db2d630e640a8f6efa33b4b956e50f5e8e6a3892de2de6f871d
persistent-QNN debug APK b2bc69981cda957a0ac223f0329c6e27b4de7f32a639b018b1dad2168e26b5b5
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

当前完整视频artifact仅开放`color_tint`；`blur`仍只生成单帧review，必须完成持久双pass shader与视频
差分后才能进入完整时间线编码。

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

1. App-UID QAIRT 2.45持久JNI已在实际解码`N=5`、`N=53`和现有长序列`N=181`完成全时间线传播，
   子进程仍是回退；更多不同`N`/VFR/遮挡输入及重复soak仍需完成，因此路径继续由第三层实验开关保护；
2. Android持久GLES→MediaCodec→AAC sample copy→Live Photo repack已在`N=5`、`N=53`和`N=181`
   素材通过，QNN执行中取消也已通过；仍需更多不同`N`/VFR素材、重复soak和DSP资源峰值验证；完整
   视频blur尚未实现；
3. UI已实现但尚未做触屏自动化、Activity/进程重建、会话恢复与旋转Gate；
4. 持久QNN tracking五轮soak与释放已通过，但完整pipeline因Qwen FastRPC/ION峰值触发6次后台LMK，
   手机总内存Gate明确失败；还需降低planner峰值并补GPU/DSP不可见私有分配；
5. 固定context银行包体仍未解决；任何确认仍只接受临时预览，不能写回。

本轮测试后已恢复当前默认`agentRuntime=loop / localTrackedPreview=false` APK；设备base.apk与host逐字节
一致，且不包含实验`qnn-net-run`：

```text
SHA-256 6bdccc048dc982fe7ccdbace21741748b845c36d7f8b0301e2ada0283e75a8a5
```

vivo百度输入法保持启用，ADBKeyboard为disabled-user，stay-awake为0，LPA进程已停止。
