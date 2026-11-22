import com.chesscheatpro.mobile.*

fun main() {
    val initial = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    check(ChessImport.isFen(initial))
    check(!ChessImport.isFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w QKkq - 0 1"))
    check(!ChessImport.isFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - +0 1"))
    check(!ChessImport.isFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 +1"))
    check(ChessImport.isFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"))
    val squares = BoardPositionCodec.decodePlacement(initial)
    check(ChessImport.isFen(BoardPositionCodec.encodeFen(squares, true)))
    val afterE4 = squares.toMutableList().apply { this[52] = null; this[36] = 'P' }
    val metadataFen = BoardPositionCodec.encodeFen(afterE4, false, setOf('q', 'K', 'k'), "e3")
    check(metadataFen.contains(" b Kkq e3 0 1"))
    check(ChessImport.isFen(metadataFen))
    val size = 256
    for (target in 0 until 64) {
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val row=y/32; val col=x/32
            val base=if ((row+col)%2==0) 0xFFE8D0A8.toInt() else 0xFF9A704C.toInt()
            val idx=row*8+col
            val fg=idx==target && x%32 in 10..22 && y%32 in 8..24
            pixels[y*size+x]=if(fg) 0xFF202020.toInt() else base
        }
        val result=BoardOccupancyClassifier.classify(size,size,pixels)
        check(result.uncertain.size == 64)
        check(result.occupied[target]) { "missed $target" }
        check(result.occupied.count { it } == 1) { "false positives for $target" }
    }
    val quietMove = BooleanArray(64) { squares[it] != null }.apply { this[52] = false; this[36] = true }
    val hint = BoardMoveTracker.inferQuietMove(initial, quietMove)
    check(hint?.from == "e2" && hint.to == "e4" && hint.piece == 'P')
    val flippedPosition = BooleanArray(64) { squares[63 - it] != null }.apply { this[55] = false; this[47] = true }
    val flippedHint = BoardMoveTracker.inferQuietMove(initial, flippedPosition, flipped = true)
    check(flippedHint?.from == "a7" && flippedHint.to == "a6" && flippedHint.piece == 'p')

    // Full position transition inference: capture, castling, en-passant, promotion and rotation.
    val captureFen = "4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1"
    val captureAfter = BoardPositionCodec.decodePlacement(captureFen).toMutableList().apply { this[36] = null; this[27] = 'P' }
    val capture = BoardMoveTracker.inferTransition(captureFen, captureAfter).move
    check(capture?.from == "e4" && capture.to == "d5" && capture.capturedPiece == 'p')
    val castleFen = "4k3/8/8/8/8/8/8/4K2R w K - 0 1"
    val castleAfter = BoardPositionCodec.decodePlacement(castleFen).toMutableList().apply { this[60] = null; this[63] = null; this[62] = 'K'; this[61] = 'R' }
    val castle = BoardMoveTracker.inferTransition(castleFen, castleAfter).move
    check(castle?.isCastle == true && castle.from == "e1" && castle.to == "g1")
    val epFen = "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1"
    val epAfter = BoardPositionCodec.decodePlacement(epFen).toMutableList().apply { this[28] = null; this[27] = null; this[19] = 'P' }
    val ep = BoardMoveTracker.inferTransition(epFen, epAfter).move
    check(ep?.isEnPassant == true && ep.from == "e5" && ep.to == "d6" && ep.capturedPiece == 'p')
    val promotionFen = "4k3/P7/8/8/8/8/8/4K3 w - - 0 1"
    val promotionAfter = BoardPositionCodec.decodePlacement(promotionFen).toMutableList().apply { this[8] = null; this[0] = 'Q' }
    val promotion = BoardMoveTracker.inferTransition(promotionFen, promotionAfter).move
    check(promotion?.from == "a7" && promotion.to == "a8" && promotion.promotion == 'Q')
    val flippedView = List(64) { afterE4[63 - it] }
    val flippedTransition = BoardMoveTracker.inferTransition(initial, flippedView, flipped = true).move
    check(flippedTransition?.from == "e2" && flippedTransition.to == "e4")

    // Occupancy-only move tracking: unique quiet move, capture, en-passant and castling.
    val occupancyE4 = BooleanArray(64) { afterE4[it] != null }
    val inferredE4 = BoardMoveTracker.inferOccupancyTransition(initial, occupancyE4).move
    check(inferredE4?.from == "e2" && inferredE4.to == "e4")
    val occupancyCaptureFen = "4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1"
    val occupancyCaptureAfter = BoardPositionCodec.decodePlacement(occupancyCaptureFen).toMutableList().apply { this[36] = null; this[27] = 'P' }
    val inferredCapture = BoardMoveTracker.inferOccupancyTransition(occupancyCaptureFen, BooleanArray(64) { occupancyCaptureAfter[it] != null }).move
    check(inferredCapture?.from == "e4" && inferredCapture.to == "d5" && inferredCapture.capturedPiece == 'p')
    val inferredCastle = BoardMoveTracker.inferOccupancyTransition(castleFen, BooleanArray(64) { castleAfter[it] != null }).move
    check(inferredCastle?.isCastle == true && inferredCastle.from == "e1" && inferredCastle.to == "g1")
    val inferredEp = BoardMoveTracker.inferOccupancyTransition(epFen, BooleanArray(64) { epAfter[it] != null }).move
    check(inferredEp?.isEnPassant == true && inferredEp.from == "e5" && inferredEp.to == "d6")
    val uncertainMask = BooleanArray(64).apply { this[10] = true }
    check(BoardMoveTracker.inferOccupancyTransition(initial, occupancyE4, uncertainInView = uncertainMask).move == null)
    val noisyOccupancy = occupancyE4.copyOf().apply { this[0] = !this[0] }
    check(BoardMoveTracker.inferOccupancyTransition(initial, noisyOccupancy).move == null)

    val width = 512; val height = 768; val boardPixels = IntArray(width * height) { 0xFF303030.toInt() }
    val boardLeft = 64; val boardTop = 192; val boardSide = 384
    for (y in boardTop until boardTop + boardSide) for (x in boardLeft until boardLeft + boardSide) {
        val row = ((y - boardTop) * 8 / boardSide).coerceIn(0, 7)
        val col = ((x - boardLeft) * 8 / boardSide).coerceIn(0, 7)
        boardPixels[y * width + x] = if ((row + col) % 2 == 0) 0xFFE8D0A8.toInt() else 0xFF9A704C.toInt()
    }
    val geometry = BoardGeometryDetector.detect(width, height, boardPixels)
    check(geometry != null) { "synthetic chessboard not detected" }
    check(kotlin.math.abs(geometry.left - boardLeft.toFloat() / width) < 0.07f)
    check(kotlin.math.abs(geometry.top - boardTop.toFloat() / height) < 0.07f)
    check(BoardGeometryDetector.detect(256, 256, IntArray(256 * 256) { 0xFF777777.toInt() }) == null)
    // Model-ready tensor preprocessing: shape, normalization, and canonical orientation.
    val tensorPixels = IntArray(256 * 256) { index ->
        val x = index % 256; val y = index / 256
        val square = (y / 32) * 8 + (x / 32)
        (0xFF shl 24) or ((square * 4) shl 16) or (80 shl 8) or 160
    }
    val tensor = BoardSquareTensorizer.tensorize(256, 256, tensorPixels, outputSize = 16)
    check(tensor.squareCount == 64 && tensor.values.size == 64 * 16 * 16 * 3)
    check(tensor.values.all { it in -1f..1f })
    val tensorFlipped = BoardSquareTensorizer.tensorize(256, 256, tensorPixels, outputSize = 16, flipped = true)
    check(kotlin.math.abs(tensor.square(0)[0] - tensorFlipped.square(63)[0]) < 0.0001f)
    check(kotlin.math.abs(tensor.square(63)[0] - tensorFlipped.square(0)[0]) < 0.0001f)

    // TFLite output decoding: empty + all 12 piece/color labels, logits and confidence rejection.
    val expectedLabels = listOf(null, 'P', 'N', 'B', 'R', 'Q', 'K', 'p', 'n', 'b', 'r', 'q', 'k')
    for (classIndex in expectedLabels.indices) {
        val logits = FloatArray(13) { -5f }.also { it[classIndex] = 5f }
        val prediction = PiecePredictionDecoder.decode(logits)
        check(prediction.piece == expectedLabels[classIndex] && prediction.accepted) { "bad model class $classIndex" }
    }
    val uncertainPrediction = PiecePredictionDecoder.decode(FloatArray(13) { 0.05f }.also { it[3] = 0.4f })
    check(!uncertainPrediction.accepted)
    check(runCatching { PiecePredictionDecoder.decode(FloatArray(12)) }.isFailure)
    check(runCatching { PiecePredictionDecoder.decode(FloatArray(13).also { it[0] = Float.NaN }) }.isFailure)

    println("PASS: FEN, occupancy, geometry, move inference, model tensors and 13-class output decoding")
}
