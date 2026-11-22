package com.chesscheatpro.mobile

/** Replays SAN moves with the app's own rules engine and rejects any illegal or ambiguous move. */
class ChessSession(private val startFen: String? = null, private val sanMoves: List<String> = emptyList()) {
    data class Snapshot(val fen: String, val ply: Int, val totalPlies: Int)

    fun snapshot(atPly: Int): Snapshot {
        var position = if (startFen.isNullOrBlank()) Position.start() else Position.fromFen(startFen)
        val target = atPly.coerceIn(0, sanMoves.size)
        for (i in 0 until target) {
            val san = sanMoves[i]
            val move = position.parseSan(san)
            check(move != null) { "Illegal or unsupported move at ply ${i + 1}: $san" }
            position = position.play(move)
        }
        return Snapshot(position.toFen(), target, sanMoves.size)
    }

    fun validateAll(): Snapshot = snapshot(sanMoves.size)
}
