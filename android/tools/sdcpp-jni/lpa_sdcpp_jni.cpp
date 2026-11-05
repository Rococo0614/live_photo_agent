#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "stable-diffusion.h"

namespace {

constexpr const char* kLogTag = "LPA_SDCPP";
constexpr const char* kRuntimeCommit = "58bb3c1b1cd4749e94e39f537e9adfce295dcaee";
constexpr const char* kBackend = "diffusion=Vulkan0,te=Vulkan0,vae=Vulkan0";

std::mutex gExecutionMutex;
std::mutex gContextMutex;
sd_ctx_t* gActiveContext = nullptr;
std::atomic<std::int64_t> gActiveRequest{0};
std::atomic<bool> gCancellationRequested{false};
std::atomic<int> gProgressStep{0};
std::atomic<int> gProgressTotal{0};

class JString final {
 public:
  JString(JNIEnv* env, jstring value) : env_(env), value_(value) {
    if (value_ == nullptr) throw std::invalid_argument("string argument is null");
    chars_ = env_->GetStringUTFChars(value_, nullptr);
    if (chars_ == nullptr) throw std::runtime_error("cannot read UTF-8 string");
  }

  ~JString() {
    if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
  }

  std::string str() const { return chars_; }

 private:
  JNIEnv* env_;
  jstring value_;
  const char* chars_ = nullptr;
};

std::vector<std::uint8_t> fromByteArray(JNIEnv* env, jbyteArray value) {
  if (value == nullptr) throw std::invalid_argument("byte array is null");
  const jsize length = env->GetArrayLength(value);
  if (length < 0) throw std::invalid_argument("negative byte array length");
  std::vector<std::uint8_t> output(static_cast<std::size_t>(length));
  if (length > 0) {
    env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(output.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("cannot read byte array");
  }
  return output;
}

void throwJava(JNIEnv* env, const char* className, const std::string& message) {
  jclass type = env->FindClass(className);
  if (type != nullptr) env->ThrowNew(type, message.c_str());
}

void logCallback(sd_log_level_t level, const char* text, void*) {
  int priority = ANDROID_LOG_INFO;
  if (level == SD_LOG_ERROR) priority = ANDROID_LOG_ERROR;
  if (level == SD_LOG_WARN) priority = ANDROID_LOG_WARN;
  if (level == SD_LOG_DEBUG || level == SD_LOG_VERBOSE) priority = ANDROID_LOG_DEBUG;
  __android_log_print(priority, kLogTag, "%s", text == nullptr ? "" : text);
}

void progressCallback(int step, int steps, float, void*) {
  gProgressStep.store(step, std::memory_order_relaxed);
  gProgressTotal.store(steps, std::memory_order_relaxed);
}

void validateAsciiPrompt(const std::string& prompt, const char* name, std::size_t maximum) {
  if (prompt.empty() || prompt.size() > maximum) {
    throw std::invalid_argument(std::string(name) + " length is invalid");
  }
  for (const unsigned char value : prompt) {
    if (value != '\n' && value != '\r' && value != '\t' && (value < 0x20 || value > 0x7e)) {
      throw std::invalid_argument(std::string(name) + " must be bounded ASCII");
    }
  }
}

class ActiveRequestGuard final {
 public:
  explicit ActiveRequestGuard(std::int64_t requestId) : requestId_(requestId) {
    std::int64_t expected = 0;
    if (!gActiveRequest.compare_exchange_strong(expected, requestId_)) {
      throw std::runtime_error("another stable-diffusion.cpp request is active");
    }
    gCancellationRequested.store(false);
    gProgressStep.store(0);
    gProgressTotal.store(0);
  }

  ~ActiveRequestGuard() {
    {
      std::lock_guard<std::mutex> lock(gContextMutex);
      gActiveContext = nullptr;
    }
    gProgressStep.store(0);
    gProgressTotal.store(0);
    gCancellationRequested.store(false);
    gActiveRequest.store(0);
  }

 private:
  std::int64_t requestId_;
};

class ContextGuard final {
 public:
  explicit ContextGuard(sd_ctx_t* context) : context_(context) {
    if (context_ == nullptr) throw std::runtime_error("stable-diffusion.cpp context creation failed");
    std::lock_guard<std::mutex> lock(gContextMutex);
    gActiveContext = context_;
  }

  ~ContextGuard() {
    {
      std::lock_guard<std::mutex> lock(gContextMutex);
      if (gActiveContext == context_) gActiveContext = nullptr;
    }
    free_sd_ctx(context_);
  }

  sd_ctx_t* get() const { return context_; }

 private:
  sd_ctx_t* context_;
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_vivo_lpa_AndroidStableDiffusionCppNativeBridge_nativeRuntimeInfo(
    JNIEnv* env,
    jclass) {
  const std::string value = std::string("stable-diffusion.cpp=") + kRuntimeCommit +
      ";backend=Vulkan0;inference_sizes=256,320,512";
  return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vivo_lpa_AndroidStableDiffusionCppNativeBridge_nativeGenerate(
    JNIEnv* env,
    jclass,
    jlong requestId,
    jstring modelPath,
    jbyteArray sourceRgb,
    jbyteArray mask,
    jint width,
    jint height,
    jstring prompt,
    jstring negativePrompt,
    jint steps,
    jfloat guidanceScale,
    jlong seed) {
  try {
    std::lock_guard<std::mutex> executionLock(gExecutionMutex);
    const std::int64_t nativeRequestId = static_cast<std::int64_t>(requestId);
    if (nativeRequestId <= 0) throw std::invalid_argument("request id must be positive");
    ActiveRequestGuard activeRequest(nativeRequestId);

    const std::string nativeModelPath = JString(env, modelPath).str();
    const std::string nativePrompt = JString(env, prompt).str();
    const std::string nativeNegativePrompt = JString(env, negativePrompt).str();
    validateAsciiPrompt(nativePrompt, "prompt", 160);
    validateAsciiPrompt(nativeNegativePrompt, "negative prompt", 320);
    if (width != height || (width != 256 && width != 320 && width != 512)) {
      throw std::invalid_argument("only certified 256x256, 320x320 or 512x512 inference is supported");
    }
    if (steps < 1 || steps > 30) throw std::invalid_argument("steps must be in 1..30");
    if (!std::isfinite(guidanceScale) || guidanceScale < 1.0f || guidanceScale > 20.0f) {
      throw std::invalid_argument("guidance scale must be in 1..20");
    }
    const std::filesystem::path model(nativeModelPath);
    if (!std::filesystem::is_regular_file(model) || std::filesystem::file_size(model) < 1024ULL * 1024ULL * 1024ULL) {
      throw std::invalid_argument("pinned model file is missing or truncated");
    }

    std::vector<std::uint8_t> nativeSource = fromByteArray(env, sourceRgb);
    std::vector<std::uint8_t> nativeMask = fromByteArray(env, mask);
    const std::size_t pixels = static_cast<std::size_t>(width) * static_cast<std::size_t>(height);
    if (nativeSource.size() != pixels * 3) throw std::invalid_argument("source RGB byte count mismatch");
    if (nativeMask.size() != pixels) throw std::invalid_argument("mask byte count mismatch");
    bool nonEmptyMask = false;
    for (const std::uint8_t value : nativeMask) nonEmptyMask = nonEmptyMask || value != 0;
    if (!nonEmptyMask) throw std::invalid_argument("generation mask is empty");

    sd_set_log_callback(logCallback, nullptr);
    sd_set_progress_callback(progressCallback, nullptr);
    sd_ctx_params_t contextParams;
    sd_ctx_params_init(&contextParams);
    contextParams.model_path = nativeModelPath.c_str();
    contextParams.backend = kBackend;
    contextParams.max_vram = "6";
    contextParams.enable_mmap = true;
    contextParams.conditioning_cache_size = 0;
    contextParams.flash_attn_mode = "auto";
    contextParams.conv_direct_mode = "auto";
    ContextGuard context(new_sd_ctx(&contextParams));
    if (gCancellationRequested.load()) {
      sd_cancel_generation(context.get(), SD_CANCEL_ALL);
      throw std::runtime_error("stable-diffusion.cpp generation cancelled before sampling");
    }

    sd_img_gen_params_t generationParams;
    sd_img_gen_params_init(&generationParams);
    generationParams.prompt = nativePrompt.c_str();
    generationParams.negative_prompt = nativeNegativePrompt.c_str();
    generationParams.init_image = {
        static_cast<std::uint32_t>(width),
        static_cast<std::uint32_t>(height),
        3,
        nativeSource.data(),
    };
    generationParams.mask_image = {
        static_cast<std::uint32_t>(width),
        static_cast<std::uint32_t>(height),
        1,
        nativeMask.data(),
    };
    generationParams.width = width;
    generationParams.height = height;
    generationParams.sample_params.sample_steps = steps;
    generationParams.sample_params.guidance.txt_cfg = guidanceScale;
    generationParams.sample_params.sample_method = EULER_SAMPLE_METHOD;
    generationParams.sample_params.scheduler = DISCRETE_SCHEDULER;
    generationParams.strength = 1.0f;
    generationParams.seed = static_cast<std::int64_t>(seed);
    generationParams.batch_count = 1;
    generationParams.vae_tiling_params.enabled = true;

    sd_image_generation_result_t* result = nullptr;
    const bool generated = generate_image(context.get(), &generationParams, &result);
    if (!generated || result == nullptr) {
      if (gCancellationRequested.load()) {
        throw std::runtime_error("stable-diffusion.cpp generation cancelled");
      }
      throw std::runtime_error("stable-diffusion.cpp image generation failed");
    }
    struct ResultGuard final {
      sd_image_generation_result_t* value;
      ~ResultGuard() { free_sd_image_generation_result(value); }
    } resultGuard{result};
    if (gCancellationRequested.load()) throw std::runtime_error("stable-diffusion.cpp generation cancelled");
    if (result->count != 1 || result->items == nullptr) {
      throw std::runtime_error("stable-diffusion.cpp returned an unexpected image count");
    }
    const sd_image_t& image = result->items[0].image;
    if (image.width != static_cast<std::uint32_t>(width) ||
        image.height != static_cast<std::uint32_t>(height) || image.channel != 3 || image.data == nullptr) {
      throw std::runtime_error("stable-diffusion.cpp returned an invalid RGB image");
    }
    const jsize outputSize = static_cast<jsize>(pixels * 3);
    jbyteArray output = env->NewByteArray(outputSize);
    if (output == nullptr) throw std::runtime_error("cannot allocate generated RGB output");
    env->SetByteArrayRegion(output, 0, outputSize, reinterpret_cast<const jbyte*>(image.data));
    if (env->ExceptionCheck()) throw std::runtime_error("cannot copy generated RGB output");
    return output;
  } catch (const std::exception& error) {
    const std::string message = error.what();
    const bool cancelled = message.find("cancel") != std::string::npos;
    throwJava(env,
              cancelled ? "java/lang/InterruptedException" : "java/lang/IllegalStateException",
              message);
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vivo_lpa_AndroidStableDiffusionCppNativeBridge_nativeCancel(
    JNIEnv*,
    jclass,
    jlong requestId) {
  if (gActiveRequest.load() != static_cast<std::int64_t>(requestId)) return;
  gCancellationRequested.store(true);
  std::lock_guard<std::mutex> lock(gContextMutex);
  if (gActiveContext != nullptr) sd_cancel_generation(gActiveContext, SD_CANCEL_ALL);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_vivo_lpa_AndroidStableDiffusionCppNativeBridge_nativeProgress(
    JNIEnv*,
    jclass,
    jlong requestId) {
  if (gActiveRequest.load() != static_cast<std::int64_t>(requestId)) return -1;
  const std::uint64_t step = static_cast<std::uint32_t>(gProgressStep.load());
  const std::uint64_t total = static_cast<std::uint32_t>(gProgressTotal.load());
  return static_cast<jlong>((total << 32U) | step);
}
