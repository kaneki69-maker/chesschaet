package com.chesscheatpro.mobile

import kotlin.math.abs

/**
 * Self-contained, pure-Kotlin implementation of the rules of chess (no Android or third-party
 * dependencies): legal move generation including castling, en passant and promotion, FEN I/O,
 * SAN output/parsing and game-end detection. It is the single source of truth for move legality
 * in the move tracker and the PGN session, and is validated with perft node counts.
 *
 * Square indexing matches the rest of the app: index 0 = a8, 7 = h8, 56 = a1, 63 = h1.
 */
class Position private constructor(
    private val cells: CharArray,
    val whiteToMove: Boolean,
    val castling: String,
    val epSquare: Int,
    val halfmoveClock: Int,
    val fullmoveNumber: Int
) {
    data class Move(
        val from: Int,
        val to: Int,
        val piece: Char,
        val captured: Char? = null,
        val promotion: Char? = null,
        val isCastle: Boolean = false,
        val isEnPassant: Boolean = false
    ) {
        val uci: String get() = squareName(from) + squareName(to) + (promotion?.lowercaseChar()?.toString() ?: "")
    }

    enum class Status { ONGOING, CHECKMATE, STALEMATE, INSUFFICIENT_MATERIAL, FIFTY_MOVE }

    fun pieceAt(index: Int): Char? = cells[index].takeIf { it != EMPTY }

    fun squares(): List<Char?> = List(64) { pieceAt(it) }

    fun inCheck(): Boolean = kingSquare(whiteToMove).let { it >= 0 && isAttacked(it, !whiteToMove) }

    fun kingSquare(white: Boolean): Int {
        val king = if (white) 'K' else 'k'
        for (i in 0 until 64) if (cells[i] == king) return i
        return -1
    }

    /** True when any piece of the given colour attacks [square] (pins are irrelevant for attacks). */
    fun isAttacked(square: Int, byWhite: Boolean): Boolean {
        val f = square % 8
        val r = square / 8
        // Pawns: a white pawn on (r+1) attacks upward (towards row 0), so it sits below the target.
        val pawn = if (byWhite) 'P' else 'p'
        val pawnRow = if (byWhite) r + 1 else r - 1
        if (pawnRow in 0..7) for (df in intArrayOf(-1, 1)) {
            val pf = f + df
            if (pf in 0..7 && cells[pawnRow * 8 + pf] == pawn) return true
        }
        val knight = if (byWhite) 'N' else 'n'
        for (k in KNIGHT_STEPS) {
            val nf = f + k[0]; val nr = r + k[1]
            if (nf in 0..7 && nr in 0..7 && cells[nr * 8 + nf] == knight) return true
        }
        val king = if (byWhite) 'K' else 'k'
        for (dr in -1..1) for (df in -1..1) {
            if (dr == 0 && df == 0) continue
            val nf = f + df; val nr = r + dr
            if (nf in 0..7 && nr in 0..7 && cells[nr * 8 + nf] == king) return true
        }
        val rook = if (byWhite) 'R' else 'r'
        val bishop = if (byWhite) 'B' else 'b'
        val queen = if (byWhite) 'Q' else 'q'
        for (dir in DIRECTIONS) {
            var nf = f + dir[0]; var nr = r + dir[1]
            val straight = dir[0] == 0 || dir[1] == 0
            while (nf in 0..7 && nr in 0..7) {
                val c = cells[nr * 8 + nf]
                if (c != EMPTY) {
                    if (c == queen || (straight && c == rook) || (!straight && c == bishop)) return true
                    break
                }
                nf += dir[0]; nr += dir[1]
            }
        }
        return false
    }

    fun legalMoves(): List<Move> {
        val out = ArrayList<Move>(48)
        for (move in pseudoLegalMoves()) {
            val next = applyUnchecked(move)
            val king = next.kingSquare(whiteToMove)
            if (king >= 0 && !next.isAttacked(king, !whiteToMove)) out += move
        }
        return out
    }

    private fun isOwn(c: Char) = c != EMPTY && c.isUpperCase() == whiteToMove

    private fun pseudoLegalMoves(): List<Move> {
        val moves = ArrayList<Move>(64)
        for (from in 0 until 64) {
            val piece = cells[from]
            if (piece == EMPTY || piece.isUpperCase() != whiteToMove) continue
            val f = from % 8
            val r = from / 8
            when (piece.lowercaseChar()) {
                'p' -> {
                    val dir = if (whiteToMove) -1 else 1
                    val startRow = if (whiteToMove) 6 else 1
                    val promoRow = if (whiteToMove) 0 else 7
                    val nr = r + dir
                    if (nr in 0..7) {
                        val ahead = nr * 8 + f
                        if (cells[ahead] == EMPTY) {
                            addPawnMove(moves, from, ahead, piece, null, nr == promoRow)
                            if (r == startRow && cells[(r + 2 * dir) * 8 + f] == EMPTY) {
                                moves += Move(from, (r + 2 * dir) * 8 + f, piece)
                            }
                        }
                        for (df in intArrayOf(-1, 1)) {
                            val nf = f + df
                            if (nf !in 0..7) continue
                            val target = nr * 8 + nf
                            val c = cells[target]
                            if (c != EMPTY && !isOwn(c) && c.lowercaseChar() != 'k') {
                                addPawnMove(moves, from, target, piece, c, nr == promoRow)
                            } else if (c == EMPTY && target == epSquare &&
                                cells[target + if (whiteToMove) 8 else -8] == (if (whiteToMove) 'p' else 'P')) {
                                moves += Move(from, target, piece, if (whiteToMove) 'p' else 'P', isEnPassant = true)
                            }
                        }
                    }
                }
                'n' -> for (k in KNIGHT_STEPS) stepTo(moves, from, piece, f + k[0], r + k[1])
                'k' -> {
                    for (dr in -1..1) for (df in -1..1) if (dr != 0 || df != 0) stepTo(moves, from, piece, f + df, r + dr)
                    addCastling(moves, from, piece)
                }
                else -> {
                    val lower = piece.lowercaseChar()
                    for (dir in DIRECTIONS) {
                        val straight = dir[0] == 0 || dir[1] == 0
                        if (lower == 'r' && !straight) continue
                        if (lower == 'b' && straight) continue
                        var nf = f + dir[0]; var nr = r + dir[1]
                        while (nf in 0..7 && nr in 0..7) {
                            val c = cells[nr * 8 + nf]
                            if (c == EMPTY) moves += Move(from, nr * 8 + nf, piece)
                            else {
                                if (!isOwn(c) && c.lowercaseChar() != 'k') moves += Move(from, nr * 8 + nf, piece, c)
                                break
                            }
                            nf += dir[0]; nr += dir[1]
                        }
                    }
                }
            }
        }
        return moves
    }

    private fun stepTo(moves: MutableList<Move>, from: Int, piece: Char, nf: Int, nr: Int) {
        if (nf !in 0..7 || nr !in 0..7) return
        val c = cells[nr * 8 + nf]
        if (c == EMPTY) moves += Move(from, nr * 8 + nf, piece)
        else if (!isOwn(c) && c.lowercaseChar() != 'k') moves += Move(from, nr * 8 + nf, piece, c)
    }

    private fun addPawnMove(moves: MutableList<Move>, from: Int, to: Int, pawn: Char, captured: Char?, promotes: Boolean) {
        if (!promotes) { moves += Move(from, to, pawn, captured); return }
        for (p in "qrbn") moves += Move(from, to, pawn, captured, promotion = if (whiteToMove) p.uppercaseChar() else p)
    }

    private fun addCastling(moves: MutableList<Move>, from: Int, king: Char) {
        val homeRow = if (whiteToMove) 7 else 0
        if (from != homeRow * 8 + 4) return
        val rook = if (whiteToMove) 'R' else 'r'
        if (isAttacked(from, !whiteToMove)) return
        val kingSide = if (whiteToMove) 'K' else 'k'
        val queenSide = if (whiteToMove) 'Q' else 'q'
        if (kingSide in castling && cells[homeRow * 8 + 7] == rook &&
            cells[homeRow * 8 + 5] == EMPTY && cells[homeRow * 8 + 6] == EMPTY &&
            !isAttacked(homeRow * 8 + 5, !whiteToMove) && !isAttacked(homeRow * 8 + 6, !whiteToMove)) {
            moves += Move(from, homeRow * 8 + 6, king, isCastle = true)
        }
        if (queenSide in castling && cells[homeRow * 8] == rook &&
            cells[homeRow * 8 + 1] == EMPTY && cells[homeRow * 8 + 2] == EMPTY && cells[homeRow * 8 + 3] == EMPTY &&
            !isAttacked(homeRow * 8 + 3, !whiteToMove) && !isAttacked(homeRow * 8 + 2, !whiteToMove)) {
            moves += Move(from, homeRow * 8 + 2, king, isCastle = true)
        }
    }

    /** Applies a move that must come from [legalMoves]; verifies membership first. */
    fun play(move: Move): Position {
        require(legalMoves().any { it == move }) { "Нелегальный ход ${move.uci}" }
        return applyUnchecked(move)
    }

    private fun applyUnchecked(move: Move): Position {
        val next = cells.copyOf()
        val piece = cells[move.from]
        next[move.from] = EMPTY
        next[move.to] = move.promotion ?: piece
        if (move.isEnPassant) next[move.to + if (whiteToMove) 8 else -8] = EMPTY
        if (move.isCastle) {
            val row = move.from / 8
            if (move.to % 8 == 6) { next[row * 8 + 5] = next[row * 8 + 7]; next[row * 8 + 7] = EMPTY }
            else { next[row * 8 + 3] = next[row * 8]; next[row * 8] = EMPTY }
        }
        var rights = castling
        fun drop(c: Char) { rights = rights.replace(c.toString(), "") }
        if (piece == 'K') { drop('K'); drop('Q') }
        if (piece == 'k') { drop('k'); drop('q') }
        for (sq in intArrayOf(move.from, move.to)) when (sq) {
            63 -> drop('K'); 56 -> drop('Q'); 7 -> drop('k'); 0 -> drop('q')
        }
        val ep = if (piece.lowercaseChar() == 'p' && abs(move.to - move.from) == 16) (move.from + move.to) / 2 else -1
        val reset = piece.lowercaseChar() == 'p' || move.captured != null
        return Position(
            next, !whiteToMove, rights, ep,
            if (reset) 0 else halfmoveClock + 1,
            if (whiteToMove) fullmoveNumber else fullmoveNumber + 1
        )
    }

    fun status(): Status {
        if (legalMoves().isEmpty()) return if (inCheck()) Status.CHECKMATE else Status.STALEMATE
        if (halfmoveClock >= 100) return Status.FIFTY_MOVE
        if (insufficientMaterial()) return Status.INSUFFICIENT_MATERIAL
        return Status.ONGOING
    }

    private fun insufficientMaterial(): Boolean {
        val minors = ArrayList<Pair<Char, Int>>()
        for (i in 0 until 64) {
            val c = cells[i].lowercaseChar()
            when (c) {
                'p', 'r', 'q' -> return false
                'n', 'b' -> minors += c to i
            }
        }
        if (minors.size <= 1) return true
        if (minors.all { it.first == 'b' }) {
            val colors = minors.map { ((it.second % 8) + (it.second / 8)) % 2 }.toSet()
            return colors.size == 1
        }
        return false
    }

    fun toFen(): String {
        val ranks = (0 until 8).joinToString("/") { row ->
            val sb = StringBuilder(); var empty = 0
            for (col in 0 until 8) {
                val c = cells[row * 8 + col]
                if (c == EMPTY) empty++ else { if (empty > 0) { sb.append(empty); empty = 0 }; sb.append(c) }
            }
            if (empty > 0) sb.append(empty)
            sb.toString()
        }
        val rights = "KQkq".filter { it in castling }.ifEmpty { "-" }
        val ep = if (epSquare >= 0) squareName(epSquare) else "-"
        return "$ranks ${if (whiteToMove) "w" else "b"} $rights $ep $halfmoveClock $fullmoveNumber"
    }

    /** Placement + side + castling + en passant only (the identity used for repetition). */
    fun placementKey(): String = toFen().substringBeforeLast(' ').substringBeforeLast(' ')

    fun uciMove(text: String): Move? = legalMoves().firstOrNull { it.uci == text.trim().lowercase() }

    /** Standard algebraic notation for a legal move of this position. */
    fun san(move: Move, legal: List<Move> = legalMoves()): String {
        val core = if (move.isCastle) {
            if (move.to % 8 == 6) "O-O" else "O-O-O"
        } else {
            val sb = StringBuilder()
            val type = move.piece.uppercaseChar()
            if (type == 'P') {
                if (move.captured != null) sb.append('a' + move.from % 8).append('x')
            } else {
                sb.append(type)
                val rivals = legal.filter { it != move && it.piece == move.piece && it.to == move.to }
                if (rivals.isNotEmpty()) {
                    val sameFile = rivals.any { it.from % 8 == move.from % 8 }
                    val sameRank = rivals.any { it.from / 8 == move.from / 8 }
                    if (!sameFile) sb.append('a' + move.from % 8)
                    else if (!sameRank) sb.append('8' - move.from / 8)
                    else sb.append(squareName(move.from))
                }
                if (move.captured != null) sb.append('x')
            }
            sb.append(squareName(move.to))
            move.promotion?.let { sb.append('=').append(it.uppercaseChar()) }
            sb.toString()
        }
        val after = applyUnchecked(move)
        val suffix = when {
            after.inCheck() -> if (after.legalMoves().isEmpty()) "#" else "+"
            else -> ""
        }
        return core + suffix
    }

    /** Parses SAN (tolerating check marks, annotations, 0-0 castling and a missing '='). */
    fun parseSan(text: String): Move? {
        fun clean(s: String) = s.replace("0", "O").replace("=", "").replace("x", "").replace(Regex("[+#!?]+"), "")
            .replace("e.p.", "")
        val wanted = clean(text.trim())
        if (wanted.isEmpty()) return null
        val legal = legalMoves()
        val matches = legal.filter { clean(san(it, legal)) == wanted }
        if (matches.size == 1) return matches[0]
        // Lenient: a pawn promotion written in lowercase, e.g. e8q.
        if (matches.isEmpty() && wanted.length >= 3 && wanted.last().lowercaseChar() in "qrbn") {
            val alt = wanted.dropLast(1) + wanted.last().uppercaseChar()
            return legal.filter { clean(san(it, legal)) == alt }.singleOrNull()
        }
        return null
    }

    fun perft(depth: Int): Long {
        if (depth == 0) return 1
        val moves = legalMoves()
        if (depth == 1) return moves.size.toLong()
        var total = 0L
        for (m in moves) total += applyUnchecked(m).perft(depth - 1)
        return total
    }

    override fun equals(other: Any?): Boolean = other is Position && other.toFen() == toFen()
    override fun hashCode(): Int = toFen().hashCode()

    companion object {
        private const val EMPTY = '.'
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        private val KNIGHT_STEPS = arrayOf(
            intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
            intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2)
        )
        private val DIRECTIONS = arrayOf(
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
            intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1)
        )

        fun squareName(index: Int): String {
            require(index in 0 until 64) { "Индекс клетки вне диапазона" }
            return "${'a' + index % 8}${'8' - index / 8}"
        }

        fun squareIndex(name: String): Int {
            require(name.length == 2 && name[0] in 'a'..'h' && name[1] in '1'..'8') { "Некорректная клетка: $name" }
            return ('8' - name[1]) * 8 + (name[0] - 'a')
        }

        fun start(): Position = fromFen(START_FEN)

        /**
         * Parses a FEN. Structural validity (one king per side, no pawns on the back ranks,
         * kings not adjacent, side not giving check to the opponent) is enforced; a missing
         * clock pair defaults to "0 1".
         */
        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.size in 4..6) { "Некорректный FEN: ожидалось 4–6 полей" }
            val rows = parts[0].split('/')
            require(rows.size == 8) { "Некорректный FEN: нужно 8 горизонталей" }
            val cells = CharArray(64) { EMPTY }
            for ((row, text) in rows.withIndex()) {
                var col = 0
                for (c in text) {
                    when {
                        c in '1'..'8' -> col += c - '0'
                        c in "prnbqkPRNBQK" -> {
                            require(col < 8) { "Некорректный FEN: горизонталь длиннее 8 клеток" }
                            cells[row * 8 + col++] = c
                        }
                        else -> throw IllegalArgumentException("Некорректный FEN: символ '$c'")
                    }
                }
                require(col == 8) { "Некорректный FEN: горизонталь не равна 8 клеткам" }
            }
            require(parts[1] == "w" || parts[1] == "b") { "Некорректный FEN: сторона хода" }
            val white = parts[1] == "w"
            require(cells.count { it == 'K' } == 1 && cells.count { it == 'k' } == 1) { "Нужно ровно по одному королю" }
            require((0 until 8).none { cells[it].lowercaseChar() == 'p' || cells[56 + it].lowercaseChar() == 'p' }) {
                "Пешки не могут стоять на 1-й и 8-й горизонталях"
            }
            var rights = if (parts[2] == "-") "" else parts[2]
            require(rights.all { it in "KQkq" }) { "Некорректные права рокировки" }
            // Silently drop rights that are impossible because the king/rook left home.
            if (cells[60] != 'K') rights = rights.replace("K", "").replace("Q", "")
            if (cells[63] != 'R') rights = rights.replace("K", "")
            if (cells[56] != 'R') rights = rights.replace("Q", "")
            if (cells[4] != 'k') rights = rights.replace("k", "").replace("q", "")
            if (cells[7] != 'r') rights = rights.replace("k", "")
            if (cells[0] != 'r') rights = rights.replace("q", "")
            val ep = if (parts[3] == "-") -1 else squareIndex(parts[3])
            if (ep >= 0) require((white && ep / 8 == 2) || (!white && ep / 8 == 5)) { "Некорректное поле en-passant" }
            val half = parts.getOrNull(4)?.toIntOrNull() ?: 0
            val full = parts.getOrNull(5)?.toIntOrNull() ?: 1
            require(half >= 0 && full >= 1) { "Некорректные счётчики ходов" }
            val position = Position(cells, white, "KQkq".filter { it in rights }, ep, half, full)
            val wk = position.kingSquare(true); val bk = position.kingSquare(false)
            require(abs(wk % 8 - bk % 8) > 1 || abs(wk / 8 - bk / 8) > 1) { "Короли не могут стоять рядом" }
            require(!position.isAttacked(if (white) bk else wk, white)) { "Сторона, которая не ходит, стоит под шахом" }
            return position
        }

        /**
         * Builds a position from a 64-square map in board index order (a8..h1), as produced by
         * the recogniser. Side to move and rights are supplied by the caller.
         */
        fun fromSquares(
            squares: List<Char?>, whiteToMove: Boolean, castling: String = "", epSquare: Int = -1,
            halfmove: Int = 0, fullmove: Int = 1
        ): Position {
            require(squares.size == 64) { "Ожидалось 64 клетки" }
            val fen = BoardPositionCodec.encodeFen(squares, whiteToMove, castling.toSet(), if (epSquare >= 0) squareName(epSquare) else "-")
            val base = fromFen(fen)
            return Position(base.cells, whiteToMove, base.castling, base.epSquare, halfmove, fullmove)
        }
    }
}
