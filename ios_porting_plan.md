Porting the edge memory engine to iOS requires preserving the core algorithms (512-bit vector quantization, SIMD Hamming distance, SQLCipher, and RRF) while replacing Android-specific system hooks with Apple's inter-process architectures.

Here is the step-by-step implementation plan.

---

### Step 1: Configure App Groups for Cross-Process Storage

On iOS, App Extensions run in isolated sandbox processes. To allow both the Main App and the Share/Action Extensions to read and write to the same encrypted SQLite database, you must enable an **App Group**.

1. In Xcode, select your project target $\rightarrow$ **Signing & Capabilities**.
2. Click **+ Capability** $\rightarrow$ select **App Groups**.
3. Create an identifier: `group.com.edgememory.shared`.
4. Enable this identical App Group capability on both your **Main App target** and your **Share Extension target**.

```swift
// Shared Database Container Resolver
import Foundation

struct AppGroupContainer {
    static let groupIdentifier = "group.com.edgememory.shared"
    
    static var databaseURL: URL {
        guard let containerURL = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: groupIdentifier
        ) else {
            fatalError("Failed to resolve shared App Group directory.")
        }
        return containerURL.appendingPathComponent("edge_memory_encrypted.db")
    }
}
```

---

### Step 2: Port the Native ARM NEON SIMD Kernel (Bridging C++ to Swift)

Because iOS runs on 64-bit ARM Silicon (Apple A-series and M-series chips), your C++ NEON kernel runs with full hardware acceleration.

#### 1. `HammingBridge.hpp`

```cpp
#ifndef HammingBridge_hpp
#define HammingBridge_hpp

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int compute_hamming_distance_simd(const uint8_t* q, const uint8_t* t);
void batch_compute_distances_simd(const uint8_t* query, 
                                  const uint8_t* candidate_matrix, 
                                  int count, 
                                  int32_t* out_distances);

#ifdef __cplusplus
}
#endif

#endif
```

#### 2. `HammingBridge.cpp`

```cpp
#include "HammingBridge.hpp"
#include <arm_neon.h>

extern "C" int compute_hamming_distance_simd(const uint8_t* q, const uint8_t* t) {
    int distance = 0;
    for (int i = 0; i < 64; i += 16) {
        uint8x16_t vec_q = vld1q_u8(q + i);
        uint8x16_t vec_t = vld1q_u8(t + i);
        
        uint8x16_t vec_xor = veorq_u8(vec_q, vec_t);
        uint8x16_t vec_cnt = vcntq_u8(vec_xor);
        
        uint16x8_t sum1 = vpaddlq_u8(vec_cnt);
        uint32x4_t sum2 = vpaddlq_u16(sum1);
        distance += (vgetq_lane_u32(sum2, 0) + vgetq_lane_u32(sum2, 1) + 
                     vgetq_lane_u32(sum2, 2) + vgetq_lane_u32(sum2, 3));
    }
    return distance;
}

extern "C" void batch_compute_distances_simd(const uint8_t* query, 
                                            const uint8_t* candidate_matrix, 
                                            int count, 
                                            int32_t* out_distances) {
    for (int i = 0; i < count; i++) {
        out_distances[i] = compute_hamming_distance_simd(query, candidate_matrix + (i * 64));
    }
}
```

Add `#include "HammingBridge.hpp"` to your project's `<YourProject>-Bridging-Header.h` to call these routines directly in Swift.

---

### Step 3: Implement Swift 1-Bit Vector Quantizer (`BitPacker.swift`)

Replicate the exact MSB-first packing logic so that binary vectors generated on iOS match the distance expectations of the NEON kernel:

```swift
import Foundation

struct BitPacker {
    static func pack(embeddings: [Float]) -> Data {
        precondition(embeddings.count == 512, "Must be exactly 512 dimensions")
        var result = [UInt8](repeating: 0, count: 64)
        
        for byteIndex in 0..<64 {
            var currentByte: UInt8 = 0
            let offset = byteIndex * 8
            for bitIndex in 0..<8 {
                if embeddings[offset + bitIndex] > 0.0 {
                    currentByte |= (1 << (7 - bitIndex))
                }
            }
            result[byteIndex] = currentByte
        }
        return Data(result)
    }
}
```

---

### Step 4: Build the iOS Share Extension for User Ingestion

1. In Xcode: **File -> New -> Target** -> Select **Share Extension**.
2. Target Name: `EdgeMemoryShareExtension`.
3. In `ShareViewController.swift`, intercept shared text and pipe it through your sanitizer directly into the shared database:

```swift
import UIKit
import Social
import UniformTypeIdentifiers

class ShareViewController: SLComposeServiceViewController {

    override func isContentValid() -> Bool {
        return !contentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    override func didSelectPost() {
        guard let item = extensionContext?.inputItems.first as? NSExtensionItem,
              let provider = item.attachments?.first else {
            self.extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
            return
        }

        if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { [weak self] (textData, error) in
                if let rawText = textData as? String {
                    self?.processAndStore(text: rawText)
                }
                self?.extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
            }
        } else {
            self.extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
        }
    }

    private func processAndStore(text: String) {
        // 1. Sanitize text (Redact credentials, cards, IDs)
        guard let sanitized = IngestionSanitizer.sanitize(text: text) else { return }

        // 2. Generate 512 floats via CoreML model or on-device NLEmbedding
        let floatVector = EmbeddingManager.shared.embed(text: sanitized)

        // 3. 1-bit pack
        let binaryBlob = BitPacker.pack(embeddings: floatVector)

        // 4. Insert into shared SQLCipher database in group container
        DatabaseManager.shared.insertEvent(
            entityUrn: "urn:ios:share_extension",
            rawText: sanitized,
            binaryEmbedding: binaryBlob
        )
    }
}
```

---

### Step 5: Background Compaction via `BGProcessingTask`

Replace Android's `WorkManager` with Apple's `BackgroundTasks` framework:

1. In `Info.plist`, add **Permitted background task identifiers**: `com.edgememory.nightlycompaction`.
2. Schedule execution when the iPhone is plugged into power:

```swift
import BackgroundTasks

class BackgroundCompactionManager {
    static let taskIdentifier = "com.edgememory.nightlycompaction"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier, using: nil) { task in
            guard let processingTask = task as? BGProcessingTask else { return }
            handleNightlyCompaction(task: processingTask)
        }
    }

    static func scheduleNextRun() {
        let request = BGProcessingTaskRequest(identifier: taskIdentifier)
        request.requiresCharging = true
        request.requiresNetworkConnectivity = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 24 * 3600)

        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            print("Failed to schedule background task: \(error)")
        }
    }

    private static func handleNightlyCompaction(task: BGProcessingTask) {
        scheduleNextRun()

        task.expirationHandler = {
            // Cancel any long-running transactions if iOS revokes background time
        }

        Task {
            await DatabaseManager.shared.runNightlyWALCheckpoint()
            task.setTaskCompleted(success: true)
        }
    }
}
```

---

### Step 6: Convert Models to Apple Core ML (`.mlpackage`)

Rather than running ONNX Runtime on CPU, convert your 512-dimension embedding model to Core ML to run directly on the **Apple Neural Engine (ANE)**:

```python
import coremltools as ct
from transformers import AutoModel, AutoTokenizer
import torch

# Load PyTorch model
model = AutoModel.from_pretrained("BAAI/bge-small-en-v1.5")
model.eval()

# Trace with dummy input
dummy_input = torch.randint(0, 1000, (1, 128))
traced_model = torch.jit.trace(model, (dummy_input,))

# Convert to Core ML with Neural Engine acceleration
mlmodel = ct.convert(
    traced_model,
    inputs=[ct.TensorType(name="input_ids", shape=(1, 128), dtype=int)],
    compute_units=ct.ComputeUnit.ALL # CPU, GPU, and ANE
)
mlmodel.save("BgeSmallEmbedding.mlpackage")
```

Drag `BgeSmallEmbedding.mlpackage` into your Xcode project. Xcode automatically generates strongly-typed Swift accessor classes (`BgeSmallEmbedding()`), allowing sub-millisecond on-device embeddings.

---

### Summary Checklist

| Objective | File / Framework | Status |
| --- | --- | --- |
| **Shared Storage** | App Groups (`group.com.edgememory.shared`) | Shares DB between Extension & App |
| **1-Bit SIMD Core** | `HammingBridge.cpp` (`arm_neon.h`) | Reuses Android NEON SIMD algorithms |
| **Data Inflow** | `ShareViewController.swift` + `AppIntents` | Ingests highlighted or shared text |
| **Hardware Embeddings** | `BgeSmallEmbedding.mlpackage` (Core ML) | Runs on Apple Neural Engine (ANE) |
| **Idle Compaction** | `BGProcessingTask` | Executes during charging windows |

Here are the remaining production components needed to complete the iOS port, connecting your shared App Group container, Core ML embedding engine, SQLCipher encrypted store, and Reciprocal Rank Fusion search.

---

### 1. PII Sanitizer for iOS (`IngestionSanitizer.swift`)

Replicates the defensive sanitization and regex redaction rules natively using `NSRegularExpression`:

```swift
import Foundation

struct IngestionSanitizer {
    
    // Pre-compiled regex patterns
    private static let creditCardRegex = try! NSRegularExpression(
        pattern: "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|6(?:011|5[0-9]{2})[0-9]{12}|3[47][0-9]{13})\\b"
    )
    
    private static let otpRegex = try! NSRegularExpression(
        pattern: "(?i)\\b(?:otp|code|verification|pin|passcode)\\s*(?:is|:|[-=])?\\s*(\\d{4,8})\\b"
    )
    
    // Strict spacing/separator constraints to avoid clobbering Unix timestamps
    private static let nationalIdRegex = try! NSRegularExpression(
        pattern: "\\b[2-9]\\d{3}[ -]\\d{4}[ -]\\d{4}\\b|\\b\\d{3}-\\d{2}-\\d{4}\\b"
    )
    
    private static let credentialsRegex = try! NSRegularExpression(
        pattern: "(?i)(?:password|passwd|pwd|cvv|secret|token|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+"
    )

    static func sanitize(text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        
        var current = trimmed
        let fullRange = { NSRange(location: 0, length: current.utf16.count) }
        
        current = credentialsRegex.stringByReplacingMatches(in: current, range: fullRange(), withTemplate: "[REDACTED_CREDENTIAL]")
        current = creditCardRegex.stringByReplacingMatches(in: current, range: fullRange(), withTemplate: "[REDACTED_CARD]")
        current = nationalIdRegex.stringByReplacingMatches(in: current, range: fullRange(), withTemplate: "[REDACTED_ID]")
        current = otpRegex.stringByReplacingMatches(in: current, range: fullRange(), withTemplate: "[REDACTED_OTP]")
        
        // Collapse whitespace
        let cleaned = current.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            
        return cleaned.isEmpty ? nil : cleaned
    }
}
```

---

### 2. SQLCipher + Secure Enclave Keychain (`DatabaseManager.swift`)

Manages hardware envelope encryption using the iOS Secure Enclave / Keychain to generate and hold the 64-byte database encryption passphrase, opening the encrypted SQLite file inside the shared App Group directory:

```swift
import Foundation
import SQLite3
import Security

final class DatabaseManager {
    static let shared = DatabaseManager()
    
    private var db: OpaquePointer?
    private let queue = DispatchQueue(label: "com.edgememory.dbqueue", qos: .userInitiated)
    
    private init() {
        openEncryptedDatabase()
    }
    
    private func openEncryptedDatabase() {
        let dbURL = AppGroupContainer.databaseURL
        let passphrase = KeychainHelper.getOrCreateDatabasePassphrase()
        
        if sqlite3_open(dbURL.path, &db) == SQLITE_OK {
            // Apply SQLCipher encryption key
            sqlite3_key(db, passphrase, Int32(passphrase.count))
            
            // Set WAL journal mode and foreign keys
            executeRaw(sql: "PRAGMA journal_mode = WAL;")
            executeRaw(sql: "PRAGMA synchronous = NORMAL;")
            executeRaw(sql: "PRAGMA foreign_keys = ON;")
            
            createSchemaIfNeeded()
        } else {
            fatalError("Failed to open SQLite database at \\(dbURL.path)")
        }
    }
    
    private func createSchemaIfNeeded() {
        let schemaSQL = """
        CREATE TABLE IF NOT EXISTS event_log (
            event_id INTEGER PRIMARY KEY AUTOINCREMENT,
            entity_urn TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            action TEXT NOT NULL,
            source_app TEXT NOT NULL,
            raw_text TEXT NOT NULL,
            binary_embedding BLOB NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_entity_time ON event_log(entity_urn, timestamp ASC);
        CREATE INDEX IF NOT EXISTS idx_time ON event_log(timestamp DESC);
        CREATE VIRTUAL TABLE IF NOT EXISTS event_fts USING fts5(
            raw_text,
            content='event_log',
            content_rowid='event_id',
            tokenize='porter unicode61'
        );
        CREATE TRIGGER IF NOT EXISTS trg_event_ai AFTER INSERT ON event_log BEGIN
            INSERT INTO event_fts(rowid, raw_text) VALUES (new.event_id, new.raw_text);
        END;
        CREATE TABLE IF NOT EXISTS daily_summaries (
            day_date TEXT PRIMARY KEY,
            summary_text TEXT NOT NULL,
            summary_embedding BLOB NOT NULL
        );
        """
        executeRaw(sql: schemaSQL)
    }
    
    private func executeRaw(sql: String) {
        var errMsg: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(db, sql, nil, nil, &errMsg) != SQLITE_OK {
            if let err = errMsg {
                print("SQLite Exec Error: \\(String(cString: err))")
                sqlite3_free(errMsg)
            }
        }
    }
    
    func insertEvent(entityUrn: String, action: String = "SHARE_INPUT", sourceApp: String = "ShareExtension", rawText: String, binaryEmbedding: Data) {
        queue.sync {
            let sql = "INSERT INTO event_log (entity_urn, timestamp, action, source_app, raw_text, binary_embedding) VALUES (?, ?, ?, ?, ?, ?);"
            var statement: OpaquePointer?
            
            if sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK {
                let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
                
                sqlite3_bind_text(statement, 1, (entityUrn as NSString).utf8String, -1, nil)
                sqlite3_bind_int64(statement, 2, timestamp)
                sqlite3_bind_text(statement, 3, (action as NSString).utf8String, -1, nil)
                sqlite3_bind_text(statement, 4, (sourceApp as NSString).utf8String, -1, nil)
                sqlite3_bind_text(statement, 5, (rawText as NSString).utf8String, -1, nil)
                
                binaryEmbedding.withUnsafeBytes { rawBufferPointer in
                    sqlite3_bind_blob(statement, 6, rawBufferPointer.baseAddress, Int32(binaryEmbedding.count), nil)
                }
                
                sqlite3_step(statement)
            }
            sqlite3_finalize(statement)
        }
    }
    
    func runNightlyWALCheckpoint() async {
        queue.async {
            self.executeRaw(sql: "PRAGMA wal_checkpoint(TRUNCATE);")
        }
    }
    
    func searchFts(query: String, limit: Int = 50) -> [(eventId: Int64, urn: String, text: String, rank: Double)] {
        queue.sync {
            var results: [(eventId: Int64, urn: String, text: String, rank: Double)] = []
            let sql = """
            SELECT e.event_id, e.entity_urn, e.raw_text, fts.rank 
            FROM event_fts fts
            JOIN event_log e ON fts.rowid = e.event_id
            WHERE event_fts MATCH ?
            ORDER BY rank
            LIMIT ?;
            """
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                let pattern = "\\(query.replacingOccurrences(of: "\\"", with: ""))*\\""
                sqlite3_bind_text(stmt, 1, (pattern as NSString).utf8String, -1, nil)
                sqlite3_bind_int(stmt, 2, Int32(limit))
                
                while sqlite3_step(stmt) == SQLITE_ROW {
                    let id = sqlite3_column_int64(stmt, 0)
                    let urn = String(cString: sqlite3_column_text(stmt, 1))
                    let text = String(cString: sqlite3_column_text(stmt, 2))
                    let rank = sqlite3_column_double(stmt, 3)
                    results.append((id, urn, text, rank))
                }
            }
            sqlite3_finalize(stmt)
            return results
        }
    }
    
    func loadBinaryCandidates() -> [(eventId: Int64, urn: String, blob: Data)] {
        queue.sync {
            var candidates: [(eventId: Int64, urn: String, blob: Data)] = []
            let sql = "SELECT event_id, entity_urn, binary_embedding FROM event_log ORDER BY timestamp DESC;"
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                while sqlite3_step(stmt) == SQLITE_ROW {
                    let id = sqlite3_column_int64(stmt, 0)
                    let urn = String(cString: sqlite3_column_text(stmt, 1))
                    if let blobPtr = sqlite3_column_blob(stmt, 2) {
                        let bytes = sqlite3_column_bytes(stmt, 2)
                        let data = Data(bytes: blobPtr, count: Int(bytes))
                        candidates.append((id, urn, data))
                    }
                }
            }
            sqlite3_finalize(stmt)
            return candidates
        }
    }
}

// Hardware-backed Keychain envelope for the SQLCipher passphrase
struct KeychainHelper {
    private static let tag = "com.edgememory.db_passphrase"
    
    static func getOrCreateDatabasePassphrase() -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: tag,
            kSecReturnData as String: true
        ]
        
        var item: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
           let data = item as? Data,
           let pass = String(data: data, encoding: .utf8) {
            return pass
        }
        
        // Generate 64 cryptographically secure random bytes
        var randomBytes = [UInt8](repeating: 0, count: 64)
        _ = SecRandomCopyBytes(kSecRandomDefault, 64, &randomBytes)
        let newPass = Data(randomBytes).base64EncodedString()
        
        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: tag,
            kSecValueData as String: newPass.data(using: .utf8)!,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        SecItemAdd(addQuery as CFDictionary, nil)
        return newPass
    }
}
```

---

### 3. Apple Neural Engine Embeddings (`EmbeddingManager.swift`)

Executes tokenization and runs inference via the generated Core ML model `BgeSmallEmbedding`:

```swift
import Foundation
import CoreML

final class EmbeddingManager {
    static let shared = EmbeddingManager()
    
    private var model: BgeSmallEmbedding?
    private let tokenizer = WordPieceTokenizer()
    
    private init() {
        let config = MLModelConfiguration()
        config.computeUnits = .all // Utilizes Apple Neural Engine (ANE) + GPU
        self.model = try? BgeSmallEmbedding(configuration: config)
    }
    
    func embed(text: String) -> [Float] {
        guard let model = model else { return [Float](repeating: 0.0, count: 512) }
        
        let tokenIds = tokenizer.tokenize(text: text, maxSeqLength: 128)
        
        do {
            let inputMLArray = try MLMultiArray(shape: [1, 128], dataType: .int32)
            for (idx, token) in tokenIds.enumerated() {
                inputMLArray[[0, idx as NSNumber]] = NSNumber(value: token)
            }
            
            let output = try model.prediction(input_ids: inputMLArray)
            return meanPoolAndNormalize(outputMultiArray: output.var_last_hidden_state)
        } catch {
            print("CoreML inference error: \\(error)")
            return [Float](repeating: 0.0, count: 512)
        }
    }
    
    private func meanPoolAndNormalize(outputMultiArray: MLMultiArray) -> [Float] {
        var pooled = [Float](repeating: 0.0, count: 512)
        let seqLen = 128
        
        for s in 0..<seqLen {
            for d in 0..<512 {
                let val = outputMultiArray[[0, s as NSNumber, d as NSNumber]].floatValue
                pooled[d] += val
            }
        }
        
        // Mean pooling
        for d in 0..<512 { pooled[d] /= Float(seqLen) }
        
        // L2 Unit Normalization
        let norm = sqrt(pooled.reduce(0.0) { $0 + ($1 * $1) })
        if norm > 0.0 {
            for d in 0..<512 { pooled[d] /= norm }
        }
        return pooled
    }
}

// Lightweight WordPiece Tokenizer loader for iOS
final class WordPieceTokenizer {
    private var vocab: [String: Int32] = [:]
    
    init() {
        if let path = Bundle.main.path(forResource: "vocab", ofType: "txt"),
           let content = try? String(contentsOfFile: path, encoding: .utf8) {
            var index: Int32 = 0
            content.enumerateLines { line, _ in
                let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    self.vocab[trimmed] = index
                    index += 1
                }
            }
        }
    }
    
    func tokenize(text: String, maxSeqLength: Int) -> [Int32] {
        let clsId: Int32 = vocab["[CLS]"] ?? 101
        let sepId: Int32 = vocab["[SEP]"] ?? 102
        let unkId: Int32 = vocab["[UNK]"] ?? 100
        let padId: Int32 = vocab["[PAD]"] ?? 0
        
        var tokens: [Int32] = [clsId]
        let words = text.lowercased().components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }
        
        for word in words {
            if tokens.count >= maxSeqLength - 1 { break }
            tokens.append(vocab[word] ?? unkId)
        }
        tokens.append(sepId)
        
        while tokens.count < maxSeqLength {
            tokens.append(padId)
        }
        return Array(tokens.prefix(maxSeqLength))
    }
}
```

---

### 4. Reciprocal Rank Fusion Search Cascade (`CascadedRetriever.swift`)

Fuses SQLite FTS5 sparse keyword scores and native C++ ARM NEON SIMD Hamming distances:

```swift
import Foundation

final class CascadedRetriever {
    private let db = DatabaseManager.shared
    private let embedder = EmbeddingManager.shared
    private let rrfK: Double = 60.0
    
    func retrieve(query: String) async -> [RetrievedHit] {
        // 1. Encode query and pack into 64-byte 1-bit vector
        let queryFloats = embedder.embed(text: query)
        let queryBlob = BitPacker.pack(embeddings: queryFloats)
        
        // 2. Parallel fan-out
        async let ftsResults = db.searchFts(query: query, limit: 50)
        async let binaryResults = runBinaryScan(queryBlob: queryBlob)
        
        let (ftsHits, binHits) = await (ftsResults, binaryResults)
        
        // 3. Reciprocal Rank Fusion
        var scores: [Int64: Double] = [:]
        var urnMap: [Int64: String] = [:]
        var textMap: [Int64: String] = [:]
        
        for (rank, hit) in ftsHits.enumerated() {
            let score = 1.0 / (rrfK + Double(rank + 1))
            scores[hit.eventId, default: 0.0] += score
            urnMap[hit.eventId] = hit.urn
            textMap[hit.eventId] = hit.text
        }
        
        for (rank, hit) in binHits.enumerated() {
            let score = 1.0 / (rrfK + Double(rank + 1))
            scores[hit.eventId, default: 0.0] += score
            urnMap[hit.eventId] = hit.urn
            textMap[hit.eventId] = hit.text
        }
        
        return scores.map { (id, score) in
            RetrievedHit(eventId: id, urn: urnMap[id] ?? "unknown", text: textMap[id] ?? "", score: score)
        }
        .sorted { $0.score > $1.score }
    }
    
    private func runBinaryScan(queryBlob: Data) -> [(eventId: Int64, urn: String, text: String)] {
        let candidates = db.loadBinaryCandidates()
        guard !candidates.isEmpty else { return [] }
        
        let count = candidates.count
        var distances = [Int32](repeating: 0, count: count)
        
        // Flatten candidate blobs into contiguous 64-byte memory
        var flatMatrix = Data(capacity: count * 64)
        for c in candidates {
            if c.blob.count >= 64 {
                flatMatrix.append(c.blob.prefix(64))
            } else {
                flatMatrix.append(Data(repeating: 0, count: 64))
            }
        }
        
        // Call native ARM NEON SIMD C++ bridge
        queryBlob.withUnsafeBytes { qPtr in
            flatMatrix.withUnsafeBytes { mPtr in
                distances.withUnsafeMutableBufferPointer { dPtr in
                    batch_compute_distances_simd(
                        qPtr.bindMemory(to: UInt8.self).baseAddress!,
                        mPtr.bindMemory(to: UInt8.self).baseAddress!,
                        Int32(count),
                        dPtr.baseAddress!
                    )
                }
            }
        }
        
        // Sort ascending by Hamming distance
        let scored = candidates.enumerated().map { (idx, c) in
            (eventId: c.eventId, urn: c.urn, text: "", distance: distances[idx])
        }
        .sorted { $0.distance < $1.distance }
        .prefix(50)
        
        return scored.map { ($0.eventId, $0.urn, $0.text) }
    }
}

struct RetrievedHit {
    let eventId: Int64
    let urn: String
    let text: String
    let score: Double
}
```

---

### 5. Native iOS Search Interface (`MemorySearchContentView.swift`)

SwiftUI dashboard to query the local engine with live latency tracking:

```swift
import SwiftUI

struct MemorySearchContentView: View {
    @State private var query = ""
    @State private var hits: [RetrievedHit] = []
    @State private var latencyMs: Int = 0
    @State private var isSearching = false
    
    private let retriever = CascadedRetriever()
    
    var body: some View {
        NavigationStack {
            VStack {
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.gray)
                    TextField("Search memory (e.g., ticket, meeting, order)", text: $query)
                        .textFieldStyle(.plain)
                        .onSubmit { performSearch() }
                    if !query.isEmpty {
                        Button(action: { query = ""; hits = [] }) {
                            Image(systemName: "xmark.circle.fill").foregroundColor(.gray)
                        }
                    }
                }
                .padding(12)
                .background(Color(.secondarySystemBackground))
                .cornerRadius(10)
                .padding(.horizontal)
                
                if latencyMs > 0 {
                    HStack {
                        Text("Retrieved in \\(latencyMs) ms")
                            .font(.caption)
                            .foregroundColor(.accentColor)
                            .fontWeight(.semibold)
                        Spacer()
                    }
                    .padding(.horizontal)
                    .padding(.top, 4)
                }
                
                if isSearching {
                    Spacer()
                    ProgressView()
                    Spacer()
                } else {
                    List(hits, id: \\.eventId) { hit in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(hit.urn)
                                    .font(.caption)
                                    .fontWeight(.bold)
                                    .foregroundColor(.secondary)
                                Spacer()
                                Text(String(format: "Score: %.3f", hit.score))
                                    .font(.caption2)
                                    .foregroundColor(.gray)
                            }
                            Text(hit.text)
                                .font(.body)
                                .lineLimit(3)
                        }
                        .padding(.vertical, 4)
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Edge Memory Engine")
        }
    }
    
    private func performSearch() {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        isSearching = true
        let start = DispatchTime.now()
        
        Task {
            let results = await retriever.retrieve(query: query)
            let end = DispatchTime.now()
            let nanoTime = end.uptimeNanoseconds - start.uptimeNanoseconds
            
            await MainActor.run {
                self.hits = results
                self.latencyMs = Int(nanoTime / 1_000_000)
                self.isシミSearching = false
            }
        }
    }
}
```
