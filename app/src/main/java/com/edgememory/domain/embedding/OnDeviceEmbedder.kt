package com.edgememory.domain.embedding

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Executes local text embedding inference using ONNX Runtime Mobile.
 * Configured for 512-dimension zero-centered embedding models (e.g. BGE-Small / Nomic).
 */
class OnDeviceEmbedder(
    private val context: Context,
    private val modelAssetPath: String = "bge_small_quant.onnx"
) : Closeable {

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private val sessionMutex = Mutex()
    private val tokenizer = SimpleWordPieceTokenizer(context, "vocab.txt")

    companion object {
        private const val MAX_SEQ_LENGTH = 128
        private const val ONNX_DIM = 384  // bge-small is 384 dimensions
        private const val PADDED_DIM = 512 // Required for 64-byte C++ parity
    }

    /**
     * Initializes the ONNX session with hardware acceleration options.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            if (ortSession != null) return@withLock

            val sessionOptions = OrtSession.SessionOptions().apply {
                // Try NNAPI for hardware acceleration; fallback to CPU automatically
                try {
                    addNnapi()
                } catch (e: Exception) {
                    setInterOpNumThreads(2)
                    setIntraOpNumThreads(2)
                }
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            val modelBytes = context.assets.open(modelAssetPath).use { it.readBytes() }
            ortSession = ortEnvironment.createSession(modelBytes, sessionOptions)
        }
    }

    /**
     * Converts raw text into a normalized 512-dimensional float vector.
     */
    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        // Automatically ensure initialized before embedding
        if (ortSession == null) {
            initialize()
        }
        val session = sessionMutex.withLock {
            ortSession ?: throw IllegalStateException("Failed to initialize ONNX Runtime session. Verify bge_small_quant.onnx exists in assets.")
        }

        // 1. Tokenize text into input_ids and attention_mask
        val tokens = tokenizer.tokenize(text, MAX_SEQ_LENGTH)
        val seqLength = tokens.inputIds.size.toLong()

        val shape = longArrayOf(1, seqLength)
        val inputIdsBuffer = LongBuffer.wrap(tokens.inputIds)
        val attentionMaskBuffer = LongBuffer.wrap(tokens.attentionMask)
        val tokenTypeIdsBuffer = LongBuffer.wrap(tokens.tokenTypeIds)

        val inputIdsTensor = OnnxTensor.createTensor(ortEnvironment, inputIdsBuffer, shape)
        val attentionMaskTensor = OnnxTensor.createTensor(ortEnvironment, attentionMaskBuffer, shape)
        val tokenTypeTensor = OnnxTensor.createTensor(ortEnvironment, tokenTypeIdsBuffer, shape)

        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attentionMaskTensor,
            "token_type_ids" to tokenTypeTensor
        )

        val rawLastHiddenState: Array<Array<FloatArray>>
        try {
            session.run(inputs).use { results ->
                // Output shape: [batch_size=1, seq_len, hidden_dim=384]
                @Suppress("UNCHECKED_CAST")
                rawLastHiddenState = results[0].value as Array<Array<FloatArray>>
            }
        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
            tokenTypeTensor.close()
        }

        // 2. Compute Mean Pooling across valid attention mask tokens
        val pooled = meanPooling(rawLastHiddenState[0], tokens.attentionMask)

        // 3. Apply L2 Unit Normalization (required for cosine and 1-bit zero-centering)
        val normalized = l2Normalize(pooled)
        
        // 4. Zero-Pad to 512 dimensions for C++ NEON compatibility
        val padded = FloatArray(PADDED_DIM) { 0.0f }
        System.arraycopy(normalized, 0, padded, 0, ONNX_DIM)
        return@withContext padded
    }

    private fun meanPooling(tokenEmbeddings: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        val result = FloatArray(ONNX_DIM) { 0.0f }
        var validTokenCount = 0.0f

        for (i in attentionMask.indices) {
            if (attentionMask[i] == 1L) {
                val embedding = tokenEmbeddings[i]
                for (d in 0 until ONNX_DIM) {
                    result[d] += embedding[d]
                }
                validTokenCount += 1.0f
            }
        }

        if (validTokenCount > 0.0f) {
            for (d in 0 until ONNX_DIM) {
                result[d] /= validTokenCount
            }
        }
        return result
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0.0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares)
        if (norm > 0.0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }

    override fun close() {
        ortSession?.close()
        ortSession = null
        ortEnvironment.close()
    }
}

/**
 * Tokenization holder structure for Transformer models.
 */
data class TokenResult(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
)

/**
 * Lightweight WordPiece Tokenizer reading from assets/vocab.txt.
 */
class SimpleWordPieceTokenizer(context: Context, vocabFile: String) {
    private val vocab = HashMap<String, Long>()

    init {
        context.assets.open(vocabFile).bufferedReader().useLines { lines ->
            var idx = 0L
            for (line in lines) {
                val token = line.trim()
                if (token.isNotEmpty()) {
                    vocab[token] = idx++
                }
            }
        }
    }

    fun tokenize(text: String, maxLength: Int): TokenResult {
        val clsId = vocab["[CLS]"] ?: 101L
        val sepId = vocab["[SEP]"] ?: 102L
        val unkId = vocab["[UNK]"] ?: 100L
        val padId = vocab["[PAD]"] ?: 0L

        val words = text.lowercase().split(Regex("\\s+"))
        val subTokens = mutableListOf<Long>()
        subTokens.add(clsId)

        for (word in words) {
            if (subTokens.size >= maxLength - 1) break
            val wordTokens = splitWordToPieces(word, unkId)
            for (wt in wordTokens) {
                if (subTokens.size < maxLength - 1) {
                    subTokens.add(wt)
                }
            }
        }
        subTokens.add(sepId)

        val totalLen = maxLength
        val inputIds = LongArray(totalLen) { padId }
        val attentionMask = LongArray(totalLen) { 0L }
        val tokenTypeIds = LongArray(totalLen) { 0L }

        for (i in subTokens.indices) {
            inputIds[i] = subTokens[i]
            attentionMask[i] = 1L
        }

        return TokenResult(inputIds, attentionMask, tokenTypeIds)
    }

    private fun splitWordToPieces(word: String, unkId: Long): List<Long> {
        val result = mutableListOf<Long>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matchedToken: Long? = null
            var matchedSubstr = ""

            while (start < end) {
                val sub = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                if (vocab.containsKey(sub)) {
                    matchedToken = vocab[sub]
                    matchedSubstr = sub
                    break
                }
                end--
            }

            if (matchedToken == null) {
                result.add(unkId)
                break
            } else {
                result.add(matchedToken)
                start += if (matchedSubstr.startsWith("##")) matchedSubstr.length - 2 else matchedSubstr.length
            }
        }
        return result
    }
}
