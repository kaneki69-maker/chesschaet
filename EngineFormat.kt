package com.chesscheatpro.mobile

/** Presentation helpers for engine output; pure JVM so they can be unit-tested. */
object EngineFormat {
    /** Converts a UCI principal variation to SAN starting from [fen]; stops at the first illegal move. */
    fun pvToSan(fen: String, pv: List<String>, maxMoves: Int = 8): List<String> {
        var position = Position.fromFen(fen)
        val out = ArrayList<String>()
        for (uci in pv.take(maxMoves)) {
            val move = position.uciMove(uci) ?: break
            out += position.san(move)
            position = position.play(move)
        }
        return out
    }

    /** "12. Nf3 Nc6 13. Bb5" style text for a line (move numbers follow the FEN's fullmove counter). */
    fun numberedLine(fen: String, pv: List<String>, maxMoves: Int = 8): String {
        val position = Position.fromFen(fen)
        val sans = pvToSan(fen, pv, maxMoves)
        val sb = StringBuilder()
        var number = position.fullmoveNumber
        var white = position.whiteToMove
        for ((i, san) in sans.withIndex()) {
            if (white) sb.append(number).append(". ") else if (i == 0) sb.append(number).append("… ")
            sb.append(san).append(' ')
            if (!white) number++
            white = !white
        }
        return sb.toString().trim()
    }

    fun bestMoveSan(fen: String, uci: String): String? =
        Position.fromFen(fen).let { p -> p.uciMove(uci)?.let { p.san(it) } }

    /** Evaluation in pawns from White's point of view, or a mate distance. */
    fun scoreText(line: EngineLine): String = line.score
}
