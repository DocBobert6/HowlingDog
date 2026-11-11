package app.howlingdog

/**
 * 2.0, feature 7 — the edge-smoothing display mode. OFF is the
 * default (the edge shader is off unless the player turns it
 * on). EDGE is plain edge smoothing; SHARP_CRT is edge
 * smoothing then scanlines only (the curated "sharp CRT"
 * combined mode, one tap). When either edge mode is on, the CRT
 * chain turns off — they fight visually, and the CRT softness
 * would hide the crispness the edge shader creates.
 */
enum class EdgeMode { OFF, EDGE, SHARP_CRT }

/** The display mode actually in effect, after the exclusivity rule. */
enum class DisplayMode { CRT, EDGE, SHARP_CRT, PLAIN }

/**
 * Resolve the display mode from the CRT toggle and the edge
 * mode. The edge mode wins: when it is on, the CRT is off.
 * Otherwise the CRT is on when its toggle is on, and plain
 * (no shader) when it is off.
 */
fun resolveDisplayMode(crtOn: Boolean, edge: EdgeMode): DisplayMode = when {
    edge == EdgeMode.EDGE -> DisplayMode.EDGE
    edge == EdgeMode.SHARP_CRT -> DisplayMode.SHARP_CRT
    crtOn -> DisplayMode.CRT
    else -> DisplayMode.PLAIN
}

/**
 * The CRT display settings (shader chain on/off, screen
 * curvature on/off), stored in the same SharedPreferences file
 * as the core options but under their own "display.*" keys.
 * Values are validated on read, so a bad or missing value
 * always falls back to the default instead of breaking the
 * picture.
 *
 * Resolution (Mariner's chain): a per-system key wins; then the
 * shared master key; then the preset's own default. The UI only
 * exposes the two master toggles — the per-system keys exist so
 * a system can be switched on its own without restructuring.
 * The shader defaults ON (the era look is the point of the
 * chains); curvature defaults to each preset's own curveOn.
 * Both take effect the next time a game starts, like the
 * "Next game" core options.
 */
class ShaderSettings(private val store: OptionStore) {

    companion object {
        const val KEY_SHADER_MASTER = "display.shader" // "on" / "off"
        const val KEY_CURVE_MASTER = "display.curvature" // "on" / "off"
        const val KEY_SHADER_PREFIX = "display.shader." // + mode
        const val KEY_CURVE_PREFIX = "display.curvature." // + mode
        const val KEY_EDGE_MASTER = "display.edge" // "off" / "edge" / "sharpCrt"
        const val KEY_EDGE_PREFIX = "display.edge." // + mode
    }

    /** Whether the CRT shader chain is on for a system mode. */
    fun shaderOn(mode: String): Boolean {
        return readToggle(KEY_SHADER_PREFIX + mode, KEY_SHADER_MASTER, true)
    }

    /** The edge-smoothing mode for a system mode (default OFF). */
    fun edgeMode(mode: String): EdgeMode {
        return readEdgeMode(KEY_EDGE_PREFIX + mode, KEY_EDGE_MASTER)
    }

    fun setEdgeMode(mode: String, value: EdgeMode) {
        store.put(KEY_EDGE_PREFIX + mode, edgeStored(value))
    }

    fun setEdgeMaster(value: EdgeMode) {
        store.put(KEY_EDGE_MASTER, edgeStored(value))
    }

    /** The stored string for an edge mode ("off", "edge", "sharpcrt"). */
    private fun edgeStored(value: EdgeMode): String = when (value) {
        EdgeMode.OFF -> "off"
        EdgeMode.EDGE -> "edge"
        EdgeMode.SHARP_CRT -> "sharpcrt"
    }

    /** The master edge mode itself (default OFF). */
    fun edgeMaster(): EdgeMode {
        return when (store.get(KEY_EDGE_MASTER)?.lowercase()) {
            "edge" -> EdgeMode.EDGE
            "sharpcrt" -> EdgeMode.SHARP_CRT
            else -> EdgeMode.OFF
        }
    }

    /** The display mode in effect for a system mode, after the exclusivity rule (edge wins over CRT). */
    fun displayMode(mode: String): DisplayMode {
        return resolveDisplayMode(shaderOn(mode), edgeMode(mode))
    }

    /** Per-system key wins, then the master key, then OFF. Anything but "edge"/"sharpcrt" counts as off. */
    private fun readEdgeMode(perKey: String, masterKey: String): EdgeMode {
        store.get(perKey)?.lowercase()?.let {
            return when (it) {
                "edge" -> EdgeMode.EDGE
                "sharpcrt" -> EdgeMode.SHARP_CRT
                else -> EdgeMode.OFF
            }
        }
        store.get(masterKey)?.lowercase()?.let {
            return when (it) {
                "edge" -> EdgeMode.EDGE
                "sharpcrt" -> EdgeMode.SHARP_CRT
                else -> EdgeMode.OFF
            }
        }
        return EdgeMode.OFF
    }

    fun setShaderOn(mode: String, on: Boolean) {
        store.put(KEY_SHADER_PREFIX + mode, if (on) "on" else "off")
    }

    fun setShaderMaster(on: Boolean) {
        store.put(KEY_SHADER_MASTER, if (on) "on" else "off")
    }

    /** The master shader toggle itself (default on). */
    fun shaderMasterOn(): Boolean {
        return when (store.get(KEY_SHADER_MASTER)?.lowercase()) {
            "off" -> false
            else -> true
        }
    }

    /** Whether screen curvature is on for a system mode. */
    fun curvatureOn(mode: String): Boolean {
        return readToggle(
            KEY_CURVE_PREFIX + mode,
            KEY_CURVE_MASTER,
            ShaderPresets.forMode(mode).curveOn,
        )
    }

    fun setCurvatureOn(mode: String, on: Boolean) {
        store.put(KEY_CURVE_PREFIX + mode, if (on) "on" else "off")
    }

    fun setCurvatureMaster(on: Boolean) {
        store.put(KEY_CURVE_MASTER, if (on) "on" else "off")
    }

    /** The master curvature toggle itself (default on). */
    fun curvatureMasterOn(): Boolean {
        return when (store.get(KEY_CURVE_MASTER)?.lowercase()) {
            "off" -> false
            else -> true
        }
    }

    /** Per-system key wins, then the master key, then the default. Anything but "off" counts as on. */
    private fun readToggle(perKey: String, masterKey: String, default: Boolean): Boolean {
        store.get(perKey)?.lowercase()?.let { return it != "off" }
        store.get(masterKey)?.lowercase()?.let { return it != "off" }
        return default
    }
}
