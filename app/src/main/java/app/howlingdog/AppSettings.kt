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
        const val KEY_SETUP_SEEN = "app.setupSeen" // "yes" once the SYSTEM SETUP screen has been dismissed
        const val KEY_DISMISSED_UPDATE = "app.dismissedUpdate" // tag of the update the user dismissed ("v1.1.0")
        const val KEY_LAST_UPDATE_CHECK = "app.lastUpdateCheck" // epoch ms, as a string
        const val KEY_RUNAHEAD = "app.runahead" // "off" / "1" / "2" frames ahead

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

    /** Whether the first-run SYSTEM SETUP screen has been shown and dismissed (default false). */
    fun setupSeen(): Boolean = store.get(KEY_SETUP_SEEN)?.lowercase() == "yes"

    fun setSetupSeen() {
        store.put(KEY_SETUP_SEEN, "yes")
    }

    /**
     * Phase 9, item 10 — the update the user dismissed ("Not
     * now" on the library notice). A dismissed version is never
     * re-offered; a newer version still is, because the stored
     * tag no longer matches. A manual "Check for updates" in
     * Settings clears it, so the notice can show again.
     */
    fun dismissedUpdateVersion(): String? =
        store.get(KEY_DISMISSED_UPDATE)?.takeIf { it.isNotBlank() }

    fun setDismissedUpdateVersion(tag: String) {
        store.put(KEY_DISMISSED_UPDATE, tag)
    }

    fun clearDismissedUpdateVersion() {
        store.put(KEY_DISMISSED_UPDATE, "")
    }

    /** When the last update check completed (epoch ms; 0 = never). */
    fun lastUpdateCheck(): Long = store.get(KEY_LAST_UPDATE_CHECK)?.toLongOrNull() ?: 0L

    fun setLastUpdateCheck(nowMillis: Long) {
        store.put(KEY_LAST_UPDATE_CHECK, nowMillis.toString())
    }

    /**
     * Phase 10 — run-ahead frames (0 = off, 1 or 2 frames ahead).
     * Off by default. Takes effect the next time a game starts,
     * like the CRT shader/curvature masters.
     */
    fun runaheadFrames(): Int = Runahead.parse(store.get(KEY_RUNAHEAD))

    fun setRunaheadFrames(frames: Int) {
        store.put(KEY_RUNAHEAD, Runahead.store(frames))
    }
}
