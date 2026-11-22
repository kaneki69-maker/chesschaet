package com.chesscheatpro.mobile

/**
 * Follows a game across successive board observations (e.g. one screenshot per user request).
 *
 * Every observation is matched against the *legal* continuations of the tracked position, so
 * captures, castling, en passant and promotions (including the promoted piece when it is
 * visible) are recognised by the rules engine rather than by ad-hoc pattern code. When more
 * than one legal move (or move sequence) explains an observation the tracker reports the
 * ambiguity instead of guessing, and it never changes state on a mismatch.
 */
class GameTracker(
    initial: Position,
    /** False when the side to move could not be read from the screen; resolved by the first move. */
    private var sideKnown: Boolean = true,
    /** Board orientation (true = Black at the bottom) once known; null = detect automatically. */
    var flipped: Boolean? = null,
    private val maxMissedPlies: Int = 3
) {
    data class Ply(val san: String, val uci: String, val fenBefore: String, val fenAfter: String)

    sealed class Outcome {
        /** The observation equals the tracked position. */
        data class Unchanged(val fen: String) : Outcome()
        /** One legal move (or a unique short sequence if moves were missed) explains the change. */
        data class Applied(val plies: List<Ply>, val fen: String, val flipped: Boolean, val note: String?) : Outcome()
        /** More than one legal explanation fits; nothing was applied. */
        data class Ambiguous(val candidates: List<String>, val reason: String) : Outcome()
        /** No legal continuation fits; nothing was applied. */
        data class NoMatch(val differingSquares: Int, val reason: String) : Outcome()
    }

    private var position: Position = initial
    private var otherSide: Position? = if (sideKnown) null else flipSide(initial)
    private val plies = ArrayList<Ply>()
    /** FEN of the position tracking started from (first reconstruction or last manual reset). */
    var startFen: String = initial.toFen()
        private set

    val current: Position get() = position
    val history: List<Ply> get() = plies
    val sideToMoveKnown: Boolean get() = sideKnown

    /**
     * @param viewSquares 64 squares as displayed on screen (index 0 = top-left), null = empty.
     * @param uncertainInView squares the recogniser could not read; they match anything.
     */
    fun observe(viewSquares: List<Char?>, uncertainInView: BooleanArray? = null): Outcome {
        require(viewSquares.size == 64) { "Ожидалось 64 клетки" }
        if (uncertainInView != null) require(uncertainInView.size == 64) { "Маска неопределённых клеток должна содержать 64 значения" }
        val unknownCount = uncertainInView?.count { it } ?: 0
        if (unknownCount > MAX_UNKNOWN) {
            return Outcome.NoMatch(unknownCount, "Слишком много нечитаемых клеток ($unknownCount). Повтори снимок или поправь позицию вручную")
        }
        val orders = when (val known = flipped) {
            null -> listOf(false, true)
            else -> listOf(known, !known)
        }
        var firstFailure: Outcome? = null
        for ((attempt, orientation) in orders.withIndex()) {
            val outcome = observeOriented(viewSquares, uncertainInView, orientation)
            when (outcome) {
                is Outcome.Unchanged, is Outcome.Applied -> {
                    // A fallback orientation is only trusted when it explains the board uniquely.
                    val note = if (attempt > 0 && flipped != null) "Ориентация доски изменилась" else null
                    flipped = orientation
                    return if (outcome is Outcome.Applied && note != null) outcome.copy(note = note) else outcome
                }
                else -> if (firstFailure == null || (firstFailure is Outcome.NoMatch && outcome is Outcome.Ambiguous)) firstFailure = outcome
            }
        }
        return firstFailure ?: Outcome.NoMatch(64, "Позиция не распознана")
    }

    private fun observeOriented(view: List<Char?>, uncertain: BooleanArray?, orientation: Boolean): Outcome {
        val board = Array(64) { if (orientation) view[63 - it] else view[it] }
        val known = BooleanArray(64) { !(uncertain?.get(if (orientation) 63 - it else it) ?: false) }
        val roots = listOfNotNull(position, otherSide)
        // Unchanged?
        val unchangedRoots = roots.filter { matches(it, board, known) }
        if (unchangedRoots.size == 1 || (unchangedRoots.isNotEmpty() && sideKnown)) {
            return Outcome.Unchanged(unchangedRoots.first().toFen())
        }
        if (unchangedRoots.size > 1) {
            return Outcome.Unchanged(position.toFen())
        }
        val found = ArrayList<Pair<Position, List<Position.Move>>>()
        for (root in roots) {
            var layer = listOf(root to emptyList<Position.Move>())
            for (depth in 1..maxMissedPlies) {
                val before = found.size
                val next = ArrayList<Pair<Position, List<Position.Move>>>()
                for ((pos, path) in layer) for (move in pos.legalMoves()) {
                    val child = pos.play(move)
                    if (matches(child, board, known)) found += root to (path + move)
                    if (depth < maxMissedPlies) next += child to (path + move)
                }
                // Only the shortest explanation is wanted; also keep the frontier bounded.
                if (found.size > before || next.size > MAX_FRONTIER) break
                layer = next
            }
        }
        if (found.isEmpty()) {
            val diff = roots.minOf { root -> (0 until 64).count { known[it] && root.pieceAt(it) != board[it] } }
            return Outcome.NoMatch(diff, "Скан не совпал ни с одним легальным ходом (отличается клеток: $diff). Проверь область доски или исправь клетки")
        }
        val shortest = found.minOf { it.second.size }
        val best = found.filter { it.second.size == shortest }
        if (best.size > 1) {
            val labels = best.map { (root, path) -> describe(root, path) }.distinct()
            if (labels.size > 1) return Outcome.Ambiguous(labels, "Найдено несколько легальных вариантов (${labels.size}); подтверди ход вручную")
        }
        val (root, path) = best.first()
        // Apply and record.
        var pos = root
        val applied = ArrayList<Ply>()
        for (move in path) {
            val san = pos.san(move)
            val after = pos.play(move)
            applied += Ply(san, move.uci, pos.toFen(), after.toFen())
            pos = after
        }
        position = pos
        otherSide = null
        sideKnown = true
        plies += applied
        val note = if (applied.size > 1) "Пропущено ходов: ${applied.size - 1}. Проверь последовательность" else null
        return Outcome.Applied(applied, pos.toFen(), orientation, note)
    }

    private fun describe(root: Position, path: List<Position.Move>): String {
        var pos = root
        val parts = ArrayList<String>()
        for (move in path) { parts += pos.san(move); pos = pos.play(move) }
        val prefix = if (root.whiteToMove) "" else "…"
        return prefix + parts.joinToString(" ")
    }

    /** Independent copy (position, history, orientation); used to preview a scan without committing it. */
    fun copy(): GameTracker {
        val c = GameTracker(Position.fromFen(startFen), sideKnown, flipped, maxMissedPlies)
        c.position = position
        c.otherSide = otherSide
        c.sideKnown = sideKnown
        c.plies.addAll(plies)
        return c
    }

    /** Removes the last recorded ply and restores the previous position. */
    fun undo(): Boolean {
        val last = plies.removeLastOrNull() ?: return false
        position = Position.fromFen(last.fenBefore)
        return true
    }

    /** Replaces the tracked position (manual correction); history is cleared. */
    fun reset(initial: Position, sideKnown: Boolean = true) {
        position = initial
        startFen = initial.toFen()
        otherSide = if (sideKnown) null else flipSide(initial)
        this.sideKnown = sideKnown
        plies.clear()
    }

    /** PGN movetext of the tracked moves, with FEN/SetUp headers when the start was not the initial position. */
    fun pgn(result: String = "*"): String {
        val sb = StringBuilder()
        if (startFen != Position.START_FEN) sb.append("[SetUp \"1\"]\n[FEN \"").append(startFen).append("\"]\n\n")
        val first = Position.fromFen(startFen)
        var number = first.fullmoveNumber
        var white = first.whiteToMove
        if (!white && plies.isNotEmpty()) sb.append("$number... ")
        for ((i, ply) in plies.withIndex()) {
            if (white) sb.append("$number. ")
            sb.append(ply.san).append(' ')
            if (!white) number++
            white = !white
            if (i == plies.lastIndex) sb.append(result)
        }
        if (plies.isEmpty()) sb.append(result)
        return sb.toString().trim()
    }

    private fun matches(position: Position, board: Array<Char?>, known: BooleanArray): Boolean {
        for (i in 0 until 64) if (known[i] && position.pieceAt(i) != board[i]) return false
        return true
    }

    private fun flipSide(p: Position): Position =
        Position.fromFen(p.toFen().split(' ').toMutableList().also { it[1] = if (p.whiteToMove) "b" else "w"; it[3] = "-" }.joinToString(" "))

    companion object {
        const val MAX_UNKNOWN = 8
        private const val MAX_FRONTIER = 40_000
    }
}

/**
 * Builds the starting point for tracking from a recognised board: decides the orientation
 * when it can be determined, and finds which assumptions (side to move, castling rights) the
 * image cannot prove so the UI can ask the user to confirm them.
 */
object PositionReconstructor {
    data class Reconstruction(
        val position: Position,
        val flipped: Boolean,
        val orientationConfident: Boolean,
        val sideToMoveKnown: Boolean,
        val assumptions: List<String>
    )

    /**
     * @param viewSquares recognised squares in display order (index 0 = top-left on screen).
     * @param flippedHint user/app supplied orientation; null = infer.
     * @param whiteToMoveHint side to move if known (e.g. from the UI); null = infer/assume.
     */
    fun reconstruct(
        viewSquares: List<Char?>,
        flippedHint: Boolean? = null,
        whiteToMoveHint: Boolean? = null
    ): Reconstruction {
        require(viewSquares.size == 64) { "Ожидалось 64 клетки" }
        val orientations = flippedHint?.let { listOf(it) } ?: listOf(false, true)
        data class Candidate(val flipped: Boolean, val score: Int, val valid: Boolean)
        val candidates = orientations.map { flipped ->
            val board = List(64) { if (flipped) viewSquares[63 - it] else viewSquares[it] }
            val valid = isStructurallyValid(board)
            Candidate(flipped, orientationScore(board), valid)
        }
        val validOnes = candidates.filter { it.valid }
        val chosen: Candidate
        var confident = true
        if (flippedHint != null) chosen = candidates.first()
        else if (validOnes.size == 1) chosen = validOnes.first()
        else {
            val pool = if (validOnes.isEmpty()) candidates else validOnes
            val sorted = pool.sortedByDescending { it.score }
            chosen = sorted.first()
            confident = sorted.size == 1 || sorted[0].score - sorted[1].score >= 4
        }
        val board = List(64) { if (chosen.flipped) viewSquares[63 - it] else viewSquares[it] }
        val assumptions = ArrayList<String>()
        if (!confident) assumptions += "Ориентация доски не определена надёжно — проверь «Белые снизу / Чёрные снизу»"

        // Side to move: the side that is NOT to move cannot be in check.
        val whiteInCheck = checkCandidate(board, true)
        val blackInCheck = checkCandidate(board, false)
        var sideKnown = whiteToMoveHint != null
        val whiteToMove = when {
            whiteToMoveHint != null -> whiteToMoveHint
            // The side NOT to move can never be in check, so a king in check belongs to the mover.
            blackInCheck && !whiteInCheck -> { sideKnown = true; false }
            whiteInCheck && !blackInCheck -> { sideKnown = true; true }
            else -> true
        }
        if (!sideKnown) assumptions += "Очередь хода не видна на снимке: первый обнаруженный ход определит её автоматически"

        val rights = StringBuilder()
        if (board[60] == 'K') { if (board[63] == 'R') rights.append('K'); if (board[56] == 'R') rights.append('Q') }
        if (board[4] == 'k') { if (board[7] == 'r') rights.append('k'); if (board[0] == 'r') rights.append('q') }
        if (rights.isNotEmpty()) assumptions += "Права рокировки (${rights}) предположены по расстановке — измени, если фигуры уже ходили"
        val position = Position.fromSquares(board, whiteToMove, rights.toString())
        return Reconstruction(position, chosen.flipped, confident, sideKnown, assumptions)
    }

    private fun isStructurallyValid(board: List<Char?>): Boolean {
        if (board.count { it == 'K' } != 1 || board.count { it == 'k' } != 1) return false
        if ((0 until 8).any { board[it]?.lowercaseChar() == 'p' || board[56 + it]?.lowercaseChar() == 'p' }) return false
        if (board.count { it == 'P' } > 8 || board.count { it == 'p' } > 8) return false
        return runCatching { Position.fromSquares(board, true) }.isSuccess ||
            runCatching { Position.fromSquares(board, false) }.isSuccess
    }

    /** Higher = White pieces more plausibly on the lower half of the board. */
    private fun orientationScore(board: List<Char?>): Int {
        var score = 0
        for (i in 0 until 64) {
            val c = board[i] ?: continue
            val row = i / 8
            val whiteBottom = if (c.isUpperCase()) row >= 4 else row < 4
            score += if (whiteBottom) 1 else -1
            if (c == 'K' && row >= 6) score += 2
            if (c == 'k' && row <= 1) score += 2
            if (c == 'P' && row == 6) score += 1
            if (c == 'p' && row == 1) score += 1
        }
        return score
    }

    /** Is the given colour's king attacked in this position (regardless of whose turn it is)? */
    private fun checkCandidate(board: List<Char?>, white: Boolean): Boolean {
        return runCatching {
            // Build with the *other* side to move so the library does not reject the position.
            val pos = Position.fromSquares(board, !white)
            pos.isAttacked(pos.kingSquare(white), !white)
        }.getOrElse {
            runCatching {
                val pos = Position.fromSquares(board, white)
                pos.isAttacked(pos.kingSquare(white), !white)
            }.getOrDefault(false)
        }
    }
}
