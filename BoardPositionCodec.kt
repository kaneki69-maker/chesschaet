package com.chesscheatpro.mobile

/** Converts a manually composed 8x8 board into a structurally valid FEN position. */
object BoardPositionCodec {
    private val pieces = "KQRBNPkqrbnp"

    fun decodePlacement(fen: String): List<Char?> {
        require(ChessImport.isFen(fen)) { "Некорректный FEN" }
        return fen.substringBefore(' ').split('/').flatMap { rank ->
            buildList {
                rank.forEach { c ->
                    if (c in '1'..'8') repeat(c.digitToInt()) { add(null) } else add(c)
                }
            }
        }.also { require(it.size == 64) { "Позиция должна содержать 64 клетки" } }
    }

    fun encodeFen(
        squares: List<Char?>,
        whiteToMove: Boolean,
        castlingRights: Set<Char> = emptySet(),
        enPassant: String = "-"
    ): String {
        require(squares.size == 64) { "Ожидалось 64 клетки" }
        require(squares.filterNotNull().all { it in pieces }) { "Неизвестная фигура" }
        val ranks = (0 until 8).map { row ->
            val out = StringBuilder()
            var empty = 0
            for (col in 0 until 8) {
                val piece = squares[row * 8 + col]
                if (piece == null) empty++ else {
                    if (empty > 0) { out.append(empty); empty = 0 }
                    out.append(piece)
                }
            }
            if (empty > 0) out.append(empty)
            out.toString()
        }
        require(castlingRights.all { it in "KQkq" }) { "Неизвестное право рокировки" }
        val castling = "KQkq".filter { it in castlingRights }.ifEmpty { "-" }
        require(enPassant == "-" || enPassant.matches(Regex("[a-h][36]"))) { "Поле en-passant должно быть '-' или клеткой вида e3" }
        val side = if (whiteToMove) "w" else "b"
        return ranks.joinToString("/") + " $side $castling $enPassant 0 1"
    }
}

