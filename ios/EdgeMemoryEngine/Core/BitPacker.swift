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
