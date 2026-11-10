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
    // Source probe (no-video bug): what the core delivered, sampled
    // in bridge_video BEFORE the copy into the frame buffer. The
    // brightness above samples the copied frame, so it cannot tell
    // "the core delivers black" apart from "the copy loses the
    // picture" — these can.
    /** bridge_video invocations in this session. */
    val srcCalls: Long = 0,
    /** Non-zero uint32s in the last delivered frame (max 304x224). */
    val srcNonzero: Int = 0,
    /** Raw first words of the last delivered frame (XRGB8888). */
    val srcFirst: Int = 0,
    val srcFirst100: Int = 0,
    val srcFirst1000: Int = 0,
    /** Received frame size and pitch from the core. */
    val srcW: Int = 0,
    val srcH: Int = 0,
    val srcPitch: Int = 0,
    /** Last error line the core logged, if any (else blank). */
    val lastError: String = "",
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

/**
 * The exact text the game screen shows for one stats snapshot: the
 * three existing DIAG lines plus the SRC probe line (and an ERR line
 * when the core has logged an error). Pure, so it is unit-tested.
 * Reading the SRC line: nonzero 0 = the core delivers all-zero
 * pixels; nonzero > 0 with bright mean 0 = the copy loses the
 * picture (or the pixels are non-zero but black).
 */
fun formatDiagText(s: FrameStats): String {
    val hex = { v: Int -> "%08X".format(v) }
    val text = StringBuilder()
    text.append("DIAG frames=${s.frames} drawn=${s.drawn} last=${s.lastW}x${s.lastH}\n")
    text.append("AV ${s.avW}x${s.avH} @ ${"%.1f".format(s.fps)}fps\n")
    text.append("bright mean=${s.brightMean} centre=(${s.centreR},${s.centreG},${s.centreB})\n")
    text.append(
        "SRC nonzero=${s.srcNonzero} first=0x${hex(s.srcFirst)} " +
            "100=0x${hex(s.srcFirst100)} 1000=0x${hex(s.srcFirst1000)} " +
            "${s.srcW}x${s.srcH} pitch=${s.srcPitch}",
    )
    if (s.lastError.isNotBlank()) {
        text.append("\nERR ${s.lastError}")
    }
    return text.toString()
}
