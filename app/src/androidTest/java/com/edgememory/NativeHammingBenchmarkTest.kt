package com.edgememory

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edgememory.data.native.NativeHamming
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random
import kotlin.system.measureNanoTime

@RunWith(AndroidJUnit4::class)
class NativeHammingBenchmarkTest {

    @Test
    fun benchmark50kHammingScan() {
        val query = ByteArray(64) { Random.nextInt().toByte() }
        val candidateCount = 50_000
        val candidates = Array(candidateCount) { ByteArray(64) { Random.nextInt().toByte() } }
        val outDistances = IntArray(candidateCount)

        // Warmup
        NativeHamming.batchComputeDistances(query, candidates.take(1000).toTypedArray(), IntArray(1000))

        val elapsedNs = measureNanoTime {
            NativeHamming.batchComputeDistances(query, candidates, outDistances)
        }

        val elapsedMs = elapsedNs / 1_000_000.0
        println(">>> 50,000 512-bit Hamming vectors scanned in: $elapsedMs ms")
        assert(elapsedMs < 10.0) { "Performance regression: took $elapsedMs ms" }
    }
}
