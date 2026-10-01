package com.edgememory.domain.quantization

/**
 * Pillar 7: BitPacker
 * Converts the 512 raw float output of the ONNX embedder into an exact 64-byte ByteArray.
 */
object BitPacker {
    
    /**
     * Quantizes float > 0.0f ? 1 : 0
     */
    fun pack(embeddings: FloatArray): ByteArray {
        require(embeddings.size == 512) { "Must be exactly 512 dimensions" }
        val result = ByteArray(64)
        for (byteIndex in 0 until 64) {
            var currentByte = 0
            for (bitIndex in 0 until 8) {
                if (embeddings[byteIndex * 8 + bitIndex] > 0.0f) {
                    currentByte = currentByte or (1 shl bitIndex)
                }
            }
            result[byteIndex] = currentByte.toByte()
        }
        return result
    }
}
