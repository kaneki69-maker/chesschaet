package com.chesscheatpro.mobile

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds an axis-aligned square 8x8 checkerboard in a screenshot.
 *
 * Stage 1 scans candidate squares (size and position) on a downsampled luminance image and scores how
 * strongly the two square parities differ in colour while each parity stays uniform (sampled near cell
 * corners, so pieces in the centre of squares hardly matter). Stage 2 refines the best candidates at full
 * resolution to single-pixel accuracy with a coordinate search over left/top/side. The exact edge matters:
 * the piece classifier is fed crops made from this rectangle.
 */
object BoardGeometryDetector {
    /** left/top are fractions of the image width/height; [size] is a fraction of min(width, height). */
    data class Detection(val left: Float, val top: Float, val size: Float, val score: Float) {
        /** Pixel rectangle for an image of the given dimensions. */
        fun toPixels(width: Int, height: Int): IntArray {
            val side = (size * min(width, height)).roundToInt().coerceIn(1, min(width, height))
            val l = (left * width).roundToInt().coerceIn(0, width - side)
            val t = (top * height).roundToInt().coerceIn(0, height - side)
            return intArrayOf(l, t, side)
        }
    }

    private const val MIN_SCORE = 14f
    private const val MIN_SIDE_PX = 128

    fun detect(width: Int, height: Int, pixels: IntArray): Detection? {
        require(width >= 160 && height >= 160) { "Скриншот слишком маленький" }
        require(pixels.size == width * height) { "Размер массива пикселей не совпадает с изображением" }

        val lum = IntArray(width * height) { i ->
            val c = pixels[i]
            (0.2126f * (c ushr 16 and 255) + 0.7152f * (c ushr 8 and 255) + 0.0722f * (c and 255)).toInt()
        }
        val minDim = min(width, height)

        // Stage 1: coarse scan on a grid; keep the best few distinct candidates.
        data class Cand(val left: Int, val top: Int, val side: Int, val score: Float)
        val coarse = ArrayList<Cand>()
        val sideStep = max(4, minDim / 48)
        var side = minDim
        while (side >= max(MIN_SIDE_PX, (minDim * 0.30f).toInt())) {
            val posStep = max(3, side / 40)
            var top = 0
            while (top <= height - side) {
                var left = 0
                while (left <= width - side) {
                    val s = score(width, lum, left, top, side)
                    if (s > 6f) coarse += Cand(left, top, side, s)
                    left += posStep
                }
                top += posStep
            }
            side -= sideStep
        }
        if (coarse.isEmpty()) return null
        coarse.sortByDescending { it.score }
        val seeds = ArrayList<Cand>()
        for (c in coarse) {
            if (seeds.size >= 6) break
            if (seeds.none { abs(it.left - c.left) < c.side / 8 && abs(it.top - c.top) < c.side / 8 && abs(it.side - c.side) < c.side / 8 }) seeds += c
        }

        // Stage 2: snap each seed to the grid lines. Between neighbouring squares of a checkerboard there is an
        // intensity edge on every grid line, so the true (left, top, side) maximise the edge energy summed over the
        // 9 vertical and 9 horizontal lines. This is exhaustive over a small neighbourhood and exact to ~1 px.
        var best: Cand? = null
        for (seed in seeds) {
            val r = snapToGrid(width, height, lum, seed.left, seed.top, seed.side)
            val sc = score(width, lum, r[0], r[1], r[2])
            if (best == null || sc > best.score) best = Cand(r[0], r[1], r[2], sc)
        }
        val b = best ?: return null
        if (b.score < MIN_SCORE) return null
        return Detection(b.left.toFloat() / width, b.top.toFloat() / height, b.side.toFloat() / minDim, b.score)
    }

    /** Returns (left, top, side) maximising grid-line edge energy near the given estimate. */
    private fun snapToGrid(width: Int, height: Int, lum: IntArray, l0: Int, t0: Int, s0: Int): IntArray {
        // Edge profiles along x (rows t0..t0+s0) and along y (columns l0..l0+s0): central differences.
        val gx = FloatArray(width)
        val gy = FloatArray(height)
        val y0 = t0.coerceIn(0, height - 1); val y1 = (t0 + s0).coerceIn(y0 + 1, height)
        val x0 = l0.coerceIn(0, width - 1); val x1 = (l0 + s0).coerceIn(x0 + 1, width)
        for (x in 1 until width - 1) {
            var sum = 0
            var y = y0
            while (y < y1) { sum += abs(lum[y * width + x + 1] - lum[y * width + x - 1]); y += 2 }
            gx[x] = sum.toFloat()
        }
        for (y in 1 until height - 1) {
            var sum = 0
            var x = x0
            while (x < x1) { sum += abs(lum[(y + 1) * width + x] - lum[(y - 1) * width + x]); x += 2 }
            gy[y] = sum.toFloat()
        }
        val span = max(8, s0 / 8)
        var bestScore = -1f
        var out = intArrayOf(l0, t0, s0)
        val sideRange = max(6, s0 / 12)
        for (side in (s0 - sideRange)..(s0 + sideRange)) {
            if (side < MIN_SIDE_PX || side > min(width, height)) continue
            var bestL = l0; var bestLScore = -1f
            for (l in (l0 - span)..(l0 + span)) {
                if (l < 0 || l + side > width) continue
                var e = 0f
                for (k in 0..8) { val x = (l + (k * side) / 8f).toInt().coerceIn(1, width - 2); e += gx[x] }
                if (e > bestLScore) { bestLScore = e; bestL = l }
            }
            var bestT = t0; var bestTScore = -1f
            for (t in (t0 - span)..(t0 + span)) {
                if (t < 0 || t + side > height) continue
                var e = 0f
                for (k in 0..8) { val y = (t + (k * side) / 8f).toInt().coerceIn(1, height - 2); e += gy[y] }
                if (e > bestTScore) { bestTScore = e; bestT = t }
            }
            val total = bestLScore + bestTScore
            if (total > bestScore) { bestScore = total; out = intArrayOf(bestL, bestT, side) }
        }
        return out
    }

    /**
     * Contrast between the two square parities minus within-parity spread, plus a term that rewards
     * the fraction of corner samples lying on the correct side of the mid threshold.
     */
    private fun score(width: Int, lum: IntArray, left: Int, top: Int, side: Int): Float {
        val cell = side / 8f
        val sum = FloatArray(2); val sq = FloatArray(2); val n = IntArray(2)
        val cellMeans = FloatArray(64)
        val offsets = floatArrayOf(0.15f, 0.85f)
        for (row in 0 until 8) for (col in 0 until 8) {
            var m = 0f
            for (oy in offsets) for (ox in offsets) {
                val x = (left + (col + ox) * cell).toInt().coerceIn(left, left + side - 1)
                val y = (top + (row + oy) * cell).toInt().coerceIn(top, top + side - 1)
                m += lum[y * width + x] / 4f
            }
            cellMeans[row * 8 + col] = m
            val g = (row + col) and 1
            sum[g] += m; sq[g] += m * m; n[g]++
        }
        val m0 = sum[0] / n[0]; val m1 = sum[1] / n[1]
        val sd = (sqrt(max(0f, sq[0] / n[0] - m0 * m0)) + sqrt(max(0f, sq[1] / n[1] - m1 * m1))) * 0.5f
        val contrast = abs(m0 - m1)
        // Consistency: how many squares fall on the right side of the midpoint.
        val mid = (m0 + m1) / 2f
        val evenBright = m0 > m1
        var ok = 0
        for (i in 0 until 64) {
            val g = ((i / 8) + (i % 8)) and 1
            val bright = cellMeans[i] > mid
            val expectBright = if (g == 0) evenBright else !evenBright
            if (bright == expectBright) ok++
        }
        val consistency = ok / 64f
        return (contrast - sd * 0.55f) * consistency * consistency
    }
}
