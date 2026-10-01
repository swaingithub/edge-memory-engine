cmake_minimum_required(VERSION 3.18.1)
project("native_hamming")

add_library(
        native_hamming
        SHARED
        src/main/cpp/hamming.cpp
        src/main/cpp/native_bridge.cpp)

# Directs Android NDK to compile with NEON SIMD
set_target_properties(native_hamming PROPERTIES COMPILE_FLAGS "-march=armv8-a+simd")

find_library(log-lib log)
target_link_libraries(native_hamming ${log-lib})
