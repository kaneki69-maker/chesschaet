import com.chesscheatpro.mobile.*
import java.io.File

/** Writes a seeded random legal game as one FEN per line (ply 0 first). Usage: GenGameKt out.txt seed plies */
fun main(args: Array<String>) {
    val rnd = java.util.Random(args[1].toLong())
    var pos = Position.start()
    val out = StringBuilder(pos.toFen()).append('\n')
    repeat(args[2].toInt()) {
        val legal = pos.legalMoves()
        if (legal.isEmpty()) return@repeat
        val caps = legal.filter { it.captured != null || it.promotion != null }
        val pick = if (caps.isNotEmpty() && rnd.nextInt(3) == 0) caps[rnd.nextInt(caps.size)] else legal[rnd.nextInt(legal.size)]
        pos = pos.play(pick)
        out.append(pos.toFen()).append('\n')
    }
    File(args[0]).writeText(out.toString())
}
