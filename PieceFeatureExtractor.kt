package com.chesscheatpro.mobile

import kotlin.math.max
import kotlin.math.min

/**
 * Turns an axis-aligned board crop into the 64 model inputs used by [NativePieceNet].
 *
 * Kotlin twin of `ml/features.py` (the code that produced the training data), so the network sees
 * exactly the same feature maps at inference time as during training:
 *  1. every square is resampled bilinearly to 32x32 RGB (uint8) with a small inner inset;
 *  2. channels 0-2 = (pixel - background) / 255 * 2 clipped to [-1, 1], where the background is the
 *     per-channel median of the square's 1-pixel border ring (so board theme/square colour cancels out);
 *  3. channel 3 = absolute luma in [-1, 1] (separates white from black pieces).
 * Squares are returned in *display* order (index 0 = top-left on screen).
 */
object PieceFeatureExtractor {
    const val SIZE = 32
    const val CHANNELS = 4
    const val INSET = 0.06f
    const val SQUARE_VALUES = CHANNELS * SIZE * SIZE

    /** Resamples all 64 squares to SIZE x SIZE RGB bytes (0..255), layout [y][x][c]. */
    fun resampleSquares(width: Int, height: Int, pixels: IntArray, inset: Float = INSET): Array<IntArray> {
        require(width >= 64 && height >= 64) { "Изображение доски слишком маленькое" }
        require(pixels.size == width * height) { "Размер массива пикселей не совпадает с изображением" }
        val cw = width / 8f
        val ch = height / 8f
        val span = 1f - 2f * inset
        return Array(64) { view ->
            val row = view / 8
            val col = view % 8
            val out = IntArray(SIZE * SIZE * 3)
            var k = 0
            for (oy in 0 until SIZE) {
                val sy = (row + inset) * ch + ((oy + 0.5f) / SIZE) * span * ch - 0.5f
                for (ox in 0 until SIZE) {
                    val sx = (col + inset) * cw + ((ox + 0.5f) / SIZE) * span * cw - 0.5f
                    k = sampleBilinear(width, height, pixels, sx, sy, out, k)
                }
            }
            out
        }
    }

    /** Feature maps for one resampled square, layout [channel][y][x]. */
    fun features(square: IntArray): FloatArray {
        require(square.size == SIZE * SIZE * 3) { "Ожидался квадрат ${SIZE}x$SIZE RGB" }
        val bg = FloatArray(3)
        val ring = IntArray(4 * SIZE - 4)
        for (c in 0..2) {
            var n = 0
            for (x in 0 until SIZE) { ring[n++] = square[(0 * SIZE + x) * 3 + c] }
            for (x in 0 until SIZE) { ring[n++] = square[((SIZE - 1) * SIZE + x) * 3 + c] }
            for (y in 1 until SIZE - 1) { ring[n++] = square[(y * SIZE) * 3 + c] }
            for (y in 1 until SIZE - 1) { ring[n++] = square[(y * SIZE + SIZE - 1) * 3 + c] }
            ring.sort()
            bg[c] = (ring[ring.size / 2 - 1] + ring[ring.size / 2]) / 2f // even count: numpy median
        }
        val out = FloatArray(SQUARE_VALUES)
        val plane = SIZE * SIZE
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val p = y * SIZE + x
            val r = square[p * 3]; val g = square[p * 3 + 1]; val b = square[p * 3 + 2]
            out[p] = ((r - bg[0]) / 255f * 2f).coerceIn(-1f, 1f)
            out[plane + p] = ((g - bg[1]) / 255f * 2f).coerceIn(-1f, 1f)
            out[2 * plane + p] = ((b - bg[2]) / 255f * 2f).coerceIn(-1f, 1f)
            out[3 * plane + p] = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 127.5f - 1f
        }
        return out
    }

    private fun sampleBilinear(width: Int, height: Int, pixels: IntArray, x: Float, y: Float, out: IntArray, start: Int): Int {
        val cx = x.coerceIn(0f, (width - 1).toFloat())
        val cy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt(); val y0 = cy.toInt()
        val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val fx = cx - x0; val fy = cy - y0
        val c00 = pixels[y0 * width + x0]; val c10 = pixels[y0 * width + x1]
        val c01 = pixels[y1 * width + x0]; val c11 = pixels[y1 * width + x1]
        var k = start
        for (shift in intArrayOf(16, 8, 0)) {
            val v00 = (c00 ushr shift) and 255; val v10 = (c10 ushr shift) and 255
            val v01 = (c01 ushr shift) and 255; val v11 = (c11 ushr shift) and 255
            val top = v00 + (v10 - v00) * fx
            val bottom = v01 + (v11 - v01) * fx
            out[k++] = max(0, min(255, (top + (bottom - top) * fy).toInt()))
        }
        return k
    }
}
