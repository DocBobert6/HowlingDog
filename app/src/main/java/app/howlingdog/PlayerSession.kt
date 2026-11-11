package app.howlingdog

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 2 spike session: owns the emulation thread, the audio thread,
 * and the frame Bitmaps. One session per game start; stop() unloads
 * the game and tears the native side down.
 *
 * Phase 4 adds pause/resume and save states. Pausing stops the
 * emulation loop cleanly (no retro_run calls, audio silenced) while
 * the game stays loaded, so the pause screen can save or load a
 * state and resume without losing anything. Serialize/unserialize
 * must only run while paused: pause() waits out any in-flight frame,
 * so the state snapshot can never race retro_run.
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
    /**
     * Phase 10 — run-ahead frames for this game (0 = off, 1 or 2),
     * resolved by the caller from the global setting and any
     * per-game override. Read once at game start; changing it in
     * Settings takes effect on the next game start.
     */
    private val runaheadFrames: Int = 0,
) {
    companion object {
        private const val TAG = "HowlingDog"
        private const val AUDIO_RATE = 48000
    }

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    /** True while a retro_run() call is in flight on the emu thread. */
    @Volatile
    private var inFrame = false

    /**
     * A one-shot task the emu thread runs while paused (see
     * [postPausedTask]). Held in a volatile so any thread can queue
     * it; the emu loop takes it exactly once.
     */
    @Volatile
    private var pausedTask: (() -> Unit)? = null

    /**
     * Queue a task to run on the emu thread the next time it is
     * paused. The task runs on the emu thread itself, so it can
     * safely call [serializeState]/[unserializeState] (which must
     * never race retro_run). Only one task is held; a new one
     * replaces a pending one, and a task queued after the session
     * stops is dropped. The caller must have paused the session
     * first (or the task waits for the next pause).
     */
    fun postPausedTask(task: () -> Unit) {
        pausedTask = task
    }

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

    /** Pause the game: the emu loop idles (no retro_run) and the audio is silenced. The game stays loaded. */
    fun pause() {
        if (!running || paused) return
        paused = true
        // Wait out any in-flight frame so a state snapshot taken
        // after this can never race retro_run (a frame takes <1/60s;
        // the deadline is only a safety valve).
        val deadline = System.nanoTime() + 200_000_000L
        while (inFrame && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5)
            } catch (e: InterruptedException) {
                break
            }
        }
        // Phase 9, item 5: flush the memory card (and NVRAM /
        // cartridge RAM / CD backup RAM) to disk now, while the
        // game is paused, so progress survives even if the app is
        // killed before quit. The flush runs on the emu thread via
        // a paused task — it can never race retro_run — and pause()
        // waits for it (2 s timeout, same as the quick-save path),
        // so the card is on disk by the time pause() returns. A
        // failed flush only logs: quitting still writes the card
        // as before, and the next pause retries it.
        val flushDone = CountDownLatch(1)
        var flushOk = false
        postPausedTask {
            flushOk = try {
                Bridge.nativeFlushSaveData()
            } catch (_: Exception) {
                false
            }
            flushDone.countDown()
        }
        try {
            flushDone.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            // The flush may still land; quitting rewrites the card.
        }
        if (!flushOk)
            Log.w(TAG, "memory-card flush on pause failed")
        audioTrack?.let {
            try {
                it.pause()
            } catch (_: IllegalStateException) {
                // Already stopped; nothing to silence.
            }
            try {
                it.flush()
            } catch (_: IllegalStateException) {
                // Nothing buffered; nothing to drop.
            }
        }
    }

    /** Resume a paused game. */
    fun resume() {
        if (!running || !paused) return
        audioTrack?.let {
            try {
                it.play()
            } catch (_: IllegalStateException) {
                // The track is gone; the audio thread restarts it on the next frame.
            }
        }
        paused = false
    }

    /**
     * Snapshot the running state as bytes. Only call while paused
     * (see [pause]); null when no game is loaded or the core refuses.
     */
    fun serializeState(): ByteArray? {
        if (!running || !paused) return null
        return try {
            Bridge.nativeSerialize()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Restore a snapshot taken by [serializeState]. Only call while
     * paused. False when the state does not match this game (the core
     * refuses states saved under a different system type or region).
     */
    fun unserializeState(data: ByteArray): Boolean {
        if (!running || !paused) return false
        return try {
            Bridge.nativeUnserialize(data)
        } catch (_: Exception) {
            false
        }
    }

    fun start() {
        if (running) return
        running = true
        emuThread = Thread(::emuLoop, "howlingdog-emu").also { it.start() }
    }

    fun stop() {
        running = false
        paused = false
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
        Log.i(TAG, "playing ${width}x${height} @ $fps fps, audio ${av[3]} Hz -> $AUDIO_RATE Hz, run-ahead ${runaheadFrames} frame(s)")
        // Push any input the UI already recorded before the load finished.
        Bridge.nativeSetInput(inputMask)
        // Phase 10 — size the run-ahead ring for this game now
        // that it is loaded (the state size is only known then).
        // With run-ahead off this stores 0 and the loop below uses
        // the plain frame path, exactly as in v1.0.0.
        Bridge.nativeSetRunahead(runaheadFrames)
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
            if (paused) {
                // A task queued for the paused emu thread (quick
                // save/load): run it here, on the emu thread
                // itself, so a state snapshot can never race
                // retro_run. One-shot: taken before it runs, so a
                // task that re-queues itself cannot loop.
                pausedTask?.let { task ->
                    pausedTask = null
                    try {
                        task()
                    } catch (e: Exception) {
                        Log.w(TAG, "paused task failed", e)
                    }
                }
                // Idle: no retro_run, no new frames. The last frame
                // stays on screen behind the pause screen.
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }
            val t0 = System.nanoTime()
            inFrame = true
            try {
                // Phase 10 — with run-ahead on, the frame goes
                // through the run-ahead step (restore, re-simulate,
                // display); with it off, the plain v1.0.0 path.
                if (runaheadFrames > 0)
                    Bridge.nativeRunFrameRunahead()
                else
                    Bridge.nativeRunFrame()
            } finally {
                inFrame = false
            }
            // Phase 10 — RetroAchievements: one evaluation per
            // displayed frame, after the frame run. With run-ahead
            // on, the re-simulation runs inside
            // nativeRunFrameRunahead must not evaluate — they do
            // not, because this call is here, once, after the
            // displayed frame. A no-op while no game is loaded
            // in RA.
            RaClient.onFrame()
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
            if (paused) {
                // Idle: the track is paused and flushed, so there is
                // nothing to drain.
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }
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
