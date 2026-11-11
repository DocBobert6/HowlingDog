package app.howlingdog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.5, feature 4 — cheats: the code format and the per-game
 * store.
 */
class CheatCodeTest {

    @Test
    fun parseAcceptsEightHexDigits() {
        assertEquals(CheatCode(0x10D0E9, 0x09), CheatCode.parse("10D0E909"))
        assertEquals(CheatCode(0x100000, 0x00), CheatCode.parse("10000000"))
        assertEquals(CheatCode(0x10FFFF, 0xFF), CheatCode.parse("10FFFFFF"))
        // Lower-case hex and surrounding whitespace work.
        assertEquals(CheatCode(0x10D0E9, 0x09), CheatCode.parse("  10d0e909\n"))
    }

    @Test
    fun parseRejectsAnythingElse() {
        assertNull(CheatCode.parse(""))
        assertNull(CheatCode.parse("10D0E90")) // 7 digits
        assertNull(CheatCode.parse("10D0E9090")) // 9 digits
        assertNull(CheatCode.parse("10D0E90G")) // not hex
        assertNull(CheatCode.parse("10D0E9 9")) // a space inside
        assertNull(CheatCode.parse("0FFFFF09")) // below the work RAM
        assertNull(CheatCode.parse("11000009")) // above the work RAM
        assertNull(CheatCode.parse("00000009")) // the zero page, not work RAM
    }

    @Test
    fun formatRoundTrips() {
        assertEquals("10D0E909", CheatCode(0x10D0E9, 0x09).format())
        assertEquals("10000000", CheatCode(0x100000, 0x00).format())
        assertEquals("10FFFFFF", CheatCode(0x10FFFF, 0xFF).format())
        assertEquals(CheatCode(0x10D0E9, 0x09), CheatCode.parse(CheatCode(0x10D0E9, 0x09).format()))
    }
}

class CheatStoreTest {

    private fun store(): Pair<CheatStore, File> {
        val dir = File("/tmp/hd-test-cheats")
        dir.mkdirs()
        return CheatStore(dir) to dir
    }

    @Test
    fun storeRoundTrip() {
        val (store, dir) = store()
        val cheats = listOf(
            StoredCheat(CheatCode(0x10D0E9, 0x09), "Infinite lives", true),
            StoredCheat(CheatCode(0x10D100, 0x63), "Infinite health", false),
        )
        store.save("kof98.neo", cheats)
        assertEquals(cheats, store.load("kof98.neo"))
        // A fresh store reads the same file.
        assertEquals(cheats, CheatStore(dir).load("kof98.neo"))
        File(dir, "kof98.neo.txt").delete()
    }

    @Test
    fun missingFileLoadsAsNoCodes() {
        val (store, _) = store()
        assertEquals(emptyList<StoredCheat>(), store.load("never-added.neo"))
    }

    @Test
    fun malformedLinesAreSkipped() {
        val (store, dir) = store()
        File(dir, "mslug3.neo.txt").writeText(
            "10D0E909|Infinite lives|1\n" +
                "not-a-code|Bad line|1\n" +
                "0FFFFF09|Below the work RAM|1\n" +
                "10D10063|Infinite health|0\n" +
                "too|few|fields\n" +
                "10D200FF||1\n",
        )
        assertEquals(
            listOf(
                StoredCheat(CheatCode(0x10D0E9, 0x09), "Infinite lives", true),
                StoredCheat(CheatCode(0x10D100, 0x63), "Infinite health", false),
                // A blank name falls back to the code itself.
                StoredCheat(CheatCode(0x10D200, 0xFF), "10D200FF", true),
            ),
            store.load("mslug3.neo"),
        )
        File(dir, "mslug3.neo.txt").delete()
    }

    @Test
    fun savingNoCodesClearsTheFile() {
        val (store, dir) = store()
        store.save("kof98.neo", listOf(StoredCheat(CheatCode(0x10D0E9, 0x09), "Infinite lives", true)))
        assertEquals(1, store.load("kof98.neo").size)
        store.save("kof98.neo", emptyList())
        assertEquals(emptyList<StoredCheat>(), store.load("kof98.neo"))
        File(dir, "kof98.neo.txt").delete()
    }
}
