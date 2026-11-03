cmake_minimum_required(VERSION 3.22.1)
project(lpa_qnn_persistent LANGUAGES CXX)

add_library(lpa_qnn_persistent SHARED qnn_persistent_jni.cpp)

target_include_directories(
    lpa_qnn_persistent
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/qairt-2.45/include/QNN
)

target_compile_features(lpa_qnn_persistent PRIVATE cxx_std_17)
target_compile_options(
    lpa_qnn_persistent
    PRIVATE
    -fexceptions
    -frtti
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

find_library(log_library log REQUIRED)
find_library(dl_library dl REQUIRED)
target_link_libraries(lpa_qnn_persistent PRIVATE ${log_library} ${dl_library})
