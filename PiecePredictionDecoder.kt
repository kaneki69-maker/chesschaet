package com.chesscheatpro.mobile

/** Decodes one model output into a chess piece label with a conservative confidence gate. */
object PiecePredictionDecoder {
    // Output order: empty, then White P/N/B/R/Q/K, then Black p/n/b/r/q/k.
    val labels: List<Char?> = listOf(null, 'P', 'N', 'B', 'R', 'Q', 'K', 'p', 'n', 'b', 'r', 'q', 'k')

    data class Prediction(val piece: Char?, val confidence: Float, val accepted: Boolean, val classIndex: Int)

    fun decode(raw: FloatArray, minimumConfidence: Float = 0.62f): Prediction {
        require(raw.size == labels.size) { "Модель должна возвращать ровно 13 классов" }
        require(minimumConfidence in 0f..1f) { "Некорректный порог уверенности" }
        require(raw.all { it.isFinite() }) { "Модель вернула NaN или бесконечное значение" }

        val sum = raw.sum()
        val probabilities = if (raw.all { it in 0f..1f } && sum in 0.98f..1.02f) {
            raw.map { it / sum }.toFloatArray()
        } else {
            val max = raw.maxOrNull() ?: 0f
            val exps = raw.map { kotlin.math.exp((it - max).coerceIn(-80f, 0f)) }
            val total = exps.sum().coerceAtLeast(1e-12f)
            exps.map { it / total }.toFloatArray()
        }
        val best = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
        val confidence = probabilities[best]
        return Prediction(labels[best], confidence, confidence >= minimumConfidence, best)
    }
}
