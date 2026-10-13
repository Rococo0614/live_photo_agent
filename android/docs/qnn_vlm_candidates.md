# QNN VLM Candidate

## Recommended first candidate

Use a Qualcomm-published **VLM** W4A16 GENIE bundle that explicitly targets
`SM8850` or Snapdragon 8 Elite Gen 5. Prefer a 4B bundle when that exact
target exists; the smaller model is a better phone-side latency and memory
choice.

The current fallback candidate is:

`qualcomm/Qwen2.5-VL-7B-Instruct`

Model card:

```text
https://huggingface.co/qualcomm/Qwen2.5-VL-7B-Instruct
```

The fallback package to arrange is:

```text
qwen2_5_vl_7b_instruct-genie-w4a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip
```

It uses QAIRT 2.45 and the `genie-app` execution path already used by the
reference Android project. The model supports image understanding and can be
used on sampled Live Photo frames. For a video-like input, the Android
preprocessor should send three or more JPEG key frames rather than the MP4
itself.

The published Android row is labelled Snapdragon 8 Elite Gen 5 for Galaxy,
while the phone under test reports `SM8850`. These are close enough to test the
fallback bundle, but not enough to claim compatibility before an on-device run.

## Alternatives

- `qualcomm/Qwen3-VL-4B-Instruct`: preferred if a Qualcomm package labelled for `SM8850`/Snapdragon 8 Elite Gen 5 is available; the currently indexed public W4A16 release is labelled `qualcomm-qcs8275` / Snapdragon X2 Elite, so that specific artifact is not assumed compatible.
- `qualcomm/Qwen3-VL-2B-Instruct`: smaller, but the public page currently exposes a universal GGUF path rather than a direct W4A16 Genie Android bundle.

## Required non-model runtime

The model ZIP is not the complete Android integration. The target needs:

- `genie_config.json`
- model weight/context files
- tokenizer and vision assets
- `genie-app-script.txt` for the multimodal path
- QAIRT/Genie runner (`genie-app` or equivalent)
- compatible `libGenie.so`, `libQnnGenAiTransformer*.so`, and HTP libraries

The X300 Ultra already exposes system `libGenie.so` and QNN HTP libraries, but
it does not expose a `genie-app` executable to third-party apps. The runner
still has to be packaged or replaced by a JNI bridge.
