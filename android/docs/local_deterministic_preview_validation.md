# 局部确定性预览 Renderer 验证

> 验证日期：2026-11-02  
> 设备：vivo V2547A / PD2547，SM8850，Android 16（SDK 36）  
> 状态：**真实语言→OWLv2→EdgeTAM关键帧mask→GLES区域工具已通过；全时序与写回仍禁用**

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

QAIRT执行层使用随实验APK提取的官方2.45 `qnn-net-run`，由App UID启动可取消子进程；context和DSP
skel来自外部模型包并逐个校验SHA-256。它已关闭“应用进程能否拥有并取消2.45 context执行”的Gate，
但不是持久JNI context，不能写成JNI生命周期已经完成。EdgeTAM encoder固定fixture的五个输出与此前
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

当前debug/release单测均为125/125，`lintDebug`、`lintRelease`及debug/release assemble通过。默认
debug/release APK都不包含`libqnn_net_run.so`；只有显式实验构建包含：

```text
default debug APK       fd1e268c738b8c0813501281f7613c6c0f212b0746e9ae1962b55ea183ad7eaf
default release APK     ef894037727686db2a758a6cf4769fe3209a1b0490b20295b4a3474b9b3b227e
full-timeline debug APK 07b13282cbf3390b22d7a8864d43cc2379032ff97bfc15df8535c46489dde58c
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
  -PfullTimelineTrackedPreview=true
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

1. App-UID QAIRT 2.45子进程已在5帧和53帧素材完成全时间线传播，但当前181帧fixture尚未由最终
   Android动态实现完整重跑；持久JNI context也未完成，因此该路径仍由二次实验开关保护；
2. PyTorch全帧调色与私有Live Photo repack已通过；Android端目前只对certificate选中的一帧生成
   GLES review bitmap，仍需按实际PTS逐帧渲染、编码并保留音频后再repack；
3. UI已实现但尚未做触屏自动化、Activity/进程重建、会话恢复与旋转Gate；
4. 多分钟完整pipeline soak、总host/GPU/DSP内存与温升尚未通过；
5. 固定context银行包体仍未解决；任何确认仍只接受临时预览，不能写回。

本轮测试后已恢复当前默认`agentRuntime=loop / localTrackedPreview=false` APK；设备base.apk与host逐字节
一致，且不包含实验`qnn-net-run`：

```text
SHA-256 fd1e268c738b8c0813501281f7613c6c0f212b0746e9ae1962b55ea183ad7eaf
```

vivo百度输入法保持启用，ADBKeyboard为disabled-user，stay-awake为0，LPA进程已停止。
