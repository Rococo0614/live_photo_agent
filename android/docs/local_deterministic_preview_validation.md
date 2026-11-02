# 局部确定性预览 Renderer 验证

> 验证日期：2026-11-02  
> 设备：vivo V2547A / PD2547，SM8850，Android 16（SDK 36）  
> 状态：**单帧、内存内、preview-only GLES renderer 通过；不授权写回或发布**

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

该类没有注册到`AgentDagExecutionEngine`，不接收路径或MediaStore URI，也不写文件、替换素材、提交或
发布。`SdkProbeService`中的H探针只把结果保存到应用私有诊断目录以便拉取验证。Runtime v2 loop和
生产APK均未改变。

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

## 4. 复现入口

```bash
adb shell am start-foreground-service \
  -n com.vivo.lpa/.SdkProbeService \
  --es cover_path /data/local/tmp/lpa-local-preview/source.png \
  --es mask_path /data/local/tmp/lpa-local-preview/mask.png \
  --ez local_preview_only true
adb logcat -d -s LPA_PROBE2:I '*:S'
```

探针会生成六个case、CPU/GLES成对PNG与`h_local_preview_evidence.json`；任一case使用fallback、mask外
变化、alpha变化或RGB误差大于1都会返回FAIL。

## 5. 尚未通过的Gate

本结果只关闭“单帧renderer数值等价”Gate，以下仍阻止mutation：

1. Android shadow bridge尚未把真实`OBJECT_TRACK`、逐帧`MASK_TRACK`和certificate materialize到预览；
2. 每帧视频/Live Photo解码、持久EGL context、时间戳保持与完整逐帧preservation尚未验证；
3. 临时预览、确认、取消和undo UI尚未闭环；
4. Activity/进程重建、多分钟pipeline soak、总host/GPU/DSP内存与温升尚未通过；
5. QAIRT 2.45本地语言locator和固定context银行包体仍未解决。

测试后已恢复冻结的Runtime v2 loop APK，并确认设备安装包SHA一致：

```text
SHA-256 5e5d012c317c8e5f8e052058b1c1f1e168b99a1a4bd0378e79b818b71bb6a6f9
```

vivo百度输入法保持启用，ADBKeyboard为disabled-user，stay-awake为0，LPA进程已停止。
