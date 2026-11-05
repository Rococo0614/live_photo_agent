# 端侧交互式 Live Photo inpainting（实验）

## 已关闭的功能闭环

当前显式实验构建支持：

```text
中文自然语言
  → Qwen masked_replace语义规划
  → OWLv2 + EdgeTAM完整Mask Track
  → 手机Adreno Vulkan上的SD2锚帧inpaint
  → 短时刚性目标传播
  → MediaCodec视频编码、原PTS/音频保持
  → 私有Live Photo review
```

生成过程不访问网络。模型在应用私有目录，APK只包含JNI runtime；输出固定
`publishEligible=false`，确认review也不能发布或覆盖源媒体。

## 固定runtime与模型

```text
stable-diffusion.cpp commit
  58bb3c1b1cd4749e94e39f537e9adfce295dcaee

liblpa_sdcpp.so
  SHA-256 6ecdb8d493a1e90e1594460a3d86ef97f3be306b101cf61815cd730cd81f9f0a

SD2 inpaint Q4_0 GGUF
  bytes   1,733,629,824
  SHA-256 7d154a1f52167da45bb86f34ded41feb3b3f4f2f0f3e729a7997c8bb394bf44b
```

Q4_0来自固定的`sd2-community/stable-diffusion-2-inpainting` commit
`5f74973cbb64c8568780732c17f43eb269d63a0d`。现有diffusers fp16组件先无损合并为
`sd2-inpainting-fp16.safetensors`：

```text
bytes   2,580,097,496
SHA-256 95b34926def21517ff9ace6499c11db9a11c7e94284c4d31002f349a1ae598dd
```

再由固定sd.cpp执行`-M convert --type q4_0`。模型采用CreativeML Open RAIL++-M，只用于研究；
生产许可、内容安全与模型分发仍未通过。

## 构建与安装

准备仓库外的固定sd.cpp源码、SPIR-V Headers、Vulkan Headers和NDK 28.2后：

```bash
android/tools/build_sdcpp_android.sh

cd android
./gradlew :app:assembleDebug \
  -PagentRuntime=dag \
  -PlocalTrackedPreview=true \
  -PfullTimelineTrackedPreview=true \
  -PpersistentQnn=true \
  -PlocalGeneratedPreview=true \
  -PonDeviceGeneratedPreview=true

adb install -r -t app/build/outputs/apk/debug/app-debug.apk
tools/install_sdcpp_inpaint_model.sh
```

模型配置位于：

```text
files/models/local-generation-sdcpp/config.json
```

默认交互档为320×320、20步、CFG 7.5。安装脚本也接受：

```bash
SDCPP_INFERENCE_SIZE=256 SDCPP_STEPS=20 tools/install_sdcpp_inpaint_model.sh
SDCPP_INFERENCE_SIZE=512 SDCPP_STEPS=20 tools/install_sdcpp_inpaint_model.sh
```

快速/高质量档均使用相同固定权重。256、320、512之外的尺寸、非固定SHA、目录穿越、超过30步或
超过超时上限都会被拒绝。

## 安全与生命周期

- Backend启动前验证模型字节数、SHA-256、runtime commit和Vulkan身份。
- 推理使用确定性请求SHA派生seed；证据绑定模型、prompt、证书、ROI和最终像素。
- 生成ROI来自认证Mask：保留面积达到主组件1%的真实断开部件，删除微小speckle。
- 锚帧只在24px扩展区域合成；区域外像素逐字节来自source。
- 动态传播只接受刚性面积/宽高比Gate，并使用面积受限的组件包络。
- 只有锚帧相对source实际变化的纹理允许传播；包络本身不能生成背景块。
- 锚帧和动态包络均做4px向内羽化，永不扩张到授权区域外。
- 空Mask、区域过大、组件包络超过Mask 3倍、数值全白/全黑、超时均fail-closed。
- UI显示20步进度；取消调用`sd_cancel_generation`并等待native context释放。
- Cancel/undo/process cleanup删除临时artifact；模型本身保留在应用私有目录。

OpenCL实验能执行UNet，但fp16/Q4_0均输出全白，因此被数值Gate拒绝。Vulkan在512无VAE tiling或
tile大于256时会触发Adreno driver fault，产品路径固定启用256px VAE tile，不使用这些配置。

## 真机结果

设备：SM8850 / Adreno 840 / 16GB。请求：

```text
把女人脚下的滑板变成悬浮板
```

最终交互档结果：

```text
backend             sdcpp_vulkan_masked_generation_v1/320x320/20step
SD2完整生成耗时    113.22s
跟踪/动态帧        5/5
PTS                 0 / 0.04 / 0.08 / 0.12 / 0.16s（逐项保持）
changed pixels      17,249
changed alpha       0
thermal status      0
source SHA          unchanged
gallery rows        38 → 38
publish             pending与accepted均硬拒绝
```

最终证据：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/
  generation-sd2-ondevice-vulkan-interactive-final-5/

evidence.json
  SHA-256 6dc9e2e1ed258593c3f676f7c1b42dfd2f80de1fac69d71031474573eac95f9a
private-livephoto.jpg
  SHA-256 b2a33de7199ec93ee9c8df805a2fe7d54dc6277688c6fa72149db0a64909e14a
preview.mp4
  SHA-256 10bc18c4541d7606b6dc6daa56dc867ddd4d50d89683088ed99a9cc7cb777e9a
timeline-validation.json
  SHA-256 af4f92163a53dcf3d1c1b590b8cc8104078346c921d6836224adb363387f2a5d
```

最终320档中途取消在第1步后5,015ms内完成，返回`CANCELLED`，无preview root、源和相册不变：

```text
cancellation-evidence.json
  SHA-256 f519403ccd8b95cf5d6542883c18dbd8e8ce8c4f374e852585efad6783cbe461
```

## 尚未关闭

当前动态结果是受限锚纹理传播，不是视频扩散。旋转、透视、非刚性形变、遮挡/重入、明显光照变化、
长序列和多目标必须拒绝。还缺通用VLM语义验收、生产内容安全、模型许可/分发、NPU加速与更多素材集。
默认构建仍不包含JNI库，也不会选择该Backend。
