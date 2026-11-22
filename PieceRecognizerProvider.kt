package com.chesscheatpro.mobile

import android.content.Context

/** Loads the bundled network (assets/piece_cnn.bin) once per process. */
object PieceRecognizerProvider {
    @Volatile private var cached: BoardRecognizer? = null

    fun get(context: Context): BoardRecognizer? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = runCatching {
                context.applicationContext.assets.open(NativePieceNet.ASSET_NAME).use { BoardRecognizer(NativePieceNet.load(it)) }
            }.getOrNull()
            cached = loaded
            return loaded
        }
    }
}
