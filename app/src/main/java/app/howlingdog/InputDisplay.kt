package app.howlingdog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 2.0, feature 4 — the on-screen input display strip: the
 * recent inputs as they happen, like a fightstick display.
 * A row of small chips, oldest first, the newest highlighted;
 * each chip shows one input's direction arrow and buttons
 * ("↖ A", "→ B C", "·" for neutral). It floats over the top of
 * the game view and never takes a row of its own, so it cannot
 * shrink the game. Shown only when the Settings toggle is on
 * (off by default) and at least one input has been recorded.
 */
@Composable
fun InputDisplayStrip(events: List<InputHistory.InputEvent>) {
    if (events.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((index, event) in events.withIndex()) {
            val newest = index == events.lastIndex
            Box(
                modifier = Modifier
                    .background(
                        color = if (newest) {
                            Color(0xFFD4A017).copy(alpha = 0.85f)
                        } else {
                            Color.Black.copy(alpha = 0.55f)
                        },
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    InputHistory.labelFor(event.mask),
                    color = if (newest) Color.Black else Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
