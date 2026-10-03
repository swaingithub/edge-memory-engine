# Contributing to Edge Memory Engine

## Development Guidelines

1. **Native Kernel Parity**
   - Any modifications to the native vector calculation must maintain bitwise parity between the C++ NEON kernel (`hamming_neon.cpp`), the JNI bridge (`native_bridge.cpp`), and the Apple Silicon bridge (`HammingBridge.cpp`).
   - Run `NativeHammingBenchmarkTest` on an `arm64-v8a` device before submitting SIMD changes. Regressions exceeding 2.5 ms per 50,000 vectors will not be merged.

2. **Zero-Media Boundary**
   - Do not add dependencies or permissions for image decoding, camera, microphone, or external shared storage (`READ_EXTERNAL_STORAGE`). This project is designed strictly as a text-only, low-overhead engine.

3. **Bit-Packing Endianness**
   - Both Kotlin and Swift bit-packers must pack vectors MSB-first:
     `byte |= (1 << (7 - bitIndex))`
   - Changing packing endianness invalidates existing databases and breaks cross-platform database portability.

## Pull Request Checklist
- [ ] Code compiles on both Android Studio (NDK 25+) and Xcode 15+.
- [ ] JNI elements cleanly release heap pointers via `JNI_ABORT` on read-only views.
- [ ] SQLite queries do not contain unescaped wildcards or unclosed quotes.
- [ ] Commits follow Conventional Commits (`feat:`, `fix:`, `perf:`, `refactor:`).
