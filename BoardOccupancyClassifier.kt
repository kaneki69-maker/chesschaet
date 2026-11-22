package com.chesscheatpro.mobile

import kotlin.math.abs
import kotlin.math.max

/**
 * Estimates cell occupancy from local contrast against each cell's own square color.
 * This deliberately does NOT identify piece type or side: those require a trained model
 * or explicit user correction.
 */
object BoardOccupancyClassifier {
    data class Result(val occupied: BooleanArray, val confidence: FloatArray, val uncertain: BooleanArray)

    fun classify(
        width: Int,
        height: Int,
        pixels: IntArray,
        colorDistanceThreshold: Int = 62,
        occupancyThreshold: Float = 0.105f
    ): Result {
        require(width >= 128 && height >= 128) { "Изображение доски слишком маленькое" }
        require(pixels.size == width * height) { "Размер массива пикселей не совпадает с изображением" }
        require(colorDistanceThreshold in 1..765) { "Некорректный порог цвета" }
        require(occupancyThreshold in 0.01f..0.8f) { "Некорректный порог занятости" }

        val occupied = BooleanArray(64)
        val confidence = FloatArray(64)
        val uncertain = BooleanArray(64)
        for (row in 0 until 8) for (col in 0 until 8) {
            val x0 = col * width / 8
            val x1 = (col + 1) * width / 8
            val y0 = row * height / 8
            val y1 = (row + 1) * height / 8
            val cellW = x1 - x0
            val cellH = y1 - y0
            if (cellW < 8 || cellH < 8) continue

            // Estimate the square's own background from a thin inner border. This adapts
            // to alternating light/dark squares and most board color themes.
            var bgR = 0L; var bgG = 0L; var bgB = 0L; var bgN = 0
            val marginX = max(1, cellW / 9)
            val marginY = max(1, cellH / 9)
            for (sample in 0 until 12) {
                val t = (sample + 0.5f) / 12f
                val x = x0 + (marginX + (cellW - 2 * marginX) * t).toInt()
                val y = y0 + (marginY + (cellH - 2 * marginY) * t).toInt()
                val coords = arrayOf(
                    x to (y0 + marginY), x to (y1 - marginY - 1),
                    (x0 + marginX) to y, (x1 - marginX - 1) to y
                )
                for ((sx, sy) in coords) {
                    val color = pixels[sy.coerceIn(y0, y1 - 1) * width + sx.coerceIn(x0, x1 - 1)]
                    bgR += (color ushr 16 and 255).toLong()
                    bgG += (color ushr 8 and 255).toLong()
                    bgB += (color and 255).toLong()
                    bgN++
                }
            }
            val r = bgR.toFloat() / bgN
            val g = bgG.toFloat() / bgN
            val b = bgB.toFloat() / bgN

            // Sample the central 64% of the square, where chess pieces normally occupy.
            var foreground = 0
            var total = 0
            for (gy in 0 until 12) for (gx in 0 until 12) {
                val px = x0 + (cellW * (0.18f + gx * 0.64f / 11f)).toInt()
                val py = y0 + (cellH * (0.18f + gy * 0.64f / 11f)).toInt()
                val color = pixels[py.coerceIn(y0, y1 - 1) * width + px.coerceIn(x0, x1 - 1)]
                val dr = abs((color ushr 16 and 255) - r)
                val dg = abs((color ushr 8 and 255) - g)
                val db = abs((color and 255) - b)
                // Manhattan RGB distance is cheap and deterministic on Android devices.
                if (dr + dg + db >= colorDistanceThreshold) foreground++
                total++
            }
            val ratio = foreground.toFloat() / total
            val index = row * 8 + col
            confidence[index] = ratio
            occupied[index] = ratio >= occupancyThreshold
            // A narrow band around the decision boundary is not trustworthy enough
            // for move inference. Keep it visible to the UI instead of forcing a guess.
            uncertain[index] = abs(ratio - occupancyThreshold) < 0.035f
        }
        return Result(occupied, confidence, uncertain)
    }
}
