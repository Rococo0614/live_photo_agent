# Mobile Agent Migration

The Android module is now the phone-side shell for the Python Live Photo Agent.

## Runtime boundary

```text
Android media picker
  -> LivePhotoPreprocessor
  -> MobilePlan (same tool names as models.ToolName)
  -> vivo VideoEditorSDK adapter
  -> MediaStore output
```

The Python `ExecutionPlan` vocabulary is preserved in `AgentModels.kt`. Python
`ffmpeg` and `opencv` implementations are not bundled into the APK.

## Current native mappings

| Tool | Backend | Status |
|---|---|---|
| `clip_trim` | vivo VideoEditorSDK | implemented |
| `template_collage` | Android Canvas `layout_context` + vivo VideoEditorSDK overlays | implemented for manual slot layout and imported template slots; verified on X300 Ultra |
| `smart_collage` | QNN VLM + template selection | VLM pipeline is deployed; planner still uses the deterministic fallback until the text pack is added |
| `asset_summarize` | Qwen3-VL-4B QNN/Genie key-frame analysis | implemented and verified on X300 Ultra |
| `export_mp4` | Android MediaStore | implemented |
| Live Photo unpack/pack | vivo LivePhotoSDK 2.4.20.0 | implemented and verified on X300 Ultra |
| `subject_segmentation` | EdgeTAM/QNN | not wired in this module yet |
| `color_enhance` | vivo SDK filter API | not wired in this module yet |

The launcher now exposes an Android Canvas editor. It supports dragging, pinch
scaling, and two-finger rotation, imports the Python `template_schema_v2` JSON
shape (including grid coordinates, percentages, role/fill metadata, and
transitions), and returns `layout_context` to the native executor. Slot
coordinates and time windows are passed to the vivo overlay API instead of
being converted back to text. The executor no longer limits explicit manual
layouts to three overlays.

## Model packs

The text planner can use the target project's Genie layout when all of these
are available under the app external files directory:

```text
models/qwen/genie_config.json
models/qwen/runtime/lib/*
models/qwen/runtime/dsp/*
libgenie_app.so in the APK native library directory
```

The visual preprocessor deliberately has a separate contract:

```text
models/vlm/manifest.json
```

Qualcomm Qwen3-VL Genie bundles may instead provide
`models/vlm/genie_config.json` and `models/vlm/genie-app-script.txt`. The
runner still has to be available either as `libgenie_app.so` in the APK or as
an executable inside the model directory.

The manifest must name a real image/video-capable QNN runner. The current APK
packages the QAIRT 2.45 ARM64 `genie-app` and QNN libraries in `jniLibs`, while
the V81 DSP skeleton remains under `models/vlm/runtime/dsp`. The current
Qwen3-VL-4B bundle runs from the app-private model cache and analyzes up to
three sampled frames per asset. A text-only Qwen pack is still not accepted as
a VLM substitute.

The seeded dataset is installed as 100 SDK-compatible MP4 files under
`Movies/LivePhotoAgent`. The original MP4 files are kept under
`Movies/LivePhotoAgent-original`; SDK-compatible 1080x608 H.264 copies are
used by the active directory because the original 960x540 files are rejected by
the vivo VideoEditorSDK. Standalone JPG files are intentionally not installed;
the Android picker accepts MP4 only.

The Android catalog now contains the web library's 18 definitions: `T01`-`T07`
plus the current `M*`, `S*`, `live_*`, and matrix templates, preserving their
original IDs and slot geometry.

## Build

```bash
./tools/build_mobile_agent.sh
```

The generated debug APK is not a claim of real-device acceptance. ADB/device
validation still requires a target arm64 vivo device and the vendor model pack.
