package app.howlingdog

import android.content.SharedPreferences

/**
 * Phase 4 — the core's options as user settings.
 *
 * The table mirrors the bridge's option table (bridge/jni/bridge.cpp)
 * and the core's documented defaults (libretro/libretro_core_options.h).
 * The two system types are NOT here: they are the per-game mode,
 * stored on the game entry (GameLibrary) and pushed through
 * [CoreSettings.applyMode] before a game starts.
 *
 * Live vs restart comes from the core's check_variables()
 * (libretro/libretro.c): the first-run-only options (region, the
 * overscan crops, Universe BIOS hardware, setting mode, four player)
 * are only read at retro_init, so they take effect the next time a
 * game starts; the rest are re-read on every GET_VARIABLE_UPDATE,
 * so they apply on the next frame.
 */
data class CoreOption(
    val key: String,
    val label: String,
    /** (value, label) pairs, in the order the settings screen shows them. */
    val values: List<Pair<String, String>>,
    val defaultValue: String,
    /** True when the core re-reads this option live; false when it only takes effect at the next game start. */
    val live: Boolean,
)

object CoreOptions {
    val ALL: List<CoreOption> = listOf(
        // Live: check_variables() re-reads these on every update.
        CoreOption(
            "geolith_palette", "Palette",
            listOf("resnet" to "Resistor network", "raw" to "Raw"),
            "resnet", live = true,
        ),
        CoreOption(
            "geolith_aspect", "Aspect ratio",
            listOf("1:1" to "Square pixels", "45:44" to "NTSC (45:44)", "4:3" to "Traditional (4:3)"),
            "1:1", live = true,
        ),
        CoreOption(
            "geolith_sprlimit", "Sprites per line",
            listOf("96" to "Hardware (96)", "192" to "Double (192)", "288" to "Triple (288)", "381" to "Max (381)"),
            "96", live = true,
        ),
        CoreOption(
            "geolith_oc", "Overclocking",
            listOf("off" to "Off", "on" to "On"),
            "off", live = true,
        ),
        CoreOption(
            "geolith_disable_adpcm_wrap", "Disable ADPCM wrap",
            listOf("off" to "Off", "on" to "On"),
            "off", live = true,
        ),
        CoreOption(
            "geolith_cd_speed_hack", "CD speed hack",
            listOf("disabled" to "Off", "enabled" to "On"),
            "disabled", live = true,
        ),
        CoreOption(
            "geolith_cd_dma_len_limit", "CD DMA length limit",
            listOf("disabled" to "Off", "enabled" to "On"),
            "disabled", live = true,
        ),
        CoreOption(
            "geolith_cd_skip_loading", "Skip loading",
            listOf("disabled" to "Off", "enabled" to "On"),
            "disabled", live = true,
        ),
        CoreOption(
            "geolith_memcard", "Memory card",
            listOf("on" to "On", "off" to "Off"),
            "on", live = true,
        ),
        CoreOption(
            "geolith_memcard_wp", "Memory card write protect",
            listOf("off" to "Off", "on" to "On"),
            "off", live = true,
        ),
        CoreOption(
            "geolith_freeplay", "Free play",
            listOf("off" to "Off", "on" to "On"),
            "off", live = true,
        ),
        // Restart: the core only reads these at retro_init.
        CoreOption(
            "geolith_region", "Region",
            listOf("us" to "USA", "jp" to "Japan", "as" to "Asia", "eu" to "Europe"),
            "us", live = false,
        ),
        CoreOption(
            "geolith_overscan_t", "Overscan (top)",
            listOf("16" to "16", "12" to "12", "8" to "8", "4" to "4", "0" to "0"),
            "8", live = false,
        ),
        CoreOption(
            "geolith_overscan_b", "Overscan (bottom)",
            listOf("16" to "16", "12" to "12", "8" to "8", "4" to "4", "0" to "0"),
            "8", live = false,
        ),
        CoreOption(
            "geolith_overscan_l", "Overscan (left)",
            listOf("16" to "16", "12" to "12", "8" to "8", "4" to "4", "0" to "0"),
            "8", live = false,
        ),
        CoreOption(
            "geolith_overscan_r", "Overscan (right)",
            listOf("16" to "16", "12" to "12", "8" to "8", "4" to "4", "0" to "0"),
            "8", live = false,
        ),
        CoreOption(
            "geolith_unibios_hw", "Universe BIOS hardware",
            listOf("mvs" to "Arcade", "aes" to "Home console"),
            "mvs", live = false,
        ),
        CoreOption(
            "geolith_settingmode", "Setting mode",
            listOf("off" to "Off", "on" to "On"),
            "off", live = false,
        ),
        CoreOption(
            "geolith_4player", "Four player mode",
            listOf("off" to "Off", "on" to "On"),
            "off", live = false,
        ),
    )

    fun byKey(key: String): CoreOption? = ALL.find { it.key == key }
}

/**
 * Minimal key/value store behind the settings, so the settings logic
 * is unit-testable on the JVM without SharedPreferences.
 */
interface OptionStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class SharedPrefsStore(private val prefs: SharedPreferences) : OptionStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/**
 * The stored core options. The store is the source of truth; the
 * bridge's option table is a cache of it, populated by [applyAll]
 * before a game starts and updated live by [set] while a game runs.
 * Invalid values and unknown keys are rejected, never stored.
 */
class CoreSettings(private val store: OptionStore) {

    fun get(key: String): String {
        val opt = CoreOptions.byKey(key) ?: return ""
        return store.get(key) ?: opt.defaultValue
    }

    fun set(key: String, value: String) {
        val opt = CoreOptions.byKey(key) ?: return
        if (opt.values.none { it.first == value }) return
        store.put(key, value)
        // Push live so a running game picks it up on the next frame
        // (restart options are ignored by the core until the next
        // retro_init, and are re-applied by applyAll then).
        if (Bridge.ensureLoaded()) Bridge.nativeSetOption(key, value)
    }

    /** Push every stored option value into the bridge (call before a game starts). */
    fun applyAll() {
        if (!Bridge.ensureLoaded()) return
        for (opt in CoreOptions.ALL) Bridge.nativeSetOption(opt.key, get(opt.key))
    }

    /** Push this game's system mode (system type for carts, disc system for discs). */
    fun applyMode(entry: GameEntry) {
        if (!Bridge.ensureLoaded()) return
        if (entry.isDisc) {
            Bridge.nativeSetOption("geolith_cd_system_type", entry.effectiveMode)
        } else {
            Bridge.nativeSetOption("geolith_system_type", entry.effectiveMode)
        }
    }
}
