import Foundation
import CoreML

final class EmbeddingManager {
    static let shared = EmbeddingManager()
    
    private var model: BgeSmallEmbedding?
    private let tokenizer = WordPieceTokenizer()
    
    private init() {
        let config = MLModelConfiguration()
        config.computeUnits = .all
        if let primaryModel = try? BgeSmallEmbedding(configuration: config) {
            self.model = primaryModel
        } else {
            // Fallback to CPU + GPU if ANE compilation is refused in extension sandbox
            config.computeUnits = .cpuAndGPU
            self.model = try? BgeSmallEmbedding(configuration: config)
        }
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
            print("CoreML inference error: \(error)")
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
