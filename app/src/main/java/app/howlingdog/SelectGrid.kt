package app.howlingdog

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 5 — the fighting-game select grid. The library as a 90s
 * fighting-game select screen (KOF '94/'95, Street Fighter II):
 * portrait tiles with chunky borders, the picked game big in the
 * middle, "SELECT YOUR GAME" as the header.
 *
 * A tile shows the game's box art when the app has fetched it
 * (libretro thumbnails, fetched at runtime on Wi-Fi and cached —
 * never bundled); otherwise it is the typographic portrait — a
 * bold background colour (stable per game name, see EraUi) with a
 * big monogram and the game's name. An art tile carries the name
 * too, as a small label in its bottom-right corner on a dark
 * maroon scrim. The art sits inside the same chunky
 * maroon-and-gold frame; the frame and the grid never change. A
 * game is a game: tiles show no media type and the grid never
 * sorts by media.
 */

/** A chunky square-cornered arcade button. Primary is gold on maroon, the rest are dark maroon with a gold border. [tight] is the four-across portrait row: smaller text and padding so four buttons fit. */
@Composable
fun ArcadeButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    compact: Boolean = false,
    tight: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Color(EraUi.Palette.GOLD) else Color(EraUi.Palette.SURFACE_RAISED),
            contentColor = if (primary) Color(EraUi.Palette.ON_GOLD) else Color(EraUi.Palette.TEXT),
        ),
        border = if (primary) null else BorderStroke(1.dp, Color(EraUi.Palette.GOLD_MUTED)),
        // Compact buttons (the pause menu) use less vertical
        // padding. The Material3 40dp minimum height still applies,
        // so they stay comfortably tappable — this only removes
        // height above that floor. Tight buttons (the portrait row)
        // also lose horizontal padding, for the same reason.
        contentPadding = if (tight) {
            PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        } else if (compact) {
            PaddingValues(horizontal = 24.dp, vertical = 4.dp)
        } else {
            ButtonDefaults.ContentPadding
        },
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = if (tight) 13.sp else 15.sp)
    }
}

/** One portrait tile in the select grid. Tap selects; it never starts the game. Long-press pins or unpins it — a pinned game sits at the top of the grid and wears a gold PIN badge. [cover] is the fetched box art, or null for the typographic tile. The name is always drawn inside the tile: on an art tile it is a small label in the bottom-right corner on a dark maroon scrim (Phase 9, item 2), so it never clashes with the art's own title; the typographic tile keeps its centred name at the bottom. */
@Composable
fun GameTile(
    entry: GameEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
    cover: Bitmap? = null,
) {
    val bg = Color(EraUi.tileColorArgb(entry.displayName))
    Box(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .background(bg)
            .border(
                width = if (selected) 4.dp else 2.dp,
                color = if (selected) Color(EraUi.Palette.GOLD) else Color(EraUi.Palette.GOLD_MUTED),
            )
            .combinedClickable(onClick = onSelect, onLongClick = onTogglePin),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = EraUi.monogram(entry.displayName),
                color = Color(EraUi.Palette.TEXT).copy(alpha = 0.3f),
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
            )
        }
        // Phase 9, item 2 — the name is drawn on every tile,
        // inside the art square. The typographic tile (cover ==
        // null) keeps its centred name at the bottom, as before.
        // On an art tile the name is a small label tucked into the
        // bottom-right corner on a dark maroon scrim, so it stays
        // readable over the art without clashing with the art's own
        // title (audit fix (c) removed the old centred overlay for
        // that clash; the corner placement avoids it). The
        // full-width Row only positions the label — the scrim hugs
        // the text, so a short name stays a small chip, and a long
        // name ellipsises inside the tile instead of overflowing
        // it.
        if (cover == null) {
            Text(
                text = entry.effectiveName,
                color = Color(EraUi.Palette.TEXT),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black,
                        offset = Offset(1f, 1f),
                        blurRadius = 2f,
                    ),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(6.dp),
            )
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier.background(
                        Color(EraUi.Palette.SURFACE).copy(alpha = 0.85f),
                    ),
                ) {
                    Text(
                        text = entry.effectiveName,
                        color = Color(EraUi.Palette.TEXT),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        }
        // Phase 9, item 1 — a pinned game wears a small gold PIN
        // badge in the tile's top corner, so it reads as a
        // favourite at a glance. It sits over the art or the
        // monogram alike; the name (when drawn) is at the bottom.
        if (entry.pinned) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color(EraUi.Palette.GOLD)),
            ) {
                Text(
                    text = "PIN",
                    color = Color(EraUi.Palette.ON_GOLD),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** The picked game, big in the middle. Tap the portrait (or START) to play. [cover] is the fetched box art, or null for the typographic portrait. RENAME opens the rename dialog (Phase 9, item 4) — the rename lives here, on the portrait, so the tile's long-press keeps meaning pin/unpin exactly as before. The gear chip in the top-right corner (Phase 9, item 8) opens this game's own settings — the four-button row below is untouched. */
@Composable
fun SelectedPortrait(
    entry: GameEntry,
    onPlay: () -> Unit,
    onMode: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    cover: Bitmap? = null,
    // v1.5, feature 3 — the metadata line ("1998 · SNK ·
    // Fighting · 2 players", unknown parts omitted) and the
    // collection this game is in (null = none), with the tap
    // that opens the collection picker.
    metadataLine: String? = null,
    collectionName: String? = null,
    onCollection: () -> Unit = {},
) {
    val bg = Color(EraUi.tileColorArgb(entry.displayName))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(bg)
            .border(4.dp, Color(EraUi.Palette.GOLD))
            .clickable { onPlay() },
    ) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = EraUi.monogram(entry.displayName),
                color = Color(EraUi.Palette.TEXT).copy(alpha = 0.3f),
                fontSize = 110.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            // Audit fix (c): same as GameTile — the name is only
            // drawn when there is no art. The system label below
            // stays: it is not part of the art, it says which system
            // the game will run on.
            if (cover == null) {
                Text(
                    text = entry.effectiveName,
                    color = Color(EraUi.Palette.TEXT),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color.Black,
                            offset = Offset(1f, 1f),
                            blurRadius = 3f,
                        ),
                    ),
                )
            }
            Text(
                text = modeLabel(entry.effectiveMode),
                color = Color(EraUi.Palette.TEXT),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black,
                        offset = Offset(1f, 1f),
                        blurRadius = 2f,
                    ),
                ),
            )
            // v1.5, feature 3 — the metadata line, when the game
            // has any: year, maker, genre, players, unknown parts
            // omitted. Same treatment as the system label above.
            if (!metadataLine.isNullOrBlank()) {
                Text(
                    text = metadataLine,
                    color = Color(EraUi.Palette.TEXT),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color.Black,
                            offset = Offset(1f, 1f),
                            blurRadius = 2f,
                        ),
                    ),
                )
            }
            // v1.5, feature 3 — the collection, tap to pick one.
            // A nested clickable, like the gear chip: tapping it
            // never starts the game.
            Text(
                text = "COLLECTION: ${collectionName ?: "—"}",
                color = Color(EraUi.Palette.GOLD),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onCollection() },
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black,
                        offset = Offset(1f, 1f),
                        blurRadius = 2f,
                    ),
                ),
            )
            // Phase 9, item 1 — the picked game says it is pinned
            // here too, under the system label, so the pin is
            // visible without going back to the grid.
            if (entry.pinned) {
                Text(
                    text = "★ PINNED",
                    color = Color(EraUi.Palette.GOLD),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color.Black,
                            offset = Offset(1f, 1f),
                            blurRadius = 2f,
                        ),
                    ),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // Four buttons across, sharing the width — the tight
            // variant keeps them inside the portrait at a narrow
            // phone width.
            Row(modifier = Modifier.fillMaxWidth()) {
                ArcadeButton(text = "▶ START", onClick = onPlay, primary = true, tight = true, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                ArcadeButton(text = "SYSTEM", onClick = onMode, tight = true, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                ArcadeButton(text = "RENAME", onClick = onRename, tight = true, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                ArcadeButton(text = "REMOVE", onClick = onRemove, tight = true, modifier = Modifier.weight(1f))
            }
        }
        // Phase 9, item 8 — the gear chip opens this game's own
        // settings. Top-right corner, clear of the name/system
        // text (top-left) and the button row (bottom); a nested
        // clickable, so tapping it never starts the game.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color(EraUi.Palette.SURFACE).copy(alpha = 0.85f))
                .border(1.dp, Color(EraUi.Palette.GOLD_MUTED))
                .clickable { onSettings() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⚙ SETTINGS",
                color = Color(EraUi.Palette.TEXT),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
