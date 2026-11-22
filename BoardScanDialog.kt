package com.chesscheatpro.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Glyphs = mapOf('K' to "♔", 'Q' to "♕", 'R' to "♖", 'B' to "♗", 'N' to "♘", 'P' to "♙", 'k' to "♚", 'q' to "♛", 'r' to "♜", 'b' to "♝", 'n' to "♞", 'p' to "♟")
private val Palette = listOf('K', 'Q', 'R', 'B', 'N', 'P', 'k', 'q', 'r', 'b', 'n', 'p')

/**
 * Scan screen: automatic board detection + ML piece recognition + move tracking.
 * The result is only committed to [session] when the user presses "Применить"; any manual
 * correction of squares, side to move or castling rights replaces the tracked position.
 */
@Composable
fun BoardScanDialog(
    imagePath: String,
    session: ScanSession,
    onDismiss: () -> Unit,
    onApply: (fen: String, flipped: Boolean) -> Unit
) {
    val context = LocalContext.current
    val source: Bitmap? = remember(imagePath) { BitmapFactory.decodeFile(imagePath) }
    DisposableEffect(source) { onDispose { if (source != null && !source.isRecycled) source.recycle() } }
    if (source == null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Ошибка изображения") },
            text = { Text("Не удалось открыть снимок экрана") }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
        return
    }
    val recognizer = remember { PieceRecognizerProvider.get(context) }
    var manualRect by remember { mutableStateOf<BoardScanPipeline.Rect?>(null) }
    var manualMode by remember { mutableStateOf(false) }
    var left by remember { mutableFloatStateOf(0.05f) }
    var top by remember { mutableFloatStateOf(0.2f) }
    var size by remember { mutableFloatStateOf(0.9f) }
    var busy by remember { mutableStateOf(true) }
    var scan by remember { mutableStateOf<BoardScanPipeline.Scan?>(null) }
    var step by remember { mutableStateOf<ScanSession.Step?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var whiteToMoveHint by remember { mutableStateOf<Boolean?>(null) }
    var flippedHint by remember { mutableStateOf<Boolean?>(null) }
    // Editable board (canonical order a8..h1) once the user touches anything.
    val squares = remember { mutableStateListOf<Char?>().apply { addAll(List(64) { null }) } }
    var edited by remember { mutableStateOf(false) }
    var flipped by remember { mutableStateOf(false) }
    var whiteToMove by remember { mutableStateOf(true) }
    var castling by remember { mutableStateOf(setOf<Char>()) }
    var enPassant by remember { mutableStateOf("-") }
    var selected by remember { mutableStateOf<Char?>('P') }
    var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }

    fun loadFen(fen: String) {
        val parts = fen.split(' ')
        val placed = BoardPositionCodec.decodePlacement(fen)
        for (i in 0 until 64) squares[i] = placed[i]
        whiteToMove = parts.getOrNull(1) != "b"
        castling = parts.getOrNull(2).orEmpty().filter { it in "KQkq" }.toSet()
        enPassant = parts.getOrNull(3) ?: "-"
    }

    // (Re)run the whole pipeline whenever the crop mode, crop or hints change.
    LaunchedEffect(source, manualMode, left, top, size, whiteToMoveHint, flippedHint, generation) {
        if (edited) return@LaunchedEffect
        busy = true; failure = null
        val outcome = withContext(Dispatchers.Default) {
            runCatching {
                val rec = recognizer ?: error("Встроенная модель распознавания не найдена в приложении")
                val pixels = IntArray(source.width * source.height)
                source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
                val rect = if (manualMode) {
                    val side = (minOf(source.width, source.height) * size).toInt()
                    BoardScanPipeline.Rect((left * source.width).toInt(), (top * source.height).toInt(), side)
                } else null
                val result = BoardScanPipeline.scan(source.width, source.height, pixels, rec, rect)
                    ?: return@runCatching null to null
                result to session.process(result, whiteToMoveHint, flippedHint)
            }
        }
        outcome.onSuccess { (result, interpreted) ->
            scan = result; step = interpreted
            if (result == null) failure = "Доска не найдена автоматически. Включи ручную область."
            when (interpreted) {
                is ScanSession.Step.Started -> { flipped = interpreted.flipped; loadFen(interpreted.fen) }
                is ScanSession.Step.Unchanged -> { flipped = session.tracker?.flipped ?: flipped; loadFen(interpreted.fen) }
                is ScanSession.Step.Moves -> { flipped = session.tracker?.flipped ?: flipped; loadFen(interpreted.fen) }
                else -> Unit
            }
        }.onFailure { failure = it.message ?: "Ошибка распознавания" }
        busy = false
    }

    val shownFlipped = flipped
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Распознавание позиции", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Ищу доску и распознаю 64 клетки…", color = Color.LightGray, fontSize = 11.sp)
                    }
                }
                failure?.let { Text(it, color = Color(0xFFE5A45C), fontSize = 11.sp) }
                val currentScan = scan
                if (currentScan != null) {
                    val rec = currentScan.recognition
                    val boardCrop = remember(currentScan) {
                        // Preview of the located board: lets the user see what was recognised.
                        val r = currentScan.rect
                        runCatching { Bitmap.createBitmap(source, r.left, r.top, r.side, r.side) }.getOrNull()
                    }
                    boardCrop?.let { Image(it.asImageBitmap(), "Найденная доска", Modifier.fillMaxWidth().heightIn(max = 170.dp)) }
                    Text(
                        (currentScan.detectionScore?.let { "Доска найдена автоматически (оценка ${it.toInt()})" } ?: "Область задана вручную") +
                            " · прочитано клеток ${64 - rec.uncertainCount}/64",
                        color = Color(0xFFD7AD58), fontSize = 11.sp
                    )
                }
                when (val s = step) {
                    is ScanSession.Step.Started -> {
                        Text("Позиция восстановлена по снимку.", color = Color(0xFF8BCB9B), fontSize = 11.sp)
                        s.assumptions.forEach { Text("• $it", color = Color(0xFFE5A45C), fontSize = 10.sp) }
                    }
                    is ScanSession.Step.Unchanged -> Text("С прошлого снимка ничего не изменилось.", color = Color(0xFF8BCB9B), fontSize = 11.sp)
                    is ScanSession.Step.Moves -> {
                        Text("Распознано: ${s.plies.joinToString(" ") { it.san }}", color = Color(0xFF8BCB9B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        s.note?.let { Text(it, color = Color(0xFFE5A45C), fontSize = 10.sp) }
                    }
                    is ScanSession.Step.Rejected -> {
                        Text(s.message, color = Color(0xFFE5A45C), fontSize = 11.sp)
                        s.candidates.forEach { Text("• $it", color = Color.LightGray, fontSize = 10.sp) }
                        Text("Ничего не применено. Поправь клетки вручную или повтори снимок.", color = Color.Gray, fontSize = 10.sp)
                    }
                    null -> Unit
                }

                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FilterChip(selected = !shownFlipped, onClick = { flipped = false; flippedHint = false; if (edited) Unit }, label = { Text("Белые снизу") })
                    FilterChip(selected = shownFlipped, onClick = { flipped = true; flippedHint = true }, label = { Text("Чёрные снизу") })
                }
                Text("Проверь доску. Нажатие на клетку ставит выбранную фигуру (ластик — пустая клетка).", color = Color.LightGray, fontSize = 10.sp)
                Column(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF29251D), RoundedCornerShape(5.dp)).padding(2.dp)) {
                    repeat(8) { row ->
                        Row(Modifier.weight(1f)) {
                            repeat(8) { col ->
                                val view = row * 8 + col
                                val canonical = if (shownFlipped) 63 - view else view
                                val piece = squares[canonical]
                                val unread = scan?.recognition?.uncertain?.getOrNull(view) == true && !edited
                                Box(
                                    Modifier.weight(1f).fillMaxHeight()
                                        .background(if (unread) Color(0xFFB04A3A) else if ((row + col) % 2 == 0) Color(0xFFF0D9B5) else Color(0xFFB58863))
                                        .clickable { squares[canonical] = selected; edited = true; error = null },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(piece?.let { Glyphs[it] }.orEmpty(), fontSize = 22.sp,
                                        color = if (piece?.isUpperCase() == true) Color.White else Color(0xFF171717))
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                    Palette.take(6).forEach { PaletteButton(it, selected == it, Modifier.weight(1f)) { selected = it } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                    Palette.drop(6).forEach { PaletteButton(it, selected == it, Modifier.weight(1f)) { selected = it } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = selected == null, onClick = { selected = null }, label = { Text("Ластик") })
                    FilterChip(selected = whiteToMove, onClick = { whiteToMove = true; whiteToMoveHint = true; edited = edited }, label = { Text("Ход белых") })
                    FilterChip(selected = !whiteToMove, onClick = { whiteToMove = false; whiteToMoveHint = false }, label = { Text("Ход чёрных") })
                }
                if (edited) {
                    Text("Позиция изменена вручную — она заменит отслеживаемую партию.", color = Color(0xFFE5A45C), fontSize = 10.sp)
                    OutlinedButton(onClick = { edited = false; generation++ }, modifier = Modifier.fillMaxWidth()) { Text("Отменить правки и распознать заново") }
                }
                Text("Рокировка (только если право ещё действует):", color = Color.LightGray, fontSize = 10.sp)
                listOf(listOf('K' to "Белые O-O", 'Q' to "Белые O-O-O"), listOf('k' to "Чёрные O-O", 'q' to "Чёрные O-O-O")).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        pair.forEach { (right, label) ->
                            FilterChip(selected = right in castling, onClick = {
                                castling = if (right in castling) castling - right else castling + right
                                edited = true
                            }, label = { Text(label, fontSize = 10.sp) })
                        }
                    }
                }
                OutlinedTextField(value = enPassant, onValueChange = { enPassant = it.trim().lowercase().take(2); edited = true },
                    label = { Text("En-passant: - или e3") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = manualMode, onCheckedChange = { manualMode = it })
                    Text("Выбрать область доски вручную", color = Color.LightGray, fontSize = 11.sp)
                }
                if (manualMode) {
                    val maxLeft = ((source.width - minOf(source.width, source.height) * size) / source.width).coerceIn(0.001f, 1f)
                    val maxTop = ((source.height - minOf(source.width, source.height) * size) / source.height).coerceIn(0.001f, 1f)
                    Text("Горизонталь: ${(left * 100).toInt()}%", color = Color.LightGray, fontSize = 10.sp)
                    Slider(left.coerceIn(0f, maxLeft), { left = it }, valueRange = 0f..maxLeft)
                    Text("Вертикаль: ${(top * 100).toInt()}%", color = Color.LightGray, fontSize = 10.sp)
                    Slider(top.coerceIn(0f, maxTop), { top = it }, valueRange = 0f..maxTop)
                    Text("Размер области: ${(size * 100).toInt()}%", color = Color.LightGray, fontSize = 10.sp)
                    Slider(size, { size = it.coerceIn(0.3f, 1f) }, valueRange = 0.3f..1f)
                }
                if (recognizer == null) {
                    Text("Файл весов piece_cnn.bin отсутствует в сборке — автоматическое распознавание недоступно, позицию можно расставить вручную.", color = Color(0xFFE5A45C), fontSize = 10.sp)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                Text("Распознавание выполняется на устройстве, снимок никуда не отправляется. Обязательно проверь позицию: ошибка в одной клетке меняет анализ.", color = Color.Gray, fontSize = 9.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    val fen = BoardPositionCodec.encodeFen(squares.toList(), whiteToMove, castling, enPassant)
                    require(ChessImport.isFen(fen)) { "Позиция не прошла проверку FEN" }
                    Position.fromFen(fen) // full legality check (kings, checks, pawns on back ranks)
                    fen
                }.onSuccess { fen ->
                    if (edited || step is ScanSession.Step.Rejected || step == null) session.resetTo(fen, flipped) else session.commit()
                    onApply(fen, flipped)
                }.onFailure { error = it.message ?: "Некорректная позиция" }
            }, enabled = !busy && scan != null || edited) { Text("Применить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = Color(0xFF1B1D25), titleContentColor = Color.White, textContentColor = Color.White
    )
}

@Composable
private fun PaletteButton(piece: Char, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(color = if (active) Color(0xFF66502A) else Color(0xFF30323B), shape = RoundedCornerShape(5.dp), modifier = modifier.height(36.dp).clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { Text(Glyphs[piece].orEmpty(), color = Color.White, fontSize = 19.sp) }
    }
}
