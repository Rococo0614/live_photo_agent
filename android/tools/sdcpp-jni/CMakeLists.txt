cmake_minimum_required(VERSION 3.22.1)
project(lpa_sdcpp_jni LANGUAGES C CXX)

if(NOT DEFINED SDCPP_SOURCE_DIR OR NOT EXISTS "${SDCPP_SOURCE_DIR}/include/stable-diffusion.h")
    message(FATAL_ERROR "SDCPP_SOURCE_DIR must point to pinned stable-diffusion.cpp sources")
endif()

set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_VULKAN ON CACHE BOOL "" FORCE)
set(SD_OPENCL OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory("${SDCPP_SOURCE_DIR}" stable-diffusion EXCLUDE_FROM_ALL)

add_library(lpa_sdcpp SHARED lpa_sdcpp_jni.cpp)
target_compile_features(lpa_sdcpp PRIVATE cxx_std_17)
target_compile_options(
    lpa_sdcpp
    PRIVATE
    -fexceptions
    -frtti
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)
target_link_options(lpa_sdcpp PRIVATE -Wl,--gc-sections)
target_include_directories(lpa_sdcpp PRIVATE "${SDCPP_SOURCE_DIR}/include")

find_library(log_library log REQUIRED)
target_link_libraries(lpa_sdcpp PRIVATE stable-diffusion ${log_library})
