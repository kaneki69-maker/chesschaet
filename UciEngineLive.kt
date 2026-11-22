import com.chesscheatpro.mobile.*
import kotlinx.coroutines.*
import java.io.File

/** Live check of UciEngine against a real UCI engine binary: java -cp ... UciEngineLiveKt /path/to/stockfish */
fun main(args: Array<String>): Unit = runBlocking {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) " — $detail" else "")); if (!ok) failures++ }
    val engine = UciEngine.start(File(args[0]), threads = 1, hashMb = 16)
    check("handshake", engine.name.contains("Stockfish"), engine.name)
    val start = engine.analyze(Position.START_FEN, depth = 10, multiPv = 3)
    check("startpos legal bestmove + 3 PVs", Position.start().uciMove(start.bestMove) != null && start.lines.size == 3, "${start.bestMove} lines=${start.lines.size} depth=${start.best.depth}")
    // Mate in one for White: Qh5# style - back rank mate Ra8#
    val mateFen = "6k1/5ppp/8/8/8/8/8/R3K3 w - - 0 1"
    val mate = engine.analyze(mateFen, depth = 8)
    check("finds back-rank mate in 1", mate.bestMove == "a1a8" && mate.best.mateInWhite == 1, "${mate.bestMove} ${mate.best.score}")
    // Black to move: score sign must be from White's view. Black has a queen up.
    val blackFen = "4k3/8/8/8/8/8/q7/4K3 b - - 0 1"
    val black = engine.analyze(blackFen, depth = 8)
    check("score reported from White's view (black up a queen => negative)", (black.best.centipawnsWhite ?: 0) < -500 || (black.best.mateInWhite ?: 0) < 0, black.best.score)
    // Promotion
    val promo = engine.analyze("8/P6k/8/8/8/8/8/K7 w - - 0 1", depth = 8)
    check("promotion move is legal", Position.fromFen("8/P6k/8/8/8/8/8/K7 w - - 0 1").uciMove(promo.bestMove) != null, promo.bestMove)
    // movetime
    val timed = engine.analyze(Position.START_FEN, moveTimeMs = 300)
    check("movetime search", timed.bestMove.length >= 4)
    // Checkmated position: must refuse
    val refused = runCatching { engine.analyze("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1", depth = 5) }
    check("no-move position rejected", refused.isFailure, refused.exceptionOrNull()?.message ?: "")
    // Cancellation re-synchronises the engine
    val job = launch { runCatching { engine.analyze(Position.START_FEN, depth = 30) } }
    delay(400); job.cancelAndJoin()
    val after = engine.analyze(Position.START_FEN, depth = 8)
    check("engine usable after cancelled search", Position.start().uciMove(after.bestMove) != null && engine.isAlive)
    // Progress callback
    var ticks = 0
    engine.analyze(Position.START_FEN, depth = 9) { ticks++ }
    check("progress callbacks delivered", ticks > 3, "ticks=$ticks")
    // Concurrent calls are serialised, not interleaved
    val results = listOf(async { engine.analyze(mateFen, depth = 6).bestMove }, async { engine.analyze(Position.START_FEN, depth = 6).bestMove }).awaitAll()
    check("concurrent analyses serialised", results[0] == "a1a8" && Position.start().uciMove(results[1]) != null, results.toString())
    engine.close()
    check("closed", !engine.isAlive)
    println(if (failures == 0) "ALL LIVE ENGINE CHECKS PASSED" else "FAILURES: $failures")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
