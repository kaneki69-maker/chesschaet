package com.chesscheatpro.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One engine variation. [score] is shown from White's point of view. */
data class EngineLine(
    val bestMove: String,
    val score: String,
    val depth: Int,
    val nodes: Long,
    val pv: List<String> = emptyList(),
    val multiPv: Int = 1,
    val centipawnsWhite: Int? = null,
    val mateInWhite: Int? = null,
    val nps: Long = 0,
    val timeMs: Long = 0
)

data class EngineAnalysis(
    val lines: List<EngineLine>,
    val bestMove: String,
    val ponder: String?,
    val engineName: String
) {
    val best: EngineLine get() = lines.first()
}

/**
 * Persistent UCI session with a Stockfish-compatible engine process. Pure JVM (no Android
 * classes) so it can be exercised against a real engine binary on a development machine.
 * All analyses are serialized; cancelling a coroutine sends `stop` and re-synchronises.
 */
class UciEngine private constructor(
    private val process: Process,
    private val reader: BufferedReader,
    private val writer: BufferedWriter,
    val name: String,
    private val lines: LinkedBlockingQueue<String>,
    private val readerThread: Thread
) : AutoCloseable {
    private val lock = Mutex()
    @Volatile private var broken = false
    val isAlive: Boolean get() = !broken && process.isAlive

    private fun send(command: String) { writer.write(command); writer.newLine(); writer.flush() }

    private suspend fun waitFor(token: String, seconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val line = lines.poll(200, TimeUnit.MILLISECONDS)
            if (line?.trim() == token) return
            if (line == null && !process.isAlive) throw IllegalStateException("Stockfish завершился до ответа $token")
        }
        throw IllegalStateException("Stockfish не прислал $token за $seconds с")
    }

    suspend fun setOption(name: String, value: String) = lock.withLock {
        withContext(Dispatchers.IO) { send("setoption name $name value $value") }
    }

    /** Starts a fresh game context (clears hash/history); call when the position source changes. */
    suspend fun newGame() = lock.withLock {
        withContext(Dispatchers.IO) { send("ucinewgame"); send("isready"); waitFor("readyok", 15) }
    }

    /**
     * Analyses [fen]. Exactly one of [depth]/[moveTimeMs] limits the search; if both are null a
     * depth of 14 is used. [onProgress] receives the evolving best lines (called off the main thread).
     */
    suspend fun analyze(
        fen: String,
        depth: Int? = null,
        moveTimeMs: Long? = null,
        multiPv: Int = 1,
        onProgress: ((List<EngineLine>) -> Unit)? = null
    ): EngineAnalysis = lock.withLock {
        withContext(Dispatchers.IO) {
            check(isAlive) { "Движок не запущен" }
            val position = Position.fromFen(fen)
            val legal = position.legalMoves()
            if (legal.isEmpty()) throw IllegalArgumentException("В этой позиции нет доступных ходов")
            require(depth == null || depth in 1..40) { "Глубина должна быть от 1 до 40" }
            require(moveTimeMs == null || moveTimeMs in 50..600_000) { "Время анализа должно быть 0.05–600 с" }
            require(multiPv in 1..5) { "MultiPV должен быть от 1 до 5" }
            lines.clear()
            send("setoption name MultiPV value ${minOf(multiPv, legal.size)}")
            send("isready"); waitFor("readyok", 15)
            send("position fen $fen")
            val limit = when {
                moveTimeMs != null -> "movetime $moveTimeMs"
                depth != null -> "depth $depth"
                else -> "depth 14"
            }
            send("go $limit")
            val byPv = sortedMapOf<Int, EngineLine>()
            var bestMove = ""
            var ponder: String? = null
            val budgetMs = (moveTimeMs ?: 120_000L) + 60_000L
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
            try {
                while (bestMove.isEmpty()) {
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() > deadline) throw IllegalStateException("Превышено время анализа Stockfish")
                    val received = lines.poll(200, TimeUnit.MILLISECONDS)
                    if (received == null) {
                        if (!process.isAlive) { broken = true; throw IllegalStateException("Stockfish завершился без bestmove") }
                        continue
                    }
                    val line = received.trim()
                    when {
                        line.startsWith("info ") -> parseInfo(line, position.whiteToMove)?.let {
                            byPv[it.multiPv] = it
                            onProgress?.invoke(byPv.values.toList())
                        }
                        line.startsWith("bestmove") -> {
                            val parts = line.split(Regex("\\s+"))
                            bestMove = parts.getOrNull(1).orEmpty()
                            ponder = parts.indexOf("ponder").takeIf { it > 0 }?.let { parts.getOrNull(it + 1) }
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                // Re-synchronise so the next analysis does not read this search's output.
                runCatching { send("stop") }
                val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var synced = false
                while (System.nanoTime() < drainDeadline) {
                    val l = lines.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (l.startsWith("bestmove")) { synced = true; break }
                }
                if (!synced) broken = true
                throw cancel
            } catch (failure: Throwable) {
                broken = true
                throw failure
            }
            require(bestMove.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) {
                if (bestMove == "(none)") "В этой позиции нет доступного хода" else "Stockfish вернул некорректный UCI-ход: $bestMove"
            }
            require(position.uciMove(bestMove) != null) { "Stockfish предложил нелегальный ход $bestMove" }
            val result = byPv.values.toList().ifEmpty { listOf(EngineLine(bestMove, "—", 0, 0)) }
            val first = result.first().copy(bestMove = bestMove)
            EngineAnalysis(listOf(first) + result.drop(1), bestMove, ponder, name)
        }
    }

    override fun close() {
        runCatching { send("quit") }
        runCatching { writer.close() }
        runCatching { process.destroy() }
        if (process.isAlive) runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
        if (process.isAlive) process.destroyForcibly()
        runCatching { reader.close() }
        readerThread.interrupt()
        broken = true
    }

    companion object {
        /** Parses a UCI `info` line into a variation; returns null for lines without a PV or with score bounds. */
        fun parseInfo(line: String, whiteToMove: Boolean): EngineLine? {
            val parts = line.split(Regex("\\s+"))
            if ("pv" !in parts || "score" !in parts) return null
            if ("lowerbound" in parts || "upperbound" in parts) return null
            fun after(key: String): String? = parts.indexOf(key).takeIf { it >= 0 && it + 1 < parts.size }?.let { parts[it + 1] }
            val depth = after("depth")?.toIntOrNull() ?: return null
            val multi = after("multipv")?.toIntOrNull() ?: 1
            val s = parts.indexOf("score")
            val kind = parts.getOrNull(s + 1)
            val raw = parts.getOrNull(s + 2)?.toIntOrNull() ?: return null
            val sign = if (whiteToMove) 1 else -1
            var cp: Int? = null
            var mate: Int? = null
            val score = when (kind) {
                "cp" -> { cp = raw * sign; String.format(Locale.US, "%+.2f", cp / 100.0) }
                "mate" -> { mate = raw * sign; "Мат ${if (mate > 0) "+" else ""}$mate" }
                else -> return null
            }
            val pv = parts.drop(parts.indexOf("pv") + 1).takeWhile { it.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")) }
            if (pv.isEmpty()) return null
            return EngineLine(
                pv.first(), score, depth, after("nodes")?.toLongOrNull() ?: 0, pv, multi, cp, mate,
                after("nps")?.toLongOrNull() ?: 0, after("time")?.toLongOrNull() ?: 0
            )
        }

        /** Launches [binary] and completes the UCI handshake. */
        suspend fun start(binary: File, threads: Int = 2, hashMb: Int = 64, workingDir: File? = binary.parentFile): UciEngine =
            withContext(Dispatchers.IO) {
                require(binary.isFile && binary.canExecute()) { "Файл движка не найден или не исполняемый: ${binary.path}" }
                val process = ProcessBuilder(binary.absolutePath).directory(workingDir).redirectErrorStream(true).start()
                val queue = LinkedBlockingQueue<String>()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val thread = Thread {
                    try { while (true) queue.put(reader.readLine() ?: break) } catch (_: Exception) { }
                }.apply { isDaemon = true; start() }
                val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
                var engineName = "UCI engine"
                try {
                    writer.write("uci"); writer.newLine(); writer.flush()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    var ok = false
                    while (System.nanoTime() < deadline) {
                        val line = queue.poll(200, TimeUnit.MILLISECONDS)
                        if (line == null) { if (!process.isAlive) throw IllegalStateException("Stockfish завершился при запуске"); continue }
                        if (line.startsWith("id name ")) engineName = line.removePrefix("id name ").trim()
                        if (line.trim() == "uciok") { ok = true; break }
                    }
                    check(ok) { "Движок не ответил uciok" }
                    val engine = UciEngine(process, reader, writer, engineName, queue, thread)
                    engine.send("setoption name Threads value ${threads.coerceIn(1, 8)}")
                    engine.send("setoption name Hash value ${hashMb.coerceIn(1, 512)}")
                    engine.send("isready")
                    engine.waitFor("readyok", 30)
                    engine
                } catch (failure: Throwable) {
                    runCatching { writer.close() }
                    process.destroyForcibly()
                    thread.interrupt()
                    throw failure
                }
            }
    }
}
