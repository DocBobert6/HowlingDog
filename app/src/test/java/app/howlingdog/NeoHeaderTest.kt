package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the .NEO header read and the cover-art
 * filename matching. The network fetch itself is NOT tested here
 * (no live network in unit tests): the art URLs were verified by
 * fetching them from the sandbox instead, and are reported with
 * the build.
 */
class NeoHeaderTest {

    /** A synthetic .NEO header with the given identity fields, like a real converted cart. */
    private fun header(title: String, maker: String, year: Int, ngh: Int): ByteArray {
        val bytes = ByteArray(0x100)
        bytes[0] = 'N'.code.toByte(); bytes[1] = 'E'.code.toByte()
        bytes[2] = 'O'.code.toByte(); bytes[3] = 0x01
        putU32(bytes, 0x1C, year)
        putU32(bytes, 0x28, ngh)
        putAscii(bytes, 0x2C, title)
        putAscii(bytes, 0x4D, maker)
        return bytes
    }

    private fun putU32(bytes: ByteArray, off: Int, v: Int) {
        bytes[off] = (v and 0xFF).toByte()
        bytes[off + 1] = ((v shr 8) and 0xFF).toByte()
        bytes[off + 2] = ((v shr 16) and 0xFF).toByte()
        bytes[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putAscii(bytes: ByteArray, off: Int, s: String) {
        s.toByteArray(Charsets.US_ASCII).copyInto(bytes, off)
    }

    // --- Header parse: Robert's five converted carts ---

    @Test fun parsesNeoBomberman() {
        val id = NeoHeader.parse(header("Neo Bomberman", "Hudson", 1997, 0x93))
        assertEquals(NeoIdentity("Neo Bomberman", "Hudson", 1997, "093"), id)
    }

    @Test fun parsesMetalSlug5() {
        val id = NeoHeader.parse(header("Metal Slug 5", "SNK Playmore", 2003, 0x268))
        assertEquals(NeoIdentity("Metal Slug 5", "SNK Playmore", 2003, "268"), id)
    }

    @Test fun parsesShockTroopers() {
        val id = NeoHeader.parse(header("Shock Troopers (set 1)", "Saurus", 1997, 0x238))
        assertEquals(NeoIdentity("Shock Troopers (set 1)", "Saurus", 1997, "238"), id)
    }

    @Test fun parsesKof98() {
        val id = NeoHeader.parse(header("The King of Fighters '98", "SNK", 1998, 0x242))
        assertEquals(NeoIdentity("The King of Fighters '98", "SNK", 1998, "242"), id)
    }

    @Test fun parsesGalaxyFight() {
        val id = NeoHeader.parse(header("Galaxy Fight", "Sunsoft", 1995, 0x78))
        assertEquals(NeoIdentity("Galaxy Fight", "Sunsoft", 1995, "078"), id)
    }

    @Test fun rejectsBadMagic() {
        val bytes = header("Metal Slug 5", "SNK Playmore", 2003, 0x268)
        bytes[0] = 'X'.code.toByte()
        assertNull(NeoHeader.parse(bytes))
    }

    @Test fun rejectsShortHeader() {
        assertNull(NeoHeader.parse(ByteArray(0x40)))
    }

    // --- Matching: the six verified filenames, plus KOF '98's long name ---

    private val index = listOf(
        "Galaxy Fight - Universal Warriors.png",
        "Metal Slug 5 (NGH-2680).png",
        "Neo Bomberman.png",
        "Shock Troopers (set 1).png",
        "Cyber-Lip (NGM-010).png",
        "Metal Slug 5 Plus (bootleg).png",
        "The King of Fighters '98 - The Slugfest _ King of Fighters '98 - dream match never ends (NGH-2420).png",
    )

    @Test fun matchesExactTitle() {
        assertEquals("Neo Bomberman.png", NeoHeader.matchArtFile("Neo Bomberman", "093", index))
    }

    @Test fun matchesNghNumber() {
        assertEquals("Metal Slug 5 (NGH-2680).png", NeoHeader.matchArtFile("Metal Slug 5", "268", index))
    }

    @Test fun matchesSetTitleExactly() {
        assertEquals(
            "Shock Troopers (set 1).png",
            NeoHeader.matchArtFile("Shock Troopers (set 1)", "238", index),
        )
    }

    @Test fun matchesTitlePrefixSubtitle() {
        assertEquals(
            "Galaxy Fight - Universal Warriors.png",
            NeoHeader.matchArtFile("Galaxy Fight", "078", index),
        )
    }

    @Test fun matchesNgmNumber() {
        assertEquals("Cyber-Lip (NGM-010).png", NeoHeader.matchArtFile("Cyber-Lip", "010", index))
    }

    @Test fun matchesLongKof98NameByNumber() {
        assertEquals(
            "The King of Fighters '98 - The Slugfest _ King of Fighters '98 - dream match never ends (NGH-2420).png",
            NeoHeader.matchArtFile("The King of Fighters '98", "242", index),
        )
    }

    @Test fun matchesBootlegOnlyWhenTitleSaysBootleg() {
        assertEquals(
            "Metal Slug 5 Plus (bootleg).png",
            NeoHeader.matchArtFile("Metal Slug 5 Plus (bootleg)", null, index),
        )
    }

    @Test fun parentGameNeverMatchesBootlegArt() {
        // The only "Metal Slug 5 …" file left is the bootleg: no match, not the bootleg.
        val bootlegOnly = listOf("Metal Slug 5 Plus (bootleg).png")
        assertNull(NeoHeader.matchArtFile("Metal Slug 5", "268", bootlegOnly))
    }

    // --- CD titles: the cue/disc display name carries region/language suffixes ---

    @Test fun matchesCdCyberLipWithRegionTags() {
        // The disc title is "Cyber Lip Japan EnJa" (the cue
        // filename's region/language suffix); the art file is
        // "Cyber-Lip (NGM-010).png". After stripping the suffixes
        // the title is "cyber lip", which prefixes the art name.
        assertEquals("Cyber-Lip (NGM-010).png", NeoHeader.matchArtFile("Cyber Lip Japan EnJa", null, index))
    }

    @Test fun matchesCdGalaxyFightWithRegionTag() {
        // The disc title is "Galaxy Fight Universal Warriors
        // Japan"; the art file is "Galaxy Fight - Universal
        // Warriors.png". After stripping, the two are exactly equal.
        assertEquals(
            "Galaxy Fight - Universal Warriors.png",
            NeoHeader.matchArtFile("Galaxy Fight Universal Warriors Japan", null, index),
        )
    }

    @Test fun matchesCdGalaxyFightWithParenthesisedRegion() {
        // The region tag in parentheses ("(Japan)") normalises
        // to the same trailing word, and is stripped the same way.
        assertEquals(
            "Galaxy Fight - Universal Warriors.png",
            NeoHeader.matchArtFile("Galaxy Fight Universal Warriors (Japan)", null, index),
        )
    }

    @Test fun stripRegionTagsStripsOnlyTrailingTags() {
        assertEquals("cyber lip", NeoHeader.stripRegionTags("cyber lip japan enja"))
        assertEquals("galaxy fight universal warriors", NeoHeader.stripRegionTags("galaxy fight universal warriors japan"))
        assertEquals("metal slug 5", NeoHeader.stripRegionTags("metal slug 5"))
        // A region word inside the title is not stripped.
        assertEquals("world heroes", NeoHeader.stripRegionTags("world heroes"))
        // A title that IS a region word is never stripped to nothing.
        assertEquals("japan", NeoHeader.stripRegionTags("japan"))
        // A glued language pair strips, and so does a region+language pair.
        assertEquals("cyber lip", NeoHeader.stripRegionTags("cyber lip jaen"))
        assertEquals("cyber lip", NeoHeader.stripRegionTags("cyber lip jpen"))
    }

    @Test fun bootlegGuardStillHoldsWithRegionTags() {
        // A disc title with a region tag still never lands on
        // the bootleg: the guard runs on the stripped title.
        val bootlegOnly = listOf("Metal Slug 5 Plus (bootleg).png")
        assertNull(NeoHeader.matchArtFile("Metal Slug 5 Japan", "268", bootlegOnly))
    }

    @Test fun noMatchReturnsNull() {
        assertNull(NeoHeader.matchArtFile("Some Unreleased Proto", null, index))
        assertNull(NeoHeader.matchArtFile("Metal Slug 5", "268", emptyList()))
    }

    // --- Index JSON + URL ---

    @Test fun parsesIndexJson() {
        val json = """
            [{"name": "Neo Bomberman.png", "type": "file"},
             {"name": "README.md", "type": "file"},
             {"name": "Metal Slug 5 (NGH-2680).png", "type": "file"}]
        """.trimIndent()
        assertEquals(
            listOf("Neo Bomberman.png", "Metal Slug 5 (NGH-2680).png"),
            NeoHeader.parseIndexJson(json),
        )
    }

    @Test fun artUrlEncodesName() {
        assertEquals(
            "https://raw.githubusercontent.com/libretro-thumbnails/SNK_-_Neo_Geo/master/Named_Boxarts/Metal%20Slug%205%20%28NGH-2680%29.png",
            NeoHeader.artUrl("Metal Slug 5 (NGH-2680).png"),
        )
    }
}
