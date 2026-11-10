package app.howlingdog

/**
 * App-level settings that are neither core options nor control
 * settings — currently just the boot splash. Stored in the same
 * SharedPreferences file ("howlingdog-settings") under their own
 * "app.*" keys, so the settings logic stays unit-testable on the
 * JVM without SharedPreferences.
 */
class AppSettings(private val store: OptionStore) {

    companion object {
        const val KEY_SHOW_BOOT = "app.showBoot" // "on" / "off"

        const val DEFAULT_SHOW_BOOT = true
    }

    /** Whether the VS-style boot splash shows on launch (default on). */
    fun showBoot(): Boolean {
        return when (store.get(KEY_SHOW_BOOT)?.lowercase()) {
            "off" -> false
            "on" -> true
            else -> DEFAULT_SHOW_BOOT
        }
    }

    fun setShowBoot(show: Boolean) {
        store.put(KEY_SHOW_BOOT, if (show) "on" else "off")
    }
}
