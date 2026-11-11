package app.howlingdog

import java.io.File

/**
 * v1.5, feature 3 — the library overhaul's pure logic: search,
 * sort, collections, and game metadata. No Android here, so it
 * is unit-tested on the plain JVM (LibraryViewTest).
 *
 * Metadata: year and maker come from the .NEO header for carts
 * (already parsed by [NeoHeader]); genre and players come from a
 * small built-in table below, keyed by the NGH catalogue number
 * first, then by the normalised title. The table covers the games
 * he owns plus the most popular Neo Geo titles, and only what is
 * confident: the NGH numbers were checked against the neo-geo.com
 * Master List and the SNK Wiki, which agree. A game with no table
 * row shows as unknown — never invented. Discs have no header,
 * so their year and maker are unknown (the table lookup by title
 * still gives them a genre and players).
 *
 * The catalogue lookup matters because a .NEO header can carry a
 * Japanese title ("Ryuuko no Ken 2") while the table knows the
 * English one ("Art of Fighting 2") — the catalogue number is
 * the same game either way.
 */
enum class LibrarySort {
    NAME,
    YEAR_NEW,
    YEAR_OLD,
    RECENT,
    SYSTEM,
}

/** The metadata shown for a game: year and maker from the header, genre and players from the table. */
data class GameMetadata(
    val year: Int?,
    val maker: String?,
    val genre: String?,
    val players: Int?,
    /**
     * The header title for a cart ("The King of Fighters
     * '98"), null for discs and unknown headers. It is NOT
     * part of the display line — the game name is already
     * shown — but the search matches it, so a cart filed as
     * "kof98.neo" is found by typing "king of fighters".
     */
    val title: String? = null,
) {
    /**
     * The metadata line under the picked game: "1998 · SNK ·
     * Fighting · 2 players", omitting unknown parts, so a disc
     * reads "Fighting · 2 players" and an unknown game reads
     * nothing at all. A single player reads "1 player".
     */
    fun displayLine(): String {
        val parts = mutableListOf<String>()
        year?.let { parts.add(it.toString()) }
        maker?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        genre?.let { parts.add(it) }
        players?.let { parts.add(if (it == 1) "1 player" else "$it players") }
        return parts.joinToString(" · ")
    }
}

object LibraryView {

    /**
     * Genre and players, by NGH catalogue number ("242", "268" —
     * the 3-digit form [NeoIdentity.catalogue] carries). Only
     * numbers verified against the neo-geo.com Master List and
     * the SNK Wiki are here; a title whose number was not
     * verified is keyed by title only, below.
     */
    private val BY_CATALOGUE: Map<String, Pair<String, Int>> = mapOf(
        "033" to ("Fighting" to 2), // Fatal Fury
        "041" to ("Sports" to 2), // Baseball Stars 2
        "044" to ("Fighting" to 2), // Art of Fighting
        "045" to ("Fighting" to 2), // Samurai Shodown
        "047" to ("Fighting" to 2), // Fatal Fury 2
        "055" to ("Fighting" to 2), // The King of Fighters '94
        "056" to ("Fighting" to 2), // Art of Fighting 2
        "058" to ("Fighting" to 2), // Fatal Fury Special
        "063" to ("Fighting" to 2), // Samurai Shodown II
        "065" to ("Sports" to 2), // Windjammers
        "069" to ("Fighting" to 2), // Fatal Fury 3
        "075" to ("Shoot 'em up" to 2), // Aero Fighters 2
        "078" to ("Fighting" to 2), // Galaxy Fight
        "084" to ("Fighting" to 2), // The King of Fighters '95
        "087" to ("Fighting" to 2), // Samurai Shodown III
        "096" to ("Fighting" to 2), // Art of Fighting 3
        "097" to ("Shoot 'em up" to 2), // Aero Fighters 3
        "200" to ("Sports" to 2), // Neo Turf Masters
        "201" to ("Run and gun" to 2), // Metal Slug
        "214" to ("Fighting" to 2), // The King of Fighters '96
        "222" to ("Fighting" to 2), // Samurai Shodown IV
        "232" to ("Fighting" to 2), // The King of Fighters '97
        "239" to ("Shoot 'em up" to 2), // Blazing Star
        "241" to ("Run and gun" to 2), // Metal Slug 2
        "242" to ("Fighting" to 2), // The King of Fighters '98
        "243" to ("Fighting" to 2), // The Last Blade 2
        "250" to ("Run and gun" to 2), // Metal Slug X
        "251" to ("Fighting" to 2), // The King of Fighters '99
        "253" to ("Fighting" to 2), // Garou: Mark of the Wolves
        "256" to ("Run and gun" to 2), // Metal Slug 3
        "257" to ("Fighting" to 2), // The King of Fighters 2000
        "262" to ("Fighting" to 2), // The King of Fighters 2001
        "263" to ("Run and gun" to 2), // Metal Slug 4
        "268" to ("Run and gun" to 2), // Metal Slug 5
    )

    /**
     * Genre and players, by normalised title ([NeoHeader.
     * normalizeForMatch] — region/language tags stripped, so a
     * disc named "Cyber Lip Japan EnJa" looks up "cyber lip").
     * Every table row is here by title as well as by catalogue,
     * so a game whose header carries no catalogue (or a disc,
     * which has no header at all) still finds its row.
     */
    private val BY_TITLE: Map<String, Pair<String, Int>> = mapOf(
        "fatal fury" to ("Fighting" to 2),
        "baseball stars 2" to ("Sports" to 2),
        "art of fighting" to ("Fighting" to 2),
        "samurai shodown" to ("Fighting" to 2),
        "fatal fury 2" to ("Fighting" to 2),
        "the king of fighters 94" to ("Fighting" to 2),
        "art of fighting 2" to ("Fighting" to 2),
        "fatal fury special" to ("Fighting" to 2),
        "samurai shodown ii" to ("Fighting" to 2),
        "windjammers" to ("Sports" to 2),
        "fatal fury 3" to ("Fighting" to 2),
        "aero fighters 2" to ("Shoot 'em up" to 2),
        "galaxy fight" to ("Fighting" to 2),
        "the king of fighters 95" to ("Fighting" to 2),
        "samurai shodown iii" to ("Fighting" to 2),
        "art of fighting 3" to ("Fighting" to 2),
        "aero fighters 3" to ("Shoot 'em up" to 2),
        "neo turf masters" to ("Sports" to 2),
        "metal slug" to ("Run and gun" to 2),
        "the king of fighters 96" to ("Fighting" to 2),
        "samurai shodown iv" to ("Fighting" to 2),
        "the king of fighters 97" to ("Fighting" to 2),
        "blazing star" to ("Shoot 'em up" to 2),
        "metal slug 2" to ("Run and gun" to 2),
        "the king of fighters 98" to ("Fighting" to 2),
        "the last blade 2" to ("Fighting" to 2),
        "metal slug x" to ("Run and gun" to 2),
        "the king of fighters 99" to ("Fighting" to 2),
        "garou mark of the wolves" to ("Fighting" to 2),
        "metal slug 3" to ("Run and gun" to 2),
        "the king of fighters 2000" to ("Fighting" to 2),
        "the king of fighters 2001" to ("Fighting" to 2),
        "metal slug 4" to ("Run and gun" to 2),
        "metal slug 5" to ("Run and gun" to 2),
        "twinkle star sprites" to ("Puzzle" to 2),
        "shock troopers" to ("Run and gun" to 2),
        "neo bomberman" to ("Action" to 2),
        "prehistoric isle 2" to ("Shoot 'em up" to 2),
        "real bout fatal fury" to ("Fighting" to 2),
        "real bout fatal fury 2" to ("Fighting" to 2),
        "world heroes" to ("Fighting" to 2),
        "world heroes 2" to ("Fighting" to 2),
        "king of the monsters" to ("Fighting" to 2),
        "king of the monsters 2" to ("Fighting" to 2),
        "magician lord" to ("Platform" to 2),
        "nam 1975" to ("Run and gun" to 2),
        "puzzle bobble" to ("Puzzle" to 2),
        "puzzle bobble 2" to ("Puzzle" to 2),
        "magical drop" to ("Puzzle" to 2),
        "magical drop ii" to ("Puzzle" to 2),
        "super sidekicks" to ("Sports" to 2),
        "league bowling" to ("Sports" to 2),
        "strikers 1945 plus" to ("Shoot 'em up" to 2),
        "viewpoint" to ("Shoot 'em up" to 2),
        "last resort" to ("Shoot 'em up" to 2),
        "pulstar" to ("Shoot 'em up" to 2),
        "cyber lip" to ("Run and gun" to 2),
        "robo army" to ("Action" to 2),
        "mutation nation" to ("Action" to 2),
        "sengoku" to ("Action" to 2),
        "ninja commando" to ("Run and gun" to 2),
        "eight man" to ("Action" to 2),
        "top hunter" to ("Platform" to 2),
        "spin master" to ("Platform" to 2),
        "blue s journey" to ("Platform" to 2),
        "crossed swords" to ("Action" to 2),
        "ninja combat" to ("Action" to 2),
        "soccer brawl" to ("Sports" to 2),
        "3 count bout" to ("Fighting" to 2),
        "kizuna encounter" to ("Fighting" to 2),
        "waku waku 7" to ("Fighting" to 2),
        "breakers" to ("Fighting" to 2),
        "rage of the dragons" to ("Fighting" to 2),
        "the super spy" to ("Action" to 1),
        "ghost pilots" to ("Shoot 'em up" to 2),
        "svc chaos snk vs capcom" to ("Fighting" to 2),
        "the last blade" to ("Fighting" to 2),
        "double dragon" to ("Fighting" to 2),
        "matrimelee" to ("Fighting" to 2),
        "karnov s revenge" to ("Fighting" to 2),
        "thrash rally" to ("Racing" to 2),
        "neo drift out" to ("Racing" to 2),
        "over top" to ("Racing" to 2),
    )

    /**
     * The metadata for a game: year and maker from its .NEO
     * header when it has one (carts), genre and players from
     * the table — catalogue number first (it survives a
     * Japanese header title), then the normalised title. A
     * disc has no header, so its year and maker are unknown
     * and its genre comes from the title alone.
     */
    fun metadataFor(entry: GameEntry, identity: NeoIdentity?): GameMetadata {
        val genrePlayers = identity?.catalogue?.let { BY_CATALOGUE[it] }
            ?: BY_TITLE[NeoHeader.normalizeForMatch(identity?.title ?: entry.displayName)]
        return GameMetadata(
            year = identity?.year,
            maker = identity?.maker?.takeIf { it.isNotBlank() },
            genre = genrePlayers?.first,
            players = genrePlayers?.second,
            title = identity?.title?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The library in the picked order. Pinned favourites stay
     * on top in every sort (the existing library rule), then
     * the sort key, then the name. A game with no year sorts
     * last in both year orders; a game never played sorts last
     * in Recently played.
     */
    fun sortGames(
        games: List<GameEntry>,
        sort: LibrarySort,
        metadata: Map<String, GameMetadata> = emptyMap(),
    ): List<GameEntry> {
        val byName = compareBy<GameEntry> { it.effectiveName.lowercase() }.thenBy { it.addedAt }
        val pinnedFirst = compareByDescending<GameEntry> { it.pinned }
        return when (sort) {
            LibrarySort.NAME -> games.sortedWith(pinnedFirst.then(byName))
            LibrarySort.YEAR_NEW -> games.sortedWith(
                pinnedFirst
                    .thenByDescending { metadata[it.mainFile]?.year ?: Int.MIN_VALUE }
                    .then(byName),
            )
            LibrarySort.YEAR_OLD -> games.sortedWith(
                pinnedFirst
                    .thenBy { metadata[it.mainFile]?.year ?: Int.MAX_VALUE }
                    .then(byName),
            )
            LibrarySort.RECENT -> games.sortedWith(
                pinnedFirst.thenByDescending { it.lastPlayed }.then(byName),
            )
            LibrarySort.SYSTEM -> games.sortedWith(
                pinnedFirst.thenBy { modeLabel(it.effectiveMode) }.then(byName),
            )
        }
    }

    /**
     * The games whose name contains the query: a case-insensitive
     * substring on the normalised name (the shown name, the
     * original, and the header title when the metadata knows
     * one — so a cart filed as "kof98.neo" is found by "king of
     * fighters"), or on the name's initials ("kof" finds the
     * King of Fighters games). A blank query matches everything.
     */
    fun filterGames(
        games: List<GameEntry>,
        query: String,
        metadata: Map<String, GameMetadata> = emptyMap(),
    ): List<GameEntry> {
        val q = NeoHeader.normalizeTitle(query)
        if (q.isBlank()) return games
        return games.filter { entry ->
            val names = listOfNotNull(
                entry.effectiveName,
                entry.displayName,
                metadata[entry.mainFile]?.title,
            )
            names.any { name ->
                val norm = NeoHeader.normalizeTitle(name)
                norm.contains(q) || initials(norm).contains(q)
            }
        }
    }

    /** The first letter of each word ("the king of fighters 98" -> "tkof98"). */
    fun initials(normalized: String): String =
        normalized.split(' ').filter { it.isNotEmpty() }.map { it[0] }.joinToString("")
}

/**
 * v1.5, feature 3 — collections: the groups a game belongs to
 * ("Fighting", "Shooters"), one per game, stored in
 * `collections.txt` as one `mainFile|collection` line each.
 * A game with no line has no collection. Plain file IO, no
 * Android — unit-tested on the plain JVM (LibraryViewTest).
 */
class CollectionStore(private val file: File) {

    /** The collection names in use, A–Z, for the filter chips and the picker. */
    fun collections(): List<String> = load().values.toSet().sorted()

    /** The collection a game is in, or null when it has none. */
    fun collectionFor(mainFile: String): String? = load()[mainFile]

    /**
     * Put a game in a collection (or take it out, with null or
     * a blank name). Names are trimmed, and '|' and newlines
     * are replaced — they would break the store line — and
     * capped at 40 characters.
     */
    fun setCollection(mainFile: String, collection: String?) {
        val map = load().toMutableMap()
        val clean = collection?.replace('|', ' ')?.replace('\n', ' ')?.replace('\r', ' ')
            ?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
        if (clean == null) map.remove(mainFile) else map[mainFile] = clean
        save(map)
    }

    private fun load(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val out = LinkedHashMap<String, String>()
        try {
            for (line in file.readLines()) {
                if (line.isBlank()) continue
                val parts = line.split('|')
                if (parts.size != 2) continue
                val main = parts[0].trim()
                val collection = parts[1].trim()
                if (main.isEmpty() || collection.isEmpty()) continue
                out[main] = collection
            }
        } catch (_: Exception) {
            return emptyMap()
        }
        return out
    }

    private fun save(map: Map<String, String>) {
        try {
            file.parentFile?.mkdirs()
            val text = map.entries.sortedBy { it.key }
                .joinToString("\n") { (main, collection) -> "$main|$collection" }
            file.writeText(if (text.isEmpty()) "" else text + "\n")
        } catch (_: Exception) {
            // Best effort, like the library store: a failed save
            // degrades to "not remembered" rather than a crash.
        }
    }
}
