# 局部确定性预览 Renderer 验证

> 验证日期：2026-11-02  
> 设备：vivo V2547A / PD2547，SM8850，Android 16（SDK 36）  
> 状态：**单帧GLES renderer与Android shadow bridge通过；不授权写回或发布**

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

本结果关闭“单帧renderer数值等价”和“supplied observation到临时artifact”Gate；以下仍阻止mutation：

1. OWLv2 QAIRT 2.45张量backend与Android输入均已通过，但Android QNN JNI尚未接入bridge；当前真机
   target/mask仍是已验证fixture，不是完整用户请求；
2. 抽样跟踪调色已通过；视频/Live Photo全帧解码、持久EGL context、时间戳保持和repack尚未验证；
3. UI已实现但尚未做触屏自动化、Activity/进程重建、会话恢复与旋转Gate；
4. 多分钟完整pipeline soak、总host/GPU/DSP内存与温升尚未通过；
5. 固定context银行包体仍未解决；任何确认仍只接受临时预览，不能写回。

测试后已恢复冻结的Runtime v2 loop APK，并确认设备安装包SHA一致：

```text
SHA-256 5e5d012c317c8e5f8e052058b1c1f1e168b99a1a4bd0378e79b818b71bb6a6f9
```

vivo百度输入法保持启用，ADBKeyboard为disabled-user，stay-awake为0，LPA进程已停止。
