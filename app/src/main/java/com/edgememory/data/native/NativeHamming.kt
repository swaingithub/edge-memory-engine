package com.edgememory.data.native

object NativeHamming {
    init {
        System.loadLibrary("native_hamming")
    }

    /**
     * Executes 128-bit ARM NEON SIMD XOR + POPCNT across two 64-byte packed BLOBs.
     * Returns an integer Hamming distance between 0 and 512.
     */
    external fun computeDistance(blobA: ByteArray, blobB: ByteArray): Int

    /**
     * Batch evaluates queryBlob against an array of candidate BLOBs directly in C++.
     * Minimizes JNI crossing overhead when scanning tens of thousands of rows.
     */
    external fun batchComputeDistances(
        queryBlob: ByteArray,
        candidateBlobs: Array<ByteArray>,
        outDistances: IntArray
    )
}
