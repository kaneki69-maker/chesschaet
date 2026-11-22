package com.chesscheatpro.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

@Composable
fun BoardEditorDialog(
    initialFen: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    val squares = remember(initialFen) { mutableStateListOf<Char?>().apply { addAll(BoardPositionCodec.decodePlacement(initialFen)) } }
    var whiteToMove by remember(initialFen) { mutableStateOf(initialFen.split(' ').getOrNull(1) != "b") }
    var selectedPiece by remember { mutableStateOf<Char?>('P') }
    var error by remember { mutableStateOf<String?>(null) }
    val glyphs = mapOf('K' to "♔", 'Q' to "♕", 'R' to "♖", 'B' to "♗", 'N' to "♘", 'P' to "♙", 'k' to "♚", 'q' to "♛", 'r' to "♜", 'b' to "♝", 'n' to "♞", 'p' to "♟")
    val palette = listOf('K','Q','R','B','N','P','k','q','r','b','n','p')

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактор позиции", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("Выбери фигуру, затем нажимай на клетки. Ластик очищает клетку.", color = Color.LightGray, fontSize = 11.sp)
                Column(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF29251D), RoundedCornerShape(6.dp)).padding(2.dp)) {
                    repeat(8) { row ->
                        Row(Modifier.weight(1f)) {
                            repeat(8) { col ->
                                val index = row * 8 + col
                                val piece = squares[index]
                                Box(
                                    Modifier.weight(1f).fillMaxHeight()
                                        .background(if ((row + col) % 2 == 0) Color(0xFFF0D9B5) else Color(0xFFB58863))
                                        .clickable { squares[index] = selectedPiece; error = null },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(piece?.let { glyphs[it] }.orEmpty(), fontSize = 25.sp,
                                        color = if (piece?.isUpperCase() == true) Color.White else Color(0xFF202027))
                                }
                            }
                        }
                    }
                }
                Text("Фигуры", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                    palette.forEach { piece ->
                        val active = selectedPiece == piece
                        Surface(
                            color = if (active) Color(0xFF66502A) else Color(0xFF30323B),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f).height(37.dp).clickable { selectedPiece = piece }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(glyphs[piece].orEmpty(), fontSize = 20.sp, color = Color.White)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = selectedPiece == null, onClick = { selectedPiece = null }, label = { Text("Ластик") })
                    FilterChip(selected = whiteToMove, onClick = { whiteToMove = true }, label = { Text("Ход белых") })
                    FilterChip(selected = !whiteToMove, onClick = { whiteToMove = false }, label = { Text("Ход чёрных") })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching { BoardPositionCodec.encodeFen(squares, whiteToMove) }
                    .onSuccess { fen ->
                        if (ChessImport.isFen(fen)) onApply(fen) else error = "Нужны ровно один белый и один чёрный король; проверь позицию"
                    }
                    .onFailure { error = it.message ?: "Не удалось создать FEN" }
            }) { Text("Применить позицию") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { for (i in squares.indices) squares[i] = null; error = null }) { Text("Очистить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
        containerColor = Color(0xFF1B1D25),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
