package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the app-level settings (boot splash toggle). */
class AppSettingsTest {

    private fun settingsWith(vararg pairs: Pair<String, String>): AppSettings {
        val map = mutableMapOf<String, String>()
        map.putAll(pairs)
        val store = object : OptionStore {
            override fun get(key: String): String? = map[key]
            override fun put(key: String, value: String) {
                map[key] = value
            }
        }
        return AppSettings(store)
    }

    @Test
    fun bootScreenOnByDefault() {
        assertTrue(settingsWith().showBoot())
    }

    @Test
    fun bootScreenToggleRoundTrips() {
        val settings = settingsWith()
        settings.setShowBoot(false)
        assertFalse(settings.showBoot())
        settings.setShowBoot(true)
        assertTrue(settings.showBoot())
    }

    @Test
    fun invalidBootValueFallsBackToDefault() {
        assertTrue(settingsWith(AppSettings.KEY_SHOW_BOOT to "maybe").showBoot())
    }

    @Test
    fun setupScreenUnseenByDefault() {
        assertFalse(settingsWith().setupSeen())
    }

    @Test
    fun setupSeenSticksOnceSet() {
        val settings = settingsWith()
        settings.setSetupSeen()
        assertTrue(settings.setupSeen())
        assertTrue(settingsWith(AppSettings.KEY_SETUP_SEEN to "yes").setupSeen())
        assertFalse(settingsWith(AppSettings.KEY_SETUP_SEEN to "no").setupSeen())
    }

    @Test
    fun dismissedUpdateEmptyByDefault() {
        assertNull(settingsWith().dismissedUpdateVersion())
        assertNull(settingsWith(AppSettings.KEY_DISMISSED_UPDATE to "").dismissedUpdateVersion())
    }

    @Test
    fun dismissedUpdateRoundTripsAndClears() {
        val settings = settingsWith()
        settings.setDismissedUpdateVersion("v1.1.0")
        assertEquals("v1.1.0", settings.dismissedUpdateVersion())
        settings.clearDismissedUpdateVersion()
        assertNull(settings.dismissedUpdateVersion())
    }

    @Test
    fun lastUpdateCheckDefaultsToZeroAndRoundTrips() {
        val settings = settingsWith()
        assertEquals(0L, settings.lastUpdateCheck())
        settings.setLastUpdateCheck(123456789L)
        assertEquals(123456789L, settings.lastUpdateCheck())
        assertEquals(0L, settingsWith(AppSettings.KEY_LAST_UPDATE_CHECK to "junk").lastUpdateCheck())
    }

    @Test
    fun raCredentialsDefaultToLoggedOutAndRoundTrip() {
        // Phase 10 — RetroAchievements: no credentials stored
        // means logged out (achievements simply inactive).
        // v1.1.2: the stored credential is the session token a
        // password login returned — the password itself is never
        // stored anywhere, and the API-key login is gone.
        val settings = settingsWith()
        assertEquals("", settings.raUsername())
        assertEquals("", settings.raToken())
        settings.setRaUsername("PlayerOne")
        settings.setRaToken("session-token-abc")
        assertEquals("PlayerOne", settings.raUsername())
        assertEquals("session-token-abc", settings.raToken())
        // Logging out clears both.
        settings.setRaUsername("")
        settings.setRaToken("")
        assertEquals("", settings.raUsername())
        assertEquals("", settings.raToken())
    }

    @Test
    fun legacyApiKeyIsClearedOnUpgrade() {
        // v1.1.2 — the API-key login is gone: an API key a
        // v1.1.0/v1.1.1 install stored must not be left behind.
        // The legacy key name is written here, not in
        // AppSettings, because AppSettings no longer knows it
        // as a credential — only as the key to remove. The
        // store's map is kept in this test so the removal itself
        // (not just the accessors) is what is checked.
        val map = mutableMapOf("ra.apiKey" to "old-api-key")
        val store = object : OptionStore {
            override fun get(key: String): String? = map[key]
            override fun put(key: String, value: String) {
                map[key] = value
            }
        }
        val settings = AppSettings(store)
        settings.setRaUsername("PlayerOne")
        settings.setRaToken("session-token-abc")
        settings.clearLegacyRaApiKey()
        assertEquals("", map["ra.apiKey"])
        // The username and token are untouched by the migration.
        assertEquals("PlayerOne", settings.raUsername())
        assertEquals("session-token-abc", settings.raToken())
        // Clearing again is a no-op.
        settings.clearLegacyRaApiKey()
        assertEquals("", map["ra.apiKey"])
    }

    /**
     * v1.2.0 (bug fix) — the session token survives a restart:
     * a fresh AppSettings over the same store (what a relaunch
     * is, at the persistence layer) still reads the username
     * and token a login stored. This is the half of the logout
     * bug that was working — the token was saved, just never
     * read back at launch.
     */
    @Test
    fun raTokenSurvivesSimulatedRestart() {
        val map = mutableMapOf<String, String>()
        val store = object : OptionStore {
            override fun get(key: String): String? = map[key]
            override fun put(key: String, value: String) {
                map[key] = value
            }
        }
        AppSettings(store).apply {
            setRaUsername("DocBobbert")
            setRaToken("session-token-abc")
        }
        // A relaunch: a new AppSettings over the same store.
        val restarted = AppSettings(store)
        assertEquals("DocBobbert", restarted.raUsername())
        assertEquals("session-token-abc", restarted.raToken())
        assertEquals("DocBobbert" to "session-token-abc", restarted.raStoredLogin())
    }

    /**
     * v1.2.0 (bug fix) — the stored login is only usable when
     * both halves are present: a token without a username (or
     * the reverse) cannot log in, so the auto-login is skipped.
     */
    @Test
    fun raStoredLoginNeedsBothHalves() {
        assertNull(settingsWith().raStoredLogin())
        assertNull(settingsWith(AppSettings.KEY_RA_USERNAME to "DocBobbert").raStoredLogin())
        assertNull(settingsWith(AppSettings.KEY_RA_TOKEN to "session-token-abc").raStoredLogin())
        assertEquals(
            "DocBobbert" to "session-token-abc",
            settingsWith(
                AppSettings.KEY_RA_USERNAME to "DocBobbert",
                AppSettings.KEY_RA_TOKEN to "session-token-abc",
            ).raStoredLogin(),
        )
    }

    @Test
    fun fastForwardOffByDefaultAndRoundTrips() {
        assertEquals(0.0, settingsWith().fastForwardSpeed(), 0.0)
        val settings = settingsWith()
        settings.setFastForwardSpeed(2.0)
        assertEquals(2.0, settings.fastForwardSpeed(), 0.0)
        settings.setFastForwardSpeed(4.0)
        assertEquals(4.0, settings.fastForwardSpeed(), 0.0)
        settings.setFastForwardSpeed(0.0)
        assertEquals(0.0, settings.fastForwardSpeed(), 0.0)
    }

    @Test
    fun slowMotionOffByDefaultAndRoundTrips() {
        assertFalse(settingsWith().slowMotionOn())
        val settings = settingsWith()
        settings.setSlowMotionOn(true)
        assertTrue(settings.slowMotionOn())
        settings.setSlowMotionOn(false)
        assertFalse(settings.slowMotionOn())
    }
}
