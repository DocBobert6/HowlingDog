package app.howlingdog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2.0, feature 3 — the memory card manager screen: what is on
 * the virtual Neo Geo memory card, which games have saves, and
 * how big each save is, with delete for stale saves.
 *
 * The entries come from [MemoryCard.listEntries] (the actual
 * files in filesDir/saves/ — nothing invented). A game the
 * library no longer holds is listed under its own file name, so
 * its saves can still be deleted. Deleting a file asks first
 * (AlertDialog, the same pattern as removing a game from the
 * library); the caller re-lists after a delete, so the screen
 * always shows the card as it is.
 *
 * Reached from Settings ("Memory card" button). Back returns to
 * Settings.
 */
@Composable
fun MemoryCardScreen(
    entries: List<MemoryCard.Entry>,
    onDeleteFile: (String) -> Unit,
    onDeleteGame: (String) -> Unit,
    onBack: () -> Unit,
) {
    // The file (by name) or game (by main file) whose delete is
    // being confirmed, or null when no dialog is up.
    var confirmFile by remember { mutableStateOf<String?>(null) }
    var confirmGame by remember { mutableStateOf<MemoryCard.Entry?>(null) }
    val totalBytes = entries.sumOf { it.totalBytes }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Memory card", style = MaterialTheme.typography.headlineMedium)
        Text(
            "The virtual Neo Geo memory card: the saves the core writes when a game saves in-game (memory card, NVRAM, save RAM, and CD backup RAM). Save states live here too, but they are not on the card and are not listed.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        if (entries.isEmpty()) {
            Text(
                "No saves on the memory card yet. A save appears here when a game writes to the card — play a game, save in-game, and quit or pause.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "${entries.size} ${if (entries.size == 1) "game" else "games"} · ${MemoryCard.formatSize(totalBytes)} total",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            for (entry in entries) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
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
                            MemoryCard.formatSize(entry.totalBytes),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    for (file in entry.files) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${MemoryCard.formatSize(file.sizeBytes)} · ${dateFormat.format(Date(file.lastModifiedMillis))}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { confirmFile = file.name }) {
                                Text("Delete")
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { confirmGame = entry }) {
                            Text("Delete all for this game")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        Button(onClick = onBack) { Text("Back") }
    }

    val fileToDelete = confirmFile
    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmFile = null },
            title = { Text("Delete save?") },
            text = {
                Text(
                    "Delete $fileToDelete from the memory card? " +
                        "The game will no longer have that save.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFile = null
                    onDeleteFile(fileToDelete)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFile = null }) { Text("Cancel") }
            },
        )
    }
    val gameToDelete = confirmGame
    if (gameToDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmGame = null },
            title = { Text("Delete all saves?") },
            text = {
                Text(
                    "Delete every save for ${gameToDelete.displayName} " +
                        "(${gameToDelete.files.size} ${if (gameToDelete.files.size == 1) "file" else "files"}, " +
                        "${MemoryCard.formatSize(gameToDelete.totalBytes)}) from the memory card?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmGame = null
                    onDeleteGame(gameToDelete.mainFile)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmGame = null }) { Text("Cancel") }
            },
        )
    }
}
