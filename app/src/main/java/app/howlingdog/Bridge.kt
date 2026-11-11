package app.howlingdog

import android.util.Log

/**
 * JNI entry points into the Phase 2 bridge (bridge/jni/bridge.cpp).
 *
 * Native libraries are loaded LAZILY, only when a game is started —
 * never in a static init and never from MainActivity.onCreate — so
 * the app launches (and the Robolectric startup gate runs) with no
 * native code present.
 */
object Bridge {
    private const val TAG = "HowlingDog"

    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("retro")
            System.loadLibrary("howlingdog-bridge")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            // Log.e is a stub that throws RuntimeException on the
            // plain JVM unit tests, so guard it: the false return
            // already reports the failure to the caller.
            try {
                Log.e(TAG, "failed to load native libraries", e)
            } catch (_: RuntimeException) {
            }
            false
        }
    }

    external fun nativeInit(systemDir: String, saveDir: String): Boolean
    external fun nativeLoadGame(path: String): Boolean

    /** [width, height, fps * 1000, sample rate], from av_info after load. */
    external fun nativeGetAvInfo(): IntArray
    external fun nativeRunFrame()

    /** Copies the latest frame into [pixels]; [dims] gets [width, height]. */
    external fun nativeGetFrame(pixels: IntArray, dims: IntArray): Boolean

    /** Drains up to [maxFrames] stereo frames into [samples]; returns frames read. */
    external fun nativeReadAudio(samples: ShortArray, maxFrames: Int): Int
    external fun nativeSetInput(mask: Int)

    /** Set one core option (see bridge.cpp). False for an unknown key. */
    external fun nativeSetOption(key: String, value: String): Boolean

    /**
     * Run-ahead (Phase 10): 0 = off, 1 or 2 frames ahead. Only
     * call on the emu thread (or before it starts); takes effect
     * on the next game start when changed in Settings.
     */
    external fun nativeSetRunahead(frames: Int)

    /** One displayed frame with run-ahead (see bridge.cpp). */
    external fun nativeRunFrameRunahead()

    /** Save-state bytes; only call while the emulation thread is paused. */
    external fun nativeSerialize(): ByteArray?
    external fun nativeUnserialize(data: ByteArray): Boolean

    /**
     * Flush the in-memory save data (memory card / NVRAM / cartridge
     * RAM / CD backup RAM) to the save directory without unloading
     * the game; only call while the emulation thread is paused.
     * False when no game is loaded or a save failed.
     */
    external fun nativeFlushSaveData(): Boolean

    external fun nativeUnloadGame()
    external fun nativeDeinit()
    external fun nativeLastError(): String

    // Phase 10 — RetroAchievements (bridge/jni/ra_client.c).
    // The client is created once and lives for the app's lifetime;
    // login persists, so it is NOT shut down in nativeDeinit. Only
    // the loaded game is unloaded in nativeUnloadGame. The memory
    // provider calls retro_get_memory_data(SYSTEM_RAM); the
    // 68k mapping lives in ra_client.c. HTTP responses and the
    // achievements JSON cross as byte[] (UTF-8, encoding-safe).
    external fun nativeRaInit(): Boolean
    // Password login (v1.1.2 — the API-key login is gone): the
    // password is used once for this login and is never stored.
    // The session token the server returns is kept instead, via
    // nativeRaToken, and the next launch re-logs in with
    // nativeRaLoginWithToken.
    external fun nativeRaLogin(username: String, password: String)
    external fun nativeRaLoginWithToken(username: String, token: String)
    external fun nativeRaToken(): String?
    external fun nativeRaLogout()
    external fun nativeRaIsLoggedIn(): Boolean
    external fun nativeRaLoggedInUsername(): String?
    external fun nativeRaLoadGame(path: String)
    external fun nativeRaUnloadGame()
    external fun nativeRaIsGameLoaded(): Boolean
    external fun nativeRaGameTitle(): String?
    external fun nativeRaDoFrame()
    external fun nativeRaReset()
    external fun nativeRaSerializeProgress(): ByteArray?
    external fun nativeRaDeserializeProgress(data: ByteArray)
    external fun nativeRaGetAchievements(): ByteArray?
    // v1.2.0 (item 2): leaderboards and rich presence. The
    // entries fetch is async: the result arrives through
    // raLeaderboardEntriesResult, and nativeRaFetchLeaderboardEntries
    // returns false when no game is loaded (no callback then).
    external fun nativeRaHasLeaderboards(): Boolean
    external fun nativeRaGetLeaderboards(): ByteArray?
    external fun nativeRaFetchLeaderboardEntries(leaderboardId: Int, aroundUser: Boolean): Boolean
    external fun nativeRaHasRichPresence(): Boolean
    external fun nativeRaGetRichPresence(): String?
    external fun nativeRaHttpResponse(requestId: Int, body: ByteArray, httpStatus: Int)

    /**
     * v1.5, feature 4 — cheats: write one byte to the game's
     * work RAM (address 0x100000–0x10FFFF, value 0–255). Called
     * from the emu thread after every displayed frame. False
     * when no game is loaded or the address is outside the work
     * RAM — a bad code is a no-op, never a crash.
     */
    external fun nativeCheatWrite(address: Int, value: Int): Boolean

    /**
     * The Kotlin-side handlers for the RA callbacks the native
     * layer calls (looked up by GetMethodID in nativeRaInit, so
     * the function names and signatures below must match
     * bridge.cpp exactly). They are nullable lambdas, set by
     * [RaClient] when it initialises — Bridge itself stays free
     * of the RA session, so the pure-test harness still compiles
     * this file. A callback with no handler set is a no-op (the
     * native client is only created after the handlers are set).
     */
    var raHttpRequestHandler: ((requestId: Int, url: String, postData: String?) -> Unit)? = null
    var raEventUnlockedHandler: ((id: Int, title: String, description: String, points: Int) -> Unit)? = null
    // v1.2.0 (item 2): a leaderboard event (tracker show/hide/
    // update, scoreboard) and the entries-fetch result. The
    // event's json is the tracker {"display"} or the scoreboard
    // payload, or null when the event carries none.
    var raLeaderboardEventHandler: ((type: Int, leaderboardId: Int, json: String?) -> Unit)? = null
    var raLeaderboardEntriesResultHandler: ((success: Boolean, error: String?, json: String?) -> Unit)? = null
    var raLoginResultHandler: ((success: Boolean, error: String?) -> Unit)? = null
    var raLoadGameResultHandler: ((success: Boolean, error: String?) -> Unit)? = null

    /** A server call from the native layer (url + optional POST body). */
    fun raHttpRequest(requestId: Int, url: String, postData: String?) {
        raHttpRequestHandler?.invoke(requestId, url, postData)
    }

    /** An achievement was unlocked. */
    fun raEventUnlocked(id: Int, title: String, description: String, points: Int) {
        raEventUnlockedHandler?.invoke(id, title, description, points)
    }

    /** A leaderboard event (tracker show/hide/update, scoreboard). */
    fun raLeaderboardEvent(type: Int, leaderboardId: Int, json: String?) {
        raLeaderboardEventHandler?.invoke(type, leaderboardId, json)
    }

    /** The leaderboard entries fetch finished. */
    fun raLeaderboardEntriesResult(success: Boolean, error: String?, json: String?) {
        raLeaderboardEntriesResultHandler?.invoke(success, error, json)
    }

    /** The login result. */
    fun raLoginResult(success: Boolean, error: String?) {
        raLoginResultHandler?.invoke(success, error)
    }

    /** The game-load result. */
    fun raLoadGameResult(success: Boolean, error: String?) {
        raLoadGameResultHandler?.invoke(success, error)
    }
}
