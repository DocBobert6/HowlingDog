package app.howlingdog

/**
 * Spike diagnostic for the Phase 2 no-video bug (Pixel 10a: game
 * boots, audio works, black game screen). The video path is correct
 * on paper — core delivers XRGB8888 frames, the bridge copies them,
 * PlayerSession draws them — so the failure is in runtime behaviour
 * we cannot see. These live numbers separate the three branches in
 * one device test:
 * - frames == 0: the core is not delivering frames at all.
 * - frames climbing + brightMean == 0: the core renders black.
 * - frames climbing + brightMean > 0: frames arrive with content
 *   but GameView is not showing the bitmap.
 */
data class FrameStats(
    /** Times nativeGetFrame returned a frame. */
    val frames: Long,
    /** Frames actually posted to the display (geometry matched). */
    val drawn: Long,
    /** Last delivered frame size (0x0 before the first frame). */
    val lastW: Int,
    val lastH: Int,
    /** Video mode from nativeGetAvInfo. */
    val avW: Int,
    val avH: Int,
    val fps: Double,
    /** Mean (R+G+B)/3 over the sampled grid, 0-255. 0 = pure black. */
    val brightMean: Int,
    val centreR: Int,
    val centreG: Int,
    val centreB: Int,
)

data class FrameBrightness(
    val mean: Int,
    val centreR: Int,
    val centreG: Int,
    val centreB: Int,
)

/**
 * Pure brightness sampling of an ARGB_8888 frame: the centre pixel
 * plus the mean of (R+G+B)/3 over a coarse ~8x8 grid. Cheap enough
 * to run on every delivered frame.
 */
fun sampleFrameBrightness(pixels: IntArray, width: Int, height: Int): FrameBrightness {
    if (width <= 0 || height <= 0 || pixels.size < width * height) {
        return FrameBrightness(0, 0, 0, 0)
    }
    val stepX = maxOf(1, width / 8)
    val stepY = maxOf(1, height / 8)
    var sum = 0L
    var n = 0
    var y = 0
    while (y < height) {
        val row = y * width
        var x = 0
        while (x < width) {
            val p = pixels[row + x]
            sum += ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
            n++
            x += stepX
        }
        y += stepY
    }
    val mean = if (n > 0) (sum / (3L * n)).toInt() else 0
    val c = pixels[(height / 2) * width + (width / 2)]
    return FrameBrightness(mean, (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
}
