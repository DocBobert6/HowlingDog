package app.howlingdog

/**
 * v1.5, feature 4 — cheats: GameShark / Pro Action Replay style
 * codes, one byte written to the game's work RAM after every
 * displayed frame.
 *
 * The format is HowlingDog's own, documented in the add-code
 * dialog — 8 hex digits, `AAAAAAVV`: the first 6 are the 68k
 * work-RAM address (0x100000–0x10FFFF), the last 2 are the byte
 * value. Example: `10D0E909` writes 0x09 to 0x10D0E9. It is NOT
 * claimed as a verified GameShark standard: the GameShark format
 * could not be verified from any source, so this is the format
 * the app supports, described honestly as its own.
 *
 * Pure Kotlin, no Android — unit-tested on the plain JVM
 * (CheatCodeTest). The write itself is native (see
 * ra_cheat_write in bridge/jni/ra_client.c): the same work RAM
 * RetroAchievements reads, through the same memory provider.
 */
data class CheatCode(
    /** The 68k work-RAM address, 0x100000–0x10FFFF. */
    val address: Int,
    /** The byte value written there every frame, 0–255. */
    val value: Int,
) {
    /** The code as the user types it: 8 hex digits, `AAAAAAVV`. */
    fun format(): String = "%06X%02X".format(address, value)

    companion object {
        /** The 68k work RAM: 0x100000–0x10FFFF (64 KB). */
        const val RAM_START: Int = 0x100000
        const val RAM_END: Int = 0x10FFFF

        /**
         * Parse an 8-hex-digit code, or null when it is not one:
         * the wrong length, a non-hex digit, or an address
         * outside the work RAM. Upper- and lower-case hex both
         * work; surrounding whitespace is ignored.
         */
        fun parse(text: String): CheatCode? {
            val clean = text.trim().uppercase()
            if (clean.length != 8) return null
            if (!clean.all { it in '0'..'9' || it in 'A'..'F' }) return null
            val address = clean.substring(0, 6).toIntOrNull(16) ?: return null
            val value = clean.substring(6, 8).toIntOrNull(16) ?: return null
            if (address !in RAM_START..RAM_END) return null
            return CheatCode(address, value)
        }
    }
}
