package com.chesscheatpro.mobile

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Pure-Kotlin inference for the bundled piece classifier (weights trained by `ml/train.py`).
 *
 * Architecture (input 4x32x32): conv3x3(4->12)+ReLU+maxpool2, conv3x3(12->24)+ReLU+maxpool2,
 * conv3x3(24->32)+ReLU+maxpool2, flatten(512, channel-major), dense(64)+ReLU, dense(13).
 *
 * Weight file `piece_cnn.bin` (little endian): magic "CCNN", int32 version(=1), int32 layerCount(=5),
 * then for each layer w (float32) followed by b (float32). Conv weights are [out][in][3][3],
 * dense weights are [in][out].
 */
class NativePieceNet private constructor(
    private val convW: List<FloatArray>,
    private val convB: List<FloatArray>,
    private val denseW: List<FloatArray>,
    private val denseB: List<FloatArray>
) {
    /** Returns 13 raw logits in the order of [PiecePredictionDecoder.labels]. */
    fun logits(features: FloatArray): FloatArray {
        require(features.size == PieceFeatureExtractor.SQUARE_VALUES) { "Неверный размер входа сети" }
        var a = features
        var size = PieceFeatureExtractor.SIZE
        for (i in CONV.indices) {
            val (cin, cout) = CONV[i]
            a = convReluPool(a, cin, cout, size, convW[i], convB[i])
            size /= 2
        }
        var h = dense(a, denseW[0], denseB[0], FLAT, HIDDEN, relu = true)
        h = dense(h, denseW[1], denseB[1], HIDDEN, CLASSES, relu = false)
        return h
    }

    private fun convReluPool(x: FloatArray, cin: Int, cout: Int, size: Int, w: FloatArray, b: FloatArray): FloatArray {
        val half = size / 2
        val out = FloatArray(cout * half * half)
        val conv = FloatArray(size * size)
        for (o in 0 until cout) {
            java.util.Arrays.fill(conv, b[o])
            for (c in 0 until cin) {
                val wBase = (o * cin + c) * 9
                val xBase = c * size * size
                for (ky in 0..2) for (kx in 0..2) {
                    val weight = w[wBase + ky * 3 + kx]
                    if (weight == 0f) continue
                    val dy = ky - 1; val dx = kx - 1
                    val yStart = max(0, -dy); val yEnd = size - max(0, dy)
                    val xStart = max(0, -dx); val xEnd = size - max(0, dx)
                    for (y in yStart until yEnd) {
                        val srcRow = xBase + (y + dy) * size + dx
                        val dstRow = y * size
                        for (xx in xStart until xEnd) conv[dstRow + xx] += weight * x[srcRow + xx]
                    }
                }
            }
            for (y in 0 until half) for (xx in 0 until half) {
                val i0 = (2 * y) * size + 2 * xx
                var m = conv[i0]
                if (conv[i0 + 1] > m) m = conv[i0 + 1]
                if (conv[i0 + size] > m) m = conv[i0 + size]
                if (conv[i0 + size + 1] > m) m = conv[i0 + size + 1]
                out[(o * half + y) * half + xx] = if (m > 0f) m else 0f // ReLU commutes with max-pool
            }
        }
        return out
    }

    private fun dense(x: FloatArray, w: FloatArray, b: FloatArray, nIn: Int, nOut: Int, relu: Boolean): FloatArray {
        val out = b.copyOf()
        for (i in 0 until nIn) {
            val v = x[i]
            if (v == 0f) continue
            val base = i * nOut
            for (o in 0 until nOut) out[o] += v * w[base + o]
        }
        if (relu) for (o in 0 until nOut) if (out[o] < 0f) out[o] = 0f
        return out
    }

    companion object {
        const val ASSET_NAME = "piece_cnn.bin"
        private val CONV = listOf(4 to 12, 12 to 24, 24 to 32)
        private const val FLAT = 512
        private const val HIDDEN = 64
        private const val CLASSES = 13

        fun load(stream: InputStream): NativePieceNet {
            val bytes = stream.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size > 12 && bytes[0] == 'C'.code.toByte() && bytes[1] == 'C'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'N'.code.toByte()) { "Файл весов повреждён (неверная сигнатура)" }
            buf.position(4)
            require(buf.int == 1) { "Неподдерживаемая версия весов" }
            require(buf.int == 5) { "Неверное число слоёв" }
            fun floats(n: Int): FloatArray {
                require(buf.remaining() >= n * 4) { "Файл весов обрезан" }
                return FloatArray(n).also { buf.asFloatBuffer().get(it); buf.position(buf.position() + n * 4) }
            }
            val cw = ArrayList<FloatArray>(); val cb = ArrayList<FloatArray>()
            for ((cin, cout) in CONV) { cw += floats(cout * cin * 9); cb += floats(cout) }
            val dw = ArrayList<FloatArray>(); val db = ArrayList<FloatArray>()
            dw += floats(FLAT * HIDDEN); db += floats(HIDDEN)
            dw += floats(HIDDEN * CLASSES); db += floats(CLASSES)
            require(buf.remaining() == 0) { "Лишние данные в файле весов" }
            require((cw + cb + dw + db).all { arr -> arr.all { it.isFinite() } }) { "Веса содержат NaN/Inf" }
            return NativePieceNet(cw, cb, dw, db)
        }
    }
}
