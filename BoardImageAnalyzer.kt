package com.chesscheatpro.mobile

import android.graphics.Bitmap
import kotlin.math.min

/** Crops a square board region and estimates which cells contain foreground objects. */
object BoardImageAnalyzer {
    data class Crop(val left: Float = 0.08f, val top: Float = 0.12f, val size: Float = 0.84f)
    data class Result(val crop: Bitmap, val occupied: BooleanArray, val confidence: FloatArray, val uncertain: BooleanArray)

    fun analyze(source: Bitmap, crop: Crop): Result {
        require(source.width >= 160 && source.height >= 160) { "Скриншот слишком маленький" }
        val side = (min(source.width, source.height) * crop.size.coerceIn(0.2f, 1f)).toInt().coerceAtLeast(128)
        val left = (crop.left.coerceIn(0f, 1f) * source.width).toInt().coerceIn(0, source.width - side)
        val top = (crop.top.coerceIn(0f, 1f) * source.height).toInt().coerceIn(0, source.height - side)
        val actualSide = min(side, min(source.width - left, source.height - top)).coerceAtLeast(1)
        val board = Bitmap.createBitmap(source, left, top, actualSide, actualSide)
        val pixels = IntArray(board.width * board.height)
        board.getPixels(pixels, 0, board.width, 0, 0, board.width, board.height)
        val classification = BoardOccupancyClassifier.classify(board.width, board.height, pixels)
        return Result(board, classification.occupied, classification.confidence, classification.uncertain)
    }
}
