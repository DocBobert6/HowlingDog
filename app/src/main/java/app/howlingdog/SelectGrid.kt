package app.howlingdog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
 * No art is ever fetched: each tile is a typographic portrait — a
 * bold background colour (stable per game name, see EraUi) with a
 * big monogram and the game's name. A game is a game: tiles show no
 * media type and the grid never sorts by media.
 */

/** A chunky square-cornered arcade button. Primary is red, the rest are dark. */
@Composable
fun ArcadeButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Color(0xFFD21F2B) else Color(0xFF333333),
            contentColor = Color.White,
        ),
        // Compact buttons (the pause menu) use less vertical
        // padding. The Material3 40dp minimum height still applies,
        // so they stay comfortably tappable — this only removes
        // height above that floor.
        contentPadding = if (compact) {
            PaddingValues(horizontal = 24.dp, vertical = 4.dp)
        } else {
            ButtonDefaults.ContentPadding
        },
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** One portrait tile in the select grid. Tap selects; it never starts the game. */
@Composable
fun GameTile(
    entry: GameEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = Color(EraUi.tileColorArgb(entry.displayName))
    Box(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .background(bg)
            .border(
                width = if (selected) 4.dp else 2.dp,
                color = if (selected) Color(0xFFF2C200) else Color.White,
            )
            .clickable { onSelect() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = EraUi.monogram(entry.displayName),
            color = Color.White.copy(alpha = 0.25f),
            fontSize = 56.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = entry.displayName,
            color = Color.White,
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
    }
}

/** The picked game, big in the middle. Tap the portrait (or START) to play. */
@Composable
fun SelectedPortrait(
    entry: GameEntry,
    onPlay: () -> Unit,
    onMode: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = Color(EraUi.tileColorArgb(entry.displayName))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(bg)
            .border(4.dp, Color(0xFFF2C200))
            .clickable { onPlay() },
    ) {
        Text(
            text = EraUi.monogram(entry.displayName),
            color = Color.White.copy(alpha = 0.25f),
            fontSize = 110.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.Center),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            Text(
                text = entry.displayName,
                color = Color.White,
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
            Text(
                text = modeLabel(entry.effectiveMode),
                color = Color.White,
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
            Spacer(modifier = Modifier.weight(1f))
            Row {
                ArcadeButton(text = "▶ START", onClick = onPlay, primary = true)
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                ArcadeButton(text = "SYSTEM", onClick = onMode)
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                ArcadeButton(text = "REMOVE", onClick = onRemove)
            }
        }
    }
}
