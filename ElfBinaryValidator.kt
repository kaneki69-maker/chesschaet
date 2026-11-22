package com.chesscheatpro.mobile

/** Pure ELF header validation, kept separate so architecture checks can be unit-tested. */
object ElfBinaryValidator {
    data class Info(val bits: Int, val machine: Int, val architecture: String)

    fun inspect(header: ByteArray, supportedAbis: List<String>): Info {
        require(header.size >= 20) { "ELF-заголовок слишком короткий" }
        require(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
            "Выбранный файл не является ELF-бинарником"
        }
        val bits = when (header[4].toInt() and 0xff) {
            1 -> 32
            2 -> 64
            else -> throw IllegalArgumentException("Неизвестный класс ELF")
        }
        require((header[5].toInt() and 0xff) == 1) { "Поддерживается только little-endian ELF" }
        require((header[6].toInt() and 0xff) == 1) { "Некорректная версия ELF" }
        val machine = ((header[19].toInt() and 0xff) shl 8) or (header[18].toInt() and 0xff)
        val (architecture, expectedBits) = when (machine) {
            183 -> "arm64-v8a" to 64
            40 -> "armeabi-v7a" to 32
            62 -> "x86_64" to 64
            else -> throw IllegalArgumentException("Неподдерживаемая архитектура ELF (machine=$machine)")
        }
        require(bits == expectedBits) { "Разрядность ELF не соответствует архитектуре $architecture" }
        require(architecture in supportedAbis) { "Движок $architecture несовместим с этим устройством" }
        return Info(bits, machine, architecture)
    }
}
