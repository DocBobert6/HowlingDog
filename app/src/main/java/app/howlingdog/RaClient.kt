package app.howlingdog

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 10 — RetroAchievements: the Kotlin-side session.
 *
 * Owns the RA client once it is initialised: the login (username
 * + API key from [AppSettings]), the game load, the per-frame
 * evaluation, the unlock popup data, the achievements list, and
 * the progress serialize/deserialize that the save-state sidecar
 * carries. The native client lives for the app's lifetime once
 * created (login persists); only the loaded game is unloaded
 * when a game quits (the bridge does that in nativeUnloadGame).
 *
 * The native layer calls back into Kotlin through [Bridge]
 * (raHttpRequest / raEventUnlocked / raLoginResult /
 * raLoadGameResult); those entry points delegate here. The HTTP
 * request runs on a background thread and the response is handed
 * back to the native layer by id, so the calling thread (the emu
 * thread for do_frame, the main thread for login/load) is never
 * blocked. Logged out, achievements are simply inactive — no
 * errors, no nagging.
 */
data class RaUnlock(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
)

object RaClient {
    private const val TAG = "HowlingDog"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True once nativeRaInit has succeeded. */
    @Volatile
    var initialised = false
        private set

    /** Logged in to RetroAchievements (a login is in progress while [loginPending] is true). */
    var loggedIn by mutableStateOf(false)
        private set

    var loginPending by mutableStateOf(false)
        private set

    /** The username the account is logged in as ("" when logged out). */
    var username by mutableStateOf("")
        private set

    /** The title of the game RA has loaded (null when none). */
    var gameTitle by mutableStateOf<String?>(null)
        private set

    /** True once the loaded game's achievements are available. */
    var gameLoaded by mutableStateOf(false)
        private set

    /** The achievements list for the loaded game (empty when none). */
    var achievements by mutableStateOf<List<RaAchievement>>(emptyList())
        private set

    /** The latest unlock, shown as the popup until cleared. */
    var lastUnlock by mutableStateOf<RaUnlock?>(null)
        private set

    /** The status line for the Settings screen. */
    var status by mutableStateOf("")
        private set

    /**
     * Initialise the native RA client and wire the Bridge
     * callbacks. Idempotent. If [AppSettings] holds credentials,
     * the login starts here (the result arrives through
     * [onLoginResult]). Returns false when the native libraries
     * could not be loaded or the client could not be created.
     */
    fun init(settings: AppSettings): Boolean {
        if (initialised) return true
        if (!Bridge.ensureLoaded()) return false
        Bridge.raHttpRequestHandler = { id, url, post -> onHttpRequest(id, url, post) }
        Bridge.raEventUnlockedHandler = { id, title, desc, points -> onUnlocked(id, title, desc, points) }
        Bridge.raLoginResultHandler = { ok, err -> onLoginResult(ok, err) }
        Bridge.raLoadGameResultHandler = { ok, err -> onLoadGameResult(ok, err) }
        if (!Bridge.nativeRaInit()) {
            status = "RetroAchievements is not available."
            return false
        }
        initialised = true
        val user = settings.raUsername()
        val key = settings.raApiKey()
        if (user.isNotEmpty() && key.isNotEmpty()) {
            login(user, key)
        }
        return true
    }

    /**
     * Log in with a username and API key. The result arrives
     * asynchronously through [onLoginResult]; until then
     * [loginPending] is true. The credentials are NOT stored here
     * — the caller stores them in [AppSettings] first.
     */
    fun login(user: String, apiKey: String) {
        if (!initialised) return
        loginPending = true
        status = "Logging in…"
        Bridge.nativeRaLogin(user, apiKey)
    }

    /** Log out and clear the session state. */
    fun logout() {
        if (initialised) Bridge.nativeRaLogout()
        loggedIn = false
        loginPending = false
        username = ""
        gameTitle = null
        gameLoaded = false
        achievements = emptyList()
        lastUnlock = null
        status = "Not logged in."
    }

    /**
     * Load the achievements for a game (called once per game
     * start, after the core loads). The game is identified by
     * hashing the file; the result arrives through
     * [onLoadGameResult]. A game whose hash is not recognised (or
     * a disc, whose hash needs a CD reader the app does not yet
     * supply) simply leaves RA inactive for that game.
     */
    fun loadGame(gamePath: String) {
        if (!initialised || !loggedIn) return
        gameLoaded = false
        gameTitle = null
        achievements = emptyList()
        Bridge.nativeRaLoadGame(gamePath)
    }

    /** The game quit: the native layer unloads it (in nativeUnloadGame); clear the per-game state here. */
    fun onGameQuit() {
        gameTitle = null
        gameLoaded = false
        achievements = emptyList()
        lastUnlock = null
    }

    /**
     * One displayed frame's achievement evaluation. Called from
     * the emu thread after the frame run; a cheap no-op while no
     * game is loaded in RA (the flag is only set by the load
     * callback, so re-simulation frames with run-ahead on never
     * double-evaluate — the Kotlin loop calls this once per
     * displayed frame).
     */
    fun onFrame() {
        if (initialised && gameLoaded) Bridge.nativeRaDoFrame()
    }

    /** Serialized RA progress, for the save-state sidecar. */
    fun serializeProgress(): ByteArray? =
        if (initialised && gameLoaded) Bridge.nativeRaSerializeProgress() else null

    /** Restore RA progress from a save-state sidecar. */
    fun deserializeProgress(bytes: ByteArray) {
        if (initialised && gameLoaded) Bridge.nativeRaDeserializeProgress(bytes)
    }

    /** Clear RA progress (a load with no sidecar). */
    fun resetProgress() {
        if (initialised && gameLoaded) Bridge.nativeRaReset()
    }

    /** Refresh the achievements list from the native layer. */
    fun refreshAchievements() {
        if (!initialised || !gameLoaded) return
        val json = Bridge.nativeRaGetAchievements() ?: return
        achievements = RaAchievements.parse(String(json, Charsets.UTF_8))
    }

    /** Clear the unlock popup (after its 4-second showing). */
    fun clearUnlock() {
        lastUnlock = null
    }

    /** A server call from the native layer: perform it, then hand the response back by id. */
    fun onHttpRequest(requestId: Int, url: String, postData: String?) {
        ioScope.launch {
            val (body, code) = httpRequest(url, postData)
            try {
                Bridge.nativeRaHttpResponse(requestId, body, code)
            } catch (_: Exception) {
                // The native layer is gone (the game quit while a
                // request was in flight); the response is dropped.
            }
        }
    }

    /** An achievement was unlocked: show the popup and refresh the list. */
    fun onUnlocked(id: Int, title: String, description: String, points: Int) {
        lastUnlock = RaUnlock(id, title, description, points)
        refreshAchievements()
    }

    /** The login result. */
    fun onLoginResult(success: Boolean, error: String?) {
        loginPending = false
        loggedIn = success
        if (success) {
            username = Bridge.nativeRaLoggedInUsername() ?: ""
            status = if (username.isNotEmpty()) "Logged in as $username." else "Logged in."
        } else {
            username = ""
            status = if (!error.isNullOrEmpty()) "Login failed: $error" else "Login failed."
        }
    }

    /** The game-load result. */
    fun onLoadGameResult(success: Boolean, error: String?) {
        gameLoaded = success
        if (success) {
            gameTitle = Bridge.nativeRaGameTitle()
            refreshAchievements()
        } else {
            gameTitle = null
            achievements = emptyList()
        }
    }

    /**
     * One HTTP request (GET when [postData] is null, POST
     * otherwise). Returns the response body and the HTTP status
     * code; on any failure an empty body and status 0, so the
     * native layer fails the call cleanly instead of hanging.
     * The RA API's POST bodies are form-encoded.
     */
    private fun httpRequest(url: String, postData: String?): Pair<ByteArray, Int> {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "HowlingDog")
                if (postData != null) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded",
                    )
                }
            }
        } catch (_: Exception) {
            return ByteArray(0) to 0
        }
        try {
            if (postData != null) {
                conn.outputStream.use { out ->
                    out.write(postData.toByteArray(Charsets.UTF_8))
                }
            }
            val code = try {
                conn.responseCode
            } catch (_: Exception) {
                return ByteArray(0) to 0
            }
            if (code != HttpURLConnection.HTTP_OK) return ByteArray(0) to code
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray() to code
        } catch (_: Exception) {
            return ByteArray(0) to 0
        } finally {
            conn.disconnect()
        }
    }
}
