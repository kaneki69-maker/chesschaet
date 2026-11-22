package com.chesscheatpro.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** Read-only cloud evaluation for imported training positions. Only cached positions are available. */
object CloudAnalysis {
    suspend fun analyze(fen: String): EngineLine = withContext(Dispatchers.IO) {
        require(ChessImport.isFen(fen)) { "Для облачного анализа передан некорректный FEN" }
        val encoded = URLEncoder.encode(fen, "UTF-8")
        val connection = URL("https://lichess.org/api/cloud-eval?fen=$encoded&multiPv=1").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ChessCheatPro-TrainingAnalyzer/1.1")
            val code = connection.responseCode
            if (code == 404) throw IllegalStateException("Этой позиции нет в облачной базе. Попробуй другую позицию позже.")
            if (code !in 200..299) throw IllegalStateException("Сервис анализа ответил HTTP $code")
            val bodyBytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 1_000_000) throw IllegalStateException("Ответ облачного анализа слишком большой")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val json = JSONObject(String(bodyBytes, Charsets.UTF_8))
            val pv = json.optJSONArray("pvs")?.optJSONObject(0) ?: throw IllegalStateException("Нет варианта анализа")
            val best = pv.optString("moves").trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            if (!best.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) {
                throw IllegalStateException("Сервис вернул некорректный UCI-ход")
            }
            // Lichess cloud-eval scores are already from White's point of view.
            val moves = pv.optString("moves").trim().split(Regex("\\s+")).filter { it.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")) }
            val mate = if (pv.has("mate")) pv.optInt("mate") else null
            val cp = if (mate == null) pv.optInt("cp", 0) else null
            val score = if (mate != null) "Мат ${if (mate > 0) "+" else ""}$mate" else String.format(java.util.Locale.US, "%+.2f", (cp ?: 0) / 100.0)
            EngineLine(best, score, json.optInt("depth", 0), json.optLong("knodes", 0) * 1000, moves, 1, cp, mate)
        } finally { connection.disconnect() }
    }
}
