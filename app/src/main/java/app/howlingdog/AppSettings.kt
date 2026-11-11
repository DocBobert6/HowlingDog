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
        // v1.5, feature 1 — fast-forward / slow motion. The speed
        // a hold runs at: "off" / "2" / "4" for fast-forward,
        // "off" / "on" for slow motion (0.5x). Both off by
        // default, so no speed button appears on the overlay
        // until one is turned on in Settings.
        const val KEY_SPEED_FF = "app.speedFF"
        const val KEY_SPEED_SLOW = "app.speedSlow"
        // Phase 10 — RetroAchievements: the account the user logs
        // in with. v1.1.2: a username and the session token the
        // password login returned — the password itself is never
        // stored anywhere. Both blank means logged out;
        // achievements are simply inactive then. Stored in the
        // app's private preferences, the standard approach for
        // an RA client.
        const val KEY_RA_USERNAME = "ra.username"
        const val KEY_RA_TOKEN = "ra.token"
        // The API-key login is gone (v1.1.2). This key name is
        // kept only so an upgrade from v1.1.0/v1.1.1 can remove
        // the stored key — it is never written again.
        private const val LEGACY_RA_API_KEY = "ra.apiKey"

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

    /**
     * v1.5, feature 1 — the fast-forward speed a hold runs at
     * (0.0 = off, 2.0 or 4.0). Off by default: the FF button
     * only appears on the overlay once a speed is picked.
     */
    fun fastForwardSpeed(): Double = SpeedControl.parseFfSetting(store.get(KEY_SPEED_FF))

    fun setFastForwardSpeed(factor: Double) {
        store.put(KEY_SPEED_FF, SpeedControl.storeFfSetting(factor))
    }

    /**
     * v1.5, feature 1 — slow motion on/off (0.5x while held).
     * Off by default: the SLOW button only appears on the
     * overlay once it is turned on.
     */
    fun slowMotionOn(): Boolean = SpeedControl.parseSlowSetting(store.get(KEY_SPEED_SLOW))

    fun setSlowMotionOn(on: Boolean) {
        store.put(KEY_SPEED_SLOW, SpeedControl.storeSlowSetting(on))
    }

    /** Phase 10 — RetroAchievements: the stored username ("" when logged out). */
    fun raUsername(): String = store.get(KEY_RA_USERNAME) ?: ""

    fun setRaUsername(username: String) {
        store.put(KEY_RA_USERNAME, username)
    }

    /**
     * Phase 10 — RetroAchievements: the stored session token
     * ("" when logged out). This is the token the password login
     * returned — the password itself is never stored anywhere.
     * The next launch re-logs in with this token, so the user
     * only types their password when the token is rejected.
     */
    fun raToken(): String = store.get(KEY_RA_TOKEN) ?: ""

    fun setRaToken(token: String) {
        store.put(KEY_RA_TOKEN, token)
    }

    /**
     * v1.2.0 (bug fix) — the stored login, for the launch-time
     * auto-login: the (username, token) pair when both are set,
     * null otherwise. Both must be present — a token without a
     * username (or the reverse) cannot log in, so the auto-login
     * is skipped and the user logs in by hand. The password is
     * never stored, so it never appears here.
     */
    fun raStoredLogin(): Pair<String, String>? {
        val username = raUsername()
        val token = raToken()
        return if (username.isNotEmpty() && token.isNotEmpty()) username to token else null
    }

    /**
     * v1.1.2 — the API-key login is gone: remove the API key a
     * v1.1.0/v1.1.1 install stored, so the old key is not left
     * lying around after the login it belonged to is removed.
     * A no-op once it is gone.
     */
    fun clearLegacyRaApiKey() {
        if (store.get(LEGACY_RA_API_KEY)?.isNotEmpty() == true) {
            store.put(LEGACY_RA_API_KEY, "")
        }
    }
}
