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
        
        let topIds = Array(scores.keys.prefix(50))
        let inflatedTexts = db.getEventsByIds(eventIds: topIds)

        return scores.map { (id, score) in
            RetrievedHit(
                eventId: id,
                urn: urnMap[id] ?? "unknown",
                text: inflatedTexts[id] ?? textMap[id] ?? "",
                score: score
            )
        }
        .sorted { $0.score > $1.score }
    }
    
    private func runBinaryScan(queryBlob: Data) -> [(eventId: Int64, urn: String, text: String)] {
        guard queryBlob.count >= 64 else { return [] }
        let candidates = db.loadBinaryCandidates().filter { $0.blob.count >= 64 }
        guard !candidates.isEmpty else { return [] }
        
        let count = candidates.count
        var distances = [Int32](repeating: 0, count: count)
        
        // Flatten candidate blobs into contiguous 64-byte memory
        var flatMatrix = Data(capacity: count * 64)
        for c in candidates {
            flatMatrix.append(c.blob.prefix(64))
        }
        
        // Call native ARM NEON SIMD C++ bridge
        queryBlob.withUnsafeBytes { qPtr in
            flatMatrix.withUnsafeBytes { mPtr in
                distances.withUnsafeMutableBufferPointer { dPtr in
                    if let qAddress = qPtr.bindMemory(to: UInt8.self).baseAddress,
                       let mAddress = mPtr.bindMemory(to: UInt8.self).baseAddress,
                       let dAddress = dPtr.baseAddress {
                        batch_compute_distances_simd(qAddress, mAddress, Int32(count), dAddress)
                    }
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
