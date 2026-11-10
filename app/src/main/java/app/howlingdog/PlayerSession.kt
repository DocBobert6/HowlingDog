package app.howlingdog

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
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
                val w = dims[0]
                val h = dims[1]
                if (w == width && h == height) {
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
