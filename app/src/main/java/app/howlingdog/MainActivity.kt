package app.howlingdog

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.Vibrator
import android.provider.OpenableColumns
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 5 era skin: the fighting-game select grid (KOF '94/'95,
 * Street Fighter II) — the library as a grid of portrait tiles
 * with the picked game big in the middle, a VS-style boot splash,
 * and a "CONTINUE?" pause screen. The mascot is the icon itself:
 * Robert's howling-dog artwork, shown big on the boot splash.
 *
 * Library screen (the home screen): the persistent game list, the
 * system-files status, and import — one game file, or a whole folder
 * (a folder import brings a cue's data files with it). Games are
 * COPIED into app-private storage (the core only reads real file
 * paths): games to filesDir/games/, system files to filesDir/bios/.
 * Nothing is uploaded anywhere. A game is a game: the list shows
 * names only, never a media type, and never sorts by media; the UI
 * shows no disc imagery.
 *
 * Game screen: the frame, plus the touch controls floating over it
 * (directions, A/B/C/D, Start, Coin) — just enough to play.
 *
 * No native code is touched here: Bridge loads its libraries lazily
 * when a game starts, so this activity launches with no native code
 * present (the Robolectric startup gate depends on that).
 *
 * Rotation: MainActivity declares configChanges in the manifest, so
 * entering the game screen's forced landscape does not recreate the
 * activity. The library itself lives on disk (GameLibrary), so a
 * recreation loses nothing — the safe fallback is the library screen
 * with the session stopped.
 */
class MainActivity : ComponentActivity() {

    private var session: PlayerSession? = null

    // --- Gamepad state (Phase 4). The touch UI keeps its own Held
    // state in the composable; the pad keeps its held buttons and
    // axis directions here, and the two masks are ORed in pushMask.
    /** Gamepad buttons currently held (KeyEvent keycodes). */
    private val padButtonsHeld = mutableSetOf<Int>()
    /** Direction bits from the gamepad axes (stick/hat). */
    private var padAxesMask = 0
    /** True only while a game is running and not paused: pad input is tracked only then, so the D-pad stays free for the pause menu and the library. */
    private var inputActive = false
    /** Set by the UI while the game screen is active; called with the current gamepad mask. */
    private var onPadMask: ((Int) -> Unit)? = null
    /** Set by the UI; called by the START+SELECT pause hotkey. */
    private var onPauseHotkey: (() -> Unit)? = null
    /** Set by the UI; called by the L1+R1 quick-save and L2+R2 quick-load hotkeys. */
    private var onQuickSaveHotkey: (() -> Unit)? = null
    private var onQuickLoadHotkey: (() -> Unit)? = null
    /**
     * Shoulder buttons held for the quick-save/load combos (L1/R1/
     * L2/R2). Tracked separately from padButtonsHeld: they are not
     * Neo Geo inputs, so they must never reach the game mask, and
     * they are dropped when the game pauses, so a shoulder held
     * across a pause cannot fire a combo on resume.
     */
    private val padQuickHeld = mutableSetOf<Int>()
    /**
     * True while a quick save/load is in progress (set on the main
     * thread, cleared when it completes). resumeGame() waits it out:
     * a resume landing mid-snapshot would let retro_run race the
     * serialize. A second quick trigger while one runs is ignored.
     */
    private var quickBusy = false
    /**
     * The GL game view (an Activity field, not composable state,
     * so onPause/onResume can forward to it: a GLSurfaceView
     * must be paused with the Activity, or its render thread
     * keeps running while the app is backgrounded).
     */
    private var gameGlView: GameGlView? = null

    /** A pad that disappears must never leave a stuck button. */
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            if (padButtonsHeld.isNotEmpty() || padAxesMask != 0) {
                padButtonsHeld.clear()
                padAxesMask = 0
                pushPadMask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (getSystemService(INPUT_SERVICE) as InputManager)
            .registerInputDeviceListener(deviceListener, null)
        setContent {
            HowlingDogTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HowlingDogUi()
                }
            }
        }
    }

    override fun onDestroy() {
        (getSystemService(INPUT_SERVICE) as InputManager)
            .unregisterInputDeviceListener(deviceListener)
        session?.stop()
        session = null
        super.onDestroy()
    }

    override fun onPause() {
        gameGlView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        gameGlView?.onResume()
    }

    /** Recompute the gamepad mask from the held buttons and axes, and hand it to the UI. */
    private fun pushPadMask() {
        var mask = padAxesMask
        for (code in padButtonsHeld) mask = mask or GamepadInput.maskForButton(code)
        onPadMask?.invoke(mask)
    }

    /** Drop all gamepad-held input (quit, disconnect). */
    private fun clearPadState() {
        padButtonsHeld.clear()
        padAxesMask = 0
        padQuickHeld.clear()
        pushPadMask()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputActive || event.repeatCount != 0) return super.onKeyDown(keyCode, event)
        // Quick-save/load shoulder combos (L1+R1 / L2+R2): these
        // buttons are not game inputs, so they are tracked apart
        // from the joypad mask and never reach the game. The combo
        // fires when the second button of the pair goes down. This
        // runs before the isGamepadButton check below, which would
        // otherwise swallow the shoulder buttons (they map to no
        // joypad bit).
        if (GamepadInput.isQuickComboButton(keyCode)) {
            padQuickHeld.add(keyCode)
            if (GamepadInput.isQuickSaveCombo(padQuickHeld)) {
                onQuickSaveHotkey?.invoke()
                return true
            }
            if (GamepadInput.isQuickLoadCombo(padQuickHeld)) {
                onQuickLoadHotkey?.invoke()
                return true
            }
            return true
        }
        if (!GamepadInput.isGamepadButton(keyCode)) return super.onKeyDown(keyCode, event)
        // START+SELECT together pauses (the emulator-menu hotkey).
        // Start on its own stays the in-game Start button, and the
        // touch Pause button stays the primary pause control.
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START &&
            KeyEvent.KEYCODE_BUTTON_SELECT in padButtonsHeld
        ) {
            onPauseHotkey?.invoke()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT &&
            KeyEvent.KEYCODE_BUTTON_START in padButtonsHeld
        ) {
            onPauseHotkey?.invoke()
            return true
        }
        padButtonsHeld.add(keyCode)
        pushPadMask()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputActive) return super.onKeyUp(keyCode, event)
        if (padQuickHeld.remove(keyCode)) {
            return true
        }
        if (padButtonsHeld.remove(keyCode)) {
            pushPadMask()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (inputActive &&
            event.source and InputDevice.SOURCE_JOYSTICK != 0 &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val mask = GamepadInput.maskForAxes(event)
            if (mask != padAxesMask) {
                padAxesMask = mask
                pushPadMask()
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private enum class Screen { Boot, Library, Playing, Settings }

    private data class Held(
        val up: Boolean = false,
        val down: Boolean = false,
        val left: Boolean = false,
        val right: Boolean = false,
        val a: Boolean = false,
        val b: Boolean = false,
        val c: Boolean = false,
        val d: Boolean = false,
        val start: Boolean = false,
        val coin: Boolean = false,
    )

    @Composable
    private fun HowlingDogUi() {
        val context = LocalContext.current
        val activity = context as Activity
        // App-level settings (boot splash toggle): read before the
        // initial screen is chosen, so a disabled splash goes
        // straight to the library on launch.
        val appSettings = remember {
            AppSettings(
                SharedPrefsStore(
                    context.getSharedPreferences(
                        "howlingdog-settings", Context.MODE_PRIVATE,
                    ),
                ),
            )
        }
        var screen by remember { mutableStateOf(if (appSettings.showBoot()) Screen.Boot else Screen.Library) }
        var games by remember { mutableStateOf<List<GameEntry>>(emptyList()) }
        var bios by remember { mutableStateOf<BiosStatus?>(null) }
        var status by remember { mutableStateOf("") }
        var playingGame by remember { mutableStateOf<GameEntry?>(null) }
        var confirmRemove by remember { mutableStateOf<GameEntry?>(null) }
        var modePickerFor by remember { mutableStateOf<GameEntry?>(null) }
        var settingsFrom by remember { mutableStateOf(Screen.Library) }
        var paused by remember { mutableStateOf(false) }
        var pauseStatus by remember { mutableStateOf("") }
        // Quick save/load status: a brief note on the game screen
        // ("Saved." / "Loaded." / "No save yet."), cleared after a
        // couple of seconds. The pause menu keeps its own status.
        var quickStatus by remember { mutableStateOf("") }
        // Phase 5 — era skin state. The select grid's picked game is
        // a main-file name, derived fresh from `games` below, so it
        // never goes stale when the library reloads. The countdowns
        // are decorative and never trigger an action.
        var selectedMainFile by remember { mutableStateOf<String?>(null) }
        val selectedGame =
            games.firstOrNull { it.mainFile == selectedMainFile } ?: games.firstOrNull()
        var timeLeft by remember { mutableStateOf(9) }
        var pauseTimeLeft by remember { mutableStateOf(9) }
        var pressStartAlpha by remember { mutableStateOf(1f) }
        var padMask by remember { mutableStateOf(0) }
        var held by remember { mutableStateOf(Held()) }
        // (The GL game view lives in the Activity field gameGlView,
        // so onPause/onResume can forward to it.)
        // Cover art (box art fetched at runtime, Wi-Fi only,
        // cached — see CoverArt). `covers` holds the decoded
        // bitmap per main-file name; `artTried` remembers games
        // with no art (or a failed fetch) so a tile is not
        // re-attempted on every recomposition; `artIndex` is the
        // cached libretro thumbnails filename list.
        var artIndex by remember { mutableStateOf<List<String>?>(null) }
        var covers by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
        var artTried by remember { mutableStateOf<Set<String>>(emptySet()) }
        val coverArt = remember { CoverArt(context.filesDir) }

        val gamesDir = File(context.filesDir, "games")
        val biosDir = File(context.filesDir, "bios")
        val settings = remember {
            CoreSettings(
                SharedPrefsStore(
                    context.getSharedPreferences(
                        "howlingdog-settings", Context.MODE_PRIVATE,
                    ),
                ),
            )
        }
        val library = remember {
            GameLibrary(
                gamesDir = gamesDir,
                biosDir = biosDir,
                storeFile = File(context.filesDir, "library.txt"),
                legacyGamesDir = File(context.cacheDir, "games"),
            )
        }

        // On-screen controls (stick, haptics, opacity, size, layout):
        // same SharedPreferences file as the core options, own keys.
        val controlSettings = remember {
            ControlSettings(
                SharedPrefsStore(
                    context.getSharedPreferences(
                        "howlingdog-settings", Context.MODE_PRIVATE,
                    ),
                ),
            )
        }
        var hapticsLevel by remember { mutableStateOf(controlSettings.haptics()) }
        var overlayOpacity by remember { mutableStateOf(controlSettings.opacity()) }
        var controlSize by remember { mutableStateOf(controlSettings.size()) }
        // The on-screen left control (stick or 8-way D-pad).
        var leftControl by remember { mutableStateOf(controlSettings.leftControl()) }
        var showBoot by remember { mutableStateOf(appSettings.showBoot()) }
        // CRT display settings (shader chain, curvature): the same
        // SharedPreferences file, own "display.*" keys.
        val shaderSettings = remember {
            ShaderSettings(
                SharedPrefsStore(
                    context.getSharedPreferences(
                        "howlingdog-settings", Context.MODE_PRIVATE,
                    ),
                ),
            )
        }
        var crtShaderOn by remember { mutableStateOf(shaderSettings.shaderMasterOn()) }
        var curvatureOn by remember { mutableStateOf(shaderSettings.curvatureMasterOn()) }
        var stickPos by remember { mutableStateOf(controlSettings.stickPosition()) }
        var buttonsPos by remember { mutableStateOf(controlSettings.buttonsPosition()) }
        // Per-group resize multipliers, set by the resize handle in
        // adjust mode (1.0 = the Settings preset size).
        var stickScale by remember { mutableStateOf(controlSettings.stickScale()) }
        var buttonsScale by remember { mutableStateOf(controlSettings.buttonsScale()) }
        // Per-button offset overrides (Item 2): a button's offset
        // from the cluster centre, in dp at scale 1.0. Absent means
        // the theme layout's default offset for that button.
        var buttonOverrides by remember { mutableStateOf(controlSettings.buttonOffsets()) }
        // Move-together toggle (Item 2, Mariner's paired-buttons
        // behaviour): on, dragging a button moves the whole
        // cluster; off, it moves that button alone. A session
        // toggle, not persisted — Mariner's `grouped` is the same.
        var moveTogether by remember { mutableStateOf(true) }
        // Adjust-layout edit mode: entered from the pause screen
        // (the game has to be running to see where the controls
        // sit). While editing, the game stays paused, the pause
        // overlay hides, and the two control units drag.
        var editControls by remember { mutableStateOf(false) }

        fun exitEditMode() {
            controlSettings.setStickPosition(stickPos)
            controlSettings.setButtonsPosition(buttonsPos)
            controlSettings.setStickScale(stickScale)
            controlSettings.setButtonsScale(buttonsScale)
            for ((id, off) in buttonOverrides) controlSettings.setButtonOffset(id, off)
            editControls = false
        }
        val haptics = remember(context) {
            Haptics(context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        }
        fun playHaptic() = haptics.perform(hapticsLevel)

        // Re-read the control settings whenever the game screen is
        // entered, so a change made in Settings (or in the adjust
        // layout mode) is live the next time a game starts.
        LaunchedEffect(screen) {
            if (screen == Screen.Playing) {
                hapticsLevel = controlSettings.haptics()
                overlayOpacity = controlSettings.opacity()
                controlSize = controlSettings.size()
                leftControl = controlSettings.leftControl()
                stickPos = controlSettings.stickPosition()
                buttonsPos = controlSettings.buttonsPosition()
                stickScale = controlSettings.stickScale()
                buttonsScale = controlSettings.buttonsScale()
                buttonOverrides = controlSettings.buttonOffsets()
            }
        }

        // Boot splash: there is NO timeout — the splash waits until
        // the user taps or presses Start, unless the splash is
        // turned off in Settings (then the app opens on the library).
        // PRESS START blink (a delay loop: in tests the delay never
        // fires, so the text simply stays visible).
        LaunchedEffect(screen) {
            if (screen != Screen.Boot) return@LaunchedEffect
            while (true) {
                pressStartAlpha = 0.15f
                delay(700)
                pressStartAlpha = 1f
                delay(700)
            }
        }
        // Library countdown: resets when the selection changes,
        // wraps at 0, never starts or quits anything.
        LaunchedEffect(screen, selectedGame?.mainFile) {
            if (screen != Screen.Library) return@LaunchedEffect
            var t = 9
            timeLeft = t
            while (true) {
                delay(1000)
                t = EraUi.countdownNext(t, 9)
                timeLeft = t
            }
        }
        // Quick save/load status: a brief note on the game screen,
        // cleared after a couple of seconds. The effect restarts
        // when the status changes, so a second quick action
        // re-arms the timer instead of being cleared early.
        LaunchedEffect(quickStatus) {
            if (quickStatus.isNotEmpty()) {
                delay(2000)
                quickStatus = ""
            }
        }

        // Pause countdown: the same treatment, only while paused.
        LaunchedEffect(paused) {
            if (!paused) return@LaunchedEffect
            var t = 9
            pauseTimeLeft = t
            while (true) {
                delay(1000)
                t = EraUi.countdownNext(t, 9)
                pauseTimeLeft = t
            }
        }

        fun refreshLibrary() {
            lifecycleScope.launch(Dispatchers.IO) {
                val loaded = library.load()
                val b = library.biosStatus()
                withContext(Dispatchers.Main) {
                    games = loaded
                    bios = b
                }
            }
        }

        LaunchedEffect(Unit) { refreshLibrary() }

        // Cover art: fetch the filename index once (Wi-Fi only,
        // cached on the device), then one small image per game —
        // only when its tile is shown, only on Wi-Fi, only once
        // per game. Any failure leaves the typographic tile;
        // nothing here blocks the library or shows an error.
        LaunchedEffect(games, artIndex) {
            if (artIndex == null && CoverArt.isWifi(context)) {
                artIndex = withContext(Dispatchers.IO) { coverArt.loadIndex(isWifi = true) }
            }
            val index = artIndex ?: return@LaunchedEffect
            val pending = games.filter { it.mainFile !in covers && it.mainFile !in artTried }
            if (pending.isEmpty()) return@LaunchedEffect
            for (game in pending) {
                launch(Dispatchers.IO) {
                    val artFile = coverArt.resolveArtFile(game, gamesDir, index)
                    val bitmap = if (artFile != null) {
                        coverArt.cachedBitmap(game) ?: coverArt.fetchBitmap(artFile, game)
                    } else {
                        null
                    }
                    withContext(Dispatchers.Main) {
                        if (bitmap != null) covers = covers + (game.mainFile to bitmap)
                        artTried = artTried + game.mainFile
                    }
                }
            }
        }

        fun pushMask() {
            session?.setInput(
                InputMask.build(
                    up = held.up, down = held.down, left = held.left, right = held.right,
                    a = held.a, b = held.b, c = held.c, d = held.d,
                    start = held.start, coin = held.coin,
                ) or padMask,
            )
        }

        fun quitToLibrary() {
            inputActive = false
            clearPadState()
            session?.stop()
            session = null
            held = Held()
            playingGame = null
            paused = false
            pauseStatus = ""
            screen = Screen.Library
        }

        fun pauseGame() {
            if (session == null || paused) return
            inputActive = false
            // Drop any shoulder buttons held for a quick-save/load
            // combo, so one held across the pause cannot fire a
            // combo the moment the game resumes.
            padQuickHeld.clear()
            session?.pause()
            paused = true
            pauseStatus = ""
        }

        fun resumeGame() {
            if (!paused) return
            // A quick save/load owns the paused session until it
            // finishes; resuming mid-snapshot would let retro_run
            // race the serialize. The pause menu stays open and the
            // Resume tap is simply re-tried once it completes.
            if (quickBusy) return
            session?.resume()
            paused = false
            pauseStatus = ""
            inputActive = true
        }

        /** One save slot per game, in the saves folder (shared by the pause menu and quick save/load). */
        fun stateFileFor(entry: GameEntry): File =
            QuickSave.stateFile(File(context.filesDir, "saves"), entry.mainFile)

        fun saveState() {
            val game = playingGame ?: return
            val s = session ?: return
            val bytes = s.serializeState()
            if (bytes == null) {
                pauseStatus = "Could not save right now."
                return
            }
            try {
                val file = stateFileFor(game)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                pauseStatus = "Saved."
            } catch (e: Exception) {
                pauseStatus = "Could not save: ${e.message}"
            }
        }

        fun loadState() {
            val game = playingGame ?: return
            val s = session ?: return
            val file = stateFileFor(game)
            if (!file.isFile) {
                pauseStatus = "No save yet for this game."
                return
            }
            val bytes = try {
                file.readBytes()
            } catch (e: Exception) {
                pauseStatus = "Could not read the save: ${e.message}"
                return
            }
            if (s.unserializeState(bytes)) {
                pauseStatus = "Loaded."
                resumeGame()
            } else {
                pauseStatus = "That save doesn't match this game setup."
            }
        }

        /**
         * Quick save/load: save and load the per-game state slot
         * instantly, without opening the pause menu. The slot is
         * the SAME file the pause menu uses (stateFileFor), so the
         * two paths always see the same state; there is no second
         * slot.
         *
         * The game is paused only for the snapshot itself, and
         * resumes right after, so gameplay is interrupted for a
         * fraction of a second at most. The serialize runs on the
         * emu thread (via postPausedTask, which can never race
         * retro_run) and the blocking work (the pause wait, the
         * file IO) runs on a worker thread — the UI thread is never
         * blocked. A quick load with no save yet shows "No save
         * yet." and touches nothing (the game is not even paused).
         * A second quick trigger while one is in progress is
         * ignored, and resumeGame() waits one out (see quickBusy).
         */
        fun quickSave() {
            val game = playingGame ?: return
            val s = session ?: return
            if (paused || quickBusy) return
            quickBusy = true
            lifecycleScope.launch(Dispatchers.Default) {
                s.pause()
                val file = stateFileFor(game)
                val done = CountDownLatch(1)
                var ok = false
                s.postPausedTask {
                    val bytes = s.serializeState()
                    ok = if (bytes != null) {
                        try {
                            file.parentFile?.mkdirs()
                            file.writeBytes(bytes)
                            true
                        } catch (e: Exception) {
                            false
                        }
                    } else {
                        false
                    }
                    done.countDown()
                }
                // The task runs on the emu thread within ~50ms of
                // the pause; the timeout only guards a session that
                // stopped mid-save (quit), in which case the game is
                // gone and resume() below is a no-op.
                if (!done.await(2, TimeUnit.SECONDS)) {
                    s.postPausedTask { }
                }
                withContext(Dispatchers.Main) {
                    // Only resume if the user has not opened the
                    // pause menu meanwhile — then the pause menu owns
                    // the paused session and the quick save must not
                    // resume it.
                    if (!paused) s.resume()
                    quickStatus = if (ok) "Saved." else "Could not save right now."
                    quickBusy = false
                }
            }
        }

        fun quickLoad() {
            val game = playingGame ?: return
            val s = session ?: return
            if (paused || quickBusy) return
            val file = stateFileFor(game)
            if (!QuickSave.loadAvailable(file)) {
                quickStatus = "No save yet."
                return
            }
            quickBusy = true
            lifecycleScope.launch(Dispatchers.Default) {
                val bytes = try {
                    file.readBytes()
                } catch (e: Exception) {
                    null
                }
                s.pause()
                val done = CountDownLatch(1)
                var ok = false
                s.postPausedTask {
                    ok = bytes != null && s.unserializeState(bytes)
                    done.countDown()
                }
                if (!done.await(2, TimeUnit.SECONDS)) {
                    s.postPausedTask { }
                }
                withContext(Dispatchers.Main) {
                    if (!paused) s.resume()
                    quickStatus = when {
                        bytes == null -> "Could not read the save."
                        ok -> "Loaded."
                        else -> "That save doesn't match this game setup."
                    }
                    quickBusy = false
                }
            }
        }

        fun startGame(entry: GameEntry) {
            val b = bios ?: library.biosStatus()
            if (!library.biosReadyFor(entry, b)) {
                status = "This game needs its system files — pick the BIOS folder."
                return
            }
            // Push the stored options and this game's mode into the
            // bridge BEFORE the session starts: the core reads the
            // system types only at retro_init.
            settings.applyAll()
            settings.applyMode(entry)
            val saveDir = File(context.filesDir, "saves").apply { mkdirs() }
            status = "Starting ${entry.displayName}…"
            session = PlayerSession(
                systemDir = biosDir.absolutePath,
                saveDir = saveDir.absolutePath,
                gamePath = entry.mainPath(gamesDir).absolutePath,
                onFrame = { bitmap -> gameGlView?.setFrame(bitmap) },
                onError = { message ->
                    inputActive = false
                    status = message
                    session = null
                    playingGame = null
                    screen = Screen.Library
                },
            )
            playingGame = entry
            screen = Screen.Playing
            session?.start()
            inputActive = true
        }

        // Wire the gamepad callbacks: the activity's key/motion
        // handlers call these, and they read the composable state
        // through the remembered State objects, so the captured
        // lambdas stay correct across recompositions.
        DisposableEffect(Unit) {
            onPadMask = { mask -> padMask = mask; pushMask() }
            onPauseHotkey = { pauseGame() }
            onQuickSaveHotkey = { quickSave() }
            onQuickLoadHotkey = { quickLoad() }
            onDispose {
                onPadMask = null
                onPauseHotkey = null
                onQuickSaveHotkey = null
                onQuickLoadHotkey = null
            }
        }

        val gamePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val name = displayName(uri) ?: "game.neo"
                val ext = name.substringAfterLast('.', "").lowercase()
                when (ext) {
                    "neo", "chd" -> {
                        val dest = File(gamesDir.apply { mkdirs() }, sanitize(name))
                        try {
                            contentResolver.openInputStream(uri)?.use { input ->
                                FileOutputStream(dest).use { output -> input.copyTo(output) }
                            }
                            val entry = library.register(dest.name, listOf(dest.name))
                            withContext(Dispatchers.Main) {
                                refreshLibrary()
                                status = "Added ${entry.displayName}."
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                status = "Could not copy the game file: ${e.message}"
                            }
                        }
                    }
                    "cue" -> withContext(Dispatchers.Main) {
                        status = "This game needs its data files too — add it with Add folder."
                    }
                    else -> withContext(Dispatchers.Main) {
                        status = "That file is not a game this app plays."
                    }
                }
            }
        }

        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                gamesDir.mkdirs()
                var added = 0
                var skipped = 0
                try {
                    val tree = DocumentFile.fromTreeUri(context, uri)
                    val files = tree?.listFiles()?.filter { it.isFile } ?: emptyList()
                    val byLower = files.associateBy { (it.name ?: "").lowercase() }
                    for (doc in files) {
                        val name = doc.name ?: continue
                        val ext = name.substringAfterLast('.', "").lowercase()
                        when (ext) {
                            "neo", "chd" -> {
                                val dest = File(gamesDir, sanitize(name))
                                contentResolver.openInputStream(doc.uri)?.use { input ->
                                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                                }
                                library.register(dest.name, listOf(dest.name))
                                added++
                            }
                            "cue" -> {
                                val cueText = try {
                                    contentResolver.openInputStream(doc.uri)
                                        ?.bufferedReader()?.readText() ?: ""
                                } catch (_: Exception) {
                                    ""
                                }
                                val bins = parseCueBinFiles(cueText)
                                val resolved = bins.map { byLower[it.lowercase()] }
                                if (resolved.any { it == null }) {
                                    skipped++
                                    continue
                                }
                                val binDocs = resolved.filterNotNull()
                                val destCue = File(gamesDir, sanitize(name))
                                contentResolver.openInputStream(doc.uri)?.use { input ->
                                    FileOutputStream(destCue).use { output -> input.copyTo(output) }
                                }
                                val stored = mutableListOf(destCue.name)
                                for ((binName, binDoc) in bins.zip(binDocs)) {
                                    val destBin = File(gamesDir, sanitize(binName))
                                    contentResolver.openInputStream(binDoc.uri)?.use { input ->
                                        FileOutputStream(destBin).use { output -> input.copyTo(output) }
                                    }
                                    stored.add(destBin.name)
                                }
                                library.register(destCue.name, stored)
                                added++
                            }
                            // Anything else (data files on their own,
                            // stray files) is not a game: ignored.
                            else -> { /* no-op */ }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        refreshLibrary()
                        status = buildString {
                            append("Added $added game${if (added == 1) "" else "s"}")
                            if (skipped > 0) append(" · $skipped skipped (missing data files)")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        status = "Could not import from that folder: ${e.message}"
                    }
                }
            }
        }

        val biosPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val dir = biosDir.apply { mkdirs() }
                var count = 0
                try {
                    val tree = DocumentFile.fromTreeUri(context, uri)
                    val zips = tree?.listFiles()
                        ?.filter { it.isFile && it.name?.lowercase()?.endsWith(".zip") == true }
                        ?: emptyList()
                    for (zip in zips) {
                        val name = zip.name ?: continue
                        contentResolver.openInputStream(zip.uri)?.use { input ->
                            FileOutputStream(File(dir, sanitize(name))).use { output ->
                                input.copyTo(output)
                            }
                        }
                        count++
                    }
                    val b = library.biosStatus()
                    withContext(Dispatchers.Main) {
                        bios = b
                        status = if (count > 0) {
                            "System files: ${b.readyCount} of ${b.totalCount} ready."
                        } else {
                            "No .zip files in that folder. Pick the folder holding the BIOS files."
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        status = "Could not copy the BIOS files: ${e.message}"
                    }
                }
            }
        }

        // Back navigation, one handler for the whole UI (audit fix
        // for "closing out of a game closes the whole app" and
        // "swiping back out of Settings closes the app"): a
        // single BackHandler, always composed and always
        // registered, routes by the current screen — instead of a
        // handler inside each `when` branch, which registers and
        // unregisters on every screen change and lets a back
        // press fall through to the Activity's default (finish)
        // when the branch handler is not the dispatcher's active
        // callback. The Library and Boot screens have no handler
        // here, so back there still exits the app, as usual.
        // Playing: back pauses the game; a second back, from the
        // pause menu, quits to the library — closing out of a game
        // returns to the library, never to the Android home screen.
        // Settings: back returns to where Settings was opened
        // from (the library, or the game).
        BackHandler(enabled = screen == Screen.Playing || screen == Screen.Settings) {
            when (screen) {
                Screen.Playing -> when {
                    editControls -> exitEditMode()
                    paused -> quitToLibrary()
                    else -> pauseGame()
                }
                Screen.Settings -> screen = settingsFrom
                else -> { /* Library/Boot: the handler is disabled there. */ }
            }
        }

        when (screen) {
            Screen.Boot -> {
                // VS-style splash: the icon, HOWLINGDOG, PRESS
                // START. Tap anywhere (or press Start) to enter the
                // library. There is no timeout — the splash waits
                // until the user moves on.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .onKeyEvent { event ->
                            // Compose's KeyEvent only exposes the
                            // native event as a member (key/type are
                            // extension properties in this Compose
                            // version); read the native key codes
                            // directly, as GamepadInput does.
                            val native = event.nativeKeyEvent
                            if (native.action == KeyEvent.ACTION_UP &&
                                (native.keyCode == KeyEvent.KEYCODE_BUTTON_START ||
                                    native.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                                    native.keyCode == KeyEvent.KEYCODE_ENTER ||
                                    native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
                            ) {
                                screen = Screen.Library
                                true
                            } else {
                                false
                            }
                        }
                        .clickable { screen = Screen.Library },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        // The icon itself, big: sized to the
                        // smaller of 85% of the width and 52% of the
                        // height, so it is the centrepiece in
                        // portrait and still fits in landscape.
                        BoxWithConstraints {
                            val iconSize = minOf(maxWidth * 0.85f, maxHeight * 0.52f)
                            Image(
                                painter = painterResource(R.drawable.howlingdog_icon),
                                contentDescription = "HowlingDog icon",
                                modifier = Modifier.size(iconSize),
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "HOWLINGDOG",
                            color = Color(EraUi.Palette.GOLD),
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 4.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "PRESS START",
                            color = Color(EraUi.Palette.GOLD),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            modifier = Modifier.alpha(pressStartAlpha),
                        )
                    }
                }
            }

            Screen.Library -> {
                // The whole screen scrolls (a small window or a long
                // library must never push content out of reach).
                // Phase 5 — the fighting-game select grid: black
                // arcade background, chunky type, portrait tiles.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(EraUi.Palette.BACKGROUND))
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Select-screen header: the title and the
                    // decorative countdown (resets on selection,
                    // wraps at 0, never starts or quits anything).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "SELECT YOUR GAME",
                            color = Color(EraUi.Palette.GOLD),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            "TIME %02d".format(timeLeft),
                            color = if (timeLeft <= 3) Color(EraUi.Palette.WARNING) else Color(EraUi.Palette.TEXT),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(EraUi.Palette.SURFACE_RAISED),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(12.dp),
                    ) {
                        Text("System files", color = Color(EraUi.Palette.TEXT), style = MaterialTheme.typography.titleSmall)
                        val b = bios
                        if (b == null) {
                            Text("Checking…", color = Color(EraUi.Palette.TEXT), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(
                                "System files: ${b.readyCount} of ${b.totalCount} ready",
                                color = Color(EraUi.Palette.TEXT),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            BiosRow("Home console", b.home)
                            BiosRow("Arcade", b.arcade)
                            BiosRow("Neo Geo CD", b.system1994)
                            BiosRow("Neo Geo CDZ", b.system1995)
                        }
                        Spacer(Modifier.height(8.dp))
                        ArcadeButton(text = "Pick BIOS folder") { biosPicker.launch(null) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArcadeButton(text = "Add game") { gamePicker.launch(arrayOf("*/*")) }
                        ArcadeButton(text = "Add folder") { folderPicker.launch(null) }
                        ArcadeButton(text = "Settings") {
                            settingsFrom = Screen.Library
                            screen = Screen.Settings
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (games.isEmpty()) {
                        // Empty state: the icon, then the prompt.
                        Image(
                            painter = painterResource(R.drawable.howlingdog_icon),
                            contentDescription = "HowlingDog icon",
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .aspectRatio(1f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No games yet. Add a game to start.",
                            color = Color(EraUi.Palette.TEXT),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        // The picked game, big in the middle.
                        val selected = selectedGame
                        if (selected != null) {
                            SelectedPortrait(
                                entry = selected,
                                onPlay = { startGame(selected) },
                                onMode = { modePickerFor = selected },
                                onRemove = { confirmRemove = selected },
                                cover = covers[selected.mainFile],
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        // The grid: plain Rows in the scrolling
                        // Column, not a lazy list — the screen
                        // itself scrolls, so every tile is composed
                        // and reachable. Fine for a v1 library;
                        // revisit if libraries grow very large.
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val cols = if (maxWidth > 600.dp) 4 else 3
                            Column {
                                for (row in games.chunked(cols)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (game in row) {
                                            GameTile(
                                                entry = game,
                                                selected = game.mainFile == selected?.mainFile,
                                                onSelect = { selectedMainFile = game.mainFile },
                                                modifier = Modifier.weight(1f),
                                                cover = covers[game.mainFile],
                                            )
                                        }
                                        repeat(cols - row.size) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(status, color = Color(EraUi.Palette.TEXT), style = MaterialTheme.typography.bodyMedium)
                }
                val toRemove = confirmRemove
                if (toRemove != null) {
                    AlertDialog(
                        onDismissRequest = { confirmRemove = null },
                        title = { Text("Remove ${toRemove.displayName}?") },
                        text = { Text("This deletes its files from the library.") },
                        confirmButton = {
                            TextButton(onClick = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    library.remove(toRemove)
                                    withContext(Dispatchers.Main) {
                                        confirmRemove = null
                                        refreshLibrary()
                                        status = "Removed ${toRemove.displayName}."
                                    }
                                }
                            }) { Text("Remove") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmRemove = null }) {
                                Text("Cancel")
                            }
                        },
                    )
                }
                val modeGame = modePickerFor
                if (modeGame != null) {
                    AlertDialog(
                        onDismissRequest = { modePickerFor = null },
                        title = { Text("System for ${modeGame.displayName}") },
                        text = {
                            Column {
                                Text(
                                    "Takes effect the next time this game starts.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.height(8.dp))
                                for (mode in modesFor(modeGame)) {
                                    TextButton(onClick = {
                                        val target = modeGame
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            library.setMode(target, mode)
                                            withContext(Dispatchers.Main) {
                                                modePickerFor = null
                                                refreshLibrary()
                                                status = "${target.displayName}: ${modeLabel(mode)}."
                                            }
                                        }
                                    }) {
                                        Text(
                                            modeLabel(mode) +
                                                if (mode == modeGame.effectiveMode) " ✓" else "",
                                        )
                                    }
                                }
                                TextButton(onClick = {
                                    val target = modeGame
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        library.setMode(target, null)
                                        withContext(Dispatchers.Main) {
                                            modePickerFor = null
                                            refreshLibrary()
                                            status = "${target.displayName}: default system."
                                        }
                                    }
                                }) {
                                    Text("Use default")
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { modePickerFor = null }) {
                                Text("Cancel")
                            }
                        },
                    )
                }
            }

            Screen.Playing -> {
                DisposableEffect(Unit) {
                    activity.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val controller = WindowInsetsControllerCompat(
                        activity.window, activity.window.decorView,
                    )
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    onDispose {
                        activity.requestedOrientation =
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    }
                }
                Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The title takes the space LEFT OVER after
                        // the buttons: it is weighted, so the Row
                        // measures the (unweighted) button row first
                        // at its intrinsic width and only then gives
                        // the title the remainder. Without the
                        // weight, a long game name (KOF '98's full
                        // display name) took the whole row and the
                        // edit-mode buttons — Together, Reset, Done
                        // — were measured at zero width and invisible
                        // (Robert: "No button to toggle adjust
                        // controller together or seperate"). The
                        // title truncates with an ellipsis instead
                        // of pushing the buttons off-screen, in any
                        // orientation.
                        Text(
                            playingGame?.displayName ?: "Game",
                            color = Color(EraUi.Palette.TEXT),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (editControls) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { moveTogether = !moveTogether }) {
                                    Text(if (moveTogether) "Together: On" else "Together: Off")
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    controlSettings.resetLayout()
                                    stickPos = ControlSettings.DEFAULT_STICK_POS
                                    buttonsPos = ControlSettings.DEFAULT_BUTTONS_POS
                                    stickScale = ControlSettings.DEFAULT_GROUP_SCALE
                                    buttonsScale = ControlSettings.DEFAULT_GROUP_SCALE
                                    buttonOverrides = emptyMap()
                                }) { Text("Reset layout") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { exitEditMode() }) { Text("Done") }
                            }
                        } else {
                            // Quick save/load sit beside Pause, in
                            // the title row — never over the game area
                            // and never fighting the stick or the
                            // buttons. They write and read the same
                            // per-game slot the pause menu uses.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { quickSave() }) { Text("SAVE") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { quickLoad() }) { Text("LOAD") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { pauseGame() }) { Text("Pause") }
                            }
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            factory = { ctx ->
                                GameGlView(ctx).also { gameGlView = it }
                            },
                            update = {
                                // Apply this game's system preset (and
                                // toggles) whenever the game screen
                                // recomposes — a new game, or a return
                                // to the game screen, re-applies it.
                                val entry = playingGame
                                if (entry != null) {
                                    val mode = entry.effectiveMode
                                    it.setPreset(
                                        ShaderPresets.forMode(mode),
                                        shaderSettings.shaderOn(mode),
                                        shaderSettings.curvatureOn(mode),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Touch controls float over the game view
                        // (standard mobile-emulator layout): the game
                        // fills the whole Box and the controls overlay
                        // its lower part instead of taking their own
                        // row. In landscape the old row ate ~240dp
                        // and left the game ~125dp tall.
                        //
                        // The controls are two units, each placed by
                        // its stored centre (a fraction of this Box):
                        // the PS1-style stick (bottom-left) and
                        // the button cluster (bottom-right). The
                        // whole overlay takes the stored opacity.
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(overlayOpacity),
                        ) {
                            val density = LocalDensity.current
                            val boxW = with(density) { maxWidth.toPx() }
                            val boxH = with(density) { maxHeight.toPx() }
                            // Effective size = the Settings preset
                            // times the group's own resize scale
                            // (set by its corner handle in adjust
                            // mode; 1.0 = the preset size).
                            val stickScaleEff = controlSize.scale * stickScale
                            val buttonsScaleEff = controlSize.scale * buttonsScale
                            // Stick geometry (dp, scaled): base 140,
                            // grab margin 16, touch area 172.
                            val stickTouchDp = 172f * stickScaleEff
                            val stickTouchPx = with(density) { stickTouchDp.dp.toPx() }
                            val stickOffset = IntOffset(
                                (stickPos.x * boxW - stickTouchPx / 2).roundToInt(),
                                (stickPos.y * boxH - stickTouchPx / 2).roundToInt(),
                            )
                            // Per-system overlay theme: the button
                            // arrangement and colours for the game
                            // being played (AES arc, MVS BCD/A, CD
                            // diamond) and the stick's colours.
                            // Appearance only — the input is identical
                            // on all four systems.
                            val overlayTheme = OverlayTheme.themeFor(
                                playingGame?.effectiveMode ?: "aes",
                            )
                            // A button's effective offset: its stored
                            // override, or the theme layout's default
                            // for this system. The cluster's bounding
                            // box follows the effective offsets, so a
                            // button moved on its own grows the
                            // cluster to contain it.
                            val buttonOffsets = OverlayTheme.buttonOffsets(overlayTheme.layout)
                                .mapValues { (id, off) -> buttonOverrides[id] ?: off }
                            // Button cluster geometry (dp, scaled):
                            // the effective bounding box at scale 1,
                            // times the effective scale.
                            val (clusterWdp1, clusterHdp1) =
                                OverlayTheme.clusterSizeFor(buttonOffsets)
                            val buttonsWdp = clusterWdp1 * buttonsScaleEff
                            val buttonsHdp = clusterHdp1 * buttonsScaleEff
                            val buttonsWPx = with(density) { buttonsWdp.dp.toPx() }
                            val buttonsHPx = with(density) { buttonsHdp.dp.toPx() }
                            val buttonsOffset = IntOffset(
                                (buttonsPos.x * boxW - buttonsWPx / 2).roundToInt(),
                                (buttonsPos.y * boxH - buttonsHPx / 2).roundToInt(),
                            )
                            // Adjust-layout drag: in edit mode each
                            // unit drags to a new centre fraction,
                            // and its bottom-end corner (the visible
                            // resize grip, 28dp) drags to resize it.
                            // The button cluster's detector also
                            // decides, from where the press landed,
                            // whether the drag moves the whole cluster
                            // or one button on its own: with Together
                            // off, a press inside a button's circle
                            // moves that button alone (its offset is
                            // stored as an override); with Together
                            // on, or a press on empty space, the
                            // whole cluster moves. One gesture
                            // detector per unit decides move vs resize
                            // vs individual-move from where the press
                            // landed, so the gestures never fight.
                            // rememberUpdatedState keeps the
                            // drag lambda reading the current
                            // position and size without restarting
                            // the gesture on every move (the size
                            // changes while resizing, so it must not
                            // be a pointerInput key). Positions and
                            // scales persist on drag end (and on
                            // Done). The units are clamped so they
                            // stay fully inside the game area — and
                            // they live inside this Box, below the
                            // title row, so a dragged or resized
                            // control can never cover the Pause
                            // button.
                            //
                            // Resize: a horizontal drag of the grip
                            // resizes proportionally — dragging the
                            // grip right by the unit's full width
                            // doubles it — clamped to 0.7x..1.5x of
                            // the preset size. (Mariner's pattern:
                            // horizontal drag, 28dp touch area, 20dp
                            // visible grip with a "↘".)
                            val stickPosState = rememberUpdatedState(stickPos)
                            val buttonsPosState = rememberUpdatedState(buttonsPos)
                            val stickScaleState = rememberUpdatedState(stickScale)
                            val buttonsScaleState = rememberUpdatedState(buttonsScale)
                            val stickTouchPxState = rememberUpdatedState(stickTouchPx)
                            val buttonsWPxState = rememberUpdatedState(buttonsWPx)
                            val buttonsHPxState = rememberUpdatedState(buttonsHPx)
                            val clusterSizeState =
                                rememberUpdatedState(clusterWdp1 to clusterHdp1)
                            val presetScaleState = rememberUpdatedState(controlSize.scale)
                            val handlePx = with(density) { 28.dp.toPx() }
                            val handlePxState = rememberUpdatedState(handlePx)
                            // The button drag reads these live (the
                            // offsets change while dragging, so they
                            // must not be pointerInput keys).
                            val buttonOffsetsState = rememberUpdatedState(buttonOffsets)
                            val moveTogetherState = rememberUpdatedState(moveTogether)
                            val buttonOverridesState = rememberUpdatedState(buttonOverrides)
                            val dpPxState = rememberUpdatedState(with(density) { 1.dp.toPx() })
                            val stickDrag = if (editControls) {
                                Modifier.pointerInput(boxW, boxH) {
                                    var resizing = false
                                    detectDragGestures(
                                        onDragStart = { start ->
                                            val sizePx = stickTouchPxState.value
                                            val grip = handlePxState.value
                                            resizing = start.x > sizePx - grip &&
                                                start.y > sizePx - grip
                                        },
                                        onDragEnd = {
                                            if (resizing) {
                                                controlSettings.setStickScale(stickScaleState.value)
                                            } else {
                                                controlSettings.setStickPosition(stickPosState.value)
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            if (resizing) {
                                                val sizePx = stickTouchPxState.value
                                                if (sizePx > 0) {
                                                    val newScale = (stickScaleState.value *
                                                        (1f + dragAmount.x / sizePx))
                                                        .coerceIn(
                                                            ControlSettings.MIN_GROUP_SCALE,
                                                            ControlSettings.MAX_GROUP_SCALE,
                                                        )
                                                    stickScale = newScale
                                                    // The grown unit must still fit:
                                                    // re-clamp its centre to the new size.
                                                    val newSizePx = with(density) {
                                                        (172f * presetScaleState.value * newScale)
                                                            .dp.toPx()
                                                    }
                                                    val halfW = newSizePx / 2 / boxW
                                                    val halfH = newSizePx / 2 / boxH
                                                    stickPos = ControlPosition(
                                                        stickPosState.value.x
                                                            .coerceIn(halfW, 1f - halfW),
                                                        stickPosState.value.y
                                                            .coerceIn(halfH, 1f - halfH),
                                                    )
                                                }
                                            } else {
                                                val cur = stickPosState.value
                                                val halfW = stickTouchPxState.value / 2 / boxW
                                                val halfH = stickTouchPxState.value / 2 / boxH
                                                stickPos = ControlPosition(
                                                    (cur.x + dragAmount.x / boxW)
                                                        .coerceIn(halfW, 1f - halfW),
                                                    (cur.y + dragAmount.y / boxH)
                                                        .coerceIn(halfH, 1f - halfH),
                                                )
                                            }
                                        },
                                    )
                                }
                            } else {
                                Modifier
                            }
                            val buttonsDrag = if (editControls) {
                                Modifier.pointerInput(boxW, boxH) {
                                    var resizing = false
                                    // The button being moved on its
                                    // own (null = the whole cluster).
                                    var dragButton: String? = null
                                    detectDragGestures(
                                        onDragStart = { start ->
                                            val wPx = buttonsWPxState.value
                                            val hPx = buttonsHPxState.value
                                            val grip = handlePxState.value
                                            resizing = start.x > wPx - grip &&
                                                start.y > hPx - grip
                                            dragButton = null
                                            if (!resizing && !moveTogetherState.value) {
                                                // Individual move: which
                                                // button (if any) did the
                                                // press land on? A press
                                                // counts when it is inside
                                                // the button's circle.
                                                val offsets = buttonOffsetsState.value
                                                val scale = presetScaleState.value *
                                                    buttonsScaleState.value
                                                val dpPx = dpPxState.value
                                                for ((id, off) in offsets) {
                                                    val cx = wPx / 2f +
                                                        off.first * scale * dpPx
                                                    val cy = hPx / 2f +
                                                        off.second * scale * dpPx
                                                    val r = OverlayTheme.buttonSize(id) *
                                                        scale * dpPx / 2f
                                                    val dx = start.x - cx
                                                    val dy = start.y - cy
                                                    if (dx * dx + dy * dy <= r * r) {
                                                        dragButton = id
                                                        break
                                                    }
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            val id = dragButton
                                            when {
                                                resizing -> controlSettings.setButtonsScale(buttonsScaleState.value)
                                                id != null -> buttonOverridesState.value[id]?.let {
                                                    controlSettings.setButtonOffset(id, it)
                                                }
                                                else -> controlSettings.setButtonsPosition(buttonsPosState.value)
                                            }
                                            dragButton = null
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            if (resizing) {
                                                val wPx = buttonsWPxState.value
                                                if (wPx > 0) {
                                                    val newScale = (buttonsScaleState.value *
                                                        (1f + dragAmount.x / wPx))
                                                        .coerceIn(
                                                            ControlSettings.MIN_GROUP_SCALE,
                                                            ControlSettings.MAX_GROUP_SCALE,
                                                        )
                                                    buttonsScale = newScale
                                                    // The cluster's bounding box at scale
                                                    // 1 comes from the effective offsets,
                                                    // so a resized cluster still fits.
                                                    val eff = presetScaleState.value * newScale
                                                    val (cw1, ch1) = clusterSizeState.value
                                                    val newWPx = with(density) {
                                                        (cw1 * eff).dp.toPx()
                                                    }
                                                    val newHPx = with(density) {
                                                        (ch1 * eff).dp.toPx()
                                                    }
                                                    val halfW = newWPx / 2 / boxW
                                                    val halfH = newHPx / 2 / boxH
                                                    buttonsPos = ControlPosition(
                                                        buttonsPosState.value.x
                                                            .coerceIn(halfW, 1f - halfW),
                                                        buttonsPosState.value.y
                                                            .coerceIn(halfH, 1f - halfH),
                                                    )
                                                }
                                            } else {
                                                val id = dragButton
                                                if (id != null) {
                                                    // Move that button alone:
                                                    // the drag moves its offset
                                                    // (dp at scale 1), clamped
                                                    // so the button stays fully
                                                    // inside the game area.
                                                    val cur = buttonOffsetsState.value[id]
                                                    if (cur != null) {
                                                        val scale = presetScaleState.value *
                                                            buttonsScaleState.value
                                                        val dpPx = dpPxState.value
                                                        val halfBtn = OverlayTheme.buttonSize(id) *
                                                            scale * dpPx / 2f
                                                        // The button's centre, in
                                                        // px of the game area.
                                                        val ccx = buttonsPosState.value.x * boxW
                                                        val ccy = buttonsPosState.value.y * boxH
                                                        val curBx = ccx + cur.first * scale * dpPx
                                                        val curBy = ccy + cur.second * scale * dpPx
                                                        val newBx = (curBx + dragAmount.x)
                                                            .coerceIn(halfBtn, boxW - halfBtn)
                                                        val newBy = (curBy + dragAmount.y)
                                                            .coerceIn(halfBtn, boxH - halfBtn)
                                                        // Back to an offset (dp
                                                        // at scale 1) from the
                                                        // cluster centre.
                                                        val newOff = (
                                                            (newBx - ccx) / (scale * dpPx)
                                                        ) to (
                                                            (newBy - ccy) / (scale * dpPx)
                                                        )
                                                        buttonOverrides = buttonOverridesState.value +
                                                            (id to newOff)
                                                    }
                                                } else {
                                                    val cur = buttonsPosState.value
                                                    val halfW = buttonsWPxState.value / 2 / boxW
                                                    val halfH = buttonsHPxState.value / 2 / boxH
                                                    buttonsPos = ControlPosition(
                                                        (cur.x + dragAmount.x / boxW)
                                                            .coerceIn(halfW, 1f - halfW),
                                                        (cur.y + dragAmount.y / boxH)
                                                            .coerceIn(halfH, 1f - halfH),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            } else {
                                Modifier
                            }
                            // The left control: the arcade stick, or
                            // the 8-way D-pad (Settings toggle). Both
                            // sit in the same slot, at the same
                            // position and size, and both drive the
                            // same direction mask.
                            if (leftControl == LeftControl.DPAD) {
                                Dpad(
                                    size = (140f * stickScaleEff).dp,
                                    grabMargin = (16f * stickScaleEff).dp,
                                    baseTop = overlayTheme.stickBaseTop,
                                    baseBottom = overlayTheme.stickBaseBottom,
                                    armTop = overlayTheme.stickNubTop,
                                    armBottom = overlayTheme.stickNubBottom,
                                    accent = overlayTheme.accent,
                                    onDirection = { mask ->
                                        held = held.copy(
                                            up = mask and (1 shl InputMask.JOY_UP) != 0,
                                            down = mask and (1 shl InputMask.JOY_DOWN) != 0,
                                            left = mask and (1 shl InputMask.JOY_LEFT) != 0,
                                            right = mask and (1 shl InputMask.JOY_RIGHT) != 0,
                                        )
                                        pushMask()
                                    },
                                    onDirectionChange = { mask ->
                                        if (mask != 0) playHaptic()
                                    },
                                    enabled = !editControls,
                                    modifier = Modifier
                                        .offset { stickOffset }
                                        .size(stickTouchDp.dp)
                                        .then(stickDrag),
                                )
                            } else {
                                PsStick(
                                    size = (140f * stickScaleEff).dp,
                                    grabMargin = (16f * stickScaleEff).dp,
                                    baseTop = overlayTheme.stickBaseTop,
                                    baseBottom = overlayTheme.stickBaseBottom,
                                    nubTop = overlayTheme.stickNubTop,
                                    nubBottom = overlayTheme.stickNubBottom,
                                    accent = overlayTheme.accent,
                                    onDirection = { mask ->
                                        held = held.copy(
                                            up = mask and (1 shl InputMask.JOY_UP) != 0,
                                            down = mask and (1 shl InputMask.JOY_DOWN) != 0,
                                            left = mask and (1 shl InputMask.JOY_LEFT) != 0,
                                            right = mask and (1 shl InputMask.JOY_RIGHT) != 0,
                                        )
                                        pushMask()
                                    },
                                    onDirectionChange = { mask ->
                                        if (mask != 0) playHaptic()
                                    },
                                    enabled = !editControls,
                                    modifier = Modifier
                                        .offset { stickOffset }
                                        .size(stickTouchDp.dp)
                                        .then(stickDrag),
                                )
                            }
                            // Neo Geo buttons, placed by the theme
                            // layout's offsets (AES arc, MVS BCD/A,
                            // CD diamond, or the 2x2 grid) — each
                            // button centred on its offset from the
                            // cluster centre, in the theme's rainbow
                            // colours. In edit mode the cluster shows
                            // a thin outline so it reads as draggable,
                            // and its buttons stop taking input.
                            Box(
                                modifier = Modifier
                                    .offset { buttonsOffset }
                                    .size(buttonsWdp.dp, buttonsHdp.dp)
                                    .then(buttonsDrag)
                                    .then(
                                        if (editControls) {
                                            Modifier.border(
                                                1.dp,
                                                Color(EraUi.Palette.GOLD).copy(alpha = 0.5f),
                                                RoundedCornerShape(8.dp),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                // Top-left of a button, in px, from
                                // its theme offset (dp at scale 1)
                                // and the cluster's effective scale.
                                fun place(id: String): IntOffset {
                                    val off = buttonOffsets.getValue(id)
                                    val sizePx = with(density) {
                                        (OverlayTheme.buttonSize(id) * buttonsScaleEff).dp.toPx()
                                    }
                                    return IntOffset(
                                        (buttonsWPx / 2f +
                                            with(density) { (off.first * buttonsScaleEff).dp.toPx() } -
                                            sizePx / 2f).roundToInt(),
                                        (buttonsHPx / 2f +
                                            with(density) { (off.second * buttonsScaleEff).dp.toPx() } -
                                            sizePx / 2f).roundToInt(),
                                    )
                                }
                                val styleA = overlayTheme.buttonStyles.getValue("A")
                                val styleB = overlayTheme.buttonStyles.getValue("B")
                                val styleC = overlayTheme.buttonStyles.getValue("C")
                                val styleD = overlayTheme.buttonStyles.getValue("D")
                                HoldButton("A", { pressed ->
                                    held = held.copy(a = pressed); pushMask()
                                }, modifier = Modifier.offset { place("A") }, scale = buttonsScaleEff, onHaptic = ::playHaptic, enabled = !editControls, color = styleA.background, labelColor = styleA.label)
                                HoldButton("B", { pressed ->
                                    held = held.copy(b = pressed); pushMask()
                                }, modifier = Modifier.offset { place("B") }, scale = buttonsScaleEff, onHaptic = ::playHaptic, enabled = !editControls, color = styleB.background, labelColor = styleB.label)
                                HoldButton("C", { pressed ->
                                    held = held.copy(c = pressed); pushMask()
                                }, modifier = Modifier.offset { place("C") }, scale = buttonsScaleEff, onHaptic = ::playHaptic, enabled = !editControls, color = styleC.background, labelColor = styleC.label)
                                HoldButton("D", { pressed ->
                                    held = held.copy(d = pressed); pushMask()
                                }, modifier = Modifier.offset { place("D") }, scale = buttonsScaleEff, onHaptic = ::playHaptic, enabled = !editControls, color = styleD.background, labelColor = styleD.label)
                                HoldButton("Coin", { pressed ->
                                    held = held.copy(coin = pressed); pushMask()
                                }, modifier = Modifier.offset { place("Coin") }, small = true, scale = buttonsScaleEff, onHaptic = ::playHaptic, enabled = !editControls)
                                HoldButton("Start", { pressed ->
                                    held = held.copy(start = pressed); pushMask()
                                }, modifier = Modifier.offset { place("Start") }, small = true, scale = buttonsScaleEff, onHaptic = ::playHaptic, enabled = !editControls)
                            }
                            // Resize grips (edit mode only): a visible
                            // "↘" grip at each unit's bottom-end
                            // corner. Purely visual — the unit's own
                            // drag detector decides move vs resize by
                            // where the press landed, so the grip
                            // takes no input itself and the two
                            // gestures never fight. 28dp touch area
                            // (the drag zone), 20dp visible grip.
                            if (editControls) {
                                ResizeGrip(
                                    offset = IntOffset(
                                        (stickPos.x * boxW + stickTouchPx / 2 - handlePx / 2).roundToInt(),
                                        (stickPos.y * boxH + stickTouchPx / 2 - handlePx / 2).roundToInt(),
                                    ),
                                )
                                ResizeGrip(
                                    offset = IntOffset(
                                        (buttonsPos.x * boxW + buttonsWPx / 2 - handlePx / 2).roundToInt(),
                                        (buttonsPos.y * boxH + buttonsHPx / 2 - handlePx / 2).roundToInt(),
                                    ),
                                )
                            }
                        }
                        // Edit-mode hint, over the game area.
                        if (editControls) {
                            Text(
                                "Drag a control to move it, drag its ↘ corner to resize it — buttons move together when Together is on, one at a time when it is off",
                                color = Color(EraUi.Palette.TEXT),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(4.dp),
                            )
                        }
                        // Quick save/load status: a brief note over
                        // the game area, cleared after a couple of
                        // seconds (see the LaunchedEffect above).
                        // Hidden while paused — the pause menu has
                        // its own status line.
                        if (quickStatus.isNotEmpty() && !paused) {
                            Text(
                                quickStatus,
                                color = Color(EraUi.Palette.GOLD),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 8.dp)
                                    .background(
                                        Color(EraUi.Palette.SURFACE),
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        // Pause overlay: covers the game and the
                        // controls (drawn last, so it is on top) and
                        // swallows touches, so no button can be
                        // pressed while paused. Hidden while the
                        // controls are being adjusted (the game
                        // stays paused underneath).
                        if (paused && !editControls) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.7f))
                                    .clickable(enabled = true, onClick = {}),
                                contentAlignment = Alignment.Center,
                            ) {
                                PauseMenu(
                                    timeLeft = pauseTimeLeft,
                                    status = pauseStatus,
                                    onResume = { resumeGame() },
                                    onSaveState = { saveState() },
                                    onLoadState = { loadState() },
                                    onSettings = {
                                        settingsFrom = Screen.Playing
                                        screen = Screen.Settings
                                    },
                                    onAdjustControls = { editControls = true },
                                    onQuit = { quitToLibrary() },
                                )
                            }
                        }
                    }
                }
            }

            Screen.Settings -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Options under \"Apply immediately\" take effect on the next frame, even while a game is running. Options under \"Next game\" take effect the next time you start a game.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Apply immediately", style = MaterialTheme.typography.titleSmall)
                    for (opt in CoreOptions.ALL.filter { it.live }) {
                        OptionRow(opt, settings)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Next game", style = MaterialTheme.typography.titleSmall)
                    for (opt in CoreOptions.ALL.filter { !it.live }) {
                        OptionRow(opt, settings)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("CRT display", style = MaterialTheme.typography.titleSmall)
                    ControlDropdownRow(
                        "CRT shader",
                        listOf("on" to "On", "off" to "Off"),
                        if (crtShaderOn) "on" else "off",
                    ) { value ->
                        val on = value == "on"
                        shaderSettings.setShaderMaster(on)
                        crtShaderOn = on
                    }
                    Text(
                        "Each system uses its own chain, tuned to the display it was played on — AES Hearth, Arcade Neon, Neo Geo CD Comet, Neo Geo CDZ Zenith. Takes effect the next time a game starts.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ControlDropdownRow(
                        "Screen curvature",
                        listOf("on" to "On", "off" to "Off"),
                        if (curvatureOn) "on" else "off",
                    ) { value ->
                        val on = value == "on"
                        shaderSettings.setCurvatureMaster(on)
                        curvatureOn = on
                    }
                    Text(
                        "Each system's curve matches its own display — the home TV curve for AES and the CD consoles, the flatter arcade-monitor curve for MVS. Takes effect the next time a game starts.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("On-screen controls", style = MaterialTheme.typography.titleSmall)
                    ControlDropdownRow(
                        "Haptics",
                        listOf(
                            "off" to "Off",
                            "light" to "Light",
                            "medium" to "Medium",
                            "heavy" to "Heavy",
                        ),
                        hapticsLevel.name.lowercase(),
                    ) { value ->
                        val level = HapticLevel.valueOf(value.uppercase())
                        controlSettings.setHaptics(level)
                        hapticsLevel = level
                    }
                    ControlDropdownRow(
                        "Controls size",
                        listOf(
                            "small" to "Small",
                            "medium" to "Medium",
                            "large" to "Large",
                        ),
                        controlSize.name.lowercase(),
                    ) { value ->
                        val size = ControlSize.valueOf(value.uppercase())
                        controlSettings.setSize(size)
                        controlSize = size
                    }
                    ControlDropdownRow(
                        "Left control",
                        listOf(
                            "stick" to "Joystick",
                            "dpad" to "D-pad",
                        ),
                        leftControl.name.lowercase(),
                    ) { value ->
                        val control = LeftControl.valueOf(value.uppercase())
                        controlSettings.setLeftControl(control)
                        leftControl = control
                    }
                    Text(
                        "The D-pad is eight-way, like the stick — its corners are diagonals. It never switches to four-way.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Opacity", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${(overlayOpacity * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Slider(
                        value = overlayOpacity,
                        onValueChange = { overlayOpacity = it },
                        valueRange = 0.2f..1.0f,
                        steps = 7,
                        onValueChangeFinished = {
                            controlSettings.setOpacity((overlayOpacity * 100).roundToInt())
                        },
                    )
                    Text(
                        "To move and resize the controls, start a game, pause, and tap Adjust controls — drag a control to move it, drag its ↘ corner to resize it. Buttons move together when Together is on, or one at a time when it is off.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Startup", style = MaterialTheme.typography.titleSmall)
                    ControlDropdownRow(
                        "Show boot screen",
                        listOf("on" to "On", "off" to "Off"),
                        if (showBoot) "on" else "off",
                    ) { value ->
                        val show = value == "on"
                        appSettings.setShowBoot(show)
                        showBoot = show
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { screen = settingsFrom }) { Text("Back") }
                }
            }
        }
    }

    @Composable
    private fun OptionRow(opt: CoreOption, settings: CoreSettings) {
        var value by remember(opt.key) { mutableStateOf(settings.get(opt.key)) }
        var expanded by remember { mutableStateOf(false) }
        val currentLabel = opt.values.firstOrNull { it.first == value }?.second ?: value
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(opt.label, style = MaterialTheme.typography.bodyMedium)
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(currentLabel)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    for ((v, label) in opt.values) {
                        DropdownMenuItem(
                            text = { Text(label + if (v == value) " ✓" else "") },
                            onClick = {
                                settings.set(opt.key, v)
                                value = v
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }

    /** A dropdown settings row for a plain (value, label) option list (the on-screen controls). */
    @Composable
    private fun ControlDropdownRow(
        label: String,
        options: List<Pair<String, String>>,
        current: String,
        onSelect: (String) -> Unit,
    ) {
        var expanded by remember { mutableStateOf(false) }
        val currentLabel = options.firstOrNull { it.first == current }?.second ?: current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(currentLabel)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    for ((v, l) in options) {
                        DropdownMenuItem(
                            text = { Text(l + if (v == current) " ✓" else "") },
                            onClick = {
                                onSelect(v)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun BiosRow(label: String, ready: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Color(EraUi.Palette.TEXT), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (ready) "✓" else "✗",
                color = if (ready) Color(EraUi.Palette.GOLD) else Color(EraUi.Palette.WARNING),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    @Composable
    private fun HoldButton(
        label: String,
        onHoldChange: (Boolean) -> Unit,
        modifier: Modifier = Modifier,
        small: Boolean = false,
        scale: Float = 1f,
        onHaptic: (() -> Unit)? = null,
        enabled: Boolean = true,
        /** ARGB background when not held (null = the raised maroon). */
        color: Int? = null,
        /** ARGB label when not held (null = the off-white text). */
        labelColor: Int? = null,
    ) {
        var held by remember { mutableStateOf(false) }
        val size = (if (small) 48.dp else 56.dp) * scale
        // The overlay's opacity comes from the container's alpha, so
        // the button is drawn at full alpha here (the old fixed 0.6
        // is gone — the default opacity setting is 0.6, so the look
        // is unchanged until the slider moves).
        val inputModifier = if (enabled) {
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    // Compose has no PointerEventType.Cancel: a cancelled gesture
                    // cancels this coroutine instead, so release the hold in
                    // finally — otherwise a stuck button keeps firing input.
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Press -> {
                                    if (!held) {
                                        held = true
                                        onHaptic?.invoke()
                                        onHoldChange(true)
                                    }
                                }
                                // A button releases when the finger
                                // slides off it (Exit) as well as when
                                // it lifts (Release) — that is correct
                                // button behaviour, and it is NOT the
                                // stick/D-pad bug: a held direction
                                // must keep tracking past the edge,
                                // but a button must let go.
                                PointerEventType.Release,
                                PointerEventType.Exit -> {
                                    if (held) {
                                        held = false
                                        onHoldChange(false)
                                    }
                                }
                            }
                        }
                    } finally {
                        if (held) {
                            held = false
                            onHoldChange(false)
                        }
                    }
                }
            }
        } else {
            Modifier
        }
        Box(
            modifier = modifier
                .size(size)
                .background(
                    if (held) Color(EraUi.Palette.GOLD)
                    else color?.let { Color(it) } ?: Color(EraUi.Palette.SURFACE_RAISED),
                    CircleShape,
                )
                .then(inputModifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (held) Color(EraUi.Palette.ON_GOLD)
                else labelColor?.let { Color(it) } ?: Color(EraUi.Palette.TEXT),
            )
        }
    }

    /**
     * The visible resize grip for adjust mode: a 28dp touch area
     * (matching the drag zone) with a 20dp white square and a "↘"
     * inside, placed at a control group's bottom-end corner.
     * Purely visual — it takes no input; the group's own drag
     * detector decides move vs resize by where the press landed.
     * (Mariner's pattern: 28dp touch area, 20dp visible grip —
     * a bigger touch area would cover most of a small control's
     * corner, so grabbing to drag would resize it instead.)
     */
    @Composable
    private fun ResizeGrip(offset: IntOffset) {
        Box(
            modifier = Modifier
                .offset { offset }
                .size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(Color(EraUi.Palette.GOLD), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "↘",
                    color = Color(EraUi.Palette.ON_GOLD),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    /**
     * The PlayStation-style stick: it LOOKS like the PS1
     * overlay joystick in Notscape Mariner (GameActivity
     * StickSurface) — a filled base disc with a smaller
     * filled nub riding inside it — but it WORKS exactly
     * like the HowlingDog stick always has: the same
     * square-gate 0.35 diagonal detection, the same 0.22
     * dead zone, the same drag handling. Only the drawing
     * changed; the flat outline rings are gone.
     *
     * Mariner proportions, kept exact: the nub is 0.55x
     * the base diameter and rides at 0.45x the finger
     * offset (clamped to the base radius), so at full
     * deflection its edge sits flush with the base edge.
     * Mariner's frost is near-white; here the discs are
     * maroon with a gold hairline by default, so the stick
     * sits in HowlingDog's palette instead of clashing with
     * it. The colours are parameters (baseTop/baseBottom/
     * nubTop/nubBottom/accent) so the per-system theme can
     * restyle them — the AES/MVS ball-top is red-tinted,
     * the CD systems are the pad's grey — without changing
     * the shape.
     *
     * Behaviour: fixed in place (it never follows the thumb
     * around the screen). Touch down inside the base (plus
     * the grab margin) and drag; the nub follows and the
     * direction comes from StickInput's square-gate rule.
     * Release snaps the nub back to centre and clears the
     * direction. A cancelled gesture clears it too, in the
     * finally block — a stuck direction is never left
     * behind. Only one pointer is tracked, so a second
     * thumb on the buttons cannot steal the stick.
     * The input travel is tuned against the old knob
     * radius and must not move: the dead zone and the
     * diagonal band are measured in units of that travel.
     */
    @Composable
    private fun PsStick(
        size: Dp, // base diameter
        grabMargin: Dp,
        baseTop: Int = EraUi.Palette.MAROON,
        baseBottom: Int = EraUi.Palette.SURFACE_RAISED,
        nubTop: Int = EraUi.Palette.MAROON_LIGHT,
        nubBottom: Int = EraUi.Palette.MAROON,
        accent: Int = EraUi.Palette.GOLD,
        onDirection: (Int) -> Unit,
        onDirectionChange: (Int) -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
    ) {
        val density = LocalDensity.current
        val touchPx = with(density) { (size + grabMargin * 2).toPx() }
        val grabPx = with(density) { grabMargin.toPx() }
        val baseR = touchPx / 2f - grabPx
        val travelKnobR = baseR * 0.38f
        val maxTravel = baseR - travelKnobR
        val knobR = baseR * 0.55f
        var knob by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = modifier
                .pointerInput(enabled, maxTravel) {
                    if (!enabled) return@pointerInput
                    awaitPointerEventScope {
                        var activeId: PointerId? = null
                        var lastMask = 0
                        fun setDirection(mask: Int) {
                            if (mask != lastMask) {
                                lastMask = mask
                                onDirection(mask)
                                if (mask != 0) onDirectionChange(mask)
                            }
                        }
                        fun updateKnob(position: Offset) {
                            // The touch area is a touchPx square, so
                            // its centre is touchPx / 2. Do NOT use
                            // the pointer scope's `size` here: an
                            // unqualified `size` resolves to the
                            // stick's Dp parameter, because function
                            // parameters shadow implicit receiver
                            // members. That broke CI twice.
                            val centre = Offset(touchPx / 2f, touchPx / 2f)
                            val raw = position - centre
                            // Direction (unchanged): the drag
                            // normalised by the input travel, so the
                            // dead zone and the 0.35 square-gate band
                            // behave exactly as before.
                            val (cx, cy) = StickInput.clamp(
                                raw.x / maxTravel,
                                raw.y / maxTravel,
                            )
                            // Nub visual (Mariner StickSurface): the
                            // nub rides at 0.45x the finger offset,
                            // clamped to the base radius, so at full
                            // deflection its edge sits flush with the
                            // base edge (0.45 + 0.55 = 1.0).
                            val rawMag = sqrt(raw.x * raw.x + raw.y * raw.y)
                            val clamped = if (rawMag > baseR) {
                                raw * (baseR / rawMag)
                            } else {
                                raw
                            }
                            knob = clamped * 0.45f
                            setDirection(StickInput.direction(cx, cy))
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Press -> {
                                        if (activeId == null) {
                                            val change = event.changes.firstOrNull { it.pressed }
                                            if (change != null) {
                                                activeId = change.id
                                                updateKnob(change.position)
                                            }
                                        }
                                    }
                                    PointerEventType.Move -> {
                                        val id = activeId ?: continue
                                        val change = event.changes.firstOrNull { it.id == id }
                                        if (change != null && change.pressed) {
                                            updateKnob(change.position)
                                        }
                                    }
                                    // Only a real Release (finger lift)
                                    // ends the drag. An Exit — the
                                    // pointer sliding out of the touch
                                    // area while still down — is
                                    // deliberately NOT handled: the
                                    // Move events keep coming with
                                    // out-of-bounds positions, and
                                    // updateKnob clamps the direction
                                    // to the edge (and the nub to the
                                    // base radius). Ending the drag on
                                    // Exit is what dropped the touch on
                                    // straight left-to-right drags (a
                                    // circular drag stays inside the
                                    // bounds, so it never fired Exit).
                                    // A Cancel ends it too, via the
                                    // finally block below.
                                    PointerEventType.Release -> {
                                        val id = activeId
                                        if (id != null && event.changes.any { it.id == id }) {
                                            activeId = null
                                            knob = Offset.Zero
                                            setDirection(0)
                                        }
                                    }
                                }
                            }
                        } finally {
                            activeId = null
                            if (knob != Offset.Zero) knob = Offset.Zero
                            setDirection(0)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centre = Offset(this.size.width / 2f, this.size.height / 2f)
                val gold = Color(accent)
                // Soft drop shadow under the base (Mariner's
                // frostBar carries a 6dp elevation; the game area
                // is dark, so this only reads on bright game art).
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = baseR,
                    center = centre + Offset(0f, 2.dp.toPx()),
                )
                // Base: a filled disc, lighter at the top, like
                // Mariner's frost base — maroon, not frost white,
                // so it sits in HowlingDog's palette (or grey on
                // the CD systems, the pad's shell colour).
                drawCircle(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color(baseTop),
                            Color(baseBottom),
                        ),
                        startY = centre.y - baseR,
                        endY = centre.y + baseR,
                    ),
                    radius = baseR,
                    center = centre,
                )
                drawCircle(
                    color = gold.copy(alpha = 0.6f),
                    radius = baseR,
                    center = centre,
                    style = Stroke(width = 1.dp.toPx()),
                )
                // Nub: a smaller filled disc riding inside the
                // base, a lighter shade so it reads against the
                // base, with the same gold hairline. On the
                // stick systems the nub is red-tinted (the
                // ball-top); on the CD systems it is grey.
                val nubCentre = centre + knob
                drawCircle(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color(nubTop),
                            Color(nubBottom),
                        ),
                        startY = nubCentre.y - knobR,
                        endY = nubCentre.y + knobR,
                    ),
                    radius = knobR,
                    center = nubCentre,
                )
                drawCircle(
                    color = gold.copy(alpha = 0.8f),
                    radius = knobR,
                    center = nubCentre,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }

    /**
     * The 8-way D-pad: the alternative left control (Settings
     * toggle). This is NOT the old four-way D-pad (Robert called
     * that one too simple): it is an eight-way pad with working
     * diagonals, using exactly the stick's logic — the same 0.22
     * dead zone and the same 0.35 square-gate diagonal band
     * ([DpadInput], which delegates to [StickInput]). A press in a
     * corner reports both directions; a press on an edge reports
     * the one direction.
     *
     * The look: a rounded-square base with a plus (cross) on top,
     * in the per-system theme's colours (maroon on the stick
     * systems, grey on the CD systems, gold hairline). The pressed
     * direction(s) light up in gold — for a diagonal, two dots.
     * The pad is fixed in place (it never follows the thumb):
     * press anywhere inside it and the direction comes from the
     * press offset, like the stick; sliding the thumb across
     * zones changes the direction; release clears it. A cancelled
     * gesture clears it too, in the finally block. Only one
     * pointer is tracked, so a second thumb on the buttons cannot
     * steal the pad. It sits in the same slot as the stick —
     * same size, position, drag/resize handling.
     */
    @Composable
    private fun Dpad(
        size: Dp, // base size (same slot as the stick)
        grabMargin: Dp,
        baseTop: Int = EraUi.Palette.MAROON,
        baseBottom: Int = EraUi.Palette.SURFACE_RAISED,
        armTop: Int = EraUi.Palette.MAROON_LIGHT,
        armBottom: Int = EraUi.Palette.MAROON,
        accent: Int = EraUi.Palette.GOLD,
        onDirection: (Int) -> Unit,
        onDirectionChange: (Int) -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
    ) {
        val density = LocalDensity.current
        val touchPx = with(density) { (size + grabMargin * 2).toPx() }
        val baseR = touchPx / 2f - with(density) { grabMargin.toPx() }
        var heldMask by remember { mutableStateOf(0) }
        Box(
            modifier = modifier
                .pointerInput(enabled, touchPx) {
                    if (!enabled) return@pointerInput
                    awaitPointerEventScope {
                        var activeId: PointerId? = null
                        var lastMask = 0
                        fun setDirection(mask: Int) {
                            if (mask != lastMask) {
                                lastMask = mask
                                heldMask = mask
                                onDirection(mask)
                                if (mask != 0) onDirectionChange(mask)
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Press -> {
                                        if (activeId == null) {
                                            val change = event.changes
                                                .firstOrNull { it.pressed }
                                            if (change != null) {
                                                activeId = change.id
                                                setDirection(
                                                    DpadInput.directionFromPress(
                                                        change.position.x,
                                                        change.position.y,
                                                        touchPx,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                    PointerEventType.Move -> {
                                        val id = activeId ?: continue
                                        val change = event.changes
                                            .firstOrNull { it.id == id }
                                        if (change != null && change.pressed) {
                                            setDirection(
                                                DpadInput.directionFromPress(
                                                    change.position.x,
                                                    change.position.y,
                                                    touchPx,
                                                ),
                                            )
                                        }
                                    }
                                    // Only a real Release (finger lift)
                                    // ends the drag — same as the stick:
                                    // an Exit (the pointer sliding out
                                    // of the pad while still down) is
                                    // deliberately NOT handled, so a
                                    // drag that runs off the pad's edge
                                    // keeps its direction (the press
                                    // position clamps to the edge in
                                    // DpadInput). Ending the drag on
                                    // Exit dropped the direction on
                                    // straight drags.
                                    PointerEventType.Release -> {
                                        val id = activeId
                                        if (id != null && event.changes.any { it.id == id }) {
                                            activeId = null
                                            setDirection(0)
                                        }
                                    }
                                }
                            }
                        } finally {
                            activeId = null
                            setDirection(0)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centre = Offset(this.size.width / 2f, this.size.height / 2f)
                val gold = Color(accent)
                // Base: a rounded square, lighter at the top.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(baseTop), Color(baseBottom)),
                        startY = centre.y - baseR,
                        endY = centre.y + baseR,
                    ),
                    topLeft = Offset(centre.x - baseR, centre.y - baseR),
                    size = Size(baseR * 2f, baseR * 2f),
                    cornerRadius = CornerRadius(baseR * 0.25f),
                )
                drawRoundRect(
                    color = gold.copy(alpha = 0.6f),
                    topLeft = Offset(centre.x - baseR, centre.y - baseR),
                    size = Size(baseR * 2f, baseR * 2f),
                    cornerRadius = CornerRadius(baseR * 0.25f),
                    style = Stroke(width = 1.dp.toPx()),
                )
                // The plus: a vertical bar and a horizontal bar,
                // each 1/3 of the base wide.
                val armW = baseR * 2f / 3f
                val armLen = baseR * 2f
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(armTop), Color(armBottom)),
                        startY = centre.y - baseR,
                        endY = centre.y + baseR,
                    ),
                    topLeft = Offset(centre.x - armW / 2f, centre.y - baseR),
                    size = Size(armW, armLen),
                    cornerRadius = CornerRadius(armW / 2f),
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(armTop), Color(armBottom)),
                        startY = centre.y - armW / 2f,
                        endY = centre.y + armW / 2f,
                    ),
                    topLeft = Offset(centre.x - baseR, centre.y - armW / 2f),
                    size = Size(armLen, armW),
                    cornerRadius = CornerRadius(armW / 2f),
                )
                // Light up the pressed direction(s): a gold dot
                // on each pressed arm. A diagonal lights two.
                val dotR = armW * 0.38f
                val dotOff = baseR * 0.55f
                if (heldMask and (1 shl InputMask.JOY_UP) != 0) {
                    drawCircle(gold, dotR, Offset(centre.x, centre.y - dotOff))
                }
                if (heldMask and (1 shl InputMask.JOY_DOWN) != 0) {
                    drawCircle(gold, dotR, Offset(centre.x, centre.y + dotOff))
                }
                if (heldMask and (1 shl InputMask.JOY_LEFT) != 0) {
                    drawCircle(gold, dotR, Offset(centre.x - dotOff, centre.y))
                }
                if (heldMask and (1 shl InputMask.JOY_RIGHT) != 0) {
                    drawCircle(gold, dotR, Offset(centre.x + dotOff, centre.y))
                }
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * The arcade "CONTINUE?" pause menu: header, decorative countdown,
 * and the six options. The button list scrolls, so every option is
 * reachable even on a short landscape screen; the header, countdown,
 * spacing and button padding are tight, so the whole menu fits
 * without scrolling when the screen is tall enough. The buttons
 * stay full-size and comfortably tappable — the compact padding
 * only removes height above the Material3 40dp floor.
 *
 * Extracted from MainActivity so the layout is testable in
 * isolation (a running game needs native code, which Robolectric
 * does not have).
 */
@Composable
fun PauseMenu(
    timeLeft: Int,
    status: String = "",
    onResume: () -> Unit,
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    onSettings: () -> Unit,
    onAdjustControls: () -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Arcade "CONTINUE?" screen: the countdown is decorative —
        // it wraps at 0 and never resumes or quits anything on its own.
        Text(
            "CONTINUE?",
            color = Color(EraUi.Palette.GOLD),
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
        Text(
            "%d".format(timeLeft),
            color = Color(EraUi.Palette.TEXT),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        ArcadeButton(text = "RESUME", primary = true, compact = true, onClick = onResume)
        Spacer(Modifier.height(4.dp))
        ArcadeButton(text = "SAVE STATE", compact = true, onClick = onSaveState)
        Spacer(Modifier.height(4.dp))
        ArcadeButton(text = "LOAD STATE", compact = true, onClick = onLoadState)
        Spacer(Modifier.height(4.dp))
        ArcadeButton(text = "SETTINGS", compact = true, onClick = onSettings)
        Spacer(Modifier.height(4.dp))
        ArcadeButton(text = "ADJUST CONTROLS", compact = true, onClick = onAdjustControls)
        Spacer(Modifier.height(4.dp))
        ArcadeButton(text = "QUIT TO LIBRARY", compact = true, onClick = onQuit)
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, color = Color(EraUi.Palette.TEXT))
        }
    }
}
