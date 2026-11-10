package app.howlingdog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform

/**
 * Phase 5 — the HowlingDog mascot: an original howling-dog
 * silhouette against a full moon, drawn programmatically.
 *
 * ORIGINAL ART ONLY: this is a geometric silhouette built from
 * circles, ellipses and triangles — it does not copy SNK's dog
 * artwork, does not use the "BIGGER BADDER BETTER" slogan, and
 * does not reproduce the Dog Tag seal. The moon is a moon, not a
 * disc.
 *
 * The dog sits facing left, head thrown back, muzzle pointing up
 * at the moon in the top-left corner. Everything is drawn in a
 * 200x180 viewBox and scaled to fit the canvas.
 */
@Composable
fun HowlingDogMascot(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.aspectRatio(200f / 180f)) {
        // Night-sky panel fills the canvas.
        drawRoundRect(
            color = Color(0xFF151538),
            cornerRadius = CornerRadius(24f, 24f),
        )
        val scale = minOf(size.width / 200f, size.height / 180f)
        val dx = (size.width - 200f * scale) / 2f
        val dy = (size.height - 180f * scale) / 2f
        withTransform({
            translate(dx, dy)
            scale(scale, scale)
        }) {
            val moon = Color(0xFFF5E9C0)
            val dog = Color(0xFF0A0A0A)
            // Stars.
            drawCircle(color = Color.White.copy(alpha = 0.6f), radius = 2f, center = Offset(140f, 20f))
            drawCircle(color = Color.White.copy(alpha = 0.5f), radius = 1.5f, center = Offset(172f, 52f))
            drawCircle(color = Color.White.copy(alpha = 0.4f), radius = 1.5f, center = Offset(118f, 14f))
            // Full moon, top-left.
            drawCircle(color = moon, radius = 24f, center = Offset(50f, 34f))
            // Tail, raised behind the haunch.
            drawLine(
                color = dog,
                start = Offset(166f, 134f),
                end = Offset(188f, 110f),
                strokeWidth = 10f,
                cap = StrokeCap.Round,
            )
            // Haunch (sitting body) and chest.
            drawOval(color = dog, topLeft = Offset(92f, 96f), size = Size(80f, 74f))
            drawOval(color = dog, topLeft = Offset(88f, 91f), size = Size(40f, 54f))
            // Neck, leaning left toward the moon. `rotate` lives on
            // DrawTransform, so it goes in a nested withTransform —
            // it is not a DrawScope member (that broke CI once).
            withTransform({ rotate(degrees = -28f, pivot = Offset(96f, 82f)) }) {
                drawRoundRect(
                    color = dog,
                    topLeft = Offset(84f, 55f),
                    size = Size(24f, 54f),
                    cornerRadius = CornerRadius(10f, 10f),
                )
            }
            // Head, thrown back.
            drawCircle(color = dog, radius = 15f, center = Offset(84f, 54f))
            // Muzzle pointing up at the moon.
            drawPath(
                path = Path().apply {
                    moveTo(76f, 44f)
                    lineTo(58f, 24f)
                    lineTo(90f, 38f)
                    close()
                },
                color = dog,
            )
            // Pointed ear.
            drawPath(
                path = Path().apply {
                    moveTo(80f, 42f)
                    lineTo(76f, 20f)
                    lineTo(94f, 38f)
                    close()
                },
                color = dog,
            )
            // Front leg, down to the ground.
            drawRoundRect(
                color = dog,
                topLeft = Offset(96f, 118f),
                size = Size(13f, 52f),
                cornerRadius = CornerRadius(6f, 6f),
            )
            // Ground.
            drawRect(
                color = dog,
                topLeft = Offset(0f, 168f),
                size = Size(200f, 4f),
            )
        }
    }
}
