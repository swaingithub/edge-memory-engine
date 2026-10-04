package com.edgememory.domain.quantization

/** Packs 384-dim zero-centered float embeddings into 48-byte MSB bit vectors. */
object BitPacker {
    
    /**
     * Quantizes float > 0.0f ? 1 : 0
     */
    fun pack(embeddings: FloatArray): ByteArray {
        require(embeddings.size == 384) { "Must be exactly 384 dimensions" }
        val result = ByteArray(48)
        for (byteIndex in 0 until 48) {
            var currentByte = 0
            val offset = byteIndex * 8
            for (bitIndex in 0 until 8) {
                if (embeddings[offset + bitIndex] > 0.0f) {
                    currentByte = currentByte or (1 shl (7 - bitIndex))
                }
            }
            result[byteIndex] = currentByte.toByte()
        }
        return result
    }
}
