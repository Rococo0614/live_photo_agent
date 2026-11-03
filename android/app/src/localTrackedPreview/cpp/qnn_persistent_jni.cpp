#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include "QnnInterface.h"
#include "HTP/QnnHtpCommon.h"
#include "HTP/QnnHtpDevice.h"
#include "System/QnnSystemInterface.h"

namespace {

constexpr char kLogTag[] = "LPA_QNN_JNI";
constexpr char kExpectedQairt[] = "2.45.0.260326154327";
constexpr std::size_t kMaximumResidentContexts = 4;

void logError(const std::string& message) {
  __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
}

void logInfo(const std::string& message) {
  __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message.c_str());
}

[[noreturn]] void fail(const std::string& message) {
  throw std::runtime_error(message);
}

void requireQnnSuccess(Qnn_ErrorHandle_t status, const char* operation) {
  if (status != QNN_SUCCESS) {
    fail(std::string(operation) + " failed with QNN status " + std::to_string(status));
  }
}

std::string fromJString(JNIEnv* env, jstring value) {
  if (value == nullptr) fail("received null Java string");
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) fail("unable to access Java string");
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

std::vector<std::string> fromJStringArray(JNIEnv* env, jobjectArray values) {
  if (values == nullptr) fail("received null Java string array");
  const jsize count = env->GetArrayLength(values);
  std::vector<std::string> result;
  result.reserve(static_cast<std::size_t>(count));
  for (jsize index = 0; index < count; ++index) {
    auto* value = static_cast<jstring>(env->GetObjectArrayElement(values, index));
    if (value == nullptr) fail("received null element in Java string array");
    result.push_back(fromJString(env, value));
    env->DeleteLocalRef(value);
  }
  return result;
}

std::vector<std::uint64_t> fromJLongArray(JNIEnv* env, jlongArray values) {
  if (values == nullptr) fail("received null Java long array");
  const jsize count = env->GetArrayLength(values);
  std::vector<jlong> temporary(static_cast<std::size_t>(count));
  env->GetLongArrayRegion(values, 0, count, temporary.data());
  if (env->ExceptionCheck()) fail("unable to access Java long array");
  std::vector<std::uint64_t> result;
  result.reserve(temporary.size());
  for (const jlong value : temporary) {
    if (value <= 0) fail("tensor byte count must be positive");
    result.push_back(static_cast<std::uint64_t>(value));
  }
  return result;
}

void throwJava(JNIEnv* env, const char* className, const std::string& message) {
  logError(message);
  jclass exceptionClass = env->FindClass(className);
  if (exceptionClass == nullptr) {
    env->ExceptionClear();
    exceptionClass = env->FindClass("java/lang/RuntimeException");
  }
  if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, message.c_str());
}

template <typename T>
T resolve(void* library, const char* symbol) {
  dlerror();
  void* address = dlsym(library, symbol);
  const char* error = dlerror();
  if (address == nullptr || error != nullptr) {
    fail(std::string("unable to resolve ") + symbol + ": " +
         (error == nullptr ? "unknown error" : error));
  }
  return reinterpret_cast<T>(address);
}

std::vector<std::uint8_t> readFile(const std::string& path) {
  std::ifstream stream(path, std::ios::binary | std::ios::ate);
  if (!stream) fail("unable to open input file: " + path);
  const std::streamoff end = stream.tellg();
  if (end <= 0) fail("input file is empty: " + path);
  if (static_cast<std::uint64_t>(end) > std::numeric_limits<std::size_t>::max()) {
    fail("input file is too large: " + path);
  }
  std::vector<std::uint8_t> bytes(static_cast<std::size_t>(end));
  stream.seekg(0, std::ios::beg);
  stream.read(reinterpret_cast<char*>(bytes.data()), end);
  if (!stream) fail("unable to read input file: " + path);
  return bytes;
}

void readFileExact(const std::string& path, std::vector<std::uint8_t>* destination) {
  if (destination == nullptr || destination->empty()) fail("invalid tensor destination buffer");
  std::ifstream stream(path, std::ios::binary | std::ios::ate);
  if (!stream) fail("unable to open tensor input: " + path);
  const std::streamoff end = stream.tellg();
  if (end < 0 || static_cast<std::uint64_t>(end) != destination->size()) {
    fail("tensor input size changed before native execution: " + path);
  }
  stream.seekg(0, std::ios::beg);
  stream.read(reinterpret_cast<char*>(destination->data()), end);
  if (!stream) fail("unable to read tensor input: " + path);
}

void writeFileExact(const std::string& path, const std::vector<std::uint8_t>& source) {
  std::ofstream stream(path, std::ios::binary | std::ios::trunc);
  if (!stream) fail("unable to open tensor output: " + path);
  stream.write(reinterpret_cast<const char*>(source.data()),
               static_cast<std::streamsize>(source.size()));
  stream.flush();
  if (!stream) fail("unable to write tensor output: " + path);
}

std::uint32_t dataTypeBits(Qnn_DataType_t type) {
  switch (type) {
    case QNN_DATATYPE_INT_2:
    case QNN_DATATYPE_UINT_2:
    case QNN_DATATYPE_SFIXED_POINT_2:
    case QNN_DATATYPE_UFIXED_POINT_2:
      return 2;
    case QNN_DATATYPE_INT_4:
    case QNN_DATATYPE_UINT_4:
    case QNN_DATATYPE_FLOAT_4:
    case QNN_DATATYPE_SFIXED_POINT_4:
    case QNN_DATATYPE_UFIXED_POINT_4:
      return 4;
    case QNN_DATATYPE_INT_8:
    case QNN_DATATYPE_UINT_8:
    case QNN_DATATYPE_FLOAT_8:
    case QNN_DATATYPE_SFIXED_POINT_8:
    case QNN_DATATYPE_UFIXED_POINT_8:
    case QNN_DATATYPE_BOOL_8:
      return 8;
    case QNN_DATATYPE_INT_16:
    case QNN_DATATYPE_UINT_16:
    case QNN_DATATYPE_FLOAT_16:
    case QNN_DATATYPE_BFLOAT_16:
    case QNN_DATATYPE_SFIXED_POINT_16:
    case QNN_DATATYPE_UFIXED_POINT_16:
      return 16;
    case QNN_DATATYPE_INT_32:
    case QNN_DATATYPE_UINT_32:
    case QNN_DATATYPE_FLOAT_32:
    case QNN_DATATYPE_SFIXED_POINT_32:
    case QNN_DATATYPE_UFIXED_POINT_32:
      return 32;
    case QNN_DATATYPE_INT_64:
    case QNN_DATATYPE_UINT_64:
    case QNN_DATATYPE_FLOAT_64:
      return 64;
    default:
      fail("unsupported QNN tensor data type " + std::to_string(type));
  }
}

const char* tensorName(const Qnn_Tensor_t& tensor) {
  return tensor.v1.name;
}

std::uint32_t tensorRank(const Qnn_Tensor_t& tensor) {
  return tensor.v1.rank;
}

std::uint32_t* tensorDimensions(const Qnn_Tensor_t& tensor) {
  return tensor.v1.dimensions;
}

Qnn_DataType_t tensorDataType(const Qnn_Tensor_t& tensor) {
  return tensor.v1.dataType;
}

std::uint64_t tensorByteCount(const Qnn_Tensor_t& tensor) {
  if (tensor.v1.dataFormat != QNN_TENSOR_DATA_FORMAT_DENSE) {
    fail("persistent runner supports only dense tensors");
  }
  if (tensor.version != QNN_TENSOR_VERSION_1 && tensor.version != QNN_TENSOR_VERSION_2) {
    fail("unsupported QNN tensor version " + std::to_string(tensor.version));
  }
  if (tensor.version == QNN_TENSOR_VERSION_2 && tensor.v2.isDynamicDimensions != nullptr) {
    for (std::uint32_t index = 0; index < tensor.v2.rank; ++index) {
      if (tensor.v2.isDynamicDimensions[index] != 0) {
        fail("persistent runner does not accept dynamic tensor dimensions");
      }
    }
  }
  const std::uint32_t rank = tensorRank(tensor);
  const std::uint32_t* dimensions = tensorDimensions(tensor);
  if (rank > 0 && dimensions == nullptr) fail("tensor dimensions are missing");
  std::uint64_t elements = 1;
  for (std::uint32_t index = 0; index < rank; ++index) {
    if (dimensions[index] == 0 ||
        elements > std::numeric_limits<std::uint64_t>::max() / dimensions[index]) {
      fail("invalid tensor dimensions");
    }
    elements *= dimensions[index];
  }
  const std::uint64_t bits = dataTypeBits(tensorDataType(tensor));
  if (elements > (std::numeric_limits<std::uint64_t>::max() - 7) / bits) {
    fail("tensor byte count overflow");
  }
  return (elements * bits + 7) / 8;
}

struct GraphMetadata {
  const char* name = nullptr;
  std::uint32_t inputCount = 0;
  Qnn_Tensor_t* inputs = nullptr;
  std::uint32_t outputCount = 0;
  Qnn_Tensor_t* outputs = nullptr;
};

GraphMetadata graphMetadata(const QnnSystemContext_GraphInfo_t& graph) {
  switch (graph.version) {
    case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
      return {graph.graphInfoV1.graphName,
              graph.graphInfoV1.numGraphInputs,
              graph.graphInfoV1.graphInputs,
              graph.graphInfoV1.numGraphOutputs,
              graph.graphInfoV1.graphOutputs};
    case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
      return {graph.graphInfoV2.graphName,
              graph.graphInfoV2.numGraphInputs,
              graph.graphInfoV2.graphInputs,
              graph.graphInfoV2.numGraphOutputs,
              graph.graphInfoV2.graphOutputs};
    case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3:
      return {graph.graphInfoV3.graphName,
              graph.graphInfoV3.numGraphInputs,
              graph.graphInfoV3.graphInputs,
              graph.graphInfoV3.numGraphOutputs,
              graph.graphInfoV3.graphOutputs};
    default:
      fail("unsupported QNN graph metadata version " + std::to_string(graph.version));
  }
}

struct BinaryMetadata {
  const char* buildId = nullptr;
  Qnn_Version_t coreVersion = QNN_VERSION_INIT;
  std::uint32_t graphCount = 0;
  QnnSystemContext_GraphInfo_t* graphs = nullptr;
};

BinaryMetadata binaryMetadata(const QnnSystemContext_BinaryInfo_t& binary) {
  switch (binary.version) {
    case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
      return {binary.contextBinaryInfoV1.buildId,
              binary.contextBinaryInfoV1.coreApiVersion,
              binary.contextBinaryInfoV1.numGraphs,
              binary.contextBinaryInfoV1.graphs};
    case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
      return {binary.contextBinaryInfoV2.buildId,
              binary.contextBinaryInfoV2.coreApiVersion,
              binary.contextBinaryInfoV2.numGraphs,
              binary.contextBinaryInfoV2.graphs};
    case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
      return {binary.contextBinaryInfoV3.buildId,
              binary.contextBinaryInfoV3.coreApiVersion,
              binary.contextBinaryInfoV3.numGraphs,
              binary.contextBinaryInfoV3.graphs};
    default:
      fail("unsupported QNN binary metadata version " + std::to_string(binary.version));
  }
}

class Runtime;

struct TensorBinding {
  explicit TensorBinding(const Qnn_Tensor_t& reflected) : tensor(reflected) {
    const char* reflectedName = tensorName(tensor);
    if (reflectedName == nullptr || reflectedName[0] == '\0') fail("QNN tensor has no name");
    name = reflectedName;
    const std::uint64_t size = tensorByteCount(tensor);
    if (size == 0 || size > std::numeric_limits<std::uint32_t>::max()) {
      fail("QNN tensor is too large for a client buffer: " + name);
    }
    buffer.resize(static_cast<std::size_t>(size));
    bind();
  }

  void bind() {
    tensor.v1.memType = QNN_TENSORMEMTYPE_RAW;
    tensor.v1.clientBuf = {buffer.data(), static_cast<std::uint32_t>(buffer.size())};
  }

  Qnn_Tensor_t tensor = QNN_TENSOR_INIT;
  std::string name;
  std::vector<std::uint8_t> buffer;
};

class Session {
 public:
  Session(Runtime* runtime,
          const std::string& path,
          const std::string& sha256,
          Qnn_SignalHandle_t signal);
  ~Session();

  Session(const Session&) = delete;
  Session& operator=(const Session&) = delete;

  Runtime* runtime;
  std::string path;
  std::string sha256;
  QnnSystemContext_Handle_t systemContext = nullptr;
  Qnn_ContextHandle_t context = nullptr;
  Qnn_GraphHandle_t graph = nullptr;
  std::string graphName;
  std::vector<TensorBinding> inputs;
  std::vector<TensorBinding> outputs;
  std::uint64_t lastUse = 0;
};

using GetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t*** providers,
                                             std::uint32_t* count);
using GetSystemProvidersFn = Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t*** providers,
                                                   std::uint32_t* count);

class Runtime {
 public:
  Runtime(const std::string& backendPath,
          const std::string& systemPath,
          const std::string& dspRoot,
          const std::string& nativeRoot) {
    try {
    const std::string adspPath = dspRoot + ";" + nativeRoot +
        ";/system/vendor/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;"
        "/vendor/lib64/hw;/vendor/npu/lib";
    if (setenv("ADSP_LIBRARY_PATH", adspPath.c_str(), 1) != 0) {
      fail("unable to set ADSP_LIBRARY_PATH");
    }

    backendLibrary = dlopen(backendPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (backendLibrary == nullptr) {
      fail(std::string("unable to load QAIRT backend: ") + dlerror());
    }
    systemLibrary = dlopen(systemPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (systemLibrary == nullptr) {
      fail(std::string("unable to load QAIRT system library: ") + dlerror());
    }

    const auto getProviders =
        resolve<GetProvidersFn>(backendLibrary, "QnnInterface_getProviders");
    const QnnInterface_t** providers = nullptr;
    std::uint32_t providerCount = 0;
    requireQnnSuccess(getProviders(&providers, &providerCount), "QnnInterface_getProviders");
    if (providers == nullptr || providerCount == 0) fail("QAIRT backend exposed no providers");
    const QnnInterface_t* selected = nullptr;
    for (std::uint32_t index = 0; index < providerCount; ++index) {
      const QnnInterface_t* candidate = providers[index];
      if (candidate != nullptr && candidate->apiVersion.coreApiVersion.major == 2 &&
          candidate->apiVersion.coreApiVersion.minor == 34 &&
          candidate->apiVersion.coreApiVersion.patch == 0) {
        selected = candidate;
        break;
      }
    }
    if (selected == nullptr) fail("QAIRT core API 2.34.0 provider is unavailable");
    qnn = selected->QNN_INTERFACE_VER_NAME;
    backendId = selected->backendId;
    if (backendId != QNN_BACKEND_ID_HTP) fail("selected QAIRT provider is not HTP");
    if (qnn.backendCreate == nullptr || qnn.backendFree == nullptr ||
        qnn.contextCreateFromBinary == nullptr || qnn.contextFree == nullptr ||
        qnn.graphRetrieve == nullptr || qnn.graphExecute == nullptr ||
        qnn.backendGetBuildId == nullptr || qnn.signalCreate == nullptr ||
        qnn.signalTrigger == nullptr || qnn.signalFree == nullptr) {
      fail("QAIRT 2.45 provider is missing required persistent APIs");
    }

    const char* build = nullptr;
    requireQnnSuccess(qnn.backendGetBuildId(&build), "QnnBackend_getBuildId");
    buildId = build == nullptr ? "" : build;
    if (buildId.find(kExpectedQairt) == std::string::npos) {
      fail("unexpected QAIRT backend build id: " + buildId);
    }
    requireQnnSuccess(qnn.backendCreate(nullptr, nullptr, &backend), "QnnBackend_create");

    if (qnn.deviceCreate != nullptr) {
      const Qnn_ErrorHandle_t deviceStatus = qnn.deviceCreate(nullptr, nullptr, &device);
      if (deviceStatus != QNN_SUCCESS && deviceStatus != QNN_DEVICE_ERROR_UNSUPPORTED_FEATURE) {
        fail("QnnDevice_create failed with status " + std::to_string(deviceStatus));
      }
      deviceCreated = deviceStatus == QNN_SUCCESS;
    }

    ensurePowerVote();

    Qnn_SignalHandle_t testSignal = nullptr;
    const Qnn_ErrorHandle_t signalStatus = createAbortSignal(&testSignal);
    signalSupported = signalStatus == QNN_SUCCESS && testSignal != nullptr;
    if (testSignal != nullptr) qnn.signalFree(testSignal);

    const auto getSystemProviders = resolve<GetSystemProvidersFn>(
        systemLibrary, "QnnSystemInterface_getProviders");
    const QnnSystemInterface_t** systemProviders = nullptr;
    std::uint32_t systemProviderCount = 0;
    requireQnnSuccess(getSystemProviders(&systemProviders, &systemProviderCount),
                      "QnnSystemInterface_getProviders");
    if (systemProviders == nullptr || systemProviderCount == 0) {
      fail("QAIRT System exposed no providers");
    }
    const QnnSystemInterface_t* selectedSystem = nullptr;
    for (std::uint32_t index = 0; index < systemProviderCount; ++index) {
      const QnnSystemInterface_t* candidate = systemProviders[index];
      if (candidate != nullptr && candidate->systemApiVersion.major == 1 &&
          candidate->systemApiVersion.minor == 9 && candidate->systemApiVersion.patch == 0) {
        selectedSystem = candidate;
        break;
      }
    }
    if (selectedSystem == nullptr) fail("QAIRT System API 1.9.0 provider is unavailable");
    system = selectedSystem->QNN_SYSTEM_INTERFACE_VER_NAME;
    if (system.systemContextCreate == nullptr || system.systemContextGetBinaryInfo == nullptr ||
        system.systemContextFree == nullptr) {
      fail("QAIRT System provider is missing context reflection APIs");
    }
    } catch (...) {
      cleanup();
      throw;
    }
  }

  ~Runtime() { cleanup(); }

  void cleanup() {
    sessions.clear();
    releasePowerVote();
    if (deviceCreated && device != nullptr && qnn.deviceFree != nullptr) {
      qnn.deviceFree(device);
    }
    device = nullptr;
    deviceCreated = false;
    if (backend != nullptr && qnn.backendFree != nullptr) qnn.backendFree(backend);
    backend = nullptr;
    if (systemLibrary != nullptr) dlclose(systemLibrary);
    systemLibrary = nullptr;
    if (backendLibrary != nullptr) dlclose(backendLibrary);
    backendLibrary = nullptr;
  }

  Session* getOrLoad(const std::string& path,
                     const std::string& sha256,
                     Qnn_SignalHandle_t signal) {
    ensurePowerVote();
    const std::string key = path + "\n" + sha256;
    auto existing = sessions.find(key);
    if (existing != sessions.end()) {
      existing->second->lastUse = ++useCounter;
      return existing->second.get();
    }
    if (sessions.size() >= kMaximumResidentContexts) {
      const auto oldest = std::min_element(
          sessions.begin(), sessions.end(), [](const auto& left, const auto& right) {
            return left.second->lastUse < right.second->lastUse;
          });
      if (oldest != sessions.end()) sessions.erase(oldest);
    }
    auto session = std::make_unique<Session>(this, path, sha256, signal);
    session->lastUse = ++useCounter;
    Session* result = session.get();
    sessions.emplace(key, std::move(session));
    return result;
  }

  void evict(const std::string& path, const std::string& sha256) {
    sessions.erase(path + "\n" + sha256);
  }

  Qnn_ErrorHandle_t createAbortSignal(Qnn_SignalHandle_t* result) const {
    QnnSignal_Config_t abortConfig = QNN_SIGNAL_CONFIG_INIT;
    abortConfig.option = QNN_SIGNAL_CONFIG_OPTION_ABORT;
    const QnnSignal_Config_t* configs[] = {&abortConfig, nullptr};
    return qnn.signalCreate(backend, configs, result);
  }

  void ensurePowerVote() {
    if (powerVoteActive) return;
    if (qnn.deviceGetInfrastructure == nullptr) {
      fail("QAIRT HTP performance infrastructure is unavailable");
    }
    QnnDevice_Infrastructure_t genericInfrastructure = nullptr;
    requireQnnSuccess(qnn.deviceGetInfrastructure(&genericInfrastructure),
                      "QnnDevice_getInfrastructure");
    if (genericInfrastructure == nullptr) fail("HTP performance infrastructure is null");
    auto* htpInfrastructure =
        reinterpret_cast<QnnHtpDevice_Infrastructure_t*>(genericInfrastructure);
    if (htpInfrastructure->infraType != QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) {
      fail("unexpected HTP infrastructure type");
    }
    performance = htpInfrastructure->perfInfra;
    if (performance.createPowerConfigId == nullptr || performance.setPowerConfig == nullptr ||
        performance.destroyPowerConfigId == nullptr) {
      fail("HTP performance infrastructure is incomplete");
    }
    requireQnnSuccess(performance.createPowerConfigId(0, 0, &powerConfigId),
                      "HTP createPowerConfigId");
    try {
      QnnHtpPerfInfrastructure_PowerConfig_t rpcLatency{};
      rpcLatency.option =
          QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY;
      rpcLatency.rpcControlLatencyConfig = 100;
      const QnnHtpPerfInfrastructure_PowerConfig_t* latencyConfigs[] = {
          &rpcLatency, nullptr};
      requireQnnSuccess(performance.setPowerConfig(powerConfigId, latencyConfigs),
                        "HTP set RPC control latency");

      QnnHtpPerfInfrastructure_PowerConfig_t rpcPolling{};
      rpcPolling.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_POLLING_TIME;
      rpcPolling.rpcPollingTimeConfig = 9999;
      const QnnHtpPerfInfrastructure_PowerConfig_t* pollingConfigs[] = {
          &rpcPolling, nullptr};
      requireQnnSuccess(performance.setPowerConfig(powerConfigId, pollingConfigs),
                        "HTP set RPC polling time");

      QnnHtpPerfInfrastructure_PowerConfig_t burst{};
      burst.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
      burst.dcvsV3Config.contextId = powerConfigId;
      burst.dcvsV3Config.setDcvsEnable = 1;
      burst.dcvsV3Config.dcvsEnable = 0;
      burst.dcvsV3Config.powerMode =
          QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
      burst.dcvsV3Config.setSleepLatency = 1;
      burst.dcvsV3Config.sleepLatency = 40;
      burst.dcvsV3Config.setSleepDisable = 1;
      burst.dcvsV3Config.sleepDisable = 1;
      burst.dcvsV3Config.setBusParams = 1;
      burst.dcvsV3Config.busVoltageCornerMin =
          DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
      burst.dcvsV3Config.busVoltageCornerTarget =
          DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
      burst.dcvsV3Config.busVoltageCornerMax =
          DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
      burst.dcvsV3Config.setCoreParams = 1;
      burst.dcvsV3Config.coreVoltageCornerMin =
          DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
      burst.dcvsV3Config.coreVoltageCornerTarget =
          DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
      burst.dcvsV3Config.coreVoltageCornerMax =
          DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
      const QnnHtpPerfInfrastructure_PowerConfig_t* burstConfigs[] = {&burst, nullptr};
      requireQnnSuccess(performance.setPowerConfig(powerConfigId, burstConfigs),
                        "HTP set burst power profile");
      powerVoteActive = true;
    } catch (...) {
      performance.destroyPowerConfigId(powerConfigId);
      powerConfigId = 0;
      throw;
    }
  }

  void releasePowerVote() {
    if (!powerVoteActive) return;
    const Qnn_ErrorHandle_t status = performance.destroyPowerConfigId(powerConfigId);
    if (status != QNN_SUCCESS) {
      logError("HTP destroyPowerConfigId failed with status " + std::to_string(status));
    }
    powerVoteActive = false;
    powerConfigId = 0;
  }

  void* backendLibrary = nullptr;
  void* systemLibrary = nullptr;
  QNN_INTERFACE_VER_TYPE qnn = QNN_INTERFACE_VER_TYPE_INIT;
  QNN_SYSTEM_INTERFACE_VER_TYPE system = QNN_SYSTEM_INTERFACE_VER_TYPE_INIT;
  std::uint32_t backendId = 0;
  Qnn_BackendHandle_t backend = nullptr;
  Qnn_DeviceHandle_t device = nullptr;
  bool deviceCreated = false;
  bool signalSupported = false;
  std::string buildId;
  QnnHtpDevice_PerfInfrastructure_t performance =
      QNN_HTP_DEVICE_PERF_INFRASTRUCTURE_INIT;
  std::uint32_t powerConfigId = 0;
  bool powerVoteActive = false;
  std::unordered_map<std::string, std::unique_ptr<Session>> sessions;
  std::uint64_t useCounter = 0;
};

Session::Session(Runtime* runtimeValue,
                 const std::string& contextPath,
                 const std::string& contextSha256,
                 Qnn_SignalHandle_t signal)
    : runtime(runtimeValue), path(contextPath), sha256(contextSha256) {
  if (runtime == nullptr) fail("persistent session has no runtime");
  try {
  const std::vector<std::uint8_t> binary = readFile(path);
  requireQnnSuccess(runtime->system.systemContextCreate(&systemContext),
                    "QnnSystemContext_create");
  const QnnSystemContext_BinaryInfo_t* reflected = nullptr;
  Qnn_ContextBinarySize_t reflectedSize = 0;
  requireQnnSuccess(runtime->system.systemContextGetBinaryInfo(
                        systemContext,
                        const_cast<std::uint8_t*>(binary.data()),
                        binary.size(),
                        &reflected,
                        &reflectedSize),
                    "QnnSystemContext_getBinaryInfo");
  if (reflected == nullptr) fail("context metadata is empty");
  const BinaryMetadata binaryInfo = binaryMetadata(*reflected);
  if (binaryInfo.buildId == nullptr ||
      std::string(binaryInfo.buildId).find(kExpectedQairt) == std::string::npos) {
    fail("context binary build id is not pinned to QAIRT 2.45");
  }
  if (binaryInfo.coreVersion.major != 2 || binaryInfo.coreVersion.minor != 34 ||
      binaryInfo.coreVersion.patch != 0) {
    fail("context binary was not produced with QNN core API 2.34.0");
  }
  if (binaryInfo.graphCount != 1 || binaryInfo.graphs == nullptr) {
    fail("persistent runner requires exactly one graph per context");
  }
  const GraphMetadata graphInfo = graphMetadata(binaryInfo.graphs[0]);
  if (graphInfo.name == nullptr || graphInfo.name[0] == '\0') fail("context graph has no name");
  if ((graphInfo.inputCount > 0 && graphInfo.inputs == nullptr) ||
      (graphInfo.outputCount > 0 && graphInfo.outputs == nullptr)) {
    fail("context graph tensor metadata is incomplete");
  }

  Qnn_ErrorHandle_t createStatus = QNN_SUCCESS;
  if (runtime->qnn.contextCreateFromBinaryWithSignal != nullptr && signal != nullptr) {
    createStatus = runtime->qnn.contextCreateFromBinaryWithSignal(runtime->backend,
                                                                  runtime->device,
                                                                  nullptr,
                                                                  binary.data(),
                                                                  binary.size(),
                                                                  &context,
                                                                  nullptr,
                                                                  signal);
  } else {
    createStatus = runtime->qnn.contextCreateFromBinary(runtime->backend,
                                                        runtime->device,
                                                        nullptr,
                                                        binary.data(),
                                                        binary.size(),
                                                        &context,
                                                        nullptr);
  }
  requireQnnSuccess(createStatus, "QnnContext_createFromBinary");
  graphName = graphInfo.name;
  requireQnnSuccess(runtime->qnn.graphRetrieve(context, graphName.c_str(), &graph),
                    "QnnGraph_retrieve");
  inputs.reserve(graphInfo.inputCount);
  for (std::uint32_t index = 0; index < graphInfo.inputCount; ++index) {
    inputs.emplace_back(graphInfo.inputs[index]);
  }
  outputs.reserve(graphInfo.outputCount);
  for (std::uint32_t index = 0; index < graphInfo.outputCount; ++index) {
    outputs.emplace_back(graphInfo.outputs[index]);
  }
  logInfo("loaded persistent context graph=" + graphName + " inputs=" +
          std::to_string(inputs.size()) + " outputs=" + std::to_string(outputs.size()));
  } catch (...) {
    if (context != nullptr && runtime->qnn.contextFree != nullptr) {
      runtime->qnn.contextFree(context, nullptr);
      context = nullptr;
    }
    if (systemContext != nullptr && runtime->system.systemContextFree != nullptr) {
      runtime->system.systemContextFree(systemContext);
      systemContext = nullptr;
    }
    throw;
  }
}

Session::~Session() {
  outputs.clear();
  inputs.clear();
  if (context != nullptr && runtime != nullptr && runtime->qnn.contextFree != nullptr) {
    runtime->qnn.contextFree(context, nullptr);
    context = nullptr;
  }
  if (systemContext != nullptr && runtime != nullptr &&
      runtime->system.systemContextFree != nullptr) {
    runtime->system.systemContextFree(systemContext);
    systemContext = nullptr;
  }
}

std::mutex gInitializationMutex;
std::mutex gExecutionMutex;
std::mutex gCancellationMutex;
std::unique_ptr<Runtime> gRuntime;
std::unordered_map<std::int64_t, Qnn_SignalHandle_t> gActiveSignals;
std::unordered_set<std::int64_t> gPreCancelled;
std::unordered_set<std::int64_t> gCancellationTriggered;
std::atomic<std::int64_t> gLastCompletedRequest{0};
std::atomic<std::int64_t> gGraphDispatchRequest{0};

void cancelRequest(std::int64_t requestId) {
  if (requestId <= 0 || requestId <= gLastCompletedRequest.load()) return;
  Qnn_SignalHandle_t signal = nullptr;
  Runtime* runtime = nullptr;
  bool shouldTrigger = false;
  {
    std::lock_guard<std::mutex> lock(gCancellationMutex);
    gPreCancelled.insert(requestId);
    shouldTrigger = gCancellationTriggered.insert(requestId).second;
    const auto active = gActiveSignals.find(requestId);
    if (active != gActiveSignals.end()) signal = active->second;
    runtime = gRuntime.get();
  }
  if (shouldTrigger && signal != nullptr && runtime != nullptr) runtime->qnn.signalTrigger(signal);
}

class SignalGuard {
 public:
  SignalGuard(Runtime* runtimeValue, std::int64_t requestIdValue)
      : runtime(runtimeValue), requestId(requestIdValue) {
    if (runtime == nullptr || !runtime->signalSupported) {
      fail("QNN cancellation signals are unavailable");
    }
    requireQnnSuccess(runtime->createAbortSignal(&signal), "QnnSignal_create(abort)");
    bool cancelledBeforeRegistration = false;
    {
      std::lock_guard<std::mutex> lock(gCancellationMutex);
      cancelledBeforeRegistration = gPreCancelled.count(requestId) > 0;
      gActiveSignals.emplace(requestId, signal);
    }
    cancellationObserved = cancelledBeforeRegistration;
    if (cancelledBeforeRegistration) runtime->qnn.signalTrigger(signal);
  }

  ~SignalGuard() {
    {
      std::lock_guard<std::mutex> lock(gCancellationMutex);
      gActiveSignals.erase(requestId);
      gPreCancelled.erase(requestId);
      gCancellationTriggered.erase(requestId);
    }
    std::int64_t completed = gLastCompletedRequest.load();
    while (completed < requestId &&
           !gLastCompletedRequest.compare_exchange_weak(completed, requestId)) {
    }
    if (signal != nullptr && runtime != nullptr) runtime->qnn.signalFree(signal);
  }

  bool wasCancelled() const {
    std::lock_guard<std::mutex> lock(gCancellationMutex);
    return cancellationObserved || gPreCancelled.count(requestId) > 0;
  }

  Runtime* runtime;
  std::int64_t requestId;
  Qnn_SignalHandle_t signal = nullptr;
  bool cancellationObserved = false;
};

class GraphDispatchGuard {
 public:
  explicit GraphDispatchGuard(std::int64_t requestIdValue) : requestId(requestIdValue) {
    gGraphDispatchRequest.store(requestId);
  }

  ~GraphDispatchGuard() {
    std::int64_t expected = requestId;
    gGraphDispatchRequest.compare_exchange_strong(expected, 0);
  }

  std::int64_t requestId;
};

std::size_t contractIndex(const TensorBinding& binding,
                          const std::vector<std::string>& names,
                          bool output) {
  for (std::size_t index = 0; index < names.size(); ++index) {
    if (names[index] == binding.name) return index;
  }
  if (output && tensorDataType(binding.tensor) != QNN_DATATYPE_FLOAT_32) {
    const std::string nativeAlias = binding.name + "_native";
    for (std::size_t index = 0; index < names.size(); ++index) {
      if (names[index] == nativeAlias) return index;
    }
  }
  fail("contract does not contain reflected tensor " + binding.name);
}

void validateAndPopulateInputs(Session* session,
                               const std::vector<std::string>& names,
                               const std::vector<std::string>& paths,
                               const std::vector<std::uint64_t>& byteCounts) {
  if (session == nullptr || names.size() != paths.size() || names.size() != byteCounts.size() ||
      names.size() != session->inputs.size()) {
    fail("native input contract cardinality mismatch");
  }
  std::vector<bool> consumed(names.size(), false);
  for (TensorBinding& binding : session->inputs) {
    const std::size_t index = contractIndex(binding, names, false);
    if (consumed[index]) fail("native input contract contains duplicate tensor mapping");
    consumed[index] = true;
    if (binding.buffer.size() != byteCounts[index]) {
      fail("native input byte count mismatch for " + binding.name);
    }
    readFileExact(paths[index], &binding.buffer);
    binding.bind();
  }
}

void validateOutputs(Session* session,
                     const std::vector<std::string>& names,
                     const std::vector<std::uint64_t>& byteCounts,
                     std::vector<std::size_t>* contractIndices) {
  if (session == nullptr || contractIndices == nullptr || names.size() != byteCounts.size() ||
      names.size() != session->outputs.size()) {
    fail("native output contract cardinality mismatch");
  }
  contractIndices->clear();
  contractIndices->reserve(session->outputs.size());
  std::vector<bool> consumed(names.size(), false);
  for (TensorBinding& binding : session->outputs) {
    const std::size_t index = contractIndex(binding, names, true);
    if (consumed[index]) fail("native output contract contains duplicate tensor mapping");
    consumed[index] = true;
    if (binding.buffer.size() != byteCounts[index]) {
      fail("native output byte count mismatch for " + binding.name);
    }
    std::fill(binding.buffer.begin(), binding.buffer.end(), 0);
    binding.bind();
    contractIndices->push_back(index);
  }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_vivo_lpa_AndroidQnnPersistentNativeBridge_nativeInitialize(
    JNIEnv* env,
    jclass,
    jstring backendPath,
    jstring systemPath,
    jstring dspRoot,
    jstring nativeRoot) {
  try {
    std::lock_guard<std::mutex> initializationLock(gInitializationMutex);
    if (gRuntime == nullptr) {
      gRuntime = std::make_unique<Runtime>(fromJString(env, backendPath),
                                           fromJString(env, systemPath),
                                           fromJString(env, dspRoot),
                                           fromJString(env, nativeRoot));
    }
    const std::string info = std::string("qairt=") + kExpectedQairt + ";core_api=2.34.0;" +
        "system_api=1.9.0;backend_id=" + std::to_string(gRuntime->backendId) +
        ";signal=" + (gRuntime->signalSupported ? "1" : "0") + ";burst=1" +
        ";max_contexts=" + std::to_string(kMaximumResidentContexts);
    return env->NewStringUTF(info.c_str());
  } catch (const std::exception& error) {
    throwJava(env, "java/lang/IllegalStateException", error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_vivo_lpa_AndroidQnnPersistentNativeBridge_nativeExecute(
    JNIEnv* env,
    jclass,
    jlong requestId,
    jstring componentId,
    jstring contextPath,
    jstring contextSha256,
    jobjectArray inputNames,
    jobjectArray inputPaths,
    jlongArray inputByteCounts,
    jobjectArray outputNames,
    jobjectArray outputPaths,
    jlongArray outputByteCounts) {
  const auto startedAt = std::chrono::steady_clock::now();
  const std::int64_t nativeRequestId = static_cast<std::int64_t>(requestId);
  std::string nativeContextPath;
  std::string nativeContextSha256;
  try {
    const std::string nativeComponentId = fromJString(env, componentId);
    nativeContextPath = fromJString(env, contextPath);
    nativeContextSha256 = fromJString(env, contextSha256);
    const std::vector<std::string> nativeInputNames = fromJStringArray(env, inputNames);
    const std::vector<std::string> nativeInputPaths = fromJStringArray(env, inputPaths);
    const std::vector<std::uint64_t> nativeInputByteCounts =
        fromJLongArray(env, inputByteCounts);
    const std::vector<std::string> nativeOutputNames = fromJStringArray(env, outputNames);
    const std::vector<std::string> nativeOutputPaths = fromJStringArray(env, outputPaths);
    const std::vector<std::uint64_t> nativeOutputByteCounts =
        fromJLongArray(env, outputByteCounts);
    if (nativeContextSha256.size() != 64) fail("invalid context SHA-256 cache key");

    std::lock_guard<std::mutex> executionLock(gExecutionMutex);
    if (gRuntime == nullptr) fail("persistent QNN runtime is not initialized");
    SignalGuard signal(gRuntime.get(), nativeRequestId);
    if (signal.wasCancelled()) fail("QNN execution was cancelled before context loading");
    Session* session = gRuntime->getOrLoad(
        nativeContextPath, nativeContextSha256, signal.signal);
    if (signal.wasCancelled()) fail("QNN execution was cancelled after context loading");
    validateAndPopulateInputs(
        session, nativeInputNames, nativeInputPaths, nativeInputByteCounts);
    std::vector<std::size_t> outputContractIndices;
    validateOutputs(session, nativeOutputNames, nativeOutputByteCounts, &outputContractIndices);
    if (signal.wasCancelled()) fail("QNN execution was cancelled before graph dispatch");

    std::vector<Qnn_Tensor_t> executionInputs;
    executionInputs.reserve(session->inputs.size());
    for (TensorBinding& binding : session->inputs) executionInputs.push_back(binding.tensor);
    std::vector<Qnn_Tensor_t> executionOutputs;
    executionOutputs.reserve(session->outputs.size());
    for (TensorBinding& binding : session->outputs) executionOutputs.push_back(binding.tensor);
    Qnn_ErrorHandle_t executeStatus = QNN_SUCCESS;
    {
      GraphDispatchGuard dispatchGuard(nativeRequestId);
      executeStatus = gRuntime->qnn.graphExecute(
          session->graph,
          executionInputs.empty() ? nullptr : executionInputs.data(),
          static_cast<std::uint32_t>(executionInputs.size()),
          executionOutputs.empty() ? nullptr : executionOutputs.data(),
          static_cast<std::uint32_t>(executionOutputs.size()),
          nullptr,
          signal.signal);
    }
    if (executeStatus != QNN_SUCCESS) {
      gRuntime->evict(nativeContextPath, nativeContextSha256);
      if (signal.wasCancelled()) {
        fail("QNN graph execution was cancelled");
      }
      fail("QnnGraph_execute failed with status " + std::to_string(executeStatus));
    }
    for (std::size_t index = 0; index < session->outputs.size(); ++index) {
      writeFileExact(nativeOutputPaths[outputContractIndices[index]],
                     session->outputs[index].buffer);
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - startedAt);
    logInfo("executed persistent component=" + nativeComponentId + " elapsed_ms=" +
            std::to_string(elapsed.count()));
    return static_cast<jlong>(elapsed.count());
  } catch (const std::exception& error) {
    bool cancelled = false;
    {
      std::lock_guard<std::mutex> lock(gCancellationMutex);
      cancelled = gPreCancelled.count(nativeRequestId) > 0;
    }
    if (gRuntime != nullptr && !nativeContextPath.empty() && !nativeContextSha256.empty()) {
      std::lock_guard<std::mutex> executionLock(gExecutionMutex);
      gRuntime->evict(nativeContextPath, nativeContextSha256);
    }
    throwJava(env,
              cancelled ? "java/lang/InterruptedException" : "java/lang/IllegalStateException",
              error.what());
    return -1;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_vivo_lpa_AndroidQnnPersistentNativeBridge_nativeCancel(JNIEnv*, jclass, jlong requestId) {
  cancelRequest(static_cast<std::int64_t>(requestId));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vivo_lpa_AndroidQnnPersistentNativeBridge_nativeCancelGraphInFlight(JNIEnv*, jclass) {
  const std::int64_t requestId = gGraphDispatchRequest.load();
  if (requestId == 0) return JNI_FALSE;
  cancelRequest(requestId);
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_vivo_lpa_AndroidQnnPersistentNativeBridge_nativeReleaseAll(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> executionLock(gExecutionMutex);
  if (gRuntime != nullptr) {
    gRuntime->sessions.clear();
    gRuntime->releasePowerVote();
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_vivo_lpa_AndroidQnnPersistentNativeBridge_nativeLoadedContextCount(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> executionLock(gExecutionMutex);
  return gRuntime == nullptr ? 0 : static_cast<jint>(gRuntime->sessions.size());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vivo_lpa_AndroidQnnPersistentNativeBridge_nativeIsGraphInFlight(JNIEnv*, jclass) {
  return gGraphDispatchRequest.load() == 0 ? JNI_FALSE : JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vivo_lpa_AndroidQnnPersistentNativeBridge_nativeIsPowerVoteActive(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> executionLock(gExecutionMutex);
  return gRuntime != nullptr && gRuntime->powerVoteActive ? JNI_TRUE : JNI_FALSE;
}
