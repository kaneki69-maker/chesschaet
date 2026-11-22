package com.chesscheatpro.mobile

/** Compares occupancy-only scans or two piece-typed positions and rejects ambiguous transitions. */
object BoardMoveTracker {
    data class MoveHint(
        val from: String,
        val to: String,
        val piece: Char,
        val capturedPiece: Char? = null,
        val promotion: Char? = null,
        val isCastle: Boolean = false,
        val isEnPassant: Boolean = false
    )

    data class TransitionResult(val move: MoveHint?, val reason: String? = null)

    /**
     * Infers a complete board transition when the current 64-square position is known,
     * including captures, castling, en-passant and promotion. This is deliberately
     * separate from occupancy-only hinting because occupancy cannot identify a piece.
     */
    fun inferTransition(previousFen: String, currentSquaresInView: List<Char?>, flipped: Boolean = false): TransitionResult {
        if (!ChessImport.isFen(previousFen)) return TransitionResult(null, "Некорректный предыдущий FEN")
        if (currentSquaresInView.size != 64) return TransitionResult(null, "Ожидалось 64 клетки")
        if (currentSquaresInView.filterNotNull().any { it !in "KQRBNPkqrbnp" }) {
            return TransitionResult(null, "Неизвестный символ фигуры")
        }
        val before = BoardPositionCodec.decodePlacement(previousFen)
        val after = List(64) { viewIndex -> currentSquaresInView[if (flipped) 63 - viewIndex else viewIndex] }
        val changed = (0 until 64).filter { before[it] != after[it] }
        if (changed.isEmpty()) return TransitionResult(null, "Позиция не изменилась")

        val emptied = changed.filter { before[it] != null && after[it] == null }
        val filled = changed.filter { before[it] == null && after[it] != null }
        val replaced = changed.filter { before[it] != null && after[it] != null }

        // Standard move: one source is emptied and one destination is filled or replaced.
        if (emptied.size == 1 && (filled.size + replaced.size) == 1) {
            val from = emptied.single()
            val to = (filled + replaced).single()
            val moving = before[from]!!
            val arrived = after[to]!!
            if (moving.isUpperCase() != arrived.isUpperCase()) {
                return TransitionResult(null, "Цвет фигуры на целевой клетке не совпадает")
            }
            val isPawn = moving.lowercaseChar() == 'p'
            val promotion = if (isPawn && arrived.lowercaseChar() != 'p') arrived else null
            if (!isPawn && arrived != moving) return TransitionResult(null, "Тип фигуры неожиданно изменился")
            if (promotion != null && (promotion.lowercaseChar() !in "qrbn" || rank(to) !in setOf(0, 7))) {
                return TransitionResult(null, "Недопустимое превращение пешки")
            }
            val captured = before[to]
            if (captured != null && captured.isUpperCase() == moving.isUpperCase()) {
                return TransitionResult(null, "Нельзя определить взятие своей фигуры")
            }
            return TransitionResult(MoveHint(squareName(from), squareName(to), moving, captured, promotion))
        }

        // En-passant: source and captured pawn disappear, destination becomes occupied.
        if (emptied.size == 2 && filled.size == 1 && replaced.isEmpty()) {
            val to = filled.single()
            val pawnFrom = emptied.filter { before[it]?.lowercaseChar() == 'p' && file(it) != file(to) }
            val capturedPawn = emptied.filter { before[it]?.lowercaseChar() == 'p' && file(it) == file(to) }
            if (pawnFrom.size == 1 && capturedPawn.size == 1 && pawnFrom.single() != capturedPawn.single()) {
                val from = pawnFrom.single()
                val capturedAt = capturedPawn.single()
                val moving = before[from]!!
                val arrived = after[to]!!
                val epTarget = previousFen.split(' ').getOrNull(3)
                if (epTarget == squareName(to) && rank(from) == rank(capturedAt)
                    && kotlin.math.abs(file(from) - file(to)) == 1
                    && kotlin.math.abs(rank(from) - rank(to)) == 1
                    && moving.lowercaseChar() == 'p' && arrived == moving
                    && before[capturedAt]?.isUpperCase() != moving.isUpperCase()) {
                    return TransitionResult(MoveHint(squareName(from), squareName(to), moving,
                        before[capturedAt], isEnPassant = true))
                }
            }
        }

        // Castling: king and rook each move from their home squares on the same rank.
        if (emptied.size == 2 && filled.size == 2 && replaced.isEmpty()) {
            val kingFrom = emptied.singleOrNull { before[it]?.lowercaseChar() == 'k' }
            val rookFrom = emptied.singleOrNull { before[it]?.lowercaseChar() == 'r' }
            if (kingFrom != null && rookFrom != null) {
                val kingTo = filled.singleOrNull { after[it]?.lowercaseChar() == 'k' }
                val rookTo = filled.singleOrNull { after[it]?.lowercaseChar() == 'r' }
                val homeRank = rank(kingFrom)
                val kingSide = file(rookFrom) == 7
                val queenSide = file(rookFrom) == 0
                val expectedKingFile = if (kingSide) 6 else 2
                val expectedRookFile = if (kingSide) 5 else 3
                if (kingSide || queenSide) {
                    if (kingTo != null && rookTo != null && rank(rookFrom) == homeRank
                        && rank(kingTo) == homeRank && rank(rookTo) == homeRank
                        && file(kingFrom) == 4 && file(kingTo) == expectedKingFile && file(rookTo) == expectedRookFile
                        && homeRank in setOf(0, 7)
                        && before[kingFrom]!!.isUpperCase() == before[rookFrom]!!.isUpperCase()
                        && after[kingTo] == before[kingFrom] && after[rookTo] == before[rookFrom]) {
                        return TransitionResult(MoveHint(squareName(kingFrom), squareName(kingTo), before[kingFrom]!!, isCastle = true))
                    }
                }
            }
        }
        return TransitionResult(null, "Изменения соответствуют нескольким возможным ходам или содержат ошибки распознавания")
    }

    private fun file(index: Int) = index % 8
    private fun rank(index: Int) = index / 8

    /**
     * Infers a move from an occupancy-only scan by generating pseudo-legal moves from the
     * previous FEN and comparing the resulting 64-cell occupancy mask with the scan.
     * It can identify a unique capture even though the destination remains occupied.
     * Check/pin legality and promoted piece identity are not inferred; ambiguous scans fail closed.
     */
    fun inferOccupancyTransition(
        previousFen: String,
        currentOccupiedInView: BooleanArray,
        flipped: Boolean = false,
        uncertainInView: BooleanArray? = null
    ): TransitionResult {
        if (!ChessImport.isFen(previousFen)) return TransitionResult(null, "Некорректный предыдущий FEN")
        if (currentOccupiedInView.size != 64) return TransitionResult(null, "Ожидалось 64 клетки")
        if (uncertainInView != null && uncertainInView.size != 64) return TransitionResult(null, "Маска неопределённых клеток должна содержать 64 значения")
        if (uncertainInView?.any { it } == true) return TransitionResult(null, "Скан содержит неоднозначные клетки. Уточни область доски или повтори снимок")
        val fields = previousFen.trim().split(Regex("\\s+"))
        val before = BoardPositionCodec.decodePlacement(previousFen)
        val whiteToMove = fields.getOrNull(1) == "w"
        val castling = fields.getOrNull(2).orEmpty()
        val ep = fields.getOrNull(3).orEmpty()
        val observed = BooleanArray(64) { view -> currentOccupiedInView[if (flipped) 63 - view else view] }
        val candidates = ArrayList<MoveHint>()

        fun addCandidate(from: Int, to: Int, castle: Boolean = false, enPassant: Boolean = false) {
            val moving = before[from] ?: return
            if (moving.isUpperCase() != whiteToMove) return
            val target = before[to]
            if (target != null && (target.isUpperCase() == moving.isUpperCase() || target.lowercaseChar() == 'k')) return
            val next = before.toMutableList()
            next[to] = moving
            next[from] = null
            var captured: Char? = target
            if (enPassant) {
                val capturedIndex = if (moving.isUpperCase()) to + 8 else to - 8
                if (capturedIndex !in 0..63 || before[capturedIndex]?.lowercaseChar() != 'p') return
                captured = before[capturedIndex]
                next[capturedIndex] = null
            }
            if (castle) {
                val rookFrom = if (to % 8 == 6) from + 3 else from - 4
                val rookTo = if (to % 8 == 6) from + 1 else from - 1
                val rook = before.getOrNull(rookFrom) ?: return
                if (rook.lowercaseChar() != 'r' || rook.isUpperCase() != moving.isUpperCase()) return
                next[rookTo] = rook
                next[rookFrom] = null
            }
            val resulting = BooleanArray(64) { next[it] != null }
            if (resulting.contentEquals(observed)) {
                candidates += MoveHint(squareName(from), squareName(to), moving, captured,
                    isCastle = castle, isEnPassant = enPassant)
            }
        }

        fun clearPath(from: Int, to: Int, stepFile: Int, stepRank: Int): Boolean {
            var f = file(from) + stepFile
            var r = rank(from) + stepRank
            while (f != file(to) || r != rank(to)) {
                if (f !in 0..7 || r !in 0..7 || before[r * 8 + f] != null) return false
                f += stepFile
                r += stepRank
            }
            return true
        }

        for (from in 0 until 64) {
            val piece = before[from] ?: continue
            if (piece.isUpperCase() != whiteToMove) continue
            val f = file(from)
            val r = rank(from)
            for (to in 0 until 64) {
                if (to == from) continue
                val df = file(to) - f
                val dr = rank(to) - r
                val target = before[to]
                val enemyTarget = target != null && target.isUpperCase() != piece.isUpperCase()
                val allowed = when (piece.lowercaseChar()) {
                    'n' -> (kotlin.math.abs(df) == 1 && kotlin.math.abs(dr) == 2) || (kotlin.math.abs(df) == 2 && kotlin.math.abs(dr) == 1)
                    'k' -> kotlin.math.abs(df) <= 1 && kotlin.math.abs(dr) <= 1 && (df != 0 || dr != 0)
                    'b' -> kotlin.math.abs(df) == kotlin.math.abs(dr) && clearPath(from, to, df.compareTo(0), dr.compareTo(0))
                    'r' -> (df == 0 || dr == 0) && clearPath(from, to, df.compareTo(0), dr.compareTo(0))
                    'q' -> ((df == 0 || dr == 0) || kotlin.math.abs(df) == kotlin.math.abs(dr)) && clearPath(from, to, df.compareTo(0), dr.compareTo(0))
                    'p' -> {
                        val direction = if (piece.isUpperCase()) -1 else 1
                        val startRank = if (piece.isUpperCase()) 6 else 1
                        when {
                            df == 0 && dr == direction && target == null -> true
                            df == 0 && dr == 2 * direction && r == startRank && target == null && before[(r + direction) * 8 + f] == null -> true
                            kotlin.math.abs(df) == 1 && dr == direction && enemyTarget -> true
                            kotlin.math.abs(df) == 1 && dr == direction && ep == squareName(to) -> true
                            else -> false
                        }
                    }
                    else -> false
                }
                if (!allowed) continue
                val isEp = piece.lowercaseChar() == 'p' && kotlin.math.abs(df) == 1 && ep == squareName(to) && target == null
                addCandidate(from, to, enPassant = isEp)
            }
            if (piece.lowercaseChar() == 'k' && r == (if (piece.isUpperCase()) 7 else 0) && f == 4) {
                val rights = if (piece.isUpperCase()) listOf("K" to 6, "Q" to 2) else listOf("k" to 6, "q" to 2)
                for ((right, destinationFile) in rights) {
                    if (right !in castling) continue
                    val rookFile = if (destinationFile == 6) 7 else 0
                    val between = if (destinationFile == 6) listOf(5, 6) else listOf(1, 2, 3)
                    if (before[r * 8 + rookFile]?.lowercaseChar() == 'r' && between.all { before[r * 8 + it] == null }) {
                        addCandidate(from, r * 8 + destinationFile, castle = true)
                    }
                }
            }
        }
        return when (candidates.size) {
            1 -> TransitionResult(candidates.single())
            0 -> TransitionResult(null, "Скан не совпал ни с одним возможным ходом. Проверь область доски и клетки вручную")
            else -> TransitionResult(null, "Найдено несколько возможных ходов (${candidates.size}); требуется подтверждение вручную")
        }
    }

    /**
     * Returns a hint only for a quiet move with exactly one vacated and one newly occupied
     * square. Captures, castling, promotion, missed detections and multiple changes are
     * deliberately reported as ambiguous for manual confirmation.
     */
    fun inferQuietMove(previousFen: String, currentOccupied: BooleanArray, flipped: Boolean = false): MoveHint? {
        require(ChessImport.isFen(previousFen)) { "Некорректный предыдущий FEN" }
        require(currentOccupied.size == 64) { "Ожидалось 64 клетки" }
        val previous = BoardPositionCodec.decodePlacement(previousFen)
        val vacated = ArrayList<Int>(2)
        val newlyOccupied = ArrayList<Int>(2)
        for (viewIndex in 0 until 64) {
            val modelIndex = if (flipped) 63 - viewIndex else viewIndex
            val wasOccupied = previous[modelIndex] != null
            val isOccupied = currentOccupied[viewIndex]
            if (wasOccupied && !isOccupied) vacated += modelIndex
            if (!wasOccupied && isOccupied) newlyOccupied += modelIndex
        }
        if (vacated.size != 1 || newlyOccupied.size != 1) return null
        val fromIndex = vacated.single()
        val toIndex = newlyOccupied.single()
        val piece = previous[fromIndex] ?: return null
        return MoveHint(squareName(fromIndex), squareName(toIndex), piece)
    }

    private fun squareName(index: Int): String {
        require(index in 0 until 64)
        val file = ('a'.code + index % 8).toChar()
        val rank = ('8'.code - index / 8).toChar()
        return "$file$rank"
    }
}
