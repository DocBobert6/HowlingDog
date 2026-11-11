package app.howlingdog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5, feature 3 — the library overhaul's pure logic: search,
 * sort, collections, and metadata.
 */
class LibraryViewTest {

    private fun entry(mainFile: String, name: String? = null, addedAt: Long = 0L, lastPlayed: Long = 0L, mode: String? = null) =
        GameEntry(
            mainFile = mainFile,
            displayName = name ?: displayNameFor(mainFile),
            files = listOf(mainFile),
            addedAt = addedAt,
            mode = mode,
            lastPlayed = lastPlayed,
        )

    @Test
    fun filterMatchesByNameSubstring() {
        val games = listOf(
            entry("kof98.neo"),
            entry("mslug3.neo"),
            entry("blazingstar.neo"),
        )
        // The cart's header title is the real title: a cart
        // filed as "kof98.neo" is found by "king of fighters".
        val meta = mapOf(
            "kof98.neo" to GameMetadata(1998, "SNK", "Fighting", 2, "The King of Fighters '98"),
        )
        assertEquals(listOf("kof98.neo"), LibraryView.filterGames(games, "king of fighters", meta).map { it.mainFile })
        assertEquals(listOf("mslug3.neo"), LibraryView.filterGames(games, "SLUG").map { it.mainFile })
        assertEquals(listOf("blazingstar.neo"), LibraryView.filterGames(games, "blazing").map { it.mainFile })
        assertEquals(games, LibraryView.filterGames(games, ""))
        assertEquals(games, LibraryView.filterGames(games, "   "))
        assertEquals(emptyList<GameEntry>(), LibraryView.filterGames(games, "no such game"))
    }

    @Test
    fun filterMatchesInitials() {
        // "kof" is not a substring of "the king of fighters 98",
        // but it is the name's initials — the natural search.
        val games = listOf(entry("kof98.neo"), entry("kof99.neo"), entry("mslug3.neo"))
        val meta = mapOf(
            "kof98.neo" to GameMetadata(1998, "SNK", "Fighting", 2, "The King of Fighters '98"),
            "kof99.neo" to GameMetadata(1999, "SNK", "Fighting", 2, "The King of Fighters '99"),
        )
        assertEquals(
            listOf("kof98.neo", "kof99.neo"),
            LibraryView.filterGames(games, "kof", meta).map { it.mainFile },
        )
    }

    @Test
    fun filterMatchesTheOriginalNameAfterARename() {
        val renamed = entry("kof98.neo").copy(customName = "My Favourite")
        val games = listOf(renamed)
        // The header title is what the real title search reads —
        // the UI always passes the metadata map, so the test does
        // too.
        val meta = mapOf(
            "kof98.neo" to GameMetadata(1998, "SNK", "Fighting", 2, "The King of Fighters '98"),
        )
        assertEquals(listOf("kof98.neo"), LibraryView.filterGames(games, "king of fighters", meta).map { it.mainFile })
        assertEquals(listOf("kof98.neo"), LibraryView.filterGames(games, "favourite").map { it.mainFile })
    }

    @Test
    fun sortByNameKeepsPinsOnTop() {
        val games = listOf(
            entry("mslug3.neo", addedAt = 3),
            entry("kof98.neo", addedAt = 1),
            entry("blazingstar.neo", addedAt = 2),
        )
        val pinned = games[1].copy(pinned = true)
        val sorted = LibraryView.sortGames(listOf(games[0], pinned, games[2]), LibrarySort.NAME)
        assertEquals(listOf("kof98.neo", "blazingstar.neo", "mslug3.neo"), sorted.map { it.mainFile })
    }

    @Test
    fun sortByYearPutsUnknownLastInBothDirections() {
        val meta = mapOf(
            "kof98.neo" to GameMetadata(1998, "SNK", "Fighting", 2),
            "mslug5.neo" to GameMetadata(2003, "SNK Playmore", "Run and gun", 2),
        )
        val games = listOf(
            entry("unknown.neo"),
            entry("kof98.neo"),
            entry("mslug5.neo"),
        )
        assertEquals(
            listOf("mslug5.neo", "kof98.neo", "unknown.neo"),
            LibraryView.sortGames(games, LibrarySort.YEAR_NEW, meta).map { it.mainFile },
        )
        assertEquals(
            listOf("kof98.neo", "mslug5.neo", "unknown.neo"),
            LibraryView.sortGames(games, LibrarySort.YEAR_OLD, meta).map { it.mainFile },
        )
    }

    @Test
    fun sortByRecentPutsNeverPlayedLast() {
        val games = listOf(
            entry("a.neo", lastPlayed = 100),
            entry("b.neo", lastPlayed = 0),
            entry("c.neo", lastPlayed = 300),
        )
        assertEquals(
            listOf("c.neo", "a.neo", "b.neo"),
            LibraryView.sortGames(games, LibrarySort.RECENT).map { it.mainFile },
        )
    }

    @Test
    fun sortBySystemGroupsByMode() {
        val games = listOf(
            entry("cdgame.cue", mode = "cd"),
            entry("cart1.neo", mode = "aes"),
            entry("cart2.neo", mode = "mvs"),
        )
        // "Arcade" < "Home console" < "Neo Geo CD" by label.
        assertEquals(
            listOf("cart2.neo", "cart1.neo", "cdgame.cue"),
            LibraryView.sortGames(games, LibrarySort.SYSTEM).map { it.mainFile },
        )
    }

    @Test
    fun metadataFromTheHeaderAndTheTable() {
        // A cart: year and maker from the header, genre and
        // players from the table (catalogue first).
        val identity = NeoIdentity(title = "The King of Fighters '98", maker = "SNK", year = 1998, catalogue = "242")
        val meta = LibraryView.metadataFor(entry("kof98.neo"), identity)
        assertEquals(GameMetadata(1998, "SNK", "Fighting", 2, "The King of Fighters '98"), meta)
        assertEquals("1998 · SNK · Fighting · 2 players", meta.displayLine())
    }

    @Test
    fun metadataFallsBackToTheTitleWhenTheCatalogueIsUnknown() {
        // A Japanese header title with no catalogue match still
        // finds its row by the English title — here the catalogue
        // is absent entirely, so the title does the work.
        val identity = NeoIdentity(title = "Garou: Mark of the Wolves", maker = "SNK", year = 1999, catalogue = null)
        val meta = LibraryView.metadataFor(entry("garou.neo"), identity)
        assertEquals("Fighting", meta.genre)
        assertEquals(2, meta.players)
    }

    @Test
    fun metadataForADiscHasNoYearOrMaker() {
        // Discs have no header: the title lookup gives a genre
        // and players, and the line omits the unknown parts. A
        // disc filed under a short name ("kof99.cue") finds no
        // row — the title has to be there to match, and nothing
        // is invented for it.
        val meta = LibraryView.metadataFor(entry("The King of Fighters '99.cue"), null)
        assertEquals(GameMetadata(null, null, "Fighting", 2), meta)
        assertEquals("Fighting · 2 players", meta.displayLine())
        assertNull(LibraryView.metadataFor(entry("kof99.cue"), null).genre)
    }

    @Test
    fun metadataForAnUnknownGameIsEmpty() {
        val meta = LibraryView.metadataFor(entry("somehomebrew.neo"), null)
        assertEquals(GameMetadata(null, null, null, null), meta)
        assertEquals("", meta.displayLine())
        assertNull(LibraryView.metadataFor(entry("x.neo"), null).genre)
    }

    @Test
    fun metadataLineUsesOnePlayerForSinglePlayerGames() {
        val meta = GameMetadata(1991, "SNK", "Action", 1)
        assertEquals("1991 · SNK · Action · 1 player", meta.displayLine())
    }

    @Test
    fun collectionStoreRoundTrip() {
        val file = File("/tmp/hd-test-collections.txt")
        file.delete()
        val store = CollectionStore(file)
        assertEquals(emptyList<String>(), store.collections())
        assertNull(store.collectionFor("kof98.neo"))
        store.setCollection("kof98.neo", "Fighting")
        store.setCollection("garou.neo", "Fighting")
        store.setCollection("mslug3.neo", "Run and gun")
        assertEquals(listOf("Fighting", "Run and gun"), store.collections())
        assertEquals("Fighting", store.collectionFor("kof98.neo"))
        // A fresh store reads the same file.
        val reopened = CollectionStore(file)
        assertEquals("Run and gun", reopened.collectionFor("mslug3.neo"))
        // Clearing one game leaves the others.
        reopened.setCollection("garou.neo", null)
        assertNull(reopened.collectionFor("garou.neo"))
        assertEquals("Fighting", reopened.collectionFor("kof98.neo"))
        // A blank name clears too.
        reopened.setCollection("kof98.neo", "   ")
        assertNull(reopened.collectionFor("kof98.neo"))
        assertEquals(listOf("Run and gun"), reopened.collections())
        file.delete()
    }

    @Test
    fun collectionNamesAreCleaned() {
        val file = File("/tmp/hd-test-collections-clean.txt")
        file.delete()
        val store = CollectionStore(file)
        store.setCollection("kof98.neo", "  Fighting | Games\n")
        assertEquals("Fighting   Games", store.collectionFor("kof98.neo"))
        assertTrue(store.collections().single().length <= 40)
        file.delete()
    }
}
