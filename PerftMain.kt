import com.chesscheatpro.mobile.Position

fun main() {
    data class Case(val name: String, val fen: String, val expected: List<Long>)
    val cases = listOf(
        Case("startpos", Position.START_FEN, listOf(20, 400, 8902, 197281)),
        Case("kiwipete", "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", listOf(48, 2039, 97862)),
        Case("pos3", "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", listOf(14, 191, 2812, 43238)),
        Case("pos4", "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", listOf(6, 264, 9467)),
        Case("pos5", "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", listOf(44, 1486, 62379)),
        Case("pos6", "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10", listOf(46, 2079, 89890))
    )
    var failed = 0
    for (c in cases) {
        val p = Position.fromFen(c.fen)
        c.expected.forEachIndexed { i, exp ->
            val got = p.perft(i + 1)
            val ok = got == exp
            if (!ok) failed++
            println("${if (ok) "PASS" else "FAIL"} perft ${c.name} d${i + 1}: $got (expected $exp)")
        }
    }
    if (failed > 0) { println("FAILED: $failed"); System.exit(1) }
    println("ALL PERFT OK")
}
