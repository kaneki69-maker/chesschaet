package com.chesscheatpro.mobile

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Engine management. Resolution order:
 *  1. the official Stockfish build bundled by Gradle as `libstockfish.so` and extracted to the app's
 *     native library directory (the only location from which Android 10+ allows executing code);
 *  2. a binary the user imported into app-private storage (works on devices/ROMs that still permit it).
 * The app never downloads or executes code from a third-party host at runtime.
 */
object LocalStockfish {
    fun install(source: File, appFilesDir: File, supportedAbis: List<String>): File {
        require(source.isFile && source.length() in (128L * 1024)..(100L * 1024 * 1024)) { "Файл движка не найден, слишком маленький или превышает 100 МБ" }
        val header = source.inputStream().use { input ->
            val bytes = ByteArray(64)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) break
                offset += count
            }
            bytes.copyOf(offset)
        }
        ElfBinaryValidator.inspect(header, supportedAbis)
        val dir = File(appFilesDir, "engine").apply { check(isDirectory || mkdirs()) { "Не удалось создать каталог движка" } }
        val target = File(dir, "stockfish")
        val staged = File(dir, "stockfish.new")
        val backup = File(dir, "stockfish.previous")
        // Recover a previous engine if the app was interrupted between renames.
        if (!target.exists() && backup.exists()) check(backup.renameTo(target)) { "Не удалось восстановить предыдущий движок" }
        staged.delete()
        if (target.exists()) backup.delete()
        try {
            source.copyTo(staged, overwrite = true)
            check(staged.length() == source.length()) { "Скопирован не весь файл движка" }
            check(staged.setExecutable(true, true) && staged.canExecute()) { "Android не разрешил сделать файл исполняемым" }
            if (target.exists()) check(target.renameTo(backup)) { "Не удалось сохранить предыдущий движок" }
            if (!staged.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Не удалось установить новый файл движка")
            }
            check(target.setExecutable(true, true) && target.canExecute()) { "Установленный движок не запускается" }
            backup.delete()
            return target
        } catch (failure: Throwable) {
            staged.delete()
            if (backup.exists()) {
                target.delete()
                backup.renameTo(target)
            }
            throw failure
        }
    }

    const val BUNDLED_LIBRARY = "libstockfish.so"

    /** Returns the bundled engine if present and executable, else the imported one, else null. */
    fun resolve(nativeLibraryDir: File?, appFilesDir: File): File? {
        val bundled = nativeLibraryDir?.let { File(it, BUNDLED_LIBRARY) }
        if (bundled != null && bundled.isFile && bundled.length() > 128 * 1024) {
            if (bundled.canExecute() || bundled.setExecutable(true, false)) return bundled
        }
        val imported = File(appFilesDir, "engine/stockfish")
        return imported.takeIf { it.isFile && it.canExecute() }
    }

    private val engineLock = Mutex()
    private var cached: Pair<String, UciEngine>? = null

    /** Shared, long-lived engine process for [binary]; restarted automatically if it died. */
    private suspend fun engineFor(binary: File): UciEngine = engineLock.withLock {
        val key = "${binary.absolutePath}:${binary.lastModified()}"
        cached?.let { (k, e) -> if (k == key && e.isAlive) return e else e.close() }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        UciEngine.start(binary, threads = threads, hashMb = 64).also { cached = key to it }
    }

    suspend fun analyze(binary: File, fen: String, depth: Int, multiPv: Int = 1, onProgress: ((List<EngineLine>) -> Unit)? = null): EngineAnalysis {
        require(ChessImport.isFen(fen)) { "Для анализа передан некорректный FEN" }
        require(depth in 1..30) { "Глубина должна быть от 1 до 30" }
        return engineFor(binary).analyze(fen, depth = depth, multiPv = multiPv, onProgress = onProgress)
    }

    suspend fun analyzeForTime(binary: File, fen: String, moveTimeMs: Long, multiPv: Int = 1, onProgress: ((List<EngineLine>) -> Unit)? = null): EngineAnalysis {
        require(ChessImport.isFen(fen)) { "Для анализа передан некорректный FEN" }
        return engineFor(binary).analyze(fen, moveTimeMs = moveTimeMs, multiPv = multiPv, onProgress = onProgress)
    }

    fun shutdown() { cached?.second?.close(); cached = null }
}
