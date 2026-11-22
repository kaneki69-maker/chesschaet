package com.chesscheatpro.mobile

/**
 * Screenshot -> recognised board, pure JVM (the Android layer only supplies ARGB pixels).
 * Steps: locate the board ([BoardGeometryDetector]) -> crop -> classify all 64 squares ([BoardRecognizer]).
 */
object BoardScanPipeline {
    data class Rect(val left: Int, val top: Int, val side: Int)

    data class Scan(
        val rect: Rect,
        /** Detector score, or null when the rectangle was supplied manually. */
        val detectionScore: Float?,
        val recognition: BoardRecognizer.Result
    )

    /**
     * @param manualRect board rectangle chosen by the user; when null the board is detected automatically.
     * @return null when no board could be located and no rectangle was supplied.
     */
    fun scan(width: Int, height: Int, pixels: IntArray, recognizer: BoardRecognizer, manualRect: Rect? = null): Scan? {
        require(pixels.size == width * height) { "Размер массива пикселей не совпадает с изображением" }
        val rect: Rect
        val score: Float?
        if (manualRect != null) {
            rect = clamp(manualRect, width, height); score = null
        } else {
            val d = BoardGeometryDetector.detect(width, height, pixels) ?: return null
            val px = d.toPixels(width, height)
            rect = Rect(px[0], px[1], px[2]); score = d.score
        }
        val crop = crop(width, pixels, rect)
        return Scan(rect, score, recognizer.recognize(rect.side, rect.side, crop))
    }

    fun clamp(r: Rect, width: Int, height: Int): Rect {
        val side = r.side.coerceIn(64, minOf(width, height))
        return Rect(r.left.coerceIn(0, width - side), r.top.coerceIn(0, height - side), side)
    }

    fun crop(width: Int, pixels: IntArray, rect: Rect): IntArray {
        val out = IntArray(rect.side * rect.side)
        for (y in 0 until rect.side) System.arraycopy(pixels, (rect.top + y) * width + rect.left, out, y * rect.side, rect.side)
        return out
    }
}

/**
 * Keeps the state of one scanning session: the first scan builds the position, every later scan is
 * matched against the legal continuations of the tracked position (moves, captures, castling, en passant,
 * promotions) by [GameTracker]. Nothing is applied when the evidence is ambiguous or inconsistent.
 */
class ScanSession {
    sealed class Step {
        /** First scan: a position was reconstructed. [assumptions] must be confirmed by the user. */
        data class Started(val fen: String, val flipped: Boolean, val assumptions: List<String>, val unreadable: Int) : Step()
        data class Unchanged(val fen: String) : Step()
        data class Moves(val plies: List<GameTracker.Ply>, val fen: String, val note: String?) : Step()
        data class Rejected(val message: String, val candidates: List<String> = emptyList()) : Step()
    }

    var tracker: GameTracker? = null
        private set
    private var pending: GameTracker? = null
    val history: List<GameTracker.Ply> get() = tracker?.history.orEmpty()
    val currentFen: String? get() = tracker?.current?.toFen()

    /**
     * Interprets [scan] WITHOUT changing the committed session; call [commit] to accept the result.
     * Repeated previews (e.g. after the user adjusts the crop) always start from the committed state.
     */
    fun process(scan: BoardScanPipeline.Scan, whiteToMoveHint: Boolean? = null, flippedHint: Boolean? = null): Step {
        val rec = scan.recognition
        val active = tracker?.copy()
        pending = null
        if (active == null) {
            val r = try {
                PositionReconstructor.reconstruct(rec.squares, flippedHint, whiteToMoveHint)
            } catch (e: Exception) {
                return Step.Rejected("Позиция не собрана: ${e.message ?: "некорректная расстановка"}. Поправь клетки вручную")
            }
            pending = GameTracker(r.position, r.sideToMoveKnown, r.flipped)
            val extra = if (rec.uncertainCount > 0) listOf("Не прочитано клеток: ${rec.uncertainCount} — проверь позицию в редакторе") else emptyList()
            return Step.Started(r.position.toFen(), r.flipped, r.assumptions + extra, rec.uncertainCount)
        }
        if (flippedHint != null) active.flipped = flippedHint
        return when (val o = active.observe(rec.squares, rec.uncertain)) {
            is GameTracker.Outcome.Unchanged -> { pending = active; Step.Unchanged(o.fen) }
            is GameTracker.Outcome.Applied -> { pending = active; Step.Moves(o.plies, o.fen, o.note) }
            is GameTracker.Outcome.Ambiguous -> Step.Rejected(o.reason, o.candidates)
            is GameTracker.Outcome.NoMatch -> Step.Rejected(o.reason)
        }
    }

    /** Accepts the last successfully interpreted [process] result. Returns false if there is nothing to commit. */
    fun commit(): Boolean {
        val p = pending ?: return false
        tracker = p; pending = null
        return true
    }

    /** Replaces the tracked position after a manual correction. */
    fun resetTo(fen: String, flipped: Boolean? = null) {
        val pos = Position.fromFen(fen)
        val t = tracker
        if (t == null) tracker = GameTracker(pos, true, flipped) else { t.reset(pos, true); if (flipped != null) t.flipped = flipped }
        pending = null
    }

    /** Forgets everything (new game / new source). */
    fun clear() { tracker = null; pending = null }

    fun undo(): Boolean = tracker?.undo() ?: false
    fun pgn(result: String = "*"): String = tracker?.pgn(result).orEmpty()
}
