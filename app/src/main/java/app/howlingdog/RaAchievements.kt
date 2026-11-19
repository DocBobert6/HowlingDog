package app.howlingdog

import java.net.URLEncoder

/**
 * Phase 10 — RetroAchievements: the achievement model.
 *
 * Pure Kotlin, so it is covered by the pure-test harness. The JSON
 * comes from the native layer (nativeRaGetAchievements), which
 * builds it from the rc_client list; each entry carries the id,
 * title, description, points, state, and — for measured
 * achievements — the progress string and percent — plus the badge
 * URLs and the server-computed rarity (added in v2.2.0, Step 1).
 * The badge *images* are loaded by the badge loader (Step 2), not
 * here.
 *
 * States mirror rc_client_achievement_state: INACTIVE (0) means
 * the achievement can never be earned in this game, ACTIVE (1)
 * means it can, UNLOCKED (2) means the player earned it, and
 * DISABLED (3) means the game is not fully loaded or the
 * achievement is unsupported. The list screen shows unlocked and
 * active achievements; inactive ones are hidden.
 *
 * The JSON is parsed by the minimal parser below, not org.json:
 * org.json is a stub in android.jar (it throws "Stub!" on the
 * plain JVM), which is why UpdateChecker — the only other
 * org.json user — is not in the pure-test harness. The parser
 * only needs the native layer's shape: an array of flat objects
 * whose values are strings, numbers, or (for robustness)
 * true/false/null.
 */
data class RaAchievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    /** One of [RaAchievements.STATE_INACTIVE], [RaAchievements.STATE_ACTIVE], [RaAchievements.STATE_UNLOCKED], [RaAchievements.STATE_DISABLED]. */
    val state: Int,
    val unlocked: Boolean,
    val measuredProgress: String? = null,
    val measuredPercent: Double? = null,
    /** The 64 px badge image URL (unlocked badge), or null when the native layer sent none. */
    val badgeUrl: String? = null,
    /** The 64 px locked-badge image URL (greyed badge), or null when the native layer sent none. */
    val badgeLockedUrl: String? = null,
    /** Server-computed rarity: percent of players who earned it (0..100), or null when unknown. */
    val rarity: Double? = null,
    /** The same, for hardcore. HowlingDog is softcore-only, so this is carried for completeness and never displayed as earnable. */
    val rarityHardcore: Double? = null,
) {
    val isUnlocked: Boolean get() = unlocked || state == RaAchievements.STATE_UNLOCKED

    /** The badge URL for the achievement's current state: the unlocked badge once earned, the locked badge until then. */
    val badgeUrlForState: String? get() = if (isUnlocked) badgeUrl else badgeLockedUrl
}

object RaAchievements {
    const val STATE_INACTIVE = 0
    const val STATE_ACTIVE = 1
    const val STATE_UNLOCKED = 2
    const val STATE_DISABLED = 3

    /**
     * Parse the achievements JSON from the native layer: an array
     * of objects with id, title, description, points, state,
     * unlocked (an int, 0/1), and optionally measured_progress /
     * measured_percent, badge_url / badge_locked_url, and rarity /
     * rarity_hardcore. Malformed JSON (or a non-array) parses to
     * an empty list — a bad payload must never crash the list
     * screen. A single malformed entry is skipped, not fatal.
     */
    fun parse(json: String): List<RaAchievement> {
        val values = try {
            RaJson.parseArray(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<RaAchievement>(values.size)
        for (value in values) {
            // A non-object entry (a stray string, a number) is
            // skipped, not fatal.
            val obj = value as? Map<String, Any?> ?: continue
            try {
                out.add(parseOne(obj))
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    private fun parseOne(obj: Map<String, Any?>): RaAchievement {
        // The native layer emits unlocked as an int (0/1) and
        // measured_progress as "" when there is none. Badge URLs
        // are "" when the achievement has no badge (or the native
        // layer sent none); rarity is 0.0 when the server sent
        // none. Empty badge strings parse to null, and a 0.0
        // rarity is kept as 0.0 (it is a real server value, not
        // an absence marker).
        val measuredProgress = (obj["measured_progress"] as? String)
            ?.takeIf { it.isNotEmpty() }
        val measuredPercent = (obj["measured_percent"] as? Number)?.toDouble()
        val unlockedRaw = obj["unlocked"]
        return RaAchievement(
            id = (obj["id"] as? Number)?.toInt() ?: 0,
            title = obj["title"] as? String ?: "",
            description = obj["description"] as? String ?: "",
            points = (obj["points"] as? Number)?.toInt() ?: 0,
            state = (obj["state"] as? Number)?.toInt() ?: STATE_DISABLED,
            unlocked = when (unlockedRaw) {
                is Number -> unlockedRaw.toInt() != 0
                is Boolean -> unlockedRaw
                else -> false
            },
            measuredProgress = measuredProgress,
            measuredPercent = measuredPercent,
            badgeUrl = (obj["badge_url"] as? String)?.takeIf { it.isNotEmpty() },
            badgeLockedUrl = (obj["badge_locked_url"] as? String)?.takeIf { it.isNotEmpty() },
            rarity = (obj["rarity"] as? Number)?.toDouble(),
            rarityHardcore = (obj["rarity_hardcore"] as? Number)?.toDouble(),
        )
    }

    /** "3 / 12 unlocked" — the header line of the achievements screen. */
    fun summary(achievements: List<RaAchievement>): String {
        val unlocked = achievements.count { it.isUnlocked }
        return "$unlocked / ${achievements.size} unlocked"
    }

    /** "120 / 400 pts" — unlocked points over the set's total points, for the Center header. */
    fun pointsSummary(achievements: List<RaAchievement>): String {
        val unlocked = achievements.filter { it.isUnlocked }.sumOf { it.points }
        val total = achievements.sumOf { it.points }
        return "$unlocked / $total pts"
    }

    /** Fraction of the set unlocked (0..1), for the Center's gold completion bar. 0 when the set is empty. */
    fun completionFraction(achievements: List<RaAchievement>): Float {
        if (achievements.isEmpty()) return 0f
        return achievements.count { it.isUnlocked }.toFloat() / achievements.size.toFloat()
    }

    /**
     * The Center's sort orders. CLOSEST is the default: it
     * surfaces what the player can earn next.
     */
    enum class Sort {
        /** Locked first; measured ones by progress descending, then unmeasured by points; unlocked last. */
        CLOSEST,
        /** As loaded from the native layer (grouped by lock state). */
        DEFAULT,
        /** Points descending. */
        POINTS,
        /** Rarest first; unknown rarity last. */
        RARITY,
    }

    /**
     * Sort a loaded game's achievements. The sort is over data
     * already in memory — no network, no re-fetch. All sorts are
     * stable, so equal keys keep the loaded order.
     */
    fun sortAchievements(achievements: List<RaAchievement>, sort: Sort): List<RaAchievement> {
        return when (sort) {
            Sort.DEFAULT -> achievements.toList()
            Sort.POINTS -> achievements.sortedByDescending { it.points }
            Sort.RARITY -> achievements.sortedWith(
                compareBy<RaAchievement> { it.rarity == null }.thenBy { it.rarity ?: Double.MAX_VALUE },
            )
            Sort.CLOSEST -> achievements.sortedWith(
                compareBy<RaAchievement> { it.isUnlocked }
                    .thenByDescending { it.measuredProgress != null }
                    .thenByDescending { it.measuredPercent ?: 0.0 }
                    .thenByDescending { it.points },
            )
        }
    }

    /** Rarity tiers, keyed off the server percent already in hand. */
    enum class RarityTier(val label: String) {
        /** Earned by 5% of players or fewer. */
        VERY_RARE("Very rare"),
        /** Earned by 10% of players or fewer. */
        RARE("Rare"),
        /** Earned by more than 10% of players. */
        COMMON("Common"),
    }

    /**
     * The tier for a server rarity percent, or null when the
     * rarity is unknown — an unknown rarity is shown as no
     * rarity, never invented as common.
     */
    fun rarityTier(rarity: Double?): RarityTier? {
        if (rarity == null) return null
        return when {
            rarity <= 5.0 -> RarityTier.VERY_RARE
            rarity <= 10.0 -> RarityTier.RARE
            else -> RarityTier.COMMON
        }
    }

    /**
     * The haptic level for an unlock, from its rarity tier and
     * the user's haptics level (Step 4). A very rare unlock is
     * a heavy click, a rare one a click, a common one a light
     * tick — but never stronger than the user's own level, so
     * OFF means silence even for a very rare unlock, and a user
     * who chose LIGHT never gets a heavy buzz. An unknown rarity
     * reads as common.
     */
    fun unlockHapticLevel(tier: RarityTier?, userLevel: HapticLevel): HapticLevel {
        val tierLevel = when (tier) {
            RarityTier.VERY_RARE -> HapticLevel.HEAVY
            RarityTier.RARE -> HapticLevel.MEDIUM
            else -> HapticLevel.LIGHT
        }
        return minOf(tierLevel, userLevel)
    }

    /**
     * The "Search for Help" URL for an achievement: a web search
     * for "<game title> <achievement title> RetroAchievements",
     * opened in the default browser. (The per-game GuideURL would
     * be preferred when non-null, but it only exists in the RA
     * web API, which HowlingDog has no client for — so the search
     * is the whole feature.)
     */
    fun searchForHelpUrl(gameTitle: String?, achievementTitle: String): String {
        val query = listOfNotNull(
            gameTitle?.takeIf { it.isNotBlank() },
            achievementTitle,
            "RetroAchievements",
        ).joinToString(" ")
        return "https://duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8")
    }
}
