import com.chesscheatpro.mobile.ElfBinaryValidator

fun main() {
    fun header(bits: Int, machine: Int) = ByteArray(64).apply {
        this[0] = 0x7f.toByte(); this[1] = 69; this[2] = 76; this[3] = 70
        this[4] = if (bits == 32) 1 else 2; this[5] = 1; this[6] = 1
        this[18] = (machine and 255).toByte(); this[19] = (machine ushr 8).toByte()
    }
    check(ElfBinaryValidator.inspect(header(64, 183), listOf("arm64-v8a")).architecture == "arm64-v8a")
    check(ElfBinaryValidator.inspect(header(32, 40), listOf("armeabi-v7a")).architecture == "armeabi-v7a")
    check(runCatching { ElfBinaryValidator.inspect(header(32, 183), listOf("arm64-v8a")) }.isFailure)
    check(runCatching { ElfBinaryValidator.inspect(header(64, 62), listOf("arm64-v8a")) }.isFailure)
    check(runCatching { ElfBinaryValidator.inspect(ByteArray(64), listOf("arm64-v8a")) }.isFailure)
    println("PASS: ELF magic, bitness, endianness and device ABI validation")
}
