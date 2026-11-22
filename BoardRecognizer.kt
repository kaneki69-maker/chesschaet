package com.chesscheatpro.mobile

/**
 * Whole-board recognition: crop pixels -> 64 squares (piece letter or empty) + per-square confidence.
 * Uses the bundled [NativePieceNet]. Squares below [threshold] confidence are reported as uncertain
 * rather than guessed.
 */
class BoardRecognizer(private val net: NativePieceNet, val threshold: Float = DEFAULT_THRESHOLD) {
    data class Result(
        /** Display order (index 0 = top-left on screen); null = empty or unreadable (see [uncertain]). */
        val squares: List<Char?>,
        val confidence: FloatArray,
        val uncertain: BooleanArray
    ) {
        val uncertainCount: Int get() = uncertain.count { it }
    }

    fun recognize(width: Int, height: Int, pixels: IntArray): Result {
        val squares = PieceFeatureExtractor.resampleSquares(width, height, pixels)
        val pieces = MutableList<Char?>(64) { null }
        val confidence = FloatArray(64)
        val uncertain = BooleanArray(64)
        for (i in 0 until 64) {
            val probabilities = softmax(net.logits(PieceFeatureExtractor.features(squares[i])))
            val prediction = PiecePredictionDecoder.decode(probabilities, threshold)
            confidence[i] = prediction.confidence
            if (prediction.accepted) pieces[i] = prediction.piece else uncertain[i] = true
        }
        return Result(pieces, confidence, uncertain)
    }

    companion object {
        fun softmax(logits: FloatArray): FloatArray {
            val max = logits.max()
            val e = FloatArray(logits.size) { kotlin.math.exp(logits[it] - max) }
            val sum = e.sum()
            return FloatArray(e.size) { e[it] / sum }
        }

        const val DEFAULT_THRESHOLD = 0.70f
    }
}
