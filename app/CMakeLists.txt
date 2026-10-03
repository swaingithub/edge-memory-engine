cmake_minimum_required(VERSION 3.18.1)
project("native_hamming")

add_library(
        native_hamming
        SHARED
        src/main/cpp/hamming.cpp
        src/main/cpp/native_bridge.cpp)

# Apply ARM NEON flags only when compiling for arm64-v8a
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(native_hamming PRIVATE -O3 -march=armv8-a+simd)
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(native_hamming PRIVATE -O3 -mfpu=neon)
endif()

find_library(log-lib log)
target_link_libraries(native_hamming ${log-lib})
