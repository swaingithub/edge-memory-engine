package com.memory.data

/**
 * Packs 512-dimensional float embeddings into 64-byte binary representations.
 */
object BitPacker {

    /**
     * Quantizes a float array into a binary byte array.
     * Uses the rule: float > 0.0f ? 1 : 0
     * 
     * @param embeddings A float array of exactly 512 dimensions.
     * @return A ByteArray of exactly 64 bytes.
     */
    fun pack(embeddings: FloatArray): ByteArray {
        require(embeddings.size == 512) { "Embeddings must be exactly 512 dimensions" }
        
        val result = ByteArray(64)
        
        for (byteIndex in 0 until 64) {
            var currentByte = 0
            for (bitIndex in 0 until 8) {
                val floatIndex = byteIndex * 8 + bitIndex
                if (embeddings[floatIndex] > 0.0f) {
                    // Set the corresponding bit. 
                    // Using Little-endian bit packing: bit 0 is the first element.
                    currentByte = currentByte or (1 shl bitIndex)
                }
            }
            result[byteIndex] = currentByte.toByte()
        }
        
        return result
    }
}
