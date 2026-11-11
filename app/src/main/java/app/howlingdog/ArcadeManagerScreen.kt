package app.howlingdog

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 2.0, feature 6 — the Arcade Manager screen: input heatmaps
 * and the virtual revenue / time tracker, the home for the
 * playtime stats from feature 1.
 *
 * The top shows the totals — every game's playtime, every
 * virtual coin drop at $0.25, and what the player would have
 * spent in real money feeding a cabinet. Below, each game with
 * arcade counts lists its playtime, coins, and revenue; tapping
 * one shows its heatmap: the stick directions (brighter = used
 * more) and the buttons, the "wear" visualization.
 *
 * The counts are presses, read from the arcade-stats file —
 * nothing is invented. A game with no counts yet shows its
 * playtime with an empty heatmap. There are NO DIP switches
 * here: the Arcade Manager counts and shows, it never changes
 * how a game plays.
 *
 * Reached from the library ("Arcade Manager" button) and from
 * Settings. Back returns to where it was opened from.
 */

@Composable
fun ArcadeManagerScreen(
    entries: List<ArcadeManagerEntry>,
    totalPlaytimeMillis: Long,
    totalCoins: Long,
    onBack: () -> Unit,
) {
    var selected by remember(entries) {
        mutableStateOf(entries.maxByOrNull { it.counts.totalPresses }?.mainFile)
    }
    val selectedEntry = entries.firstOrNull { it.mainFile == selected } ?: entries.firstOrNull()
    val totalRevenueCents = ArcadeStats.revenueCents(totalCoins)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Arcade Manager", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Your arcade, on paper: how much each game has been played, what it would have cost in quarters, and which controls wear out first.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Total playtime ${formatPlaytime(totalPlaytimeMillis)} · " +
                "$totalCoins coins · ${ArcadeStats.formatMoney(totalRevenueCents)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))
        if (entries.isEmpty()) {
            Text(
                "No arcade stats yet. Play a game — every stick movement and button press is counted, and every Coin press is a quarter.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            for (entry in entries) {
                val isSelected = entry.mainFile == selectedEntry?.mainFile
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = entry.mainFile }
                        .background(
                            color = if (isSelected) {
                                Color(EraUi.Palette.SURFACE_RAISED)
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatPlaytime(entry.playtimeMillis),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "${entry.counts.coin} coins · ${ArcadeStats.formatRevenue(entry.counts.coin)} · ${entry.counts.totalPresses} presses",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val heatmapEntry = selectedEntry
            if (heatmapEntry != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Heatmap — ${heatmapEntry.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Brighter means used more. The stick directions, then the buttons.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                HeatmapView(heatmapEntry.counts)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

/**
 * The heatmap for one game's counts: the eight stick directions
 * in a 3x3 grid (the centre shows the total direction presses,
 * labelled TOTAL) and the six buttons in a row. Each cell's gold is brighter
 * the more that control was pressed, scaled to the hottest
 * control in its group.
 */
@Composable
fun HeatmapView(counts: ArcadeCounts) {
    val maxDirection = ArcadeStats.DIRECTION_NAMES.maxOfOrNull { counts.directionCount(it) } ?: 0L
    val maxButton = ArcadeStats.BUTTON_IDS.maxOfOrNull { counts.buttonCount(it) } ?: 0L

    fun heatColor(count: Long, max: Long): Color {
        val intensity = if (max <= 0L) 0f else count.toFloat() / max.toFloat()
        return Color(0xFFD4A017).copy(alpha = 0.12f + 0.88f * intensity)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Directions: 3x3, centre = the total direction presses.
        val rows = listOf(
            listOf("upLeft", "up", "upRight"),
            listOf("left", null, "right"),
            listOf("downLeft", "down", "downRight"),
        )
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (name in row) {
                    if (name == null) {
                        // Centre: the direction total, labelled so
                        // it is not read as a ninth direction.
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    color = Color(EraUi.Palette.SURFACE),
                                    shape = RoundedCornerShape(4.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${counts.totalDirectionPresses}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White,
                                )
                                Text(
                                    "TOTAL",
                                    fontSize = 10.sp,
                                    color = Color.White,
                                )
                            }
                        }
                    } else {
                        val count = counts.directionCount(name)
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    color = heatColor(count, maxDirection),
                                    shape = RoundedCornerShape(4.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    when (name) {
                                        "upLeft" -> "↖"
                                        "up" -> "↑"
                                        "upRight" -> "↗"
                                        "left" -> "←"
                                        "right" -> "→"
                                        "downLeft" -> "↙"
                                        "down" -> "↓"
                                        "downRight" -> "↘"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Black,
                                )
                                Text(
                                    "$count",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Black,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        // Buttons: one chip each, brightest = pressed most.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (id in ArcadeStats.BUTTON_IDS) {
                val count = counts.buttonCount(id)
                Box(
                    modifier = Modifier
                        .background(
                            color = heatColor(count, maxButton),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(id, style = MaterialTheme.typography.bodyMedium, color = Color.Black)
                        Text("$count", style = MaterialTheme.typography.bodySmall, color = Color.Black)
                    }
                }
            }
        }
    }
}
