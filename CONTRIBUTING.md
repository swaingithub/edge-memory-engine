# Contributing

Contributions are welcome! Please follow these guidelines:
1. For C++ SIMD changes: verify performance on `arm64-v8a` devices using `NativeHammingBenchmarkTest`.
2. Keep zero-media rules intact: do not add permissions for camera or raw disk access.
3. Ensure both Android (`BitPacker.kt`) and iOS (`BitPacker.swift`) maintain bitwise parity.
