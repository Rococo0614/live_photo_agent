# Masked generation backend（实验）

## 边界

- Backend只生成photo timestamp对应的私有锚帧；受限传播器可生成短动态私有Live Photo。
- Android始终以本地source和认证mask重新合成，任何Backend都不能修改mask外像素。
- 动态生成artifact永久不可发布；确认review也不授权写回。
- fixture与SD2均不是生产后端，模型许可、安全、质量与时序一致性需另行评审。

## Android开关

```bash
./gradlew :app:assembleDebug \
  -PagentRuntime=dag \
  -PlocalTrackedPreview=true \
  -PfullTimelineTrackedPreview=true \
  -PpersistentQnn=true \
  -PlocalGeneratedPreview=true \
  -PonDeviceGeneratedPreview=true
```

无HTTP配置时使用`fixture_masked_generation_v1`。HTTP配置位于应用私有目录：

```text
files/models/local-generation-http/config.json
```

示例：

```json
{
  "endpoint": "http://127.0.0.1:8765/v1/inpaint",
  "model_id": "sd2-community/stable-diffusion-2-inpainting",
  "connect_timeout_ms": 15000,
  "read_timeout_ms": 300000
}
```

远端明文HTTP会被拒绝；127.0.0.1/localhost只用于ADB反向代理：

```bash
adb reverse tcp:8765 tcp:8765
```

## 主机研究服务

环境和权重放在仓库外：

```bash
python3.10 -m venv /mnt/ssd/lpa-generation-backend/venv
/mnt/ssd/lpa-generation-backend/venv/bin/pip install \
  torch torchvision diffusers transformers accelerate safetensors flask pillow scipy

HF_HOME=/mnt/ssd/lpa-generation-backend/hf-cache \
/mnt/ssd/lpa-generation-backend/venv/bin/python \
  android/tools/masked_inpaint_server.py \
  --model /mnt/ssd/lpa-generation-backend/model/sd2-inpainting \
  --model-id sd2-community/stable-diffusion-2-inpainting \
  --cache-dir /mnt/ssd/lpa-generation-backend/hf-cache
```

当前研究模型固定为`sd2-community/stable-diffusion-2-inpainting` commit
`5f74973cbb64c8568780732c17f43eb269d63a0d`，fp16 LFS SHA-256：

```text
text_encoder/model.fp16.safetensors
  681c555376658c81dc273f2d737a2aeb23ddb6d1d8e5b3a7064636d359a22668
unet/diffusion_pytorch_model.fp16.safetensors
  29a698f37775d5904a958c9cebed98184483dfb441729a8e5f98dd5b65df70c8
vae/diffusion_pytorch_model.fp16.safetensors
  3e4c08995484ee61270175e9e7a072b66a6e4eeb5f0c266667fe1f45b90daf9a
```

模型采用CreativeML Open RAIL++-M许可，仅作为研究后端；生产使用前必须单独完成许可与安全评审。

服务只绑定loopback，提供：

```text
GET  /health
POST /v1/inpaint
```

POST使用multipart：`source`、`mask`、`prompt`、`model_id`、`seed`、`request_sha256`和
`certificate_fingerprint`。响应为JSON，含同尺寸PNG的Base64、request SHA和output SHA。

## 首个验收请求

```text
把女人脚下的滑板变成悬浮板
```

Qwen必须拆成：

```text
target            = skateboard under a woman's feet
generation_prompt = hoverboard
effect_kind       = masked_replace
```

首个真实模型验收使用固定24px派生生成区域；该padding进入DAG并由executor从证书Mask确定性计算。
N=5手机闭环通过`http_masked_generation_v1/sd2_diffusers_inpaint`，生成可辨识的悬浮板：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/
  generation-sd2-hoverboard-5/evidence.json
  generation-sd2-hoverboard-5/preview.png
```

结果仍是单帧私有preview；完整动态生成、padding保护区语义、模型安全与生产许可尚未通过。

## 短动态刚性传播

首版动态能力不是视频扩散模型。它将真实生成锚帧纹理按每帧认证Mask包围盒做平移/缩放，限制为最多
60帧，并要求所有Mask非空、面积和宽高比变化通过刚性Gate。每帧保留达到主组件1%且至少4px的显著
断开部件，删除微小speckle，再做24px扩展；传播还受面积包络和锚变化支持集限制。输出可打包为私有
Live Photo，但artifact永久`publishEligible=false`。

验证产物：

```text
/mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/
  generation-sd2-hoverboard-dynamic-5-clean/private-livephoto.jpg
  generation-sd2-hoverboard-dynamic-5-clean/preview.mp4
  generation-sd2-hoverboard-dynamic-5-clean/timeline-validation.json
```

该路径只适合短时刚性目标；旋转、形变、遮挡/重入和光照变化必须返回不支持，不能降级成静止贴图。

## 端侧Vulkan Backend

`sdcpp_vulkan_masked_generation_v1`使用固定stable-diffusion.cpp、Q4_0 SD2 inpaint和Adreno
Vulkan，完全不访问网络。模型不进入APK，必须从应用私有目录通过字节数与SHA-256 Gate。默认320×320、
20步；256为快速档，512为高质量档。

N=5最终闭环完成端侧锚帧生成、完整Mask Track、刚性传播、私有Live Photo打包与中途取消。详细构建、
安全策略、性能和证据见`android/docs/on_device_livephoto_inpainting.md`。主界面现可直接循环播放私有
运动轨道、切换原图、确认私有review并撤销；确认生成结果不会出现发布入口。
