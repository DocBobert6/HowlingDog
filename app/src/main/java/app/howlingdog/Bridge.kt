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
            Log.e(TAG, "failed to load native libraries", e)
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

    /**
     * Source probe (no-video bug): what the core delivered, sampled in
     * bridge_video before the copy. Returns [video calls, non-zero
     * uint32s in the last delivered frame, data[0], data[100],
     * data[1000], width, height, pitch bytes]. The raw words are bit
     * patterns; format them as hex on the Kotlin side.
     */
    external fun nativeGetSrcProbe(): IntArray

    /**
     * TEMPORARY DIAGNOSTIC (no-video bug): palette/VRAM write counters
     * from the core. Returns [palram writes, vram writes, pal0..pal7,
     * palette bank, 68k PC]. Pal values are 16-bit; PC is a 24-bit
     * 68k address. Counts are 32-bit-truncated.
     */
    external fun nativeGetPalDiag(): IntArray

    /** Drains up to [maxFrames] stereo frames into [samples]; returns frames read. */
    external fun nativeReadAudio(samples: ShortArray, maxFrames: Int): Int
    external fun nativeSetInput(mask: Int)
    external fun nativeUnloadGame()
    external fun nativeDeinit()
    external fun nativeLastError(): String
    external fun nativeSystemInfo(): String
}
