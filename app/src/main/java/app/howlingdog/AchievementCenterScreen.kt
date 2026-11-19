package app.howlingdog

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Achievement Center Step 3 — the hub.
 *
 * A select screen, not a settings page: the game title in the
 * Neo Geo system font, a gold completion bar, and one row per
 * achievement with its badge, a progress bar where the
 * achievement has one, and its points / rarity on the right.
 * The Leaderboards screen is the Center's second section
 * (same body as before, restyled to match, linked from the
 * header). Reached from the pause menu; the game stays paused
 * underneath (the session is not resumed until the player goes
 * back).
 *
 * This lives in its own file, NOT inlined in HowlingDogUi:
 * HowlingDogUi is at 243 registers in the CI debug build and
 * the Center must not push it over 255 (the v1.3.1 crash).
 * Replacing the two inline branches with calls to this screen
 * takes registers out of HowlingDogUi, not in.
 */
enum class CenterSection {
    ACHIEVEMENTS,
    LEADERBOARDS,
}

@Composable
fun AchievementCenterScreen(
    initialSection: CenterSection,
    badgeStore: RaBadgeStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val isWifi = remember { CoverArt.isWifi(context) }
    var section by remember { mutableStateOf(initialSection) }
    var sort by remember { mutableStateOf(RaAchievements.Sort.CLOSEST) }
    var selectedId by remember { mutableStateOf(0) }
    var selectedBoardId by remember { mutableStateOf(0) }

    // Live bars: refresh on open, then re-read while the Center
    // is visible. The game is paused underneath, so the poll
    // mostly re-reads the same values — but it keeps the bars
    // current if the Center is ever open during play, and it is
    // one cheap JNI call with no network. The effect is scoped
    // to this composable, so the timer stops the moment the
    // Center closes; nothing polls while playing.
    LaunchedEffect(Unit) {
        RaClient.refreshAchievements()
        while (true) {
            delay(2000)
            RaClient.refreshAchievements()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Header: game title in the system font (falling back
        // to the default font when the title has characters the
        // system font does not cover), the unlock + points
        // summary, and the gold completion bar.
        val title = RaClient.gameTitle ?: "Achievements"
        Text(
            title,
            color = Color(EraUi.Palette.GOLD),
            fontFamily = if (DisplayFontSpec.covers(title)) DisplayFontFamily else null,
            fontSize = DisplayFontSpec.LIBRARY_TITLE_SP.sp,
            fontWeight = FontWeight.Normal,
        )
        Spacer(Modifier.height(4.dp))
        if (RaClient.gameLoaded) {
            Text(
                RaAchievements.summary(RaClient.achievements) +
                    " — " + RaAchievements.pointsSummary(RaClient.achievements),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(Color(EraUi.Palette.MAROON)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(RaAchievements.completionFraction(RaClient.achievements))
                        .height(8.dp)
                        .background(Color(EraUi.Palette.GOLD)),
                )
            }
        } else {
            Text(
                "No achievements for this game.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        // Section switcher: Achievements | Leaderboards.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { section = CenterSection.ACHIEVEMENTS },
                enabled = section != CenterSection.ACHIEVEMENTS,
            ) { Text("Achievements") }
            Button(
                onClick = { section = CenterSection.LEADERBOARDS },
                enabled = section != CenterSection.LEADERBOARDS,
            ) { Text("Leaderboards") }
        }
        Spacer(Modifier.height(12.dp))
        if (section == CenterSection.ACHIEVEMENTS) {
            // Sort row. Closest first is the default: it
            // surfaces what the player can earn next.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { sort = RaAchievements.Sort.CLOSEST },
                    enabled = sort != RaAchievements.Sort.CLOSEST,
                ) { Text("Closest") }
                Button(
                    onClick = { sort = RaAchievements.Sort.DEFAULT },
                    enabled = sort != RaAchievements.Sort.DEFAULT,
                ) { Text("Default") }
                Button(
                    onClick = { sort = RaAchievements.Sort.POINTS },
                    enabled = sort != RaAchievements.Sort.POINTS,
                ) { Text("Points") }
                Button(
                    onClick = { sort = RaAchievements.Sort.RARITY },
                    enabled = sort != RaAchievements.Sort.RARITY,
                ) { Text("Rarity") }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (RaClient.gameLoaded) {
                    val sorted = RaAchievements.sortAchievements(RaClient.achievements, sort)
                    for (achievement in sorted) {
                        if (achievement.state == RaAchievements.STATE_INACTIVE) continue
                        AchievementRow(
                            achievement = achievement,
                            badgeStore = badgeStore,
                            isWifi = isWifi,
                            expanded = selectedId == achievement.id,
                            onToggle = {
                                selectedId = if (selectedId == achievement.id) 0 else achievement.id
                            },
                            onSearchForHelp = {
                                val url = RaAchievements.searchForHelpUrl(
                                    RaClient.gameTitle,
                                    achievement.title,
                                )
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            },
                        )
                    }
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                LeaderboardCenterSection(
                    selectedBoardId = selectedBoardId,
                    onSelectBoard = { selectedBoardId = it },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

/** One achievement row: badge, title, description, progress bar, points / rarity. Tap to expand the detail. */
@Composable
private fun AchievementRow(
    achievement: RaAchievement,
    badgeStore: RaBadgeStore,
    isWifi: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSearchForHelp: () -> Unit,
) {
    val unlocked = achievement.isUnlocked
    val rarity = achievement.rarity
    val tier = RaAchievements.rarityTier(rarity)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(modifier = Modifier.weight(1f)) {
                AchievementBadge(achievement, badgeStore, isWifi)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        achievement.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (unlocked) Color(EraUi.Palette.GOLD) else Color(EraUi.Palette.TEXT),
                    )
                    Text(
                        achievement.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unlocked) Color(EraUi.Palette.TEXT) else Color(EraUi.Palette.TEXT_MUTED),
                    )
                    // A progress bar only where the achievement
                    // was authored with a measured target — never
                    // an invented percentage for the rest.
                    if (achievement.measuredProgress != null) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { (achievement.measuredPercent ?: 0.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(EraUi.Palette.GOLD),
                            trackColor = Color(EraUi.Palette.MAROON),
                        )
                        Text(
                            achievement.measuredProgress,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(EraUi.Palette.TEXT_MUTED),
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (unlocked) "Unlocked" else "${achievement.points} pts",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unlocked) Color(EraUi.Palette.GOLD) else Color(EraUi.Palette.TEXT),
                    fontWeight = if (unlocked) FontWeight.Bold else FontWeight.Normal,
                )
                if (tier != null && rarity != null) {
                    Text(
                        "${tier.label} · ${formatRarityPercent(rarity)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tier == RaAchievements.RarityTier.COMMON) {
                            Color(EraUi.Palette.TEXT_MUTED)
                        } else {
                            Color(EraUi.Palette.GOLD)
                        },
                    )
                }
            }
        }
        // The detail: rarity and Search for Help. An unknown
        // rarity is shown as no rarity, never invented.
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            if (tier != null && rarity != null) {
                Text(
                    "Rarity: ${tier.label} — ${formatRarityPercent(rarity)} of players earned it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
            }
            Button(onClick = onSearchForHelp) { Text("Search for Help") }
        }
    }
}

/** The 64 px badge in the maroon frame, or a dimmed "?" while it loads (or when there is none). */
@Composable
private fun AchievementBadge(
    achievement: RaAchievement,
    badgeStore: RaBadgeStore,
    isWifi: Boolean,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    // The badge for the achievement's current state: unlocking
    // it swaps the locked badge for the colour one.
    LaunchedEffect(achievement.id, achievement.isUnlocked) {
        bitmap = withContext(Dispatchers.IO) {
            badgeStore.cachedBitmap(achievement) ?: badgeStore.fetchBitmap(achievement, isWifi)
        }
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(Color(EraUi.Palette.SURFACE_RAISED))
            .border(
                2.dp,
                Color(
                    if (achievement.isUnlocked) EraUi.Palette.GOLD else EraUi.Palette.GOLD_MUTED,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                "?",
                color = Color(EraUi.Palette.TEXT_MUTED),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The Leaderboards section: the v1.2.0 Leaderboards screen body,
 * restyled to match the Center (the Center header covers the
 * title; the Center provides Back). Scores are submitted
 * automatically by rc_client while the game runs — this section
 * only shows the boards and their entries.
 */
@Composable
private fun LeaderboardCenterSection(
    selectedBoardId: Int,
    onSelectBoard: (Int) -> Unit,
) {
    LaunchedEffect(Unit) {
        if (selectedBoardId == 0) {
            val first = RaClient.leaderboards.firstOrNull()
            if (first != null) {
                onSelectBoard(first.id)
                RaClient.fetchLeaderboardEntries(first.id, false)
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (RaClient.richPresence.isNotEmpty()) {
            // The message is already a complete sentence
            // ("Playing <title>", or the game's own presence
            // string), so it is shown as-is.
            Text(
                RaClient.richPresence,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            if (RaClient.gameLoaded) {
                "${RaClient.leaderboards.size} leaderboard${if (RaClient.leaderboards.size == 1) "" else "s"}"
            } else {
                "No leaderboards for this game."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        if (RaClient.gameLoaded) {
            for (board in RaClient.leaderboards) {
                val selected = board.id == selectedBoardId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectBoard(board.id)
                            RaClient.fetchLeaderboardEntries(board.id, false)
                        }
                        .padding(vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            board.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color(EraUi.Palette.GOLD) else Color(EraUi.Palette.TEXT),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            RaLeaderboards.formatName(board.format),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (board.description.isNotEmpty()) {
                        Text(
                            board.description,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // The live attempt on this board, if one is running.
                    if (board.id == RaClient.activeTrackerBoardId && RaClient.activeTrackerDisplay.isNotEmpty()) {
                        Text(
                            "Attempt: ${RaClient.activeTrackerDisplay}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(EraUi.Palette.GOLD),
                        )
                    } else if (board.trackerValue.isNotEmpty()) {
                        Text(
                            "Attempt: ${board.trackerValue}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(EraUi.Palette.GOLD),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // The entries for the selected board.
            val entries = RaClient.leaderboardEntries
            if (RaClient.leaderboardEntriesLoading) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            } else if (RaClient.leaderboardEntriesError != null) {
                Text(
                    RaClient.leaderboardEntriesError ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (entries != null && selectedBoardId != 0 &&
                RaClient.leaderboardEntriesBoardId == selectedBoardId
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { RaClient.fetchLeaderboardEntries(selectedBoardId, false) },
                        enabled = RaClient.leaderboardEntriesAroundUser,
                    ) { Text("Top 10") }
                    Button(
                        onClick = { RaClient.fetchLeaderboardEntries(selectedBoardId, true) },
                        enabled = !RaClient.leaderboardEntriesAroundUser,
                    ) { Text("Near me") }
                }
                Spacer(Modifier.height(8.dp))
                for (entry in entries.entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "#${entry.rank}  ${entry.user}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (entry.user == RaClient.username) FontWeight.Bold else FontWeight.Normal,
                            color = if (entry.user == RaClient.username) Color(EraUi.Palette.GOLD) else Color(EraUi.Palette.TEXT),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            entry.display,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${entries.totalEntries} entries" +
                        if (entries.userIndex >= 0) " — you are #${entries.userIndex + 1}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** "12.5%" — one decimal, US locale so the separator is always a point. */
private fun formatRarityPercent(rarity: Double): String =
    String.format(Locale.US, "%.1f%%", rarity)
