package com.chesscheatpro.mobile

/** PGN tokenization and conservative FEN validation. Legal move replay is handled by ChessSession. */
object ChessImport {
    private val tagLine = Regex("^\\s*\\[([A-Za-z0-9_]+)\\s+\"((?:\\\\.|[^\"])*)\"\\]\\s*$")
    private val moveNumber = Regex("^\\d+\\.(?:\\.\\.)?$")
    private val results = setOf("1-0", "0-1", "1/2-1/2", "*")
    data class ImportedGame(val headers: Map<String, String>, val moves: List<String>, val result: String)

    /** Structural and selected reachability checks; full chess legality is validated by ChessSession. */
    fun isFen(text: String): Boolean {
        val fields = text.trim().split(Regex("\\s+"))
        if (fields.size != 6 || fields[1] !in setOf("w", "b")) return false
        val ranks = fields[0].split('/')
        if (ranks.size != 8) return false
        val board = Array(8) { arrayOfNulls<Char>(8) }
        var whitePawns = 0
        var blackPawns = 0
        var pieceCount = 0
        for ((rankIndex, rank) in ranks.withIndex()) {
            var file = 0
            for (c in rank) {
                when (c) {
                    in '1'..'8' -> file += c.digitToInt()
                    in "prnbqkPRNBQK" -> {
                        if (file >= 8) return false
                        if (c.lowercaseChar() == 'p' && rankIndex in setOf(0, 7)) return false
                        if (c == 'P') whitePawns++
                        if (c == 'p') blackPawns++
                        board[rankIndex][file++] = c
                        pieceCount++
                    }
                    else -> return false
                }
            }
            if (file != 8) return false
        }
        if (board.sumOf { row -> row.count { it == 'K' } } != 1 ||
            board.sumOf { row -> row.count { it == 'k' } } != 1) return false
        if (whitePawns > 8 || blackPawns > 8 || pieceCount > 32) return false

        val whiteKing = findSquare(board, 'K') ?: return false
        val blackKing = findSquare(board, 'k') ?: return false
        if (kotlin.math.abs(whiteKing.first - blackKing.first) <= 1 &&
            kotlin.math.abs(whiteKing.second - blackKing.second) <= 1) return false

        val castling = fields[2]
        if (castling != "-") {
            // FEN castling flags are unique and ordered KQkq.
            if (!Regex("^(K?Q?k?q?)$").matches(castling) || castling.isEmpty()) return false
            if ('K' in castling && (board[7][4] != 'K' || board[7][7] != 'R')) return false
            if ('Q' in castling && (board[7][4] != 'K' || board[7][0] != 'R')) return false
            if ('k' in castling && (board[0][4] != 'k' || board[0][7] != 'r')) return false
            if ('q' in castling && (board[0][4] != 'k' || board[0][0] != 'r')) return false
        }

        val ep = fields[3]
        if (ep != "-") {
            if (!Regex("^[a-h][36]$").matches(ep)) return false
            val file = ep[0] - 'a'
            if (fields[1] == "w") {
                if (ep[1] != '6' || board[2][file] != null || board[3][file] != 'p' || board[1][file] != null) return false
            } else {
                if (ep[1] != '3' || board[5][file] != null || board[4][file] != 'P' || board[6][file] != null) return false
            }
        }
        if (!fields[4].matches(Regex("^\\d+$")) || !fields[5].matches(Regex("^[1-9]\\d*$"))) return false
        val halfmove = fields[4].toIntOrNull() ?: return false
        val fullmove = fields[5].toIntOrNull() ?: return false
        if (halfmove < 0 || fullmove < 1) return false
        return true
    }

    private fun findSquare(board: Array<Array<Char?>>, piece: Char): Pair<Int, Int>? {
        for (rank in 0 until 8) for (file in 0 until 8) {
            if (board[rank][file] == piece) return rank to file
        }
        return null
    }

    fun parsePgn(text: String): ImportedGame {
        require(text.isNotBlank()) { "PGN пуст" }
        val headers = linkedMapOf<String, String>()
        val body = StringBuilder()
        text.lineSequence().forEach { line ->
            val match = tagLine.matchEntire(line)
            if (match != null) {
                val key = match.groupValues[1]
                require(key !in headers) { "Повторяющийся PGN-заголовок: $key" }
                headers[key] = match.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\")
            } else body.append(line).append('\n')
        }
        val cleanedBody = stripCommentsAndVariations(body.toString())
        val tokens = cleanedBody
            .replace(Regex("\\b\\d+\\.(?:\\.\\.)?"), " ")
            .replace(Regex("\\$\\d+"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val result = tokens.lastOrNull { it in results } ?: headers["Result"] ?: "*"
        require(result in results) { "Некорректный результат PGN" }
        if (headers["Result"] != null && tokens.lastOrNull { it in results } != null) {
            require(headers["Result"] == tokens.last { it in results }) { "Результат партии не совпадает с PGN-заголовком" }
        }
        val moves = tokens.filterNot { it in results || it == "e.p." || it == "ep" || moveNumber.matches(it) || it.startsWith("%") }
            .map { token -> token.replace(Regex("[!?+#]+$"), "").removeSuffix("e.p.").removeSuffix("ep") }
            .filter { it.isNotBlank() }
        return ImportedGame(headers, moves, result)
    }

    private fun stripCommentsAndVariations(input: String): String {
        val out = StringBuilder()
        var braceDepth = 0
        var variationDepth = 0
        var semicolonComment = false
        input.forEach { ch ->
            if (semicolonComment) {
                if (ch == '\n') { semicolonComment = false; out.append(' ') }
                return@forEach
            }
            when {
                braceDepth > 0 && ch == '{' -> braceDepth++
                braceDepth > 0 && ch == '}' -> braceDepth--
                braceDepth > 0 -> Unit
                ch == '{' -> braceDepth = 1
                ch == ';' -> semicolonComment = true
                ch == '(' -> variationDepth++
                ch == ')' && variationDepth > 0 -> variationDepth--
                variationDepth == 0 -> out.append(ch)
            }
        }
        require(braceDepth == 0) { "В PGN не закрыт комментарий в фигурных скобках" }
        require(variationDepth == 0) { "В PGN не закрыт вариант в круглых скобках" }
        return out.toString()
    }
}
