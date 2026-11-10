package app.howlingdog

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Phase 2 spike session: owns the emulation thread, the audio thread,
 * and the frame Bitmaps. One session per game start; stop() unloads
 * the game and tears the native side down.
 *
 * Threading: the emulation thread calls retro_run() once per frame
 * and copies the new frame into the back Bitmap, then swaps it to the
 * front and posts it to the main thread. The audio thread drains the
 * bridge's resampled ring buffer into AudioTrack at 48 kHz. Input is
 * a single volatile mask written by the touch UI.
 */
class PlayerSession(
    private val systemDir: String,
    private val saveDir: String,
    private val gamePath: String,
    private val onFrame: (Bitmap) -> Unit,
    private val onError: (String) -> Unit,
    private val onStats: (FrameStats) -> Unit = {},
) {
    companion object {
        private const val TAG = "HowlingDog"
        private const val AUDIO_RATE = 48000
    }

    @Volatile
    private var running = false
    private var emuThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var inputMask = 0

    // Spike diagnostic (no-video bug): live frame counters for the
    // on-screen readout and the once-a-second logcat line.
    private var framesReceived = 0L
    private var framesDrawn = 0L
    private var lastW = 0
    private var lastH = 0
    private var bright = FrameBrightness(0, 0, 0, 0)
    private var avW = 0
    private var avH = 0
    private var avFps = 0.0
    private var lastStatsAtMs = 0L

    fun setInput(mask: Int) {
        inputMask = mask
        if (running) Bridge.nativeSetInput(mask)
    }

    fun start() {
        if (running) return
        running = true
        emuThread = Thread(::emuLoop, "howlingdog-emu").also { it.start() }
    }

    fun stop() {
        running = false
        try {
            emuThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "emu thread join interrupted", e)
        }
        try {
            audioThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "audio thread join interrupted", e)
        }
        emuThread = null
        audioThread = null
    }

    private fun fail(message: String) {
        running = false
        mainHandler.post { onError(message) }
    }

    private fun emuLoop() {
        if (!Bridge.ensureLoaded()) {
            fail("Native libraries failed to load.")
            return
        }
        if (!Bridge.nativeInit(systemDir, saveDir)) {
            fail("Could not initialise the core.")
            return
        }
        if (!Bridge.nativeLoadGame(gamePath)) {
            val detail = Bridge.nativeLastError().ifBlank { "unknown error" }
            Bridge.nativeDeinit()
            fail("Could not load the game. $detail")
            return
        }
        val av = Bridge.nativeGetAvInfo()
        val width = av[0]
        val height = av[1]
        val fps = av[2] / 1000.0
        avW = width
        avH = height
        avFps = fps
        Log.i(TAG, "playing ${width}x${height} @ $fps fps, audio ${av[3]} Hz -> $AUDIO_RATE Hz")
        // Push any input the UI already recorded before the load finished.
        Bridge.nativeSetInput(inputMask)
        if (width <= 0 || height <= 0 || fps <= 0.0) {
            Bridge.nativeUnloadGame()
            Bridge.nativeDeinit()
            fail("The core reported no video mode.")
            return
        }

        val frameNs = (1_000_000_000.0 / fps).toLong()
        val pixels = IntArray(width * height)
        val dims = IntArray(2)
        val bitmaps = arrayOf(
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
        )
        var front = 0

        startAudio()

        while (running) {
            val t0 = System.nanoTime()
            Bridge.nativeRunFrame()
            if (Bridge.nativeGetFrame(pixels, dims)) {
                framesReceived++
                val w = dims[0]
                val h = dims[1]
                lastW = w
                lastH = h
                if (w == width && h == height) {
                    framesDrawn++
                    bright = sampleFrameBrightness(pixels, width, height)
                    val back = 1 - front
                    bitmaps[back].setPixels(pixels, 0, width, 0, 0, width, height)
                    front = back
                    val frame = bitmaps[front]
                    mainHandler.post { onFrame(frame) }
                } else {
                    // Geometry changed mid-game (rare for Neo Geo);
                    // the spike drops the frame rather than guessing.
                    Log.w(TAG, "frame geometry changed to ${w}x$h, dropping frame")
                }
            }
            // Diagnostic: emit the live numbers once a second, on the
            // main thread for the overlay and in logcat for bug reports.
            // This runs even when no frames arrive, so frames == 0 is
            // visible too.
            val now = SystemClock.uptimeMillis()
            if (now - lastStatsAtMs >= 1000) {
                lastStatsAtMs = now
                val probe = Bridge.nativeGetSrcProbe()
                // TEMPORARY DIAGNOSTIC (no-video bug): palette/VRAM
                // write counters + 68k PC from the core. The counts
                // are unsigned 32-bit values in a signed IntArray.
                val pal = Bridge.nativeGetPalDiag()
                val s = FrameStats(
                    framesReceived, framesDrawn, lastW, lastH,
                    avW, avH, avFps, bright.mean,
                    bright.centreR, bright.centreG, bright.centreB,
                    srcCalls = probe[0].toLong(),
                    srcNonzero = probe[1],
                    srcFirst = probe[2],
                    srcFirst100 = probe[3],
                    srcFirst1000 = probe[4],
                    srcW = probe[5],
                    srcH = probe[6],
                    srcPitch = probe[7],
                    lastError = Bridge.nativeLastError(),
                    palWrites = pal[0].toLong() and 0xFFFFFFFFL,
                    vramWrites = pal[1].toLong() and 0xFFFFFFFFL,
                    palBank = pal[10],
                    palPc = pal[11],
                    pal = pal.copyOfRange(2, 10),
                )
                // Same text as the on-screen readout, one line for
                // logcat (pipes stand in for the newlines).
                Log.i(TAG, formatDiagText(s).replace("\n", " | "))
                mainHandler.post { onStats(s) }
            }
            val elapsed = System.nanoTime() - t0
            val remaining = frameNs - elapsed
            if (remaining > 0) {
                try {
                    Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                } catch (e: InterruptedException) {
                    break
                }
            }
        }

        stopAudio()
        Bridge.nativeUnloadGame()
        Bridge.nativeDeinit()
        Log.i(TAG, "session stopped")
    }

    private fun startAudio() {
        val minBuffer = AudioTrack.getMinBufferSize(
            AUDIO_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer * 2, AUDIO_RATE / 10 * 2 * 2)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()
        audioThread = Thread(::audioLoop, "howlingdog-audio").also { it.start() }
    }

    private fun audioLoop() {
        val track = audioTrack ?: return
        val samples = ShortArray(4096 * 2) // stereo frames * 2 channels
        while (running) {
            val frames = Bridge.nativeReadAudio(samples, 4096)
            if (frames > 0) {
                track.write(samples, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
            } else {
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun stopAudio() {
        audioThread?.let {
            try {
                it.join(1000)
            } catch (e: InterruptedException) {
                Log.w(TAG, "audio thread join interrupted", e)
            }
        }
        audioThread = null
        audioTrack?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "audio track stop failed", e)
            }
            it.release()
        }
        audioTrack = null
    }
}
