# Edge Memory Engine

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-green.svg)]()
[![SIMD](https://img.shields.io/badge/SIMD-ARM%20NEON-red.svg)]()

An on-device, offline episodic memory engine for mobile clients. Replaces cloud RAG architectures with 1-bit quantized vector retrieval, native ARM NEON Hamming distance evaluations, and SQLite FTS5 sparse indexing fused via Reciprocal Rank Fusion (RRF).

---

## Retrieval Architecture

```
            +-------------------------------+
            |      User Query / Stream      |
            +---------------+---------------+
                            |
            +---------------v---------------+
            |  512-dim Float Embedding (L2) |
            +-------+---------------+-------+
                    |               |
            [MSB BitPacker]         | [Tokenizer]
                    |               |
                    v               v

+------------------------------------+  +------------------------------------+
|  Dense Scan: 64-byte BitBLOBs      |  |  Sparse Scan: SQLite FTS5 (BM25)   |
|  - 4x 128-bit ARM NEON load (vld1q)|  |  - Content-rowid trigger sync      |
|  - veorq_u8 + vcntq_u8 + vpaddl    |  |  - Porter unicode61 tokenizer      |
|  - Top-50 via Hamming distance     |  |  - Top-50 via term frequency       |
+-----------------+------------------+  +-----------------+------------------+
|                                       |
+-----------------+---------------------+
v
+-----------------------------------+
|  Reciprocal Rank Fusion (k=60)    |
|  Hydrate entity timelines (ASC)   |
+-----------------+-----------------+
v
+-----------------------------------+
|  Quantized On-Device SLM Prompt   |
+-----------------------------------+
```

## Performance Benchmarks

Measured on physical hardware running release builds (`-O3 -march=armv8-a+simd`):

| Device | Metric | Throughput / Latency |
| :--- | :--- | :--- |
| **Google Pixel 8** (Tensor G3) | 50,000 vector Hamming scan | **1.72 ms** |
| **iPhone 15 Pro** (A17 Pro) | 50,000 vector Hamming scan | **1.14 ms** |
| **Storage Footprint** | 100,000 events (vector + index) | **6.4 MB** (vs 204.8 MB FP32) |
| **Retrieval Cascade** | Stage-1 NEON + Stage-2 FTS5 join | **< 3.4 ms** end-to-end |

## Repository Structure

```text
+-- app/                         # Android Client (Kotlin / Compose / NDK)
|   +-- src/main/cpp/            # ARM NEON kernels (hamming_neon.cpp, native_bridge.cpp)
|   +-- src/main/java/           # Clean Architecture (Data, Domain, Presentation)
|   \-- src/androidTest/        # Microbenchmarks (NativeHammingBenchmarkTest.kt)
+-- ios/                         # iOS Client (Swift / SwiftUI / CoreML)
|   +-- EdgeMemoryEngine/        # App Target (Core, Storage, Native, UI)
|   +-- EdgeMemoryShareExtension/# Sandboxed Share Ingestion target
|   \-- Podfile                  # SQLCipher with fts5 subspec
\-- scripts/                     # Quantization & model conversion tools
```

## Hardware & Runtime Dependencies

### Android
- Android API 26+ (arm64-v8a required for NEON intrinsics)
- SQLCipher 4.5.4 via Zetetic
- ONNX Runtime Mobile 1.17.0 (`bge-small-en-v1.5` quantized)
- MediaPipe Tasks GenAI 0.10.14 (optional SLM generation)

### iOS
- iOS 15.0+ (ARM64 Apple Silicon)
- SQLCipher with FTS5 enabled via CocoaPods
- Core ML Neural Engine package (`BgeSmallEmbedding.mlpackage`)
- Shared App Group (`group.com.edgememory.shared`)

## Building & Installation

### Android
```bash
# Verify assets
test -f app/src/main/assets/bge_small_quant.onnx || ./scripts/fetch_models.sh

# Run native benchmarks on connected device
./gradlew connectedAndroidTest

# Build debug APK
./gradlew assembleDebug
```

### iOS

```bash
cd ios
pod install
open EdgeMemoryEngine.xcworkspace
# Build with target 'Any iOS Device (arm64)'
```

## Security & Storage Invariants

* **Hardware-Isolated Passphrase:** Master encryption keys are generated inside the Android Keystore / iOS Secure Enclave using AES-256-GCM.
* **Append-Only Ledger:** Events cannot be updated or mutated in place. State changes append new rows with monotonically increasing timestamps to ensure consistent timeline replays.
* **Strict Privacy Ingestion:** Password fields (`isPassword == true`), media views (`ImageView`, `VideoView`), and blacklisted packages (cameras, authenticators, system galleries) are rejected prior to tokenization.
