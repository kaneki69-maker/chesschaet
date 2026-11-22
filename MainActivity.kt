package com.chesscheatpro.mobile

import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

private val Bg = Color(0xFF101116)
private val Panel = Color(0xFF1B1D25)
private val Gold = Color(0xFFD7AD58)
private const val INITIAL_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

class MainActivity : ComponentActivity() {
    private var capturedImagePath by mutableStateOf<String?>(null)
    private var captureMessage by mutableStateOf("Захват экрана выключен")
    /** True right after a capture arrives: the scan screen opens by itself so every move costs one tap. */
    private var scanRequested by mutableStateOf(false)
    private val scanSession = ScanSession()
    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenCaptureService.ACTION_CAPTURED -> {
                    capturedImagePath = intent.getStringExtra(ScreenCaptureService.EXTRA_PATH)
                    scanRequested = capturedImagePath != null
                    captureMessage = "Снимок сохранён в приватной папке приложения"
                }
                ScreenCaptureService.ACTION_FAILED -> captureMessage = intent.getStringExtra(ScreenCaptureService.EXTRA_ERROR) ?: "Ошибка захвата"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_CAPTURED)
            addAction(ScreenCaptureService.ACTION_FAILED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(captureReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(captureReceiver, filter)
        }
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Bg, surface = Panel)) { TrainingScreen() } }
    }

    override fun onDestroy() {
        try { unregisterReceiver(captureReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    @Composable
    private fun TrainingScreen() {
        var pgnMoves by remember { mutableStateOf(emptyList<String>()) }
        var headers by remember { mutableStateOf(emptyMap<String, String>()) }
        var startFen by remember { mutableStateOf(INITIAL_FEN) }
        var currentPly by remember { mutableIntStateOf(0) }
        var currentFen by remember { mutableStateOf(INITIAL_FEN) }
        var inputFen by remember { mutableStateOf("") }
        var showBoardEditor by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Импортируй PGN/FEN или сделай снимок экрана с шахматной доской.") }
        var lines by remember { mutableStateOf(emptyList<EngineLine>()) }
        var analyzedFen by remember { mutableStateOf<String?>(null) }
        var analyzing by remember { mutableStateOf(false) }
        var analysisJob by remember { mutableStateOf<Job?>(null) }
        var boardFlipped by remember { mutableStateOf(false) }
        var tracking by remember { mutableStateOf(false) }
        var engineFile by remember { mutableStateOf(LocalStockfish.resolve(applicationInfo.nativeLibraryDir?.let { File(it) }, filesDir)) }
        var engineStatus by remember {
            mutableStateOf(
                engineFile?.let { if (it.name == LocalStockfish.BUNDLED_LIBRARY) "Stockfish встроен в приложение" else "Stockfish импортирован пользователем" }
                    ?: "Локальный движок не найден — доступен только облачный анализ (кэш Lichess)"
            )
        }
        var depth by remember { mutableIntStateOf(14) }
        var multiPv by remember { mutableIntStateOf(3) }
        val scope = rememberCoroutineScope()
        val best: EngineLine? = lines.firstOrNull()

        fun resetAnalysis() { analysisJob?.cancel(); lines = emptyList(); analyzedFen = null; analyzing = false }

        val overlayPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this@MainActivity)) {
                best?.let { line ->
                    val overlayIntent = Intent(this@MainActivity, AnalysisOverlayService::class.java).apply {
                        putExtra(AnalysisOverlayService.EXTRA_MOVE, line.bestMove)
                        putExtra(AnalysisOverlayService.EXTRA_SCORE, "Оценка ${line.score} · глубина ${line.depth}")
                    }
                    startService(overlayIntent)
                }
            } else status = "Разрешение на плавающее окно не предоставлено"
        }
        val screenCaptureLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_CAPTURE
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
                }
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                    captureMessage = "Запрошен один снимок экрана…"
                } catch (e: Exception) { captureMessage = e.message ?: "Не удалось запустить захват" }
            } else captureMessage = "Разрешение на захват не предоставлено"
        }

        fun refreshPosition(ply: Int) {
            try {
                val session = ChessSession(startFen, pgnMoves)
                currentFen = session.snapshot(ply).fen
                currentPly = ply.coerceIn(0, pgnMoves.size)
                resetAnalysis()
                status = "Позиция ${currentPly}/${pgnMoves.size} · легальность ходов проверена"
            } catch (e: Exception) {
                status = "Ошибка партии: ${e.message ?: "некорректный ход"}"
            }
        }

        /** Mirrors the tracked scan session into the move list / board. */
        fun syncFromSession() {
            val tracker = scanSession.tracker ?: return
            startFen = tracker.startFen
            pgnMoves = tracker.history.map { it.san }
            currentPly = pgnMoves.size
            currentFen = tracker.current.toFen()
            boardFlipped = tracker.flipped ?: boardFlipped
            headers = emptyMap()
            tracking = true
            resetAnalysis()
        }

        fun startAnalysis() {
            val fen = currentFen
            val binary = engineFile
            val selectedDepth = depth
            val selectedMulti = multiPv
            analysisJob?.cancel()
            analyzing = true; lines = emptyList(); analyzedFen = null
            status = if (binary != null) "Stockfish анализирует позицию…" else "Запрос облачной базы…"
            analysisJob = scope.launch {
                try {
                    if (binary != null) {
                        val result = LocalStockfish.analyze(binary, fen, selectedDepth, selectedMulti) { progress ->
                            if (currentFen == fen) { lines = progress; analyzedFen = fen }
                        }
                        if (currentFen == fen) { lines = result.lines; analyzedFen = fen; status = "Готово · ${result.engineName} · глубина ${result.best.depth}" }
                    } else {
                        val line = CloudAnalysis.analyze(fen)
                        if (currentFen == fen) { lines = listOf(line); analyzedFen = fen; status = "Анализ получен из публичной облачной базы" }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    status = "Анализ остановлен"; throw e
                } catch (e: Exception) { status = e.message ?: "Анализ недоступен" }
                finally { analyzing = false }
            }
        }

        val enginePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    val staged = File(cacheDir, "stockfish-upload")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(staged).use { output -> input.copyTo(output) }
                    } ?: error("Не удалось прочитать выбранный файл")
                    try { LocalStockfish.install(staged, filesDir, Build.SUPPORTED_ABIS.toList()) } finally { staged.delete() }
                }.onSuccess {
                    LocalStockfish.shutdown()
                    engineFile = LocalStockfish.resolve(applicationInfo.nativeLibraryDir?.let { File(it) }, filesDir)
                    engineStatus = "Stockfish импортирован · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}"
                    status = "Локальный движок готов к анализу"
                }.onFailure {
                    engineStatus = "Ошибка установки движка"
                    status = it.message ?: "Неизвестная ошибка"
                }
            }
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Файл пуст")
            }.onSuccess { content ->
                scanSession.clear(); tracking = false
                if (ChessImport.isFen(content.trim())) {
                    startFen = content.trim(); pgnMoves = emptyList(); headers = emptyMap(); currentPly = 0
                    currentFen = startFen; resetAnalysis(); status = "FEN импортирован"
                } else {
                    val game = ChessImport.parsePgn(content)
                    require(game.moves.isNotEmpty()) { "В PGN не найдены ходы" }
                    val importedFen = game.headers["FEN"]?.takeIf { game.headers["SetUp"] == "1" } ?: INITIAL_FEN
                    require(ChessImport.isFen(importedFen)) { "В заголовке PGN указан некорректный FEN" }
                    val session = ChessSession(importedFen, game.moves)
                    val final = session.validateAll()
                    pgnMoves = game.moves; headers = game.headers; startFen = importedFen
                    currentPly = 0; currentFen = importedFen; resetAnalysis()
                    status = "PGN загружен · ${final.totalPlies} полуходов · результат ${game.result}"
                }
            }.onFailure { status = "Не удалось импортировать: ${it.message}" }
        }

        val scanPath = capturedImagePath
        if (scanRequested && scanPath != null) {
            BoardScanDialog(
                imagePath = scanPath,
                session = scanSession,
                onDismiss = { scanRequested = false },
                onApply = { fen, flipped ->
                    scanRequested = false
                    syncFromSession()
                    boardFlipped = flipped
                    status = if (pgnMoves.isEmpty()) "Позиция из снимка применена · можно запустить анализ"
                             else "Отслеживание: ${pgnMoves.size} полуходов · последний ${pgnMoves.last()}"
                    if (currentFen != fen) currentFen = fen
                }
            )
        }

        if (showBoardEditor) {
            BoardEditorDialog(
                initialFen = currentFen,
                onDismiss = { showBoardEditor = false },
                onApply = { fen ->
                    scanSession.resetTo(fen, boardFlipped)
                    syncFromSession()
                    showBoardEditor = false
                    status = "Позиция из редактора применена · можно запустить анализ"
                }
            )
        }

        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("CHESSCHEAT PRO", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("Training Analyzer", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Surface(color = Color(0xFF29251D), shape = RoundedCornerShape(18.dp)) {
                        Text("ML + STOCKFISH · 3.0", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = Gold, fontSize = 9.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("text/*", "application/*", "*/*")) }, modifier = Modifier.weight(1f)) { Text("Импорт PGN / FEN") }
                    OutlinedButton(onClick = {
                        scanSession.clear(); tracking = false; boardFlipped = false
                        startFen = INITIAL_FEN; pgnMoves = emptyList(); headers = emptyMap(); currentPly = 0; currentFen = startFen
                        resetAnalysis(); status = "Новая тренировочная позиция"
                    }) { Text("Новая") }
                }
                OutlinedButton(onClick = { showBoardEditor = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Открыть редактор шахматной позиции")
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Распознавание по снимку экрана", color = Gold, fontWeight = FontWeight.Bold)
                        Text("Один снимок за раз, только после системного разрешения Android. Доска, фигуры и ход определяются на устройстве; непрерывного наблюдения нет.", color = Color.LightGray, fontSize = 10.sp)
                        Button(onClick = {
                            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (tracking) "Сканировать следующий ход" else "Сделать снимок и распознать") }
                        Text(captureMessage, color = Color.Gray, fontSize = 10.sp)
                        if (capturedImagePath != null) {
                            OutlinedButton(onClick = { scanRequested = true }, modifier = Modifier.fillMaxWidth()) { Text("Открыть последний снимок") }
                        }
                        if (tracking) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    if (scanSession.undo()) { syncFromSession(); status = "Последний ход отменён" } else status = "Нет ходов для отмены"
                                }, modifier = Modifier.weight(1f)) { Text("Отменить ход") }
                                OutlinedButton(onClick = {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("PGN", scanSession.pgn()))
                                    status = "PGN скопирован в буфер обмена"
                                }, modifier = Modifier.weight(1f)) { Text("Копировать PGN") }
                            }
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Движок", color = Gold, fontWeight = FontWeight.Bold)
                        Text(engineStatus, color = if (engineFile != null) Color(0xFF8BCB9B) else Color(0xFFE5A45C), fontSize = 11.sp)
                        OutlinedButton(onClick = { enginePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (engineFile == null) "Импортировать Stockfish (ELF)" else "Заменить Stockfish")
                        }
                    }
                }
                if (headers.isNotEmpty()) {
                    Text("${headers["White"].orEmpty()}  —  ${headers["Black"].orEmpty()}   ${headers["Result"].orEmpty()}", color = Color.LightGray, fontSize = 12.sp)
                }
                val highlight = best?.takeIf { analyzedFen == currentFen && it.bestMove.length >= 4 }?.let {
                    runCatching { Position.squareIndex(it.bestMove.substring(0, 2)) to Position.squareIndex(it.bestMove.substring(2, 4)) }.getOrNull()
                }
                ChessBoard(currentFen, boardFlipped, highlight)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (currentFen.split(" ").getOrNull(1) == "w") "Ход белых" else "Ход чёрных", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { boardFlipped = !boardFlipped }) { Text("Перевернуть доску") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { refreshPosition(0) }, enabled = currentPly > 0, modifier = Modifier.weight(1f)) { Text("|◀") }
                    OutlinedButton(onClick = { refreshPosition(currentPly - 1) }, enabled = currentPly > 0, modifier = Modifier.weight(1f)) { Text("◀") }
                    Text("${currentPly}/${pgnMoves.size}", color = Gold, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { refreshPosition(currentPly + 1) }, enabled = currentPly < pgnMoves.size, modifier = Modifier.weight(1f)) { Text("▶") }
                    OutlinedButton(onClick = { refreshPosition(pgnMoves.size) }, enabled = currentPly < pgnMoves.size, modifier = Modifier.weight(1f)) { Text("▶|") }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Анализ позиции", color = Gold, fontWeight = FontWeight.Bold)
                        Text("Глубина: $depth", color = Color.LightGray, fontSize = 11.sp)
                        Slider(value = depth.toFloat(), onValueChange = { depth = it.toInt().coerceIn(4, 24) }, valueRange = 4f..24f, steps = 19)
                        if (engineFile != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Линий:", color = Color.LightGray, fontSize = 11.sp)
                                (1..3).forEach { n -> FilterChip(selected = multiPv == n, onClick = { multiPv = n }, label = { Text("$n") }) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { startAnalysis() }, enabled = !analyzing, modifier = Modifier.weight(1f)) {
                                Text(if (analyzing) "Анализирую…" else if (engineFile != null) "Анализировать Stockfish" else "Найти облачный анализ")
                            }
                            if (analyzing) OutlinedButton(onClick = { analysisJob?.cancel() }) { Text("Стоп") }
                        }
                        val shown = lines.takeIf { analyzedFen == currentFen }.orEmpty()
                        shown.firstOrNull()?.let { line ->
                            val san = EngineFormat.bestMoveSan(currentFen, line.bestMove) ?: line.bestMove
                            Text("Лучший ход: $san  (${line.bestMove.take(2)} → ${line.bestMove.substring(2, 4)})", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        shown.forEach { line ->
                            Text("${line.score} · гл. ${line.depth} · ${EngineFormat.numberedLine(currentFen, line.pv.ifEmpty { listOf(line.bestMove) }, 7)}", color = Color.LightGray, fontSize = 11.sp)
                        }
                        shown.firstOrNull()?.let { line ->
                            Text("${line.nodes} узлов${if (line.nps > 0) " · ${line.nps / 1000} kn/s" else ""}", color = Color.Gray, fontSize = 10.sp)
                            OutlinedButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this@MainActivity)) {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                                    overlayPermissionLauncher.launch(intent)
                                } else {
                                    val overlayIntent = Intent(this@MainActivity, AnalysisOverlayService::class.java).apply {
                                        putExtra(AnalysisOverlayService.EXTRA_MOVE, line.bestMove)
                                        putExtra(AnalysisOverlayService.EXTRA_SCORE, "Оценка ${line.score} · глубина ${line.depth}")
                                    }
                                    startService(overlayIntent)
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Показать в плавающем окне") }
                        }
                        Text(status, color = Color(0xFFE6DCC8), fontSize = 11.sp)
                        Text("Облачный анализ доступен только для позиций из публичного кэша Lichess. Для остальных нужен локальный Stockfish.", color = Color.Gray, fontSize = 10.sp)
                    }
                }
                if (pgnMoves.isNotEmpty()) {
                    Text("Ходы партии", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        pgnMoves.chunked(2).forEachIndexed { index, pair ->
                            Row(Modifier.fillMaxWidth().background(if (index * 2 < currentPly) Color(0xFF29251D) else Panel, RoundedCornerShape(7.dp)).padding(9.dp)) {
                                Text("${index + 1}.", color = Gold, modifier = Modifier.width(34.dp))
                                Text(pair.getOrElse(0) { "" }, color = if (currentPly == index * 2 + 1) Gold else Color.White, modifier = Modifier.weight(1f))
                                Text(pair.getOrElse(1) { "" }, color = if (currentPly == index * 2 + 2) Gold else Color.LightGray, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    OutlinedTextField(value = inputFen, onValueChange = { inputFen = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Вставить FEN") }, minLines = 2)
                    OutlinedButton(onClick = {
                        if (ChessImport.isFen(inputFen)) {
                            scanSession.clear(); tracking = false
                            startFen = inputFen.trim(); pgnMoves = emptyList(); currentPly = 0; currentFen = startFen; resetAnalysis(); status = "FEN принят"
                        } else status = "Некорректный FEN: проверь 8 горизонталей и 6 полей"
                    }, modifier = Modifier.fillMaxWidth()) { Text("Загрузить позицию") }
                }
                Text("Анализ партий и позиций для обучения. Не используй приложение для подсказок в играх против других людей: это нарушает правила платформ и честную игру.", color = Color.Gray, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun ChessBoard(fen: String, flipped: Boolean, highlight: Pair<Int, Int>?) {
    val squares = remember(fen) { runCatching { BoardPositionCodec.decodePlacement(fen) }.getOrDefault(List(64) { null }) }
    val glyphs = mapOf('K' to "♔", 'Q' to "♕", 'R' to "♖", 'B' to "♗", 'N' to "♘", 'P' to "♙", 'k' to "♚", 'q' to "♛", 'r' to "♜", 'b' to "♝", 'n' to "♞", 'p' to "♟")
    Column(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF29251D), RoundedCornerShape(8.dp)).padding(3.dp)) {
        repeat(8) { row ->
            Row(Modifier.weight(1f)) {
                repeat(8) { col ->
                    val view = row * 8 + col
                    val index = if (flipped) 63 - view else view
                    val piece = squares[index]
                    val light = (row + col) % 2 == 0
                    val marked = highlight != null && (index == highlight.first || index == highlight.second)
                    val base = if (light) Color(0xFFF0D9B5) else Color(0xFFB58863)
                    Box(Modifier.weight(1f).fillMaxHeight().background(if (marked) Color(0xFFD7AD58) else base), contentAlignment = Alignment.Center) {
                        Text(piece?.let { glyphs[it] }.orEmpty(), fontSize = 31.sp, color = if (piece?.isUpperCase() == true) Color(0xFFFDFDFD) else Color(0xFF202027))
                    }
                }
            }
        }
    }
}
