package com.chesscheatpro.mobile

/**
 * Converts an axis-aligned 8x8 board crop into a deterministic model-ready RGB tensor.
 *
 * Output layout: [squareIndex][y][x][channel], channels are R, G, B and values are
 * normalized to [-1, 1]. The small per-cell inset excludes grid lines and outer borders.
 * This is preprocessing only: it does not identify pieces or include a trained model.
 */
object BoardSquareTensorizer {
    data class Tensor(
        val values: FloatArray,
        val squareCount: Int,
        val outputSize: Int,
        val channels: Int = 3
    ) {
        fun square(index: Int): FloatArray {
            require(index in 0 until squareCount) { "Индекс клетки вне диапазона" }
            val length = outputSize * outputSize * channels
            return values.copyOfRange(index * length, (index + 1) * length)
        }
    }

    fun tensorize(
        width: Int,
        height: Int,
        pixels: IntArray,
        outputSize: Int = 64,
        inset: Float = 0.06f,
        flipped: Boolean = false
    ): Tensor {
        require(width >= 128 && height >= 128) { "Изображение доски слишком маленькое" }
        require(pixels.size == width * height) { "Размер массива пикселей не совпадает с изображением" }
        require(outputSize in 8..256) { "Размер тензора должен быть от 8 до 256" }
        require(inset in 0f..0.2f) { "Некорректный отступ клетки" }

        val values = FloatArray(64 * outputSize * outputSize * 3)
        val cellWidth = width / 8f
        val cellHeight = height / 8f
        val span = 1f - 2f * inset
        var offset = 0

        // Tensor order is canonical chess order (a8..h1). For a board shown from
        // Black's side, source cells are read in reverse display order.
        for (canonicalIndex in 0 until 64) {
            val viewIndex = if (flipped) 63 - canonicalIndex else canonicalIndex
            val row = viewIndex / 8
            val col = viewIndex % 8
            val xStart = (col + inset) * cellWidth
            val yStart = (row + inset) * cellHeight
            val xSpan = span * cellWidth
            val ySpan = span * cellHeight

            for (oy in 0 until outputSize) {
                val sy = yStart + ((oy + 0.5f) / outputSize) * ySpan - 0.5f
                for (ox in 0 until outputSize) {
                    val sx = xStart + ((ox + 0.5f) / outputSize) * xSpan - 0.5f
                    val color = sampleBilinear(width, height, pixels, sx, sy)
                    values[offset++] = (((color ushr 16) and 255) / 127.5f) - 1f
                    values[offset++] = (((color ushr 8) and 255) / 127.5f) - 1f
                    values[offset++] = ((color and 255) / 127.5f) - 1f
                }
            }
        }
        return Tensor(values, 64, outputSize)
    }

    private fun sampleBilinear(width: Int, height: Int, pixels: IntArray, x: Float, y: Float): Int {
        val clampedX = x.coerceIn(0f, (width - 1).toFloat())
        val clampedY = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = clampedX.toInt()
        val y0 = clampedY.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = clampedX - x0
        val fy = clampedY - y0
        val c00 = pixels[y0 * width + x0]
        val c10 = pixels[y0 * width + x1]
        val c01 = pixels[y1 * width + x0]
        val c11 = pixels[y1 * width + x1]
        var result = 0xFF000000.toInt()
        for (channel in 0..2) {
            val shift = 16 - channel * 8
            val v00 = (c00 ushr shift) and 255
            val v10 = (c10 ushr shift) and 255
            val v01 = (c01 ushr shift) and 255
            val v11 = (c11 ushr shift) and 255
            val top = v00 + (v10 - v00) * fx
            val bottom = v01 + (v11 - v01) * fx
            val value = (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
            result = result or (value shl shift)
        }
        return result
    }
}
