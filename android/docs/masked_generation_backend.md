# Masked generation backend（实验）

## 边界

- 只生成photo timestamp对应条件帧的私有单帧预览。
- Android始终以本地source和认证mask重新合成，远端不能修改mask外像素。
- 不生成或发布Live Photo；确认review也不授权写回。
- fixture与SD2均不是生产后端，模型许可、安全、质量与时序一致性需另行评审。

## Android开关

```bash
./gradlew :app:assembleDebug \
  -PagentRuntime=dag \
  -PlocalTrackedPreview=true \
  -PfullTimelineTrackedPreview=true \
  -PpersistentQnn=true \
  -PlocalGeneratedPreview=true
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
