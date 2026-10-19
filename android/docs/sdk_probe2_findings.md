# mediaeffectsdk 未启用能力离屏探针结论

验证日期：2026-10-19
设备：vivo PD2547（10AFC42G010009P），Android 16
载体：`SdkProbeService`（前台 Service，无 Activity / 无窗口），
触发 `adb shell am start-foreground-service -n com.vivo.lpa/.SdkProbeService`，
日志 tag `LPA_PROBE2`，产物目录 `files/probe2/`。

## 结论总表

| 通路 | 目标 | 实测 | 判定 |
| --- | --- | --- | --- |
| D `ImageProcessOffscreenRender` | bitmap→滤镜→文件 | 无产物、无回调；主 Looper 重试无效 | **离屏不可行** |
| E `processSegmentPoints` | 人像分割 mask | analyze 成功（返回 0），分割恒 0 框，人像图同样 | **不可行：SDK 内无人像模型** |
| F `VMEditMotionEffectEngine` | 编辑引擎 init+滤镜+文字贴纸 | init=0、setFilter(cube)=true，但 processTexture 恒返回 1 且无输出纹理，全程无渲染线程日志 | **离屏不渲染** |
| G `GPUMattingFilter` | mask+主图合成 | init/onInit/onDrawFrame 在自建 EGL pbuffer 上全部成功；process() 恒 NPE（内部 VBO 为空） | **不可独立驱动** |

## 关键证据

1. **E 不是人像分割**。`libImageProcess.so` 的导出符号是 OpenCV 的
   `LineSegmentDetectorImpl`（直线段检测），`processSegmentPoints` 返回的
   `InpaintingBox.partMask` 是修复（inpainting）语义。全文档检索 `.so` 字符串
   无任何模型文件引用（无 tflite/mnn/tnn/ncnn），**vendored SDK 不含分割权重**。
   人像 mask 来自相机侧 AI（`VMLivePhotoEffectEngine.setSkinMaskBuffer` 是
   消费方而非生产方），三方拿不到。
2. **G 的 NPE 定位**：`VertexBufferObject.getVboBufferId()` 空指针发生在
   `process()` 自有的合成 VBO 上；基类 `onDrawFrame(tex, cubeBuf, rotBuf)` 预热
   返回有效纹理 id（基类滤镜在自建 pbuffer 上可离屏渲染，
   `MotionTimelineEffect` 现网已在用），说明缺的不是 EGL 环境，而是引擎内部
   渲染图对 matting 私有缓冲的装配 —— 只能由引擎管线驱动，而 F 证明引擎管线
   离屏不渲染。
3. **F 与 VMEditor 的差异**：`VMEditor`（videoeditorsdk）离屏可用
   （headless_render_findings.md），但同属"编辑"的 mediaeffectsdk
   `VMEditMotionEffectEngine` 离屏静默不渲染。两者不可类比。

## 可行的新增能力（CPU 原生，无需 GPU/窗口）

`ImageProcessRenderEngine` 的纯 CPU 原生函数在探针中真实执行：
- `nativeProcessAnalyzeData`（返回 0，analyze 链可用）
- `nativeAnalyzeDominantColor` / `nativeAnalyzeDominantColorForCamera`（主色提取）
- `nativeAnalyzeColorCards` / `nativeAnalyzeDopamineColors`
- `nativeGetHistogram` / `nativeGetAutoFixParam` / `nativeGetAutoRotateAngle`

这些可做"智能调色建议 / 自动校正参数 / 自动旋转"类工具，且能被 planner 规划。

## 不可行项的出路

- 分割 / matting / 贴纸跟随：需要外部模型（自带 NCNN/MediaPipe 分割模型）
  或厂商相机侧 AI，均超出 vendored SDK 范围；贴纸内容层（.pag）同样缺失。
- 文字贴纸：F 未渲染，暂不接入。

## 复现

```bash
adb logcat -c
adb shell am start-foreground-service \
  --es person_path /storage/emulated/0/Pictures/LivePhotoAgent/people_3627.jpg \
  -n com.vivo.lpa/.SdkProbeService
adb logcat -d -s LPA_PROBE2
```
