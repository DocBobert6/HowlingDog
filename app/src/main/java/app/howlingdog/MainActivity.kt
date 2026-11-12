package app.howlingdog

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * 2.0, feature 1 — playtime: the tracker for the current
     * game, created per game start with the game's stored total
     * (see PlaytimeTracker — it holds the full total, so
     * persisting is a plain overwrite). Null when no game is
     * running. Discarded (not persisted) if the game fails to
     * load.
     */
    private var playtimeTracker: PlaytimeTracker? = null

    /**
     * 2.0, feature 4 — the on-screen input display's history:
     * the recent inputs as they happen, like a fightstick
     * display. Recorded at the shared pushMask() point (touch +
     * gamepad, before the turbo pulse). Cleared on every game
     * start and quit, so a new game starts with an empty strip.
     */
    private val inputHistory = InputHistory()

    /**
     * 2.0, feature 6 — the Arcade Manager's counts: the session's
     * presses (in memory, reset on persist) and the previous input
     * mask (for the press transitions). The stored per-game counts
     * live in filesDir/arcade-stats.txt via [arcadeStatsStore].
     * The session is persisted on pause and on quit, so a kill
     * mid-game loses only the running session's counts.
     */
    private val arcadeStatsStore by lazy { ArcadeStatsStore(File(filesDir, "arcade-stats.txt")) }
    private val arcadeMutex = Mutex()
    private var arcadeSessionCounts = ArcadeCounts()
    private var lastArcadeMask: Int = 0

    /**
     * 2.0, feature 6 — persist the session's arcade counts:
     * capture and reset them on the main thread (pushMask runs
     * there too, so no race), then merge them into the stored
     * counts and write the file on IO, serialized through
     * arcadeMutex so a pause-persist and a quit-persist cannot
     * interleave and lose each other's counts.
     */
    private fun persistArcadeStats() {
        val game = playingGameState.value ?: return
        val session = arcadeSessionCounts
        if (session.isEmpty()) return
        arcadeSessionCounts = ArcadeCounts()
        val mainFile = game.mainFile
        lifecycleScope.launch(Dispatchers.IO) {
            arcadeMutex.withLock {
                val merged = arcadeStatsStore.load(mainFile) + session
                arcadeStatsStore.save(mainFile, merged)
            }
        }
    }

    /**
     * 2.0, feature 1 — serializes the playtime persists: pause
     * and quit both persist, and without this a pause-persist
     * launched just before a quit-persist could land after it
     * and overwrite a newer total with an older one.
     */
    private val playtimeMutex = Mutex()

    // --- Gamepad state (Phase 4). The touch UI keeps its own Held
    // state in the composable; the pad keeps its held buttons and
    // axis directions here, and the two masks are ORed in pushMask.
    /** Gamepad buttons currently held (KeyEvent keycodes). */
    private val padButtonsHeld = mutableSetOf<Int>()
    /** Direction bits from the gamepad axes (stick/hat). */
    private var padAxesMask = 0
    /**
     * v1.5, feature 5 — two-player multiplayer: which gamepad is
     * player 1 and which is player 2 (first to send input is
     * player 1, second is player 2, a third is ignored). The
     * touch overlay is always player 1.
     */
    private val playerAssignment = PlayerAssignment()
    /** Player-2 gamepad buttons currently held (KeyEvent keycodes). */
    private val pad2ButtonsHeld = mutableSetOf<Int>()
    /** Direction bits from the player-2 gamepad axes (stick/hat). */
    private var pad2AxesMask = 0
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
     * v1.5, feature 1 — stick clicks held for the speed
     * controls: L3 = fast-forward, R3 = slow motion. Tracked
     * apart from padButtonsHeld, like the shoulders: they are
     * not Neo Geo inputs, so they must never reach the game
     * mask, and they are dropped when the game pauses.
     */
    private var padFfHeld = false
    private var padSlowHeld = false
    /** Set by the UI; called when a speed button goes down or up, so the session speed is recomputed. */
    private var onPadSpeed: (() -> Unit)? = null
    /** Set by the remap screen while it is waiting for a button
        (v1.3.0, part 1): called with the pressed keycode, and
        returns true when the press was captured (so it never
        reaches the game). Null when not capturing. */
    private var captureKeyHandler: ((Int) -> Boolean)? = null
    /** The gamepad button mapping, mirrored from the UI (v1.3.0,
        part 1): the Activity's key handlers read it, the UI writes
        it whenever the mapping changes. */
    private var gamepadMappingField: Map<String, Int> = GamepadMapping.DEFAULT
    /** Set by the UI; called when the app backgrounds (Activity ON_PAUSE). */
    private var onAppBackgrounded: (() -> Unit)? = null
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

    /**
     * v1.5, feature 2 — the most recent frame Bitmap, kept so
     * a save can capture a thumbnail of where the game was.
     * The session double-buffers and posts the front Bitmap;
     * while paused no frames run, so the last posted Bitmap is
     * stable and safe to copy at save time. Cleared when a new
     * game starts, so a save before the first frame never
     * pictures the previous game.
     */
    private var lastFrame: Bitmap? = null

    /** A pad that disappears must never leave a stuck button. */
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            // v1.5, feature 5 — two-player multiplayer: only the
            // removed pad's state is dropped, and its player slot
            // is freed for the next gamepad. The other pad keeps
            // playing untouched.
            if (deviceId == playerAssignment.deviceP1) {
                if (padButtonsHeld.isNotEmpty() || padAxesMask != 0 || padFfHeld || padSlowHeld) {
                    padButtonsHeld.clear()
                    padAxesMask = 0
                    padFfHeld = false
                    padSlowHeld = false
                    pushPadMask()
                    onPadSpeed?.invoke()
                }
                playerAssignment.release(deviceId)
            } else if (deviceId == playerAssignment.deviceP2) {
                if (pad2ButtonsHeld.isNotEmpty() || pad2AxesMask != 0) {
                    pad2ButtonsHeld.clear()
                    pad2AxesMask = 0
                    pushPad2Mask()
                }
                playerAssignment.release(deviceId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (getSystemService(INPUT_SERVICE) as InputManager)
            .registerInputDeviceListener(deviceListener, null)
        // App backgrounded (home, recents, screen off, call): the
        // UI releases every held input, so a turbo button held when
        // the app goes away can never keep firing while it is
        // backgrounded — a gamepad key-up may never arrive, and the
        // touch stream alone is not enough to trust.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) onAppBackgrounded?.invoke()
        })
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

    /**
     * 2.0, feature 3 — re-read the memory card from the saves
     * folder, so the MemoryCard screen always shows the card as
     * it is. Called when the screen is opened and after every
     * delete.
     */
    private fun refreshMemoryCard() {
        memoryCardEntriesState.value =
            MemoryCard.listEntries(File(filesDir, "saves"), gamesState.value)
    }

    /**
     * 2.0, feature 6 — rebuild the Arcade Manager's entries and
     * totals from the arcade-stats file and the library, so the
     * screen always shows the counts as they are. Called when
     * the screen is opened. The rows come from
     * [ArcadeStats.buildArcadeEntries]: library games by their
     * display name, and games with stats that left the library
     * by their file stem. Games with neither playtime nor
     * counts are left out; the rest sort by presses, most first.
     * The totals cover every game in the library (playtime) and
     * every stored count (coins, including games no longer in
     * the library).
     */
    private fun refreshArcadeManager() {
        val stored = arcadeStatsStore.loadAll()
        val games = gamesState.value
        arcadeEntriesState.value = ArcadeStats.buildArcadeEntries(games, stored)
        arcadeTotalCoinsState.value = stored.values.sumOf { it.coin }
        arcadeTotalPlaytimeState.value = games.sumOf { it.totalPlayMillis }
    }

    /** Recompute the gamepad mask from the held buttons and axes, and hand it to the UI. */
    private fun pushPadMask() {
        var mask = padAxesMask
        for (code in padButtonsHeld) mask = mask or GamepadInput.maskForButton(code, gamepadMappingField)
        onPadMask?.invoke(mask)
    }

    /**
     * 2.0, feature 2 — true when the current layout (the
     * Activity-level states) is exactly the global layout. A
     * per-game override that matches the global layout is
     * redundant, so saving it is skipped and any stored override
     * is dropped instead.
     */
    private fun layoutMatchesGlobal(): Boolean {
        return stickPosState.value == controlSettings.stickPosition() &&
            buttonsPosState.value == controlSettings.buttonsPosition() &&
            stickScaleState.value == controlSettings.stickScale() &&
            buttonsScaleState.value == controlSettings.buttonsScale() &&
            dpadScaleState.value == controlSettings.dpadScale() &&
            buttonOverridesState.value == controlSettings.buttonOffsets() &&
            buttonSizeMultsState.value == controlSettings.buttonSizeMults()
    }

    /**
     * 2.0, feature 2 — write the current control layout (the
     * Activity-level states, the source of truth while the user
     * drags) to the active scope. When "For this game" is on and a
     * game is loaded, the layout is saved as that game's own
     * override in PerGameSettings; otherwise it goes to the global
     * ControlSettings, exactly as before. Called on every drag end
     * and on Done, so the store always matches what the user sees.
     * The per-button overrides are written per id with a null
     * clear for absent ids, so the stored per-game set matches the
     * current map exactly and a removed override does not go stale.
     * When the layout matches the global one exactly, there is
     * nothing to override — the game's saved layout is dropped
     * instead of storing a redundant copy (this is what Reset
     * does: it restores the global values, so Done after a Reset
     * clears the override rather than re-storing it).
     */
    private fun saveLayoutToScope() {
        val game = playingGameState.value
        if (saveForThisGameState.value && game != null) {
            if (layoutMatchesGlobal()) {
                perGameSettings.clearControlLayout(game.mainFile)
                return
            }
            perGameSettings.setStickPositionOverride(game.mainFile, stickPosState.value)
            perGameSettings.setButtonsPositionOverride(game.mainFile, buttonsPosState.value)
            perGameSettings.setStickScaleOverride(game.mainFile, stickScaleState.value)
            perGameSettings.setButtonsScaleOverride(game.mainFile, buttonsScaleState.value)
            perGameSettings.setDpadScaleOverride(game.mainFile, dpadScaleState.value)
            for (id in ControlSettings.BUTTON_IDS) {
                perGameSettings.setButtonOffsetOverride(game.mainFile, id, buttonOverridesState.value[id])
                perGameSettings.setButtonSizeMultOverride(game.mainFile, id, buttonSizeMultsState.value[id])
            }
        } else {
            controlSettings.setStickPosition(stickPosState.value)
            controlSettings.setButtonsPosition(buttonsPosState.value)
            controlSettings.setStickScale(stickScaleState.value)
            controlSettings.setButtonsScale(buttonsScaleState.value)
            controlSettings.setDpadScale(dpadScaleState.value)
            for ((id, off) in buttonOverridesState.value) controlSettings.setButtonOffset(id, off)
            for ((id, mult) in buttonSizeMultsState.value) controlSettings.setButtonSizeMult(id, mult)
        }
    }

    /**
     * v1.5, feature 5 — two-player multiplayer: recompute the
     * player-2 gamepad mask and hand it straight to the session.
     * Player 2 is gamepad-only (no touch, no turbo, no UniBIOS
     * combo), so there is nothing to OR it with — the mask IS the
     * input. The same remapping applies as for player 1.
     */
    private fun pushPad2Mask() {
        var mask = pad2AxesMask
        for (code in pad2ButtonsHeld) mask = mask or GamepadInput.maskForButton(code, gamepadMappingField)
        session?.setInput2(mask)
    }

    /** Drop all gamepad-held input (quit, disconnect). */
    private fun clearPadState() {
        padButtonsHeld.clear()
        padAxesMask = 0
        padQuickHeld.clear()
        padFfHeld = false
        padSlowHeld = false
        pushPadMask()
        onPadSpeed?.invoke()
        // v1.5, feature 5 — two-player multiplayer: the second
        // pad's held input goes too, so no player-2 button sticks
        // across a quit or a background. The assignment itself is
        // NOT cleared here — the pads are still the same physical
        // devices, so they keep their players; a slot is only
        // freed when its device actually disconnects (see the
        // device listener above).
        pad2ButtonsHeld.clear()
        pad2AxesMask = 0
        pushPad2Mask()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // v1.5, feature 5 — two-player multiplayer: a gamepad
        // button belongs to whichever player its device is assigned
        // to (first gamepad is player 1, second is player 2, a
        // third is ignored). Player 2's buttons go straight to the
        // player-2 mask — the quick combos, speed buttons, pause
        // hotkey, and remap capture are all player-1 system
        // functions and never fire for it.
        if (GamepadInput.isGamepadButton(keyCode) ||
            GamepadInput.isQuickComboButton(keyCode) ||
            GamepadInput.isSpeedButton(keyCode)
        ) {
            when (playerAssignment.playerFor(event.deviceId)) {
                PlayerAssignment.PLAYER_2 -> return onPad2KeyDown(keyCode, event)
                PlayerAssignment.IGNORED -> return super.onKeyDown(keyCode, event)
                else -> { /* Player 1: fall through to the code below. */ }
            }
        }
        // Remapping capture (v1.3.0, part 1): while the remap
        // screen is waiting for a button, a recognised gamepad
        // button press is captured here, before the game-input
        // path (which needs inputActive). A captured press never
        // reaches the game. Any other key (volume, and so on)
        // never reaches the handler, so it falls through below.
        if (GamepadInput.isGamepadButton(keyCode) || GamepadInput.isQuickComboButton(keyCode)) {
            captureKeyHandler?.let { handler ->
                if (handler(keyCode)) return true
            }
        }
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
        // v1.5, feature 1 — speed buttons (stick clicks): L3 =
        // fast-forward, R3 = slow motion. Not Neo Geo inputs and
        // not quick-combo buttons, so they are tracked apart from
        // the joypad mask and never reach the game. Whether the
        // hold does anything depends on the Settings speeds (the
        // onPadSpeed callback reads them); a press with the
        // feature off is swallowed and does nothing.
        if (GamepadInput.isSpeedButton(keyCode)) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL) padFfHeld = true else padSlowHeld = true
            onPadSpeed?.invoke()
            return true
        }
        if (!GamepadInput.isGamepadButton(keyCode)) return super.onKeyDown(keyCode, event)
        // START+SELECT together pauses (the emulator-menu hotkey).
        // Start on its own stays the in-game Start button, and the
        // touch Pause button stays the primary pause control. The
        // pair follows the remapping (v1.3.0, part 1): pause is the
        // two physical buttons assigned to Neo Start and Neo Coin,
        // held together — not necessarily the pad's Start/Select.
        val startKey = GamepadMapping.keyCodeFor("Start", gamepadMappingField)
        val coinKey = GamepadMapping.keyCodeFor("Coin", gamepadMappingField)
        if (keyCode == startKey && coinKey in padButtonsHeld) {
            onPauseHotkey?.invoke()
            return true
        }
        if (keyCode == coinKey && startKey in padButtonsHeld) {
            onPauseHotkey?.invoke()
            return true
        }
        padButtonsHeld.add(keyCode)
        pushPadMask()
        return true
    }

    /**
     * v1.5, feature 5 — two-player multiplayer: a player-2 gamepad
     * button press. Only the six Neo Geo inputs reach the game;
     * the quick combos, speed buttons, and pause hotkey are
     * player-1 system functions and never fire here.
     */
    private fun onPad2KeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputActive || event.repeatCount != 0) return super.onKeyDown(keyCode, event)
        if (!GamepadInput.isGamepadButton(keyCode)) return super.onKeyDown(keyCode, event)
        pad2ButtonsHeld.add(keyCode)
        pushPad2Mask()
        return true
    }

    /**
     * v1.5, feature 5 — two-player multiplayer: a player-2 gamepad
     * button release.
     */
    private fun onPad2KeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputActive) return super.onKeyUp(keyCode, event)
        if (pad2ButtonsHeld.remove(keyCode)) {
            pushPad2Mask()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // v1.5, feature 5 — two-player multiplayer: route the
        // release to the player that owns this device.
        if (GamepadInput.isGamepadButton(keyCode) ||
            GamepadInput.isQuickComboButton(keyCode) ||
            GamepadInput.isSpeedButton(keyCode)
        ) {
            when (playerAssignment.playerFor(event.deviceId)) {
                PlayerAssignment.PLAYER_2 -> return onPad2KeyUp(keyCode, event)
                PlayerAssignment.IGNORED -> return super.onKeyUp(keyCode, event)
                else -> { /* Player 1: fall through to the code below. */ }
            }
        }
        if (!inputActive) return super.onKeyUp(keyCode, event)
        if (padQuickHeld.remove(keyCode)) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL || keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL) padFfHeld = false else padSlowHeld = false
            onPadSpeed?.invoke()
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
            // v1.5, feature 5 — two-player multiplayer: the axes
            // belong to whichever player this device is assigned to.
            when (playerAssignment.playerFor(event.deviceId)) {
                PlayerAssignment.PLAYER_2 -> {
                    val mask = GamepadInput.maskForAxes(event)
                    if (mask != pad2AxesMask) {
                        pad2AxesMask = mask
                        pushPad2Mask()
                    }
                    return true
                }
                PlayerAssignment.IGNORED -> return super.onGenericMotionEvent(event)
                else -> {
                    val mask = GamepadInput.maskForAxes(event)
                    if (mask != padAxesMask) {
                        padAxesMask = mask
                        pushPadMask()
                    }
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private enum class Screen { Boot, Library, Playing, Settings, Licences, Setup, GameSettings, Achievements, Leaderboards, GamepadRemap, Cheats, MemoryCard, ArcadeManager }

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

    // v1.3.1: HowlingDogUi's states and settings live here,
    // not in the composable. The composable had grown past
    // the 256-register dex limit and ART rejected the class
    // at launch (VerifyError), so the app died before
    // anything drew. `by lazy` initialises on first access,
    // during the first composition -- the same timing as
    // the remember()s these replace. The screens re-delegate
    // these fields, so their bodies are unchanged.
    private val appSettings by lazy {
        AppSettings(
            SharedPrefsStore(
                getSharedPreferences(
                    "howlingdog-settings", Context.MODE_PRIVATE,
                ),
            ),
        )
    }
    private val settings by lazy {
        CoreSettings(
            SharedPrefsStore(
                getSharedPreferences(
                    "howlingdog-settings", Context.MODE_PRIVATE,
                ),
            ),
        )
    }
    private val controlSettings by lazy {
        ControlSettings(
            SharedPrefsStore(
                getSharedPreferences(
                    "howlingdog-settings", Context.MODE_PRIVATE,
                ),
            ),
        )
    }
    private val shaderSettings by lazy {
        ShaderSettings(
            SharedPrefsStore(
                getSharedPreferences(
                    "howlingdog-settings", Context.MODE_PRIVATE,
                ),
            ),
        )
    }
    private val perGameSettings by lazy {
        PerGameSettings(
            SharedPrefsStore(
                getSharedPreferences(
                    "howlingdog-settings", Context.MODE_PRIVATE,
                ),
            ),
        )
    }
    // v1.5, feature 4 — cheats: the per-game cheat store, one
    // file per game in filesDir/cheats. The Cheats screen and
    // startGame both read it; the session only holds the current
    // enabled codes (see PlayerSession.setCheats).
    private val cheatStore by lazy { CheatStore(File(filesDir, "cheats")) }
    private val topBar by lazy { TopBarVisibility() }
    private val screenState by lazy { mutableStateOf(if (appSettings.showBoot()) Screen.Boot else Screen.Library) }
    private val gamesState by lazy { mutableStateOf<List<GameEntry>>(emptyList()) }
    private val biosState by lazy { mutableStateOf<BiosStatus?>(null) }
    private val statusState by lazy { mutableStateOf("") }
    private val playingGameState by lazy { mutableStateOf<GameEntry?>(null) }
    private val confirmRemoveState by lazy { mutableStateOf<GameEntry?>(null) }
    // 2.0, feature 3 — the memory card entries the MemoryCard
    // screen shows, re-read from the saves folder whenever the
    // screen is opened or a save is deleted.
    private val memoryCardEntriesState by lazy { mutableStateOf<List<MemoryCard.Entry>>(emptyList()) }
    // 2.0, feature 6 — the Arcade Manager's entries and totals,
    // re-read from the arcade-stats file and the library whenever
    // the screen is opened; and where Back returns to (the
    // library, or Settings).
    private val arcadeEntriesState by lazy { mutableStateOf<List<ArcadeManagerEntry>>(emptyList()) }
    private val arcadeTotalCoinsState by lazy { mutableStateOf(0L) }
    private val arcadeTotalPlaytimeState by lazy { mutableStateOf(0L) }
    private val arcadeManagerFromState by lazy { mutableStateOf(Screen.Library) }
    private val modePickerForState by lazy { mutableStateOf<GameEntry?>(null) }
    private val renameForState by lazy { mutableStateOf<GameEntry?>(null) }
    private val renameTextState by lazy { mutableStateOf("") }
    private val searchTextState by lazy { mutableStateOf("") }
    private val librarySortState by lazy { mutableStateOf(LibrarySort.NAME) }
    private val collectionFilterState by lazy { mutableStateOf<String?>(null) }
    private val collectionsState by lazy { mutableStateOf<List<String>>(emptyList()) }
    private val collectionForState by lazy { mutableStateOf<GameEntry?>(null) }
    private val collectionTextState by lazy { mutableStateOf("") }
    private val perGameForState by lazy { mutableStateOf<GameEntry?>(null) }
    private val perGameTickState by lazy { mutableStateOf(0) }
    private val settingsFromState by lazy { mutableStateOf(Screen.Library) }
    private val pausedState by lazy { mutableStateOf(false) }
    private val pauseStatusState by lazy { mutableStateOf("") }
    private val uniBiosHoldUntilState by lazy { mutableStateOf(0L) }
    private val saveSlotState by lazy { mutableStateOf(QuickSave.QUICK_SLOT) }
    private val quickStatusState by lazy { mutableStateOf("") }
    private val selectedMainFileState by lazy { mutableStateOf<String?>(null) }
    private val timeLeftState by lazy { mutableStateOf(9) }
    private val pauseTimeLeftState by lazy { mutableStateOf(9) }
    private val pressStartAlphaState by lazy { mutableStateOf(1f) }
    private val padMaskState by lazy { mutableStateOf(0) }
    private val heldState by lazy { mutableStateOf(Held()) }
    private val artIndexState by lazy { mutableStateOf<List<String>?>(null) }
    private val coversState by lazy { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    private val artTriedState by lazy { mutableStateOf<Set<String>>(emptySet()) }
    private val hapticsLevelState by lazy { mutableStateOf(controlSettings.haptics()) }
    // 2.0, feature 4 — the input display: the recent-input events
    // the strip shows (updated from pushMask), and whether the
    // strip is on (a Settings toggle, off by default).
    private val inputHistoryState by lazy { mutableStateOf<List<InputHistory.InputEvent>>(emptyList()) }
    private val inputDisplayOnState by lazy { mutableStateOf(controlSettings.inputDisplay()) }
    private val stickOpacityState by lazy { mutableStateOf(controlSettings.stickOpacity()) }
    private val buttonsOpacityState by lazy { mutableStateOf(controlSettings.buttonsOpacity()) }
    private val controlSizeState by lazy { mutableStateOf(controlSettings.size()) }
    private val leftControlState by lazy { mutableStateOf(controlSettings.leftControl()) }
    private val gamepadMappingState by lazy { mutableStateOf(controlSettings.gamepadMapping()) }
    private val remapCapturingState by lazy { mutableStateOf<String?>(null) }
    private val remapHintState by lazy { mutableStateOf("") }
    private val turboMaskState by lazy { mutableStateOf(controlSettings.turboMask()) }
    private val runaheadFramesState by lazy { mutableStateOf(appSettings.runaheadFrames()) }
    private val ffHeldState by lazy { mutableStateOf(false) }
    private val slowHeldState by lazy { mutableStateOf(false) }
    private val ffSpeedState by lazy { mutableStateOf(appSettings.fastForwardSpeed()) }
    private val slowOnState by lazy { mutableStateOf(appSettings.slowMotionOn()) }
    private val autoSaveOnQuitState by lazy { mutableStateOf(appSettings.autoSaveOnQuit()) }
    private val raUsernameTextState by lazy { mutableStateOf(appSettings.raUsername()) }
    private val raPasswordTextState by lazy { mutableStateOf("") }
    // 2.0.1, audit fix 6 — whether the RA login fields are
    // shown while logged in. False by default: when logged in,
    // the fields stay hidden until "Log in again" is tapped.
    private val raLoginFieldsVisibleState by lazy { mutableStateOf(false) }
    private val showBootState by lazy { mutableStateOf(appSettings.showBoot()) }
    private val crtShaderOnState by lazy { mutableStateOf(shaderSettings.shaderMasterOn()) }
    // 2.0, feature 7 — the edge-smoothing mode (off by default).
    private val edgeModeState by lazy { mutableStateOf(shaderSettings.edgeMaster()) }
    private val curvatureOnState by lazy { mutableStateOf(shaderSettings.curvatureMasterOn()) }
    private val stickPosState by lazy { mutableStateOf(controlSettings.stickPosition()) }
    private val buttonsPosState by lazy { mutableStateOf(controlSettings.buttonsPosition()) }
    private val stickScaleState by lazy { mutableStateOf(controlSettings.stickScale()) }
    private val buttonsScaleState by lazy { mutableStateOf(controlSettings.buttonsScale()) }
    private val dpadScaleState by lazy { mutableStateOf(controlSettings.dpadScale()) }
    private val buttonOverridesState by lazy { mutableStateOf(controlSettings.buttonOffsets()) }
    private val buttonSizeMultsState by lazy { mutableStateOf(controlSettings.buttonSizeMults()) }
    private val moveTogetherState by lazy { mutableStateOf(true) }
    // 2.0, feature 2 — where the adjust mode saves to: this game
    // only (a per-game layout override) or all games (the global
    // settings). Set when edit mode is entered: on when the game
    // already has a saved layout, off otherwise.
    private val saveForThisGameState by lazy { mutableStateOf(false) }
    private val editControlsState by lazy { mutableStateOf(false) }
    private val topBarVisibleState by lazy { mutableStateOf(topBar.visible) }
    private val topBarGenerationState by lazy { mutableStateOf(topBar.generation) }
    private val setupPendingState by lazy { mutableStateOf(false) }
    private val dismissedUpdateState by lazy { mutableStateOf(appSettings.dismissedUpdateVersion()) }

    @Composable
    private fun HowlingDogUi() {
        val context = LocalContext.current
        val activity = context as Activity
        // App-level settings (boot splash toggle): read before the
        // initial screen is chosen, so a disabled splash goes
        // straight to the library on launch.
        var screen by screenState
        var games by gamesState
        var bios by biosState
        var status by statusState
        var playingGame by playingGameState
        // Phase 9, item 4 — the game being renamed, and the text in
        // the rename dialog (prefilled with the name it shows now).
        // v1.5, feature 3 — library search / sort / collections:
        // the search text, the sort order, the collection chip
        // picked (null = all games), the collection names in use,
        // and the game whose collection is being picked (with the
        // text for a new collection name).
        var collections by collectionsState
        // Phase 9, item 8 — the game whose own settings screen is
        // open (null when it is closed). `perGameTick` is bumped on
        // every per-game change so that screen recomposes from the
        // store (its rows read the overrides directly).
        var perGameFor by perGameForState
        var perGameTick by perGameTickState
        // 2.0, feature 3 — the memory card entries the MemoryCard
        // screen shows.
        var memoryCardEntries by memoryCardEntriesState
        // 2.0, feature 6 — the Arcade Manager's entries and
        // totals, and where Back returns to.
        var arcadeEntries by arcadeEntriesState
        var arcadeTotalCoins by arcadeTotalCoinsState
        var arcadeTotalPlaytime by arcadeTotalPlaytimeState
        var arcadeManagerFrom by arcadeManagerFromState
        var settingsFrom by settingsFromState
        var paused by pausedState
        var pauseStatus by pauseStatusState
        // Phase 9, item 9 — the uptimeMillis deadline until which
        // the injected UniBIOS menu combo (START+SELECT) is held
        // in the input mask; 0 means no injection is armed. Set by
        // the pause menu's UNIVERSE BIOS MENU button, cleared by a
        // pause, a quit, or the release timer below.
        var uniBiosHoldUntil by uniBiosHoldUntilState
        // Phase 9, item 3 — the save-state slot the pause menu's
        // Save / Load acts on (1..QuickSave.SLOT_COUNT). Slot 1 is
        // the legacy slot the quick path uses too; a new game starts
        // on slot 1 (reset in startGame).
        var saveSlot by saveSlotState
        // Quick save/load status: a brief note on the game screen
        // ("Saved." / "Loaded." / "No save yet."), cleared after a
        // couple of seconds. The pause menu keeps its own status.
        var quickStatus by quickStatusState
        // Phase 5 — era skin state. The select grid's picked game is
        // a main-file name, derived fresh from `games` below, so it
        // never goes stale when the library reloads. The countdowns
        // are decorative and never trigger an action.
        var selectedMainFile by selectedMainFileState
        val selectedGame =
            games.firstOrNull { it.mainFile == selectedMainFile } ?: games.firstOrNull()
        var timeLeft by timeLeftState
        var pauseTimeLeft by pauseTimeLeftState
        var pressStartAlpha by pressStartAlphaState
        var padMask by padMaskState
        var held by heldState
        // (The GL game view lives in the Activity field gameGlView,
        // so onPause/onResume can forward to it.)
        // Cover art (box art fetched at runtime, Wi-Fi only,
        // cached — see CoverArt). `covers` holds the decoded
        // bitmap per main-file name; `artTried` remembers games
        // with no art (or a failed fetch) so a tile is not
        // re-attempted on every recomposition; `artIndex` is the
        // cached libretro thumbnails filename list.
        var artIndex by artIndexState
        var covers by coversState
        var artTried by artTriedState
        val coverArt = remember { CoverArt(context.filesDir) }

        val gamesDir = File(context.filesDir, "games")
        val biosDir = File(context.filesDir, "bios")
        val library = remember {
            GameLibrary(
                gamesDir = gamesDir,
                biosDir = biosDir,
                storeFile = File(context.filesDir, "library.txt"),
                legacyGamesDir = File(context.cacheDir, "games"),
            )
        }
        // v1.5, feature 3 — collections, one per game, in
        // filesDir/collections.txt (see CollectionStore).
        val collectionStore = remember {
            CollectionStore(File(context.filesDir, "collections.txt"))
        }
        // On-screen controls (stick, haptics, opacity, size, layout):
        // same SharedPreferences file as the core options, own keys.
        var hapticsLevel by hapticsLevelState
        // 2.0, feature 4 — the input display: the recent-input
        // events the strip shows, and whether it is on.
        var inputHistoryEvents by inputHistoryState
        var inputDisplayOn by inputDisplayOnState
        // Per-group opacity (Part 2): the stick/D-pad and the
        // buttons each have their own, 20–100%, default 60.
        var stickOpacity by stickOpacityState
        var buttonsOpacity by buttonsOpacityState
        var controlSize by controlSizeState
        // The on-screen left control (stick or 8-way D-pad).
        var leftControl by leftControlState
        // Gamepad button remapping (v1.3.0, part 1): Neo id ->
        // physical keycode, a permutation of the six remappable
        // buttons. The game reads it through GamepadInput.
        var gamepadMapping by gamepadMappingState
        // The remap screen's capture: the Neo id waiting for a
        // button press, or null when not capturing.
        var remapCapturing by remapCapturingState
        // A one-line hint on the remap screen (what to press, or
        // why a press was not a remappable button).
        var remapHint by remapHintState
        // Turbo/autofire (Phase 9, item 6): the per-button toggles,
        // persisted in ControlSettings, default off for every button.
        var turboMask by turboMaskState
        // Run-ahead (Phase 10): 0 = off, 1 or 2 frames ahead.
        // Off by default; takes effect the next time a game starts.
        var runaheadFrames by runaheadFramesState
        // v1.5, feature 1 — fast-forward / slow motion: the
        // on-screen hold buttons' state, and the speeds from
        // Settings. Both speeds are off by default, so no speed
        // button appears on the overlay until one is turned on.
        // The gamepad stick clicks (L3/R3) live in the Activity
        // fields (padFfHeld / padSlowHeld) and are read by
        // pushSpeed() alongside these.
        var ffHeld by ffHeldState
        var slowHeld by slowHeldState
        var ffSpeed by ffSpeedState
        var slowOn by slowOnState
        // v1.5, feature 2 — auto-save on quit, on by default.
        var autoSaveOnQuit by autoSaveOnQuitState
        // RetroAchievements (Phase 10, v1.1.2): the username and
        // password typed in Settings. The username is stored; the
        // password lives only in this editable copy while the
        // screen is open and is never stored anywhere — the login
        // keeps the session token instead.
        // The pulse state machine (each turbo button's press start time).
        val turboState = remember { TurboState() }
        // CRT display settings (shader chain, curvature): the same
        // SharedPreferences file, own "display.*" keys.
        // Phase 9, item 8 — per-game configuration profiles: a
        // game's own overrides for region, a few core options, the
        // CRT shader / curvature, and the left control. Same
        // SharedPreferences file, own "pergame.*" keys.
        var stickPos by stickPosState
        var buttonsPos by buttonsPosState
        // Per-group resize multipliers, set by the resize handle in
        // adjust mode (1.0 = the Settings preset size). The D-pad
        // has its own (Part 2), so it is sized separately from the
        // stick instead of sharing the stick's scale.
        var stickScale by stickScaleState
        var buttonsScale by buttonsScaleState
        var dpadScale by dpadScaleState
        // Per-button offset overrides (Item 2): a button's offset
        // from the cluster centre, in dp at scale 1.0. Absent means
        // the theme layout's default offset for that button.
        var buttonOverrides by buttonOverridesState
        // Per-button size multipliers (Part 2): a button's own size
        // relative to the group size (1.0 = the group size). Absent
        // means 1.0.
        var buttonSizeMults by buttonSizeMultsState
        // Move-together toggle (Item 2, Mariner's paired-buttons
        // behaviour): on, dragging a button moves the whole
        // cluster; off, it moves that button alone. A session
        // toggle, not persisted — Mariner's `grouped` is the same.
        // Adjust-layout edit mode: entered from the pause screen
        // (the game has to be running to see where the controls
        // sit). While editing, the game stays paused, the pause
        // overlay hides, and the two control units drag.
        var editControls by editControlsState
        // 2.0, feature 2 — where the adjust mode saves to: this
        // game only (a per-game layout override) or all games (the
        // global settings). Set when edit mode is entered.
        var saveForThisGame by saveForThisGameState
        // Phase 9, item 7 — top bar auto-hide: the title row
        // (game name + SAVE / LOAD / Pause) hides a couple of
        // seconds after a game starts and comes back on a swipe
        // down from the top edge, hiding again after a few idle
        // seconds. The pure state machine is TopBarVisibility
        // (no Compose dependency, unit-tested on the JVM); these
        // two Compose states mirror it so recomposition follows
        // it — `generation` is the timer's restart signal, bumped
        // on every reveal.
        var topBarVisible by topBarVisibleState
        var topBarGeneration by topBarGenerationState
        fun syncTopBar() {
            topBarVisible = topBar.visible
            topBarGeneration = topBar.generation
        }
        // First-run setup (audit 2): offered once on a genuinely
        // fresh install, after the boot splash. `setupPending` is
        // set when the first library load finds no system files
        // and no games; it is consumed when the setup screen is
        // left, and never re-offered after it has been seen.
        var setupPending by setupPendingState
        // Phase 9, item 10 — in-app updater: the update the user
        // dismissed ("Not now" on the library notice), mirrored in
        // a Compose state so dismissing it recomposes the banner
        // away at once; the store is the source of truth across
        // restarts.

        fun exitEditMode() {
            // 2.0, feature 2 — Done commits the layout to the
            // active scope: this game's own override when "For
            // this game" is on, else the global settings.
            saveLayoutToScope()
            editControls = false
            // Leaving edit mode re-arms the idle timer, so the bar
            // hides again a few seconds after Done.
            topBar.onEditModeChanged(false)
            syncTopBar()
        }
        val haptics = remember(context) {
            Haptics(context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        }
        fun playHaptic() = haptics.perform(hapticsLevel)
        // 2.0, feature 5 — the Coin button's thud: a heavier buzz
        // for the coin drop, distinct from a button press at every
        // level. Respects OFF (performCoin returns without
        // vibrating when the level is OFF).
        fun playCoinHaptic() = haptics.performCoin(hapticsLevel)

        // Re-read the control settings whenever the game screen is
        // entered, so a change made in Settings (or in the adjust
        // layout mode) is live the next time a game starts. The
        // left control resolves per game first (Phase 9, item 8):
        // a game's own choice wins over the global one.
        LaunchedEffect(screen) {
            if (screen == Screen.Playing) {
                hapticsLevel = controlSettings.haptics()
                // 2.0, feature 4 — re-read the input display
                // toggle, so a change made in Settings is live the
                // next time a game starts.
                inputDisplayOn = controlSettings.inputDisplay()
                stickOpacity = controlSettings.stickOpacity()
                buttonsOpacity = controlSettings.buttonsOpacity()
                controlSize = controlSettings.size()
                leftControl = playingGame?.let {
                    perGameSettings.resolveLeftControl(it.mainFile, controlSettings.leftControl())
                } ?: controlSettings.leftControl()
                turboMask = controlSettings.turboMask()
                // 2.0, feature 2 — the layout resolves per game
                // first: a game's own saved positions win over the
                // global layout, exactly like the left control
                // above. A game with no saved layout follows the
                // global one.
                stickPos = playingGame?.let {
                    perGameSettings.resolveStickPosition(it.mainFile, controlSettings.stickPosition())
                } ?: controlSettings.stickPosition()
                buttonsPos = playingGame?.let {
                    perGameSettings.resolveButtonsPosition(it.mainFile, controlSettings.buttonsPosition())
                } ?: controlSettings.buttonsPosition()
                stickScale = playingGame?.let {
                    perGameSettings.resolveStickScale(it.mainFile, controlSettings.stickScale())
                } ?: controlSettings.stickScale()
                buttonsScale = playingGame?.let {
                    perGameSettings.resolveButtonsScale(it.mainFile, controlSettings.buttonsScale())
                } ?: controlSettings.buttonsScale()
                dpadScale = playingGame?.let {
                    perGameSettings.resolveDpadScale(it.mainFile, controlSettings.dpadScale())
                } ?: controlSettings.dpadScale()
                buttonOverrides = playingGame?.let {
                    perGameSettings.resolveButtonOffsets(it.mainFile, controlSettings.buttonOffsets())
                } ?: controlSettings.buttonOffsets()
                buttonSizeMults = playingGame?.let {
                    perGameSettings.resolveButtonSizeMults(it.mainFile, controlSettings.buttonSizeMults())
                } ?: controlSettings.buttonSizeMults()
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

        // Phase 10 — RetroAchievements: the unlock popup clears
        // after four seconds. The effect restarts when a new
        // unlock arrives, so a second unlock re-arms the timer.
        LaunchedEffect(RaClient.lastUnlock) {
            if (RaClient.lastUnlock != null) {
                delay(4000)
                RaClient.clearUnlock()
            }
        }

        // v1.2.0 (item 2) — the leaderboard scoreboard note
        // clears the same way, four seconds after a score is
        // submitted.
        LaunchedEffect(RaClient.lastScoreboard) {
            if (RaClient.lastScoreboard != null) {
                delay(4000)
                RaClient.clearScoreboard()
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
        // First-run setup offer: the offer lands while the user is
        // on the library (the splash is off, or they tapped
        // through before the first library load finished) — step
        // into the setup screen once, instead of leaving a fresh
        // install staring at an empty library.
        LaunchedEffect(screen, setupPending) {
            if (setupPending && screen == Screen.Library) {
                setupPending = false
                screen = Screen.Setup
            }
        }

        fun refreshLibrary() {
            lifecycleScope.launch(Dispatchers.IO) {
                val loaded = library.load()
                val b = library.biosStatus()
                withContext(Dispatchers.Main) {
                    games = loaded
                    bios = b
                    // v1.5, feature 3 — the collection names in
                    // use, for the filter chips and the picker.
                    collections = collectionStore.collections()
                    // First-run setup: a genuinely fresh install (no
                    // system files, no games) is offered the SYSTEM
                    // SETUP screen once. The offer is consumed when
                    // the screen is left, and never re-offered after
                    // it has been seen.
                    if (shouldShowSetup(b.readyCount, loaded.size, appSettings.setupSeen())) {
                        setupPending = true
                    }
                }
            }
        }

        // Phase 9, item 1 — long-press a tile pins or unpins the
        // game. The game is selected too, so the big portrait shows
        // the pin at once; the library reload re-sorts it to the
        // top (or back to its name position).
        fun togglePin(entry: GameEntry) {
            selectedMainFile = entry.mainFile
            lifecycleScope.launch(Dispatchers.IO) {
                library.setPinned(entry, !entry.pinned)
                withContext(Dispatchers.Main) {
                    refreshLibrary()
                    status = if (entry.pinned) {
                        "Unpinned ${entry.effectiveName}."
                    } else {
                        "Pinned ${entry.effectiveName}."
                    }
                }
            }
        }

        LaunchedEffect(Unit) { refreshLibrary() }

        // v1.2.0 (bug fix) — RetroAchievements: log in with the
        // stored session token on launch, so closing the app does
        // not log the user out. Before this, init only ran when a
        // game started (and from the Settings login button,
        // without the auto-login), so a fresh launch always showed
        // "Not logged in." even though the username and token were
        // stored — the user had to retype their password every
        // time. init is idempotent, so the later startGame call
        // is a no-op, and a logged-out user simply stays logged
        // out (no stored login, no auto-login, no error).
        LaunchedEffect(Unit) { RaClient.init(appSettings) }

        // Phase 9, item 10 — automatic update check when the
        // library is entered (throttled to once an hour inside
        // checkAsync; the check itself is one small API call, and
        // nothing downloads until the user taps Download).
        LaunchedEffect(screen) {
            if (screen == Screen.Library) {
                UpdateChecker.checkAsync(appSettings, BuildConfig.VERSION_NAME)
            }
        }

        // Cover art: the filename index is fetched once (Wi-Fi
        // only, cached on the device), then one small image per
        // game — only when its tile is shown, only on Wi-Fi, only
        // once per game. Any failure leaves the typographic tile;
        // nothing here blocks the library or shows an error.
        //
        // Two effects, on purpose. The index load sets `artIndex`,
        // which is a key of the art effect below — if the index
        // were loaded inside that effect, setting the key would
        // restart it and cancel the art fetches it had just
        // launched, so every game would be fetched twice per
        // launch (and, on a slow index load, the fetch could be
        // cancelled before it ever wrote the cache). Loading the
        // index in its own effect means the art effect only ever
        // restarts when the index actually arrives, at which
        // point it has launched nothing to cancel.
        LaunchedEffect(games) {
            if (artIndex == null && CoverArt.isWifi(context)) {
                artIndex = withContext(Dispatchers.IO) { coverArt.loadIndex(isWifi = true) }
            }
        }
        LaunchedEffect(games, artIndex) {
            // The index may still be loading (or unavailable);
            // cached art does not need it, so the effect runs
            // regardless and serves the cache first.
            val index = artIndex
            val pending = games.filter { it.mainFile !in covers && it.mainFile !in artTried }
            if (pending.isEmpty()) return@LaunchedEffect
            for (game in pending) {
                launch(Dispatchers.IO) {
                    // The disk cache comes FIRST, before the index
                    // or the network: a game whose art was fetched
                    // once is served from filesDir/art/ on every
                    // later launch with no network call at all —
                    // even if the index is unavailable or the
                    // match would fail. Only a game with no cached
                    // art resolves its filename and fetches, and
                    // only when the index is available.
                    val bitmap = coverArt.cachedBitmap(game) ?: run {
                        val artFile = index?.let { coverArt.resolveArtFile(game, gamesDir, it) }
                        if (artFile != null) coverArt.fetchBitmap(artFile, game) else null
                    }
                    withContext(Dispatchers.Main) {
                        if (bitmap != null) covers = covers + (game.mainFile to bitmap)
                        // Only mark tried when a bitmap was found
                        // or the index was available (a fetch was
                        // attempted). A game with no cache and no
                        // index yet is left untried, so it is
                        // retried once the index arrives.
                        if (bitmap != null || index != null) artTried = artTried + game.mainFile
                    }
                }
            }
        }

        fun pushMask() {
            // The shared input point: the touch mask and the gamepad
            // mask are ORed here, and the turbo pulse is applied to
            // the result — so one wiring covers both. The phase comes
            // from the wall clock inside TurboState, so a late tick
            // cannot drift the rate.
            val raw = InputMask.build(
                up = held.up, down = held.down, left = held.left, right = held.right,
                a = held.a, b = held.b, c = held.c, d = held.d,
                start = held.start, coin = held.coin,
            ) or padMask or
                // Phase 9, item 9 — while the UniBIOS menu injection
                // is armed, START+SELECT rides along: the UniBIOS
                // opens its in-game menu on that combo. START and
                // SELECT are not turbo bits, so the pulse below
                // passes them through unchanged.
                if (UniBiosMenu.holdActive(uniBiosHoldUntil, SystemClock.uptimeMillis())) {
                    UniBiosMenu.comboMask()
                } else {
                    0
                }
            session?.setInput(turboState.apply(raw, turboMask, SystemClock.uptimeMillis()))
            // 2.0, feature 4 — the input display records the raw
            // mask here, at the shared input point (what the player
            // is holding, before the turbo pulse — the strip shows
            // the hold, not the autofire flicker). Only a change
            // adds an event, so the state below only updates on a
            // real input change, and the strip only recomposes then.
            if (inputHistory.record(raw, SystemClock.uptimeMillis())) {
                inputHistoryEvents = inputHistory.snapshot()
            }
            // 2.0, feature 6 — the Arcade Manager counts the same
            // raw mask's transitions (presses, not time held):
            // each new direction actuation and each button press
            // adds one to the session's counts, persisted on pause
            // and quit.
            arcadeSessionCounts = ArcadeStats.recordTransition(lastArcadeMask, raw, arcadeSessionCounts)
            lastArcadeMask = raw
        }

        /**
         * v1.5, feature 1 — push the current speed into the
         * session: the on-screen hold buttons (ffHeld/slowHeld)
         * OR the gamepad stick clicks (padFfHeld/padSlowHeld),
         * at the speeds from Settings. Called whenever a hold
         * starts or ends, and when the game pauses or quits (the
         * holds are cleared there, so the session returns to
         * normal speed). A null session (no game running) is a
         * no-op — the flags still reset, so a fresh game starts
         * at normal speed.
         */
        fun pushSpeed() {
            session?.setSpeed(
                SpeedControl.factor(
                    ffHeld || padFfHeld,
                    slowHeld || padSlowHeld,
                    ffSpeed,
                    slowOn,
                ),
            )
        }

        // Turbo/autofire (Phase 9, item 6): while a turbo button is
        // physically held, re-push the mask every half-period so the
        // pulse toggles. Keyed on turboActive: the loop starts when
        // a turbo button goes down and cancels the moment it is
        // released (or the game pauses, or edit mode opens), so the
        // pulse can never outlive the hold. The release itself
        // already pushes the released mask, so the last push is
        // always all-off.
        val turboActive = screen == Screen.Playing && !paused && !editControls &&
            turboMask != 0 &&
            (InputMask.build(a = held.a, b = held.b, c = held.c, d = held.d) or padMask) and
                turboMask != 0
        LaunchedEffect(turboActive) {
            if (!turboActive) return@LaunchedEffect
            while (true) {
                delay(TurboInput.HALF_PERIOD_MS)
                pushMask()
            }
        }

        // Phase 9, item 9 — release the injected UniBIOS menu
        // combo when its hold expires, so the combo can never
        // stick: re-push the mask (now without START+SELECT) once
        // the deadline passes. Keyed on the deadline, so arming a
        // new injection restarts the timer.
        LaunchedEffect(uniBiosHoldUntil) {
            val until = uniBiosHoldUntil
            if (until <= 0L) return@LaunchedEffect
            val remaining = until - SystemClock.uptimeMillis()
            if (remaining > 0) delay(remaining)
            pushMask()
        }

        // Leaving the boot splash: a fresh install that was offered
        // the setup screen goes there instead of straight to the
        // library.
        fun goFromBoot() {
            screen = if (setupPending) Screen.Setup else Screen.Library
        }

        // Leaving the setup screen (SKIP, back, or picking a game
        // from row 3): the screen has been seen, so it is never
        // offered again on its own — it stays reachable from
        // Settings as the Setup guide.
        fun leaveSetup() {
            appSettings.setSetupSeen()
            setupPending = false
            screen = Screen.Library
        }

        fun pauseGame() {
            if (session == null || paused) return
            inputActive = false
            // Phase 9, item 9 — a pause cancels a pending UniBIOS
            // menu injection before the released mask is pushed.
            uniBiosHoldUntil = 0L
            // Drop any shoulder buttons held for a quick-save/load
            // combo, so one held across the pause cannot fire a
            // combo the moment the game resumes.
            padQuickHeld.clear()
            // v1.5, feature 1 — a pause drops the speed holds
            // too, so a fast-forward or slow-motion hold cannot
            // survive into the resume: the session returns to
            // normal speed, and a new hold starts it again.
            padFfHeld = false
            padSlowHeld = false
            ffHeld = false
            slowHeld = false
            pushSpeed()
            // A pause releases every held input: the pause overlay
            // swallows touches, so a finger lifted while paused
            // would never reach its button's release — and a turbo
            // button left "held" would keep firing after resume.
            // Clearing the held state here (and the turbo phase
            // with it) means a resume always starts from nothing
            // held, and the core sees all buttons released before
            // the pause lands.
            held = Held()
            turboState.releaseAll()
            pushMask()
            session?.pause()
            paused = true
            pauseStatus = ""
            // 2.0, feature 1 — playtime: the pause ends the
            // current segment, and the total is persisted now,
            // so a kill mid-pause loses nothing (a kill mid-game
            // loses only the running segment). Serialized through
            // playtimeMutex so a later quit-persist cannot be
            // overwritten by this one.
            val playedGame = playingGame
            val playedTotal = playtimeTracker?.let {
                it.pause(System.currentTimeMillis())
                it.totalMillis
            }
            if (playedGame != null && playedTotal != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    playtimeMutex.withLock {
                        library.setTotalPlayMillis(playedGame, playedTotal)
                    }
                }
            }
            // 2.0, feature 6 — the pause also persists the
            // session's arcade counts, so a kill mid-pause loses
            // nothing.
            persistArcadeStats()
            // Phase 9, item 7 — a pause always shows the bar, so
            // SAVE / LOAD stay reachable even when it had hidden
            // (e.g. paused from the gamepad while hidden).
            topBar.onPaused()
            syncTopBar()
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
            // 2.0, feature 1 — playtime: a new segment starts.
            playtimeTracker?.resume(System.currentTimeMillis())
            inputActive = true
            // Phase 9, item 7 — a resume shows the bar, then hides
            // it again after the start delay.
            topBar.onResumed()
            syncTopBar()
        }

        /**
         * Phase 9, item 9 — open the Universe BIOS in-game menu.
         * The UniBIOS watches the input ports itself, so the app
         * delivers its documented combo (START+SELECT) by resuming
         * the game with the combo injected into the input mask for
         * one hold window (see UniBiosMenu): the BIOS opens its
         * menu within a frame or two, and the release timer drops
         * the combo again. Only reachable from the pause menu when
         * the game booted in Universe BIOS mode — under any other
         * system there is no UniBIOS to open, so the button is not
         * shown at all (the guard below is for any future caller).
         */
        fun openUniBiosMenu() {
            val game = playingGame ?: return
            if (!UniBiosMenu.isUniBiosMode(game.effectiveMode)) {
                pauseStatus = "Start this game in Universe BIOS mode to open its menu."
                return
            }
            resumeGame()
            // A quick save/load still owns the paused session:
            // resumeGame() left the game paused, so arm nothing
            // and let the user tap again once it finishes.
            if (paused) return
            uniBiosHoldUntil = SystemClock.uptimeMillis() + UniBiosMenu.HOLD_MS
            pushMask()
        }

        /**
         * The save-state file for a game and slot, in the saves
         * folder. Slot 1 is the legacy `<mainFile>.state` file,
         * shared by the pause menu and quick save/load; slots 2
         * and 3 are `<mainFile>.state2` / `.state3` (see
         * QuickSave). The one-argument form is the quick path's
         * slot 1.
         */
        fun stateFileFor(entry: GameEntry, slot: Int = QuickSave.QUICK_SLOT): File =
            QuickSave.stateFile(File(context.filesDir, "saves"), entry.mainFile, slot)

        fun raStateFileFor(entry: GameEntry, slot: Int = QuickSave.QUICK_SLOT): File =
            QuickSave.raStateFile(File(context.filesDir, "saves"), entry.mainFile, slot)

        /**
         * Phase 10 — RetroAchievements: write the RA progress to
         * the save state's sidecar (or delete a stale one when RA
         * is not tracking this game, so a state never carries
         * progress from an older session). Only call while the
         * emu thread is paused, like the state snapshot itself.
         */
        fun writeRaSidecar(entry: GameEntry, slot: Int) {
            val sidecar = raStateFileFor(entry, slot)
            try {
                val bytes = RaClient.serializeProgress()
                if (bytes != null) {
                    sidecar.parentFile?.mkdirs()
                    sidecar.writeBytes(bytes)
                } else if (sidecar.isFile) {
                    sidecar.delete()
                }
            } catch (_: Exception) {
                // The state itself is already saved; a failed
                // sidecar must not fail the save.
            }
        }

        /**
         * Phase 10 — RetroAchievements: restore the RA progress
         * from a save state's sidecar (or reset it when the state
         * has none, so the progress matches the rewound game).
         * Only call after a successful unserialize, while paused.
         */
        fun readRaSidecar(entry: GameEntry, slot: Int) {
            if (!RaClient.gameLoaded) return
            val sidecar = raStateFileFor(entry, slot)
            val bytes = try {
                if (sidecar.isFile) sidecar.readBytes() else null
            } catch (_: Exception) {
                null
            }
            if (bytes != null) RaClient.deserializeProgress(bytes)
            else RaClient.resetProgress()
        }

        /**
         * v1.5, feature 2 — write the thumbnail for a save-state
         * slot: the last frame, downsampled to 160px wide and
         * stored as a PNG alongside the state file. Best effort:
         * a failed thumbnail never fails the save, and a save
         * before the first frame (no frame yet) simply has none.
         * Only call while the session is paused, like the state
         * snapshot itself — then the last frame is stable.
         */
        fun writeThumbnail(entry: GameEntry, slot: Int) {
            val frame = lastFrame ?: return
            if (frame.width <= 0 || frame.height <= 0) return
            try {
                val thumb = QuickSave.thumbnailFile(
                    File(context.filesDir, "saves"),
                    entry.mainFile,
                    slot,
                )
                thumb.parentFile?.mkdirs()
                val scaled = Bitmap.createScaledBitmap(
                    frame,
                    QuickSave.THUMBNAIL_WIDTH_PX,
                    (QuickSave.THUMBNAIL_WIDTH_PX * frame.height) / frame.width,
                    true,
                )
                try {
                    thumb.outputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } finally {
                    // createScaledBitmap may return the source
                    // when no scaling is needed — never recycle
                    // the frame the session still owns.
                    if (scaled !== frame) scaled.recycle()
                }
            } catch (_: Exception) {
                // The state itself is already saved; a failed
                // thumbnail must not fail the save.
            }
        }

        fun saveState() {
            val game = playingGame ?: return
            val s = session ?: return
            val bytes = s.serializeState()
            if (bytes == null) {
                pauseStatus = "Could not save right now."
                return
            }
            try {
                val file = stateFileFor(game, saveSlot)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                writeRaSidecar(game, saveSlot)
                writeThumbnail(game, saveSlot)
                pauseStatus = "Saved to slot $saveSlot."
            } catch (e: Exception) {
                pauseStatus = "Could not save: ${e.message}"
            }
        }

        fun loadState() {
            val game = playingGame ?: return
            val s = session ?: return
            val file = stateFileFor(game, saveSlot)
            if (!file.isFile) {
                pauseStatus = "No save in slot $saveSlot yet."
                return
            }
            val bytes = try {
                file.readBytes()
            } catch (e: Exception) {
                pauseStatus = "Could not read the save: ${e.message}"
                return
            }
            if (s.unserializeState(bytes)) {
                readRaSidecar(game, saveSlot)
                pauseStatus = "Loaded from slot $saveSlot."
                resumeGame()
            } else {
                pauseStatus = "That save doesn't match this game setup."
            }
        }

        fun quitToLibrary() {
            inputActive = false
            clearPadState()
            turboState.releaseAll()
            uniBiosHoldUntil = 0L
            // v1.5, feature 2 — auto-save on quit: the game
            // saves itself to slot 1 (the quick-save slot) when
            // he quits, so quick load brings him back to where
            // he left off; slots 2 and 3 stay his keepers. On
            // by default, with a Settings switch to turn it
            // off. Quit is only reached from the pause menu (or
            // the second back press from it), so the session is
            // paused already; pause first if it somehow is not,
            // so the serialize is safe. A failed auto-save only
            // logs — the quit must never be blocked.
            if (appSettings.autoSaveOnQuit()) {
                val game = playingGame
                val s = session
                if (game != null && s != null) {
                    try {
                        if (!paused) pauseGame()
                        val bytes = s.serializeState()
                        if (bytes != null) {
                            val file = stateFileFor(game, QuickSave.QUICK_SLOT)
                            file.parentFile?.mkdirs()
                            file.writeBytes(bytes)
                            writeRaSidecar(game, QuickSave.QUICK_SLOT)
                            writeThumbnail(game, QuickSave.QUICK_SLOT)
                        }
                    } catch (_: Exception) {
                        // The quit goes ahead regardless.
                    }
                }
            }
            // 2.0, feature 1 — playtime: end the segment and
            // persist the total now. If the game was paused, the
            // pause above already persisted, and stopping an
            // already-paused tracker is a no-op, so this never
            // double-counts. Serialized through playtimeMutex.
            val playedGame = playingGame
            val playedTotal = playtimeTracker?.let {
                it.stop(System.currentTimeMillis())
                it.totalMillis
            }
            if (playedGame != null && playedTotal != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    playtimeMutex.withLock {
                        library.setTotalPlayMillis(playedGame, playedTotal)
                    }
                }
            }
            // 2.0, feature 6 — the quit also persists the
            // session's arcade counts. If the game was paused, the
            // pause already persisted and the session is empty, so
            // this is a no-op and never double-counts.
            persistArcadeStats()
            playtimeTracker = null
            session?.stop()
            session = null
            held = Held()
            // 2.0, feature 4 — the input display clears with the
            // game, so a new game starts with an empty strip.
            inputHistory.clear()
            inputHistoryEvents = emptyList()
            playingGame = null
            paused = false
            pauseStatus = ""
            // Phase 10 — RetroAchievements: the game is gone, so
            // the per-game RA state goes with it (the login stays).
            RaClient.onGameQuit()
            // Phase 9, item 7 — reset the bar to shown for the next game.
            topBar.onQuit()
            syncTopBar()
            screen = Screen.Library
        }

        /**
         * Quick save/load: save and load the per-game state slot
         * instantly, without opening the pause menu. The slot is
         * slot 1 — the SAME file the pause menu uses when slot 1
         * is picked (stateFileFor's default) — so the two paths
         * always see the same state on that slot; slots 2 and 3
         * belong to the pause menu's slot picker only.
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
                            // Phase 10 — RetroAchievements: the
                            // sidecar travels with the state.
                            writeRaSidecar(game, QuickSave.QUICK_SLOT)
                            // v1.5, feature 2 — the thumbnail
                            // travels with it too.
                            writeThumbnail(game, QuickSave.QUICK_SLOT)
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
                    // Phase 10 — RetroAchievements: the sidecar
                    // rewinds the achievements with the game.
                    if (ok) readRaSidecar(game, QuickSave.QUICK_SLOT)
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
            // system types only at retro_init. The per-game core
            // overrides (Phase 9, item 8) go on top of the global
            // values, so a game starts with its own region, palette,
            // and the rest.
            settings.applyAll()
            settings.applyMode(entry)
            perGameSettings.applyCoreOverrides(entry)
            val saveDir = File(context.filesDir, "saves").apply { mkdirs() }
            status = "Starting ${entry.effectiveName}…"
            session = PlayerSession(
                systemDir = biosDir.absolutePath,
                saveDir = saveDir.absolutePath,
                gamePath = entry.mainPath(gamesDir).absolutePath,
                onFrame = { bitmap ->
                    // v1.5, feature 2 — keep the last frame so a
                    // save can capture a thumbnail of it.
                    lastFrame = bitmap
                    gameGlView?.setFrame(bitmap)
                },
                onError = { message ->
                    inputActive = false
                    status = message
                    session = null
                    playingGame = null
                    // 2.0, feature 1 — the game failed to load,
                    // so its tracker is discarded, not persisted.
                    playtimeTracker = null
                    screen = Screen.Library
                },
                // Phase 10 — run-ahead for this game: the per-game
                // override wins, otherwise the global setting.
                runaheadFrames = perGameSettings.resolveRunahead(
                    entry.mainFile, appSettings.runaheadFrames(),
                ),
            )
            playingGame = entry
            // A new game starts on slot 1 — the legacy slot the
            // quick path uses — whatever slot the last game left
            // the pause menu on.
            saveSlot = QuickSave.QUICK_SLOT
            // v1.5, feature 2 — no frame yet: a save before the
            // first frame must not picture the previous game.
            lastFrame = null
            // v1.5, feature 1 — a fresh game starts at normal
            // speed: no hold survives from the last game.
            ffHeld = false
            slowHeld = false
            screen = Screen.Playing
            session?.start()
            inputActive = true
            // 2.0, feature 1 — playtime: a fresh tracker for this
            // game, seeded with its stored total, started now
            // (game start). Pause ends the segment, resume starts
            // a new one, quit stops it.
            playtimeTracker = PlaytimeTracker(entry.totalPlayMillis).also {
                it.start(System.currentTimeMillis())
            }
            // 2.0, feature 4 — a new game starts with an empty
            // input strip.
            inputHistory.clear()
            inputHistoryEvents = emptyList()
            // 2.0, feature 6 — a new game starts with empty
            // arcade session counts and a neutral last mask.
            arcadeSessionCounts = ArcadeCounts()
            lastArcadeMask = 0
            // v1.5, feature 4 — cheats: this game's enabled codes
            // go to the session now, so they are written after
            // every displayed frame from the first one. A game
            // with no codes (or none enabled) sends an empty
            // list, which is a no-op in the session.
            session?.setCheats(
                cheatStore.load(entry.mainFile)
                    .filter { it.enabled }
                    .map { it.code },
            )
            // v1.5, feature 3 — the game was started: stamp it
            // played now, so the Recently played sort orders it
            // first when he comes back to the library (which
            // reloads and picks the new time up).
            lifecycleScope.launch(Dispatchers.IO) {
                library.setLastPlayed(entry)
            }
            // Phase 10 — RetroAchievements: initialise the client
            // (and log in with the stored credentials) and load
            // this game's achievements. The load is async; a game
            // whose hash is not recognised (or a disc, whose hash
            // needs a CD reader the app does not yet supply) simply
            // leaves RA inactive for it.
            if (RaClient.init(appSettings)) {
                RaClient.loadGame(entry.mainPath(gamesDir).absolutePath)
            }
            // Phase 9, item 7 — show the bar, then hide it after
            // the start delay (long enough to see it first).
            topBar.onGameStarted()
            syncTopBar()
        }

        // Starting a game from the setup screen's PLAY row: the
        // setup is done, so it is marked seen before the game
        // starts. (Declared after startGame: local functions must
        // be declared before use.)
        fun playFromSetup() {
            val game = selectedGame ?: return
            appSettings.setSetupSeen()
            setupPending = false
            startGame(game)
        }

        // Mirror the gamepad mapping into the Activity field, so
        // the key handlers (which run outside composition) read
        // the current mapping (v1.3.0, part 1).
        LaunchedEffect(gamepadMapping) {
            gamepadMappingField = gamepadMapping
        }

        // Remapping capture (v1.3.0, part 1): while the remap
        // screen is waiting for a button, the Activity's onKeyDown
        // hands the press here. A remappable button is assigned
        // (swapping with the input that had it) and capturing ends;
        // a shoulder or the D-pad is consumed with a hint and
        // capturing continues.
        DisposableEffect(remapCapturing) {
            val capturing = remapCapturing
            if (capturing != null) {
                captureKeyHandler = { keyCode ->
                    if (!GamepadMapping.isRemappable(keyCode)) {
                        remapHint = when {
                            GamepadInput.isQuickComboButton(keyCode) ->
                                "The shoulders stay as quick save and quick load — pick a face button."
                            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ->
                                "The D-pad steers — pick a face button."
                            else -> "That button is not one of the six — pick a face button."
                        }
                        true
                    } else {
                        val newMapping = GamepadMapping.assign(gamepadMapping, capturing, keyCode)
                        controlSettings.setGamepadMapping(newMapping)
                        gamepadMapping = newMapping
                        remapCapturing = null
                        remapHint = ""
                        true
                    }
                }
            }
            onDispose { captureKeyHandler = null }
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
            // v1.5, feature 1 — a speed button (L3/R3) went down
            // or up: recompute the session speed from the holds.
            onPadSpeed = { pushSpeed() }
            // App backgrounded: release every held input, like a
            // pause. The touch stream is cancelled by the system,
            // but a gamepad key-up may never arrive — without this
            // a turbo button held when the app backgrounds would
            // keep firing while the app is away.
            onAppBackgrounded = {
                held = Held()
                clearPadState()
                turboState.releaseAll()
                pushMask()
            }
            onDispose {
                onPadMask = null
                onPauseHotkey = null
                onQuickSaveHotkey = null
                onQuickLoadHotkey = null
                onPadSpeed = null
                onAppBackgrounded = null
            }
        }

        val gamePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            // Audit fix F4: a .chd can be 300 MB+ and the copy runs
            // silently — say something is happening, and that the
            // file is copied into the app (storage is duplicated:
            // the original stays where it was).
            status = "Copying the game… The file is copied into the app."
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
                                status = "Added ${entry.effectiveName}."
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
            // Audit fix F4: same — a folder of games copies
            // silently, and a big .chd takes a while.
            status = "Copying games… Files are copied into the app."
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
                    // Audit fix F2: copy ONLY the four known system
                    // files — a folder holding other zips must not
                    // have them copied into app-private storage too.
                    val zips = tree?.listFiles()
                        ?.filter { it.isFile && isKnownBiosFile(it.name ?: "") }
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
                            // Audit fix (d): the System files card
                            // already shows the ready count, so the
                            // status line must not repeat it.
                            "BIOS folder set."
                        } else {
                            // Audit fix F2: name the four files that
                            // were expected, so the user knows what
                            // the folder was supposed to hold.
                            "No system files in that folder. Pick the folder holding aes.zip, neogeo.zip, neocd.zip and neocdz.zip."
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
        // from (the library, or the game). A game's own settings
        // screen (Phase 9, item 8) returns to the library.
        BackHandler(enabled = screen == Screen.Playing || screen == Screen.Settings || screen == Screen.Licences || screen == Screen.Setup || screen == Screen.GameSettings || screen == Screen.Achievements || screen == Screen.Leaderboards || screen == Screen.GamepadRemap || screen == Screen.Cheats || screen == Screen.MemoryCard || screen == Screen.ArcadeManager) {
            when (screen) {
                Screen.Playing -> when {
                    editControls -> exitEditMode()
                    paused -> quitToLibrary()
                    else -> pauseGame()
                }
                Screen.Settings -> screen = settingsFrom
                Screen.Licences -> screen = Screen.Settings
                Screen.Setup -> leaveSetup()
                Screen.Achievements -> screen = Screen.Playing
                Screen.Leaderboards -> screen = Screen.Playing
                Screen.Cheats -> screen = Screen.Playing
                Screen.MemoryCard -> screen = Screen.Settings
                Screen.ArcadeManager -> screen = arcadeManagerFrom
                Screen.GamepadRemap -> {
                    remapCapturing = null
                    screen = Screen.Settings
                }
                Screen.GameSettings -> {
                    perGameFor = null
                    screen = Screen.Library
                }
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
                                goFromBoot()
                                true
                            } else {
                                false
                            }
                        }
                        .clickable { goFromBoot() },
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
                            fontFamily = DisplayFontFamily,
                            fontSize = DisplayFontSpec.BOOT_TITLE_SP.sp,
                            fontWeight = FontWeight.Normal,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "PRESS START",
                            color = Color(EraUi.Palette.GOLD),
                            fontFamily = DisplayFontFamily,
                            fontSize = DisplayFontSpec.BOOT_PROMPT_SP.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.alpha(pressStartAlpha),
                        )
                    }
                }
            }

            Screen.Library -> {
                LibraryScreen(
                    library = library,
                    collectionStore = collectionStore,
                    coverArt = coverArt,
                    gamesDir = gamesDir,
                    refreshLibrary = { refreshLibrary() },
                    onAddGame = { gamePicker.launch(arrayOf("*/*")) },
                    onAddFolder = { folderPicker.launch(null) },
                    onPickBios = { biosPicker.launch(null) },
                    startGame = { entry -> startGame(entry) },
                    togglePin = { entry -> togglePin(entry) },
                )
            }

            Screen.Setup -> {
                // First-run SYSTEM SETUP (audit 2): the Neo Geo
                // debug-menu-style onboarding. The rows read their
                // status from the real state, so they never lie;
                // PLAY is only enabled once rows 1-3 are done.
                // Leaving (SKIP, back, or row 3's library trip)
                // marks the screen seen.
                SetupScreen(
                    state = SetupState(
                        biosReady = bios?.readyCount ?: 0,
                        gameCount = games.size,
                        pickedName = selectedGame?.effectiveName,
                    ),
                    onSystemFiles = { biosPicker.launch(null) },
                    onAddGame = { gamePicker.launch(arrayOf("*/*")) },
                    onAddFolder = { folderPicker.launch(null) },
                    onPickGame = { leaveSetup() },
                    onPlay = { playFromSetup() },
                    onSkip = { leaveSetup() },
                )
            }

            Screen.Playing -> {
                PlayingScreen(
                    syncTopBar = { syncTopBar() },
                    exitEditMode = { exitEditMode() },
                    playHaptic = { playHaptic() },
                    playCoinHaptic = { playCoinHaptic() },
                    pushMask = { pushMask() },
                    pushSpeed = { pushSpeed() },
                    quickSave = { quickSave() },
                    quickLoad = { quickLoad() },
                    pauseGame = { pauseGame() },
                    resumeGame = { resumeGame() },
                    openUniBiosMenu = { openUniBiosMenu() },
                    saveState = { saveState() },
                    loadState = { loadState() },
                    quitToLibrary = { quitToLibrary() },
                )
            }

            Screen.Settings -> {
                SettingsScreen()
            }
            Screen.GamepadRemap -> {
                // Gamepad button remapping (v1.3.0, part 1): a
                // capture-and-assign screen. Tap a Neo Geo button,
                // then press a gamepad button to assign it; the two
                // swap, so every input stays reachable. The shoulders
                // stay as quick save/load and the D-pad and stick
                // keep steering, so they are never offered here.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Gamepad button mapping", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Tap a Neo Geo button, then press a gamepad button to assign it. The two swap, so every button stays reachable. The shoulders (L1/R1/L2/R2) stay as quick save and quick load, and the D-pad and stick keep steering.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    for (neoId in GamepadMapping.NEO_IDS) {
                        val capturing = remapCapturing == neoId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    remapCapturing = neoId
                                    remapHint = "Press a gamepad button for Neo $neoId…"
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Neo $neoId",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (capturing) Color(EraUi.Palette.GOLD)
                                else Color.Unspecified,
                            )
                            Text(
                                if (capturing) "Press a button…"
                                else GamepadMapping.displayName(
                                    GamepadMapping.keyCodeFor(neoId, gamepadMapping),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (capturing) Color(EraUi.Palette.GOLD)
                                else Color.Unspecified,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (remapHint.isNotEmpty()) {
                        Text(remapHint, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            controlSettings.resetGamepadMapping()
                            gamepadMapping = controlSettings.gamepadMapping()
                            remapCapturing = null
                            remapHint = ""
                        }) { Text("Reset to defaults") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            remapCapturing = null
                            screen = Screen.Settings
                        }) { Text("Done") }
                    }
                }
            }
            Screen.Achievements -> {
                // Phase 10 — RetroAchievements: the loaded game's
                // achievements. Reached from the pause menu; the
                // game stays paused underneath (the session is not
                // resumed until the player goes back).
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Text(
                        RaClient.gameTitle ?: "Achievements",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (RaClient.gameLoaded) {
                            RaAchievements.summary(RaClient.achievements)
                        } else {
                            "No achievements for this game."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (RaClient.gameLoaded) {
                        for (achievement in RaClient.achievements) {
                            if (achievement.state == RaAchievements.STATE_INACTIVE) continue
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        achievement.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        achievement.description,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (!achievement.measuredProgress.isNullOrEmpty()) {
                                        Text(
                                            achievement.measuredProgress,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        if (achievement.isUnlocked) "Unlocked" else "${achievement.points} pts",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (achievement.isUnlocked) {
                                            Color(EraUi.Palette.GOLD)
                                        } else {
                                            Color(EraUi.Palette.TEXT)
                                        },
                                        fontWeight = if (achievement.isUnlocked) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { screen = Screen.Playing }) { Text("Back") }
                }
            }
            Screen.Leaderboards -> {
                // v1.2.0 (item 2) — the loaded game's
                // leaderboards. Reached from the pause menu; the
                // game stays paused underneath (the session is not
                // resumed until the player goes back). Scores are
                // submitted automatically by rc_client while the
                // game runs — this screen only shows the boards and
                // their entries. The rich presence line is what
                // the RetroAchievements site shows for the game
                // right now.
                var selectedBoardId by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                    val first = RaClient.leaderboards.firstOrNull()
                    if (first != null) {
                        selectedBoardId = first.id
                        RaClient.fetchLeaderboardEntries(first.id, false)
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Text(
                        RaClient.gameTitle ?: "Leaderboards",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (RaClient.richPresence.isNotEmpty()) {
                        // The message is already a complete
                        // sentence ("Playing <title>", or the
                        // game's own presence string), so it is
                        // shown as-is.
                        Text(
                            RaClient.richPresence,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        if (RaClient.gameLoaded) {
                            "${RaClient.leaderboards.size} leaderboard${if (RaClient.leaderboards.size == 1) "" else "s"}"
                        } else {
                            "No leaderboards for this game."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (RaClient.gameLoaded) {
                        for (board in RaClient.leaderboards) {
                            val selected = board.id == selectedBoardId
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedBoardId = board.id
                                        RaClient.fetchLeaderboardEntries(board.id, false)
                                    }
                                    .padding(vertical = 6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        board.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) Color(EraUi.Palette.GOLD) else Color(EraUi.Palette.TEXT),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        RaLeaderboards.formatName(board.format),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (board.description.isNotEmpty()) {
                                    Text(
                                        board.description,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                // The live attempt on this board, if
                                // one is running.
                                if (board.id == RaClient.activeTrackerBoardId && RaClient.activeTrackerDisplay.isNotEmpty()) {
                                    Text(
                                        "Attempt: ${RaClient.activeTrackerDisplay}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(EraUi.Palette.GOLD),
                                    )
                                } else if (board.trackerValue.isNotEmpty()) {
                                    Text(
                                        "Attempt: ${board.trackerValue}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(EraUi.Palette.GOLD),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // The entries for the selected board.
                        val entries = RaClient.leaderboardEntries
                        if (RaClient.leaderboardEntriesLoading) {
                            Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                        } else if (RaClient.leaderboardEntriesError != null) {
                            Text(
                                RaClient.leaderboardEntriesError ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else if (entries != null && selectedBoardId != 0 &&
                            RaClient.leaderboardEntriesBoardId == selectedBoardId) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { RaClient.fetchLeaderboardEntries(selectedBoardId, false) },
                                    enabled = RaClient.leaderboardEntriesAroundUser,
                                ) { Text("Top 10") }
                                Button(
                                    onClick = { RaClient.fetchLeaderboardEntries(selectedBoardId, true) },
                                    enabled = !RaClient.leaderboardEntriesAroundUser,
                                ) { Text("Near me") }
                            }
                            Spacer(Modifier.height(8.dp))
                            for (entry in entries.entries) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "#${entry.rank}  ${entry.user}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (entry.user == RaClient.username) FontWeight.Bold else FontWeight.Normal,
                                        color = if (entry.user == RaClient.username) Color(EraUi.Palette.GOLD) else Color(EraUi.Palette.TEXT),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        entry.display,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${entries.totalEntries} entries" +
                                    if (entries.userIndex >= 0) " — you are #${entries.userIndex + 1}" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { screen = Screen.Playing }) { Text("Back") }
                }
            }
            Screen.Cheats -> {
                CheatsScreen()
            }
            Screen.GameSettings -> {
                // Phase 9, item 8 — this game's own settings: the
                // per-game overrides (region, palette, aspect
                // ratio, sprites per line, overclocking, free play,
                // CRT shader, curvature, left control). Anything
                // left on "Use global" follows Settings; Reset
                // drops every override for this game, so it follows
                // the global settings again. Takes effect the next
                // time this game starts.
                val game = perGameFor
                if (game != null) {
                    key(perGameTick) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .verticalScroll(rememberScrollState())
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Settings — ${game.effectiveName}",
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Text(
                                "Only this game. Anything left on \"Use global\" follows Settings. Takes effect the next time this game starts.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                if (perGameSettings.hasProfile(game.mainFile)) {
                                    "This game has its own settings."
                                } else {
                                    "Using the global settings."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(16.dp))
                            for (coreKey in PerGameSettings.CORE_KEYS) {
                                val opt = CoreOptions.byKey(coreKey) ?: continue
                                ControlDropdownRow(
                                    opt.label,
                                    listOf("global" to "Use global") + opt.values,
                                    perGameSettings.coreOverride(game.mainFile, coreKey) ?: "global",
                                ) { value ->
                                    perGameSettings.setCoreOverride(
                                        game.mainFile, coreKey,
                                        if (value == "global") null else value,
                                    )
                                    perGameTick++
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("CRT display", style = MaterialTheme.typography.titleSmall)
                            ControlDropdownRow(
                                "CRT shader",
                                listOf("global" to "Use global", "on" to "On", "off" to "Off"),
                                perGameSettings.shaderOverride(game.mainFile)?.let { if (it) "on" else "off" } ?: "global",
                            ) { value ->
                                perGameSettings.setShaderOverride(
                                    game.mainFile,
                                    when (value) {
                                        "on" -> true
                                        "off" -> false
                                        else -> null
                                    },
                                )
                                perGameTick++
                            }
                            ControlDropdownRow(
                                "Screen curvature",
                                listOf("global" to "Use global", "on" to "On", "off" to "Off"),
                                perGameSettings.curvatureOverride(game.mainFile)?.let { if (it) "on" else "off" } ?: "global",
                            ) { value ->
                                perGameSettings.setCurvatureOverride(
                                    game.mainFile,
                                    when (value) {
                                        "on" -> true
                                        "off" -> false
                                        else -> null
                                    },
                                )
                                perGameTick++
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("On-screen controls", style = MaterialTheme.typography.titleSmall)
                            ControlDropdownRow(
                                "Left control",
                                listOf("global" to "Use global", "stick" to "Joystick", "dpad" to "D-pad"),
                                perGameSettings.leftControlOverride(game.mainFile)?.name?.lowercase() ?: "global",
                            ) { value ->
                                perGameSettings.setLeftControlOverride(
                                    game.mainFile,
                                    when (value) {
                                        "stick" -> LeftControl.STICK
                                        "dpad" -> LeftControl.DPAD
                                        else -> null
                                    },
                                )
                                perGameTick++
                            }
                            Text(
                                "The D-pad is eight-way, like the stick — its corners are diagonals. It never switches to four-way.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("Latency", style = MaterialTheme.typography.titleSmall)
                            ControlDropdownRow(
                                "Run-ahead",
                                listOf("global" to "Use global") + Runahead.OPTIONS,
                                perGameSettings.runaheadOverride(game.mainFile)?.let { Runahead.store(it) } ?: "global",
                            ) { value ->
                                perGameSettings.setRunaheadOverride(
                                    game.mainFile,
                                    if (value == "global") null else Runahead.parse(value),
                                )
                                perGameTick++
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = {
                                perGameSettings.clear(game.mainFile)
                                perGameTick++
                                status = "${game.effectiveName}: using global settings."
                            }) { Text("Reset to global settings") }
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = {
                                perGameFor = null
                                screen = Screen.Library
                            }) { Text("Back") }
                        }
                    }
                }
            }
            Screen.Licences -> {
                // Phase 8: the full licence texts for every library
                // in the APK, plus the GPL-3.0 frontend note and the
                // SNK disclaimer. Back returns to Settings.
                LicencesScreen(onBack = { screen = Screen.Settings })
            }
            Screen.MemoryCard -> {
                // 2.0, feature 3 — the memory card manager: the
                // saves on the virtual Neo Geo memory card, with
                // delete. A delete re-lists the card, so the screen
                // always shows it as it is. Back returns to Settings.
                MemoryCardScreen(
                    entries = memoryCardEntries,
                    onDeleteFile = { name ->
                        MemoryCard.deleteFile(File(context.filesDir, "saves"), name)
                        refreshMemoryCard()
                    },
                    onDeleteGame = { mainFile ->
                        MemoryCard.deleteGame(File(context.filesDir, "saves"), mainFile)
                        refreshMemoryCard()
                    },
                    onBack = { screen = Screen.Settings },
                )
            }
            Screen.ArcadeManager -> {
                // 2.0, feature 6 — the Arcade Manager: input
                // heatmaps and the virtual revenue / time tracker,
                // the home for the playtime stats. Back returns to
                // where it was opened from (the library, or
                // Settings).
                ArcadeManagerScreen(
                    entries = arcadeEntries,
                    totalPlaytimeMillis = arcadeTotalPlaytime,
                    totalCoins = arcadeTotalCoins,
                    onBack = { screen = arcadeManagerFrom },
                )
            }
        }
    }

    // v1.5 merge — the library screen, split out of HowlingDogUi.
    // After the v1.3.1 split + hoist landed in main, HowlingDogUi
    // was still 266 registers (the v1.5 library overhaul lives in
    // the Library branch, which stayed in HowlingDogUi). Splitting
    // it out drops the method under the 255-register limit. Shared
    // states are Activity-level lazy fields, re-delegated with `by`;
    // the objects arrive as params so their `remember` lifecycle in
    // HowlingDogUi is unchanged. The body below is HowlingDogUi's
    // Library branch verbatim.
    @Composable
    private fun LibraryScreen(
        library: GameLibrary,
        collectionStore: CollectionStore,
        coverArt: CoverArt,
        gamesDir: File,
        refreshLibrary: () -> Unit,
        onAddGame: () -> Unit,
        onAddFolder: () -> Unit,
        onPickBios: () -> Unit,
        startGame: (GameEntry) -> Unit,
        togglePin: (GameEntry) -> Unit,
    ) {
        val context = LocalContext.current
        var screen by screenState
        var settingsFrom by settingsFromState
        // 2.0, feature 6 — where the Arcade Manager's Back returns
        // to (the library, when opened from here).
        var arcadeManagerFrom by arcadeManagerFromState
        var games by gamesState
        var bios by biosState
        var status by statusState
        var timeLeft by timeLeftState
        var searchText by searchTextState
        var librarySort by librarySortState
        var collectionFilter by collectionFilterState
        var collections by collectionsState
        var collectionFor by collectionForState
        var collectionText by collectionTextState
        var selectedMainFile by selectedMainFileState
        var confirmRemove by confirmRemoveState
        var modePickerFor by modePickerForState
        var perGameFor by perGameForState
        var renameFor by renameForState
        var renameText by renameTextState
        var dismissedUpdate by dismissedUpdateState
        var covers by coversState

        // The select grid's picked game is a main-file name,
        // derived fresh from `games`, so it never goes stale
        // when the library reloads.
        val selectedGame =
            games.firstOrNull { it.mainFile == selectedMainFile } ?: games.firstOrNull()
        // The metadata per game (year/maker from the .NEO
        // header, genre/players from the table), read once per
        // library load: the header read is 256 bytes per cart,
        // so it stays on the main thread. The sort and the
        // search both read this map.
        val metadataByGame = remember(games) {
            games.associate { entry ->
                entry.mainFile to LibraryView.metadataFor(
                    entry,
                    coverArt.readIdentity(entry, gamesDir),
                )
            }
        }
        // The whole screen scrolls (a small window or a long
        // library must never push content out of reach).
        // Phase 5 — the fighting-game select grid: black
        // arcade background, chunky type, portrait tiles.
        // Audit fix (a/f): status-bar inset padding, applied
        // outside the scroll, so the header starts below the
        // status bar (targetSdk 36 enforces edge-to-edge) and
        // scrolled content can never slide under it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(EraUi.Palette.BACKGROUND))
                .statusBarsPadding()
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
                    fontFamily = DisplayFontFamily,
                    fontSize = DisplayFontSpec.LIBRARY_TITLE_SP.sp,
                    fontWeight = FontWeight.Normal,
                )
                // Audit fix F5: the countdown is hidden on
                // an empty library — it only means something
                // once there are games to pick from, and on
                // an empty screen "TIME 09" reads like a
                // deadline. (A note under it would be clutter
                // drawing attention to a decorative element.)
                if (games.isNotEmpty()) {
                    Text(
                        "TIME %02d".format(timeLeft),
                        color = if (timeLeft <= 3) Color(EraUi.Palette.WARNING) else Color(EraUi.Palette.TEXT),
                        fontFamily = DisplayFontFamily,
                        fontSize = DisplayFontSpec.COUNTDOWN_SP.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // Phase 9, item 10 — update notice: shown when
            // a newer stable release is known and the user
            // has not dismissed this version. Dismissing
            // remembers the version (persisted), so it is
            // never re-offered; a newer version still
            // shows. The download starts only on an
            // explicit tap, on the current connection.
            val updateAvailable = UpdateChecker.availableVersion
            if (updateAvailable != null && updateAvailable != dismissedUpdate) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color(EraUi.Palette.SURFACE_RAISED),
                            RoundedCornerShape(8.dp),
                        )
                        .border(
                            1.dp,
                            Color(EraUi.Palette.GOLD_MUTED),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(12.dp),
                ) {
                    Text(
                        "Update available — v$updateAvailable",
                        color = Color(EraUi.Palette.GOLD),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    when (val progress = UpdateChecker.downloadProgress) {
                        in 0..100 -> Text(
                            "Downloading… $progress%",
                            color = Color(EraUi.Palette.TEXT),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        -2 -> Text(
                            "The download failed — check your connection and try again.",
                            color = Color(EraUi.Palette.WARNING),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        -3 -> Text(
                            "That download failed its check and was deleted — try again.",
                            color = Color(EraUi.Palette.WARNING),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        -4 -> Text(
                            "Version v$updateAvailable is out, but it has no APK attached yet.",
                            color = Color(EraUi.Palette.TEXT),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> Text(
                            "Downloads only when you tap Download, on your current connection.",
                            color = Color(EraUi.Palette.TEXT_MUTED),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (UpdateChecker.downloadUrl != null &&
                            UpdateChecker.downloadProgress !in 0..100
                        ) {
                            ArcadeButton(text = "Download v$updateAvailable") {
                                UpdateChecker.downloadAndInstall(context)
                            }
                        }
                        ArcadeButton(text = "Not now") {
                            appSettings.setDismissedUpdateVersion(updateAvailable)
                            dismissedUpdate = updateAvailable
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
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
                        "${b.readyCount} of ${b.totalCount} ready",
                        color = Color(EraUi.Palette.TEXT),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    BiosRow("Home console", b.home)
                    BiosRow("Arcade", b.arcade)
                    BiosRow("Neo Geo CD", b.system1994)
                    BiosRow("Neo Geo CDZ", b.system1995)
                }
                Spacer(Modifier.height(8.dp))
                ArcadeButton(text = "Pick BIOS folder") { onPickBios() }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArcadeButton(text = "Add game") { onAddGame() }
                ArcadeButton(text = "Add folder") { onAddFolder() }
                ArcadeButton(text = "Settings") {
                    settingsFrom = Screen.Library
                    screen = Screen.Settings
                }
                // 2.0, feature 6 — the Arcade Manager: input
                // heatmaps and the virtual revenue / time tracker.
                ArcadeButton(text = "Arcade Manager") {
                    arcadeManagerFrom = Screen.Library
                    refreshArcadeManager()
                    screen = Screen.ArcadeManager
                }
            }
            Spacer(Modifier.height(16.dp))
            // v1.5, feature 3 — search, sort, collections.
            // The visible games are the searched, filtered,
            // sorted list; the portrait below keeps showing
            // the picked game even when the filter hides it.
            // Only once there are games to organise.
            val visibleGames: List<GameEntry> = if (games.isEmpty()) {
                emptyList()
            } else {
                var list = LibraryView.filterGames(games, searchText, metadataByGame)
                collectionFilter?.let { wanted ->
                    list = list.filter { collectionStore.collectionFor(it.mainFile) == wanted }
                }
                LibraryView.sortGames(list, librarySort, metadataByGame)
            }
            if (games.isNotEmpty()) {
                // 2.0.2, fix 1 — the Search field, themed with
                // the app and sized so the text fits fully.
                // 2.0.1 fix 7 pinned it to 40dp, the button
                // height, but a 16sp line needs ~24dp plus
                // padding, so only the top half of "Search"
                // showed — and the field was still a plain
                // Material3 default (dark box, thin light
                // border, default text), not the app's era
                // look. Now: maroon field, gold border, gold
                // text in the Neo Geo system font, a placeholder
                // (not a floating label, which the short height
                // clipped), and 48dp — 24dp of text + 12dp
                // padding top and bottom — a single-line field
                // that shows the whole text.
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = DisplayFontFamily,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = Color(EraUi.Palette.TEXT),
                    ),
                    placeholder = {
                        Text(
                            "Search",
                            fontFamily = DisplayFontFamily,
                            fontSize = 16.sp,
                            color = Color(EraUi.Palette.TEXT_MUTED),
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(EraUi.Palette.SURFACE_RAISED),
                        unfocusedContainerColor = Color(EraUi.Palette.SURFACE_RAISED),
                        focusedTextColor = Color(EraUi.Palette.TEXT),
                        unfocusedTextColor = Color(EraUi.Palette.TEXT),
                        cursorColor = Color(EraUi.Palette.GOLD),
                        focusedBorderColor = Color(EraUi.Palette.GOLD),
                        unfocusedBorderColor = Color(EraUi.Palette.GOLD_MUTED),
                        focusedPlaceholderColor = Color(EraUi.Palette.TEXT_MUTED),
                        unfocusedPlaceholderColor = Color(EraUi.Palette.TEXT_MUTED),
                    ),
                    shape = RoundedCornerShape(2.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                )
                Spacer(Modifier.height(8.dp))
                ControlDropdownRow(
                    "Sort",
                    listOf(
                        "NAME" to "Name",
                        "YEAR_NEW" to "Newest",
                        "YEAR_OLD" to "Oldest",
                        "RECENT" to "Recently played",
                        "SYSTEM" to "System",
                    ),
                    librarySort.name,
                ) { librarySort = LibrarySort.valueOf(it) }
                // Collection chips: All, then each collection
                // in use. The row scrolls sideways, so a long
                // list of collections never wraps the screen.
                if (collections.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (label in listOf("All") + collections) {
                            val picked = if (label == "All") {
                                collectionFilter == null
                            } else {
                                collectionFilter == label
                            }
                            Box(
                                modifier = Modifier
                                    .height(28.dp)
                                    .clickable {
                                        collectionFilter = if (label == "All") null else label
                                    }
                                    .background(
                                        if (picked) Color(EraUi.Palette.GOLD)
                                        else Color(EraUi.Palette.SURFACE_RAISED),
                                        RoundedCornerShape(2.dp),
                                    )
                                    .border(
                                        1.dp,
                                        if (picked) Color(EraUi.Palette.GOLD)
                                        else Color(EraUi.Palette.GOLD_MUTED),
                                        RoundedCornerShape(2.dp),
                                    )
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    color = if (picked) Color(EraUi.Palette.ON_GOLD)
                                    else Color(EraUi.Palette.TEXT),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
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
                        onRename = {
                            renameFor = selected
                            renameText = selected.effectiveName
                        },
                        onSettings = {
                            perGameFor = selected
                            screen = Screen.GameSettings
                        },
                        // v1.5, feature 3 — the metadata
                        // line ("1998 · SNK · Fighting · 2
                        // players", unknown parts omitted)
                        // and the collection picker.
                        metadataLine = metadataByGame[selected.mainFile]
                            ?.displayLine()?.takeIf { it.isNotEmpty() },
                        collectionName = collectionStore.collectionFor(selected.mainFile),
                        onCollection = {
                            collectionFor = selected
                            collectionText = ""
                        },
                        cover = covers[selected.mainFile],
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (visibleGames.isEmpty()) {
                    Text(
                        "No games match.",
                        color = Color(EraUi.Palette.TEXT),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                // The grid: plain Rows in the scrolling
                // Column, not a lazy list — the screen
                // itself scrolls, so every tile is composed
                // and reachable. Fine for a v1 library;
                // revisit if libraries grow very large.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val cols = if (maxWidth > 600.dp) 4 else 3
                    Column {
                        for (row in visibleGames.chunked(cols)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (game in row) {
                                    GameTile(
                                        entry = game,
                                        selected = game.mainFile == selected?.mainFile,
                                        onSelect = { selectedMainFile = game.mainFile },
                                        onTogglePin = { togglePin(game) },
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
            }
            Spacer(Modifier.height(16.dp))
            Text(status, color = Color(EraUi.Palette.TEXT), style = MaterialTheme.typography.bodyMedium)
        }
        val toRemove = confirmRemove
        if (toRemove != null) {
            AlertDialog(
                onDismissRequest = { confirmRemove = null },
                title = { Text("Remove ${toRemove.effectiveName}?") },
                text = { Text("This deletes its files from the library.") },
                confirmButton = {
                    TextButton(onClick = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            library.remove(toRemove)
                            withContext(Dispatchers.Main) {
                                confirmRemove = null
                                refreshLibrary()
                                status = "Removed ${toRemove.effectiveName}."
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
                title = { Text("System for ${modeGame.effectiveName}") },
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
                                        status = "${target.effectiveName}: ${modeLabel(mode)}."
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
                                    status = "${target.effectiveName}: default system."
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
        // v1.5, feature 3 — pick a collection for the
        // game, or type a new one. One collection per
        // game; "No collection" takes it out again. Saving
        // an empty name clears it, like the rename dialog.
        val collectionGame = collectionFor
        if (collectionGame != null) {
            val current = collectionStore.collectionFor(collectionGame.mainFile)
            AlertDialog(
                onDismissRequest = { collectionFor = null },
                title = { Text("Collection for ${collectionGame.effectiveName}") },
                text = {
                    Column {
                        for (name in collections) {
                            TextButton(onClick = {
                                val target = collectionGame
                                lifecycleScope.launch(Dispatchers.IO) {
                                    collectionStore.setCollection(target.mainFile, name)
                                    withContext(Dispatchers.Main) {
                                        collectionFor = null
                                        refreshLibrary()
                                        status = "${target.effectiveName} is in $name."
                                    }
                                }
                            }) {
                                Text(name + if (name == current) " ✓" else "")
                            }
                        }
                        TextButton(onClick = {
                            val target = collectionGame
                            lifecycleScope.launch(Dispatchers.IO) {
                                collectionStore.setCollection(target.mainFile, null)
                                withContext(Dispatchers.Main) {
                                    collectionFor = null
                                    refreshLibrary()
                                    status = "${target.effectiveName} has no collection."
                                }
                            }
                        }) { Text("No collection") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = collectionText,
                            onValueChange = { collectionText = it },
                            singleLine = true,
                            label = { Text("New collection…") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val target = collectionGame
                        val typed = collectionText
                        lifecycleScope.launch(Dispatchers.IO) {
                            collectionStore.setCollection(target.mainFile, typed)
                            withContext(Dispatchers.Main) {
                                collectionFor = null
                                collectionText = ""
                                refreshLibrary()
                                status = if (typed.isBlank()) {
                                    "${target.effectiveName} has no collection."
                                } else {
                                    "${target.effectiveName} is in ${typed.trim()}."
                                }
                            }
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { collectionFor = null }) { Text("Cancel") }
                },
            )
        }
        // Phase 9, item 4 — rename the picked game. Save
        // with an empty name clears the custom name, so the
        // game shows its original name again.
        val renameTarget = renameFor
        if (renameTarget != null) {
            AlertDialog(
                onDismissRequest = { renameFor = null },
                title = { Text("Rename ${renameTarget.effectiveName}") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val target = renameTarget
                        val typed = renameText
                        lifecycleScope.launch(Dispatchers.IO) {
                            val updated = library.setCustomName(target, typed)
                            withContext(Dispatchers.Main) {
                                renameFor = null
                                refreshLibrary()
                                status = if (updated.customName == null) {
                                    "Back to ${updated.displayName}."
                                } else {
                                    "Renamed to ${updated.effectiveName}."
                                }
                            }
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { renameFor = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }

    // v1.5, feature 4 — cheats: the current game's codes, reached
    // from the pause menu; the game stays paused underneath (the
    // session is not resumed until the player goes back). A code is
    // 8 hex digits, `AAAAAAVV`: the first 6 are the 68k work-RAM
    // address (0x100000–0x10FFFF), the last 2 are the byte value.
    // Enabled codes are written to the game's RAM after every
    // displayed frame; disabled ones are kept but do nothing. No
    // bundled cheat database: the user types codes in, and they
    // persist per game in filesDir/cheats. There are no cheat
    // controls on the on-screen overlay (v1.5 standing rule).
    @Composable
    private fun CheatsScreen() {
        var screen by screenState
        var playingGame by playingGameState
        val game = playingGame
        // The stored cheats for this game, loaded when the game
        // changes. This list is the screen's working copy; every
        // change saves to the store and pushes the enabled codes
        // to the session, so a toggle takes effect on the next
        // frame without waiting for anything.
        var cheats by remember(game?.mainFile) {
            mutableStateOf(game?.let { cheatStore.load(it.mainFile) } ?: emptyList())
        }
        var showAddDialog by remember { mutableStateOf(false) }
        var addCodeText by remember { mutableStateOf("") }
        var addNameText by remember { mutableStateOf("") }
        var addError by remember { mutableStateOf<String?>(null) }

        fun pushAndSave(next: List<StoredCheat>) {
            cheats = next
            val g = game ?: return
            cheatStore.save(g.mainFile, next)
            session?.setCheats(next.filter { it.enabled }.map { it.code })
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                if (game != null) "${game.effectiveName} — Cheats" else "Cheats",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Codes write one byte to the game's work RAM after every frame. " +
                    "A code is 8 hex digits: the first 6 are the address, the last 2 are the value " +
                    "(example: 10D0E909 writes 0x09 to 0x10D0E9).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (game == null) {
                Text(
                    "No game loaded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (cheats.isEmpty()) {
                Text(
                    "No cheats for this game yet. Add one below.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                for ((index, stored) in cheats.withIndex()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stored.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                stored.code.format(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = stored.enabled,
                            onCheckedChange = { on ->
                                val next = cheats.toMutableList()
                                next[index] = stored.copy(enabled = on)
                                pushAndSave(next)
                            },
                        )
                        TextButton(onClick = {
                            val next = cheats.toMutableList()
                            next.removeAt(index)
                            pushAndSave(next)
                        }) { Text("Delete") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                addCodeText = ""
                addNameText = ""
                addError = null
                showAddDialog = true
            }) { Text("ADD CODE") }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { screen = Screen.Playing }) { Text("Back") }
        }
        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add code") },
                text = {
                    Column {
                        Text(
                            "8 hex digits: the first 6 are the work-RAM address " +
                                "(100000–10FFFF), the last 2 are the byte value.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = addCodeText,
                            onValueChange = { addCodeText = it },
                            label = { Text("Code") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = addNameText,
                            onValueChange = { addNameText = it },
                            label = { Text("Name (optional)") },
                            singleLine = true,
                        )
                        if (addError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                addError ?: "",
                                color = Color(EraUi.Palette.WARNING),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val parsed = CheatCode.parse(addCodeText)
                        if (parsed == null) {
                            addError = "Enter 8 hex digits, address 100000–10FFFF."
                            return@TextButton
                        }
                        val name = addNameText.trim().ifEmpty { parsed.format() }
                        pushAndSave(cheats + StoredCheat(parsed, name, true))
                        showAddDialog = false
                        addCodeText = ""
                        addNameText = ""
                        addError = null
                    }) { Text("Add") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }

    // v1.3.1 — the settings screen, split out of HowlingDogUi.
    // Extracting the game screen alone only moved HowlingDogUi
    // from 264 to 261 registers (the peak lives in the remaining
    // branches); the Settings branch is the next biggest, and
    // extracting it too drops the method comfortably under the
    // 256-register limit. Shared states arrive as MutableState
    // holders and are re-delegated with `by`, so the body below
    // is HowlingDogUi's Settings branch verbatim.
    @Composable
    private fun SettingsScreen(
    ) {
        val context = LocalContext.current
        var screen by screenState
        var crtShaderOn by crtShaderOnState
        var curvatureOn by curvatureOnState
        // 2.0, feature 7 — the edge-smoothing mode (off by default).
        var edgeMode by edgeModeState
        // 2.0, feature 6 fix — the Arcade Manager's Back target
        // was used by the Settings button but never declared in
        // this screen, so the build failed (unresolved
        // reference). Declared here, as in the library screen.
        var arcadeManagerFrom by arcadeManagerFromState
        var hapticsLevel by hapticsLevelState
        // 2.0, feature 4 — the input display toggle (off by default).
        var inputDisplayOn by inputDisplayOnState
        var controlSize by controlSizeState
        var leftControl by leftControlState
        var turboMask by turboMaskState
        var remapCapturing by remapCapturingState
        var remapHint by remapHintState
        var runaheadFrames by runaheadFramesState
        var raUsernameText by raUsernameTextState
        var raPasswordText by raPasswordTextState
        var raLoginFieldsVisible by raLoginFieldsVisibleState
        var stickOpacity by stickOpacityState
        var buttonsOpacity by buttonsOpacityState
        var showBoot by showBootState
        var dismissedUpdate by dismissedUpdateState
        var ffSpeed by ffSpeedState
        var slowOn by slowOnState
        var autoSaveOnQuit by autoSaveOnQuitState
        var settingsFrom by settingsFromState
        // Audit fix (a): same status-bar inset padding as the
        // library, so the "Settings" title is not drawn under
        // the status bar and scrolled content stays below it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
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
            // 2.0.1, audit fix 1 — the row shows the EFFECTIVE
            // state: when Edge smoothing is on, the CRT is off
            // (resolveDisplayMode: the edge wins), even though
            // the stored CRT preference is still on. The stored
            // value is untouched, so turning Edge smoothing back
            // off restores the CRT automatically.
            ControlDropdownRow(
                "CRT shader",
                listOf("on" to "On", "off" to "Off"),
                if (resolveDisplayMode(crtShaderOn, edgeMode) == DisplayMode.CRT) "on" else "off",
            ) { value ->
                val on = value == "on"
                shaderSettings.setShaderMaster(on)
                crtShaderOn = on
            }
            Text(
                "Each system uses its own chain, tuned to the display it was played on — AES Hearth, Arcade Neon, Neo Geo CD Comet, Neo Geo CDZ Zenith. Takes effect the next time a game starts.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (edgeMode != EdgeMode.OFF) {
                Text(
                    "Edge smoothing is on, so the CRT shader is off.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 2.0, feature 7 — the edge-smoothing display mode.
            // Off by default. When it is on, the CRT shader above
            // turns off (they fight visually) — the edge shader is
            // the alternative display mode. "Sharp CRT" is edge
            // smoothing then scanlines only, one tap.
            ControlDropdownRow(
                "Edge smoothing",
                listOf(
                    "off" to "Off",
                    "edge" to "Edge smoothing",
                    "sharpcrt" to "Sharp CRT",
                ),
                when (edgeMode) {
                    EdgeMode.OFF -> "off"
                    EdgeMode.EDGE -> "edge"
                    EdgeMode.SHARP_CRT -> "sharpcrt"
                },
            ) { value ->
                val mode = when (value) {
                    "edge" -> EdgeMode.EDGE
                    "sharpcrt" -> EdgeMode.SHARP_CRT
                    else -> EdgeMode.OFF
                }
                shaderSettings.setEdgeMaster(mode)
                edgeMode = mode
            }
            Text(
                "Very clean, sharp pixel art: hard sprite edges are anti-aliased along their direction, while checkerboard dither (transparency, shadows, shading) is preserved and acute corners stay sharp. When on, the CRT shader turns off. Takes effect the next time a game starts.",
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
            Text(
                "How the click feels depends on the phone: a Pixel's linear actuator renders the stick's microswitch click and the Coin thud crisply, while a cheap motor renders both as a generic buzz.",
                style = MaterialTheme.typography.bodySmall,
            )
            // 2.0, feature 4 — the input display: a small strip
            // over the game showing the recent inputs as they
            // happen, like a fightstick display. Off by default.
            ControlDropdownRow(
                "Input display",
                listOf("off" to "Off", "on" to "On"),
                if (inputDisplayOn) "on" else "off",
            ) { value ->
                val on = value == "on"
                controlSettings.setInputDisplay(on)
                inputDisplayOn = on
            }
            Text(
                "Shows the recent inputs (arrows + buttons) in a strip over the game, for fighting games and for diagnosing the controls. Off by default.",
                style = MaterialTheme.typography.bodySmall,
            )
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
            Text("Turbo (autofire)", style = MaterialTheme.typography.bodyMedium)
            for (id in ControlSettings.TURBO_BUTTON_IDS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Turbo $id", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = turboMask and TurboInput.bitFor(id) != 0,
                        onCheckedChange = { on ->
                            controlSettings.setTurboEnabled(id, on)
                            turboMask = controlSettings.turboMask()
                        },
                    )
                }
            }
            Text(
                "While held, a turbo button presses and releases about 12 times a second — for shooters and run-and-guns. Off by default. Applies to the on-screen buttons and the gamepad.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Text("Gamepad", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Button mapping", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    remapCapturing = null
                    remapHint = ""
                    screen = Screen.GamepadRemap
                }) { Text("Remap") }
            }
            Text(
                "Move the Neo Geo buttons to whichever gamepad buttons you like. The six face and menu buttons swap with each other; the shoulders stay as quick save and quick load, and the D-pad and stick keep steering.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Second player: connect a second gamepad and it controls player 2. The first gamepad you press is player 1, the second is player 2, and a third gamepad is ignored. The same button mapping applies to both players; the shoulders, stick clicks, and START+SELECT pause stay with player 1.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Text("Latency", style = MaterialTheme.typography.titleSmall)
            ControlDropdownRow(
                "Run-ahead",
                Runahead.OPTIONS,
                Runahead.store(runaheadFrames),
            ) { value ->
                val frames = Runahead.parse(value)
                appSettings.setRunaheadFrames(frames)
                runaheadFrames = frames
            }
            Text(
                "Runs the game one or two frames ahead of the display, so a press shows up sooner. Off by default. Takes effect the next time a game starts.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Text("Speed", style = MaterialTheme.typography.titleSmall)
            ControlDropdownRow(
                "Fast-forward",
                listOf("off" to "Off", "2" to "2x", "4" to "4x"),
                SpeedControl.storeFfSetting(ffSpeed),
            ) { value ->
                val speed = SpeedControl.parseFfSetting(value)
                appSettings.setFastForwardSpeed(speed)
                ffSpeed = speed
            }
            Text(
                "Hold the FF button on the game screen, or hold the left stick click (L3) on a gamepad, to run the game faster — for skipping loading screens and long intros. The button only appears on the overlay while a speed is picked. Sound is muted while it runs.",
                style = MaterialTheme.typography.bodySmall,
            )
            ControlDropdownRow(
                "Slow motion",
                listOf("off" to "Off", "on" to "0.5x"),
                SpeedControl.storeSlowSetting(slowOn),
            ) { value ->
                val on = SpeedControl.parseSlowSetting(value)
                appSettings.setSlowMotionOn(on)
                slowOn = on
            }
            Text(
                "Hold the SLOW button on the game screen, or hold the right stick click (R3) on a gamepad, to run the game at half speed — for hard sections. The button only appears on the overlay while it is on. Sound is muted while it runs.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Text("Save states", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-save on quit", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = autoSaveOnQuit,
                    onCheckedChange = { on ->
                        appSettings.setAutoSaveOnQuit(on)
                        autoSaveOnQuit = on
                    },
                )
            }
            Text(
                "Quitting to the library saves the game to slot 1 automatically, so a quick load brings it back to where it was left. Slots 2 and 3 are untouched. On by default.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            // Phase 10 — RetroAchievements (v1.1.2): sign
            // in with a username and password, like other
            // emulators — the API-key login is gone. The
            // password is used once, for this login, and is
            // never stored: the session token the server
            // returns is what the app keeps. Logged out,
            // achievements are simply inactive — no errors,
            // no nagging. The status line below is the live
            // session state.
            Text("RetroAchievements", style = MaterialTheme.typography.titleSmall)
            // 2.0.1, audit fix 6 — when logged in, the Username
            // and Password fields are hidden: an empty Password
            // field under "Logged in as ..." reads as if a
            // password is still needed. The fields show only
            // when logged out, or after "Log in again" is tapped.
            if (!RaClient.loggedIn || raLoginFieldsVisible) {
                OutlinedTextField(
                    value = raUsernameText,
                    onValueChange = { raUsernameText = it },
                    singleLine = true,
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = raPasswordText,
                    onValueChange = { raPasswordText = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                RaClient.status.ifEmpty { "Not logged in." },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    // 2.0.1, audit fix 6 — when logged in and
                    // the fields are hidden, the first tap only
                    // reveals them: submitting with the hidden,
                    // empty password would otherwise log the
                    // user out (the else branch below).
                    if (RaClient.loggedIn && !raLoginFieldsVisible) {
                        raLoginFieldsVisible = true
                        return@Button
                    }
                    val user = raUsernameText.trim()
                    val password = raPasswordText
                    appSettings.setRaUsername(user)
                    raUsernameText = user
                    if (user.isNotEmpty() && password.isNotEmpty()) {
                        // init logs in with the stored
                        // token when it first creates the
                        // client; here the typed password
                        // is the fresh intent, so init
                        // without the auto-login and log
                        // in with the password directly —
                        // the login is never started twice.
                        if (!RaClient.initialised) {
                            RaClient.init(appSettings, autoLogin = false)
                        }
                        RaClient.login(user, password)
                    } else {
                        RaClient.logout()
                    }
                    // Hide the fields again after submitting,
                    // so a successful login returns to the
                    // clean logged-in view (a failed login
                    // shows its error in the status line, and
                    // another "Log in again" tap reveals the
                    // fields to retry).
                    raLoginFieldsVisible = false
                }) {
                    Text(if (RaClient.loggedIn) "Log in again" else "Log in")
                }
                if (RaClient.loggedIn || appSettings.raUsername().isNotEmpty()) {
                    OutlinedButton(onClick = {
                        appSettings.setRaUsername("")
                        appSettings.setRaToken("")
                        raUsernameText = ""
                        raPasswordText = ""
                        RaClient.logout()
                    }) {
                        Text("Log out")
                    }
                }
            }
            Text(
                "Sign in with your RetroAchievements account to earn achievements while you play. Your password is used once to log in and is not stored on the device. Achievements are softcore — save states stay available. A game the site does not recognise (or a disc) simply has no achievements.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Stick opacity", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${(stickOpacity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Slider(
                value = stickOpacity,
                onValueChange = { stickOpacity = it },
                valueRange = 0.2f..1.0f,
                steps = 7,
                onValueChangeFinished = {
                    controlSettings.setStickOpacity((stickOpacity * 100).roundToInt())
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Buttons opacity", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${(buttonsOpacity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Slider(
                value = buttonsOpacity,
                onValueChange = { buttonsOpacity = it },
                valueRange = 0.2f..1.0f,
                steps = 7,
                onValueChangeFinished = {
                    controlSettings.setButtonsOpacity((buttonsOpacity * 100).roundToInt())
                },
            )
            Text(
                "To move and resize the controls, start a game, pause, and tap Adjust controls — drag a control to move it, drag its ↘ corner to resize it. Buttons move together when Together is on, or one at a time when it is off; with Together off, drag a button's own ↘ corner to resize just that button.",
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
            Text("About", style = MaterialTheme.typography.titleSmall)
            Text(
                "HowlingDog — a standalone Neo Geo emulator (AES / MVS / CD / CDZ).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "HowlingDog is not affiliated with or endorsed by SNK Corporation.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { screen = Screen.Licences }) { Text("Licences") }
                // First-run SYSTEM SETUP, re-openable any
                // time: the debug-menu-style guide for a
                // fresh install (audit 2).
                Button(onClick = { screen = Screen.Setup }) { Text("Setup guide") }
            }
            // 2.0, feature 3 — the memory card manager: the saves
            // on the virtual Neo Geo memory card, with delete for
            // stale saves. The entries are re-read when the screen
            // opens, so it always shows the card as it is.
            Spacer(Modifier.height(16.dp))
            Text("Memory card", style = MaterialTheme.typography.titleSmall)
            Text(
                "Which games have saves on the virtual Neo Geo memory card, and how big each save is. Delete a game's saves here to free the card.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                refreshMemoryCard()
                screen = Screen.MemoryCard
            }) { Text("Memory card") }
            // 2.0, feature 6 — the Arcade Manager: input heatmaps
            // and the virtual revenue / time tracker. The entries
            // are re-read when the screen opens, so it always shows
            // the counts as they are.
            Spacer(Modifier.height(16.dp))
            Text("Arcade Manager", style = MaterialTheme.typography.titleSmall)
            Text(
                "How much each game has been played, what it would have cost in quarters, and which controls wear out first.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                arcadeManagerFrom = Screen.Settings
                refreshArcadeManager()
                screen = Screen.ArcadeManager
            }) { Text("Arcade Manager") }
            // Phase 9, item 10 — in-app updater: a manual
            // check on demand, plus the download when one
            // is available. A manual check clears any
            // dismissal first, so a found update shows its
            // library notice again.
            Spacer(Modifier.height(16.dp))
            Text("Updates", style = MaterialTheme.typography.titleSmall)
            Text(
                "Updates come from the project's GitHub releases and install in place — same signature, your saves untouched.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    appSettings.clearDismissedUpdateVersion()
                    dismissedUpdate = null
                    UpdateChecker.checkAsync(appSettings, BuildConfig.VERSION_NAME, force = true)
                }) { Text("Check for updates") }
                val settingsAvailable = UpdateChecker.availableVersion
                if (settingsAvailable != null &&
                    UpdateChecker.downloadUrl != null &&
                    UpdateChecker.downloadProgress !in 0..100
                ) {
                    Button(onClick = { UpdateChecker.downloadAndInstall(context) }) {
                        Text("Download v$settingsAvailable")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                UpdateChecker.checking -> Text(
                    "Checking for updates…",
                    style = MaterialTheme.typography.bodyMedium,
                )
                UpdateChecker.downloadProgress in 0..100 -> Text(
                    "Downloading version ${UpdateChecker.availableVersion}… ${UpdateChecker.downloadProgress}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                UpdateChecker.availableVersion != null -> {
                    val settingsAvailable = UpdateChecker.availableVersion
                    Text(
                        "Version v$settingsAvailable is available.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (UpdateChecker.downloadUrl == null) {
                        Text(
                            "It has no APK attached yet — check back later.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    UpdateChecker.releaseNotes?.let { notes ->
                        Spacer(Modifier.height(8.dp))
                        Text("What's new", style = MaterialTheme.typography.titleSmall)
                        Text(
                            notes.take(1000),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (UpdateChecker.downloadProgress == -2) {
                        Text(
                            "The download failed — check your connection and try again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (UpdateChecker.downloadProgress == -3) {
                        Text(
                            "That download failed its check and was deleted — try again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                UpdateChecker.lastCheckOutcome == CheckOutcome.UP_TO_DATE -> Text(
                    "You're up to date.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                UpdateChecker.lastCheckOutcome == CheckOutcome.FAILED -> Text(
                    "Couldn't check — check your connection and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Text(
                    "Updates are checked when you open the library.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { screen = settingsFrom }) { Text("Back") }
        }
    }

    // v1.3.1 — the game screen, split out of HowlingDogUi.
    // HowlingDogUi had grown past 256 registers in v1.3.0,
    // which made d8 truncate the $changed parameter (v263)
    // to v7 in the prologue and ART rejected the class at
    // launch (VerifyError). The shared states arrive as
    // MutableState holders and are re-delegated with `by`,
    // so the body below is HowlingDogUi's Playing branch
    // verbatim. Keeping this screen separate also keeps
    // HowlingDogUi under the limit as more screens grow.
    @Composable
    private fun PlayingScreen(
        syncTopBar: () -> Unit,
        exitEditMode: () -> Unit,
        playHaptic: () -> Unit,
        // 2.0, feature 5 — the Coin button's thud, distinct from a
        // button press.
        playCoinHaptic: () -> Unit,
        pushMask: () -> Unit,
        pushSpeed: () -> Unit,
        quickSave: () -> Unit,
        quickLoad: () -> Unit,
        pauseGame: () -> Unit,
        resumeGame: () -> Unit,
        openUniBiosMenu: () -> Unit,
        saveState: () -> Unit,
        loadState: () -> Unit,
        quitToLibrary: () -> Unit,
    ) {
        val context = LocalContext.current
        val activity = context as Activity
        var screen by screenState
        var settingsFrom by settingsFromState
        var editControls by editControlsState
        var saveSlot by saveSlotState
        var moveTogether by moveTogetherState
        // 2.0, feature 2 — where the adjust mode saves to: this
        // game only (a per-game layout override) or all games.
        var saveForThisGame by saveForThisGameState
        var stickPos by stickPosState
        var buttonsPos by buttonsPosState
        var stickScale by stickScaleState
        var buttonsScale by buttonsScaleState
        var dpadScale by dpadScaleState
        var buttonOverrides by buttonOverridesState
        var buttonSizeMults by buttonSizeMultsState
        var held by heldState
        var ffHeld by ffHeldState
        var slowHeld by slowHeldState
        var playingGame by playingGameState
        var paused by pausedState
        var topBarVisible by topBarVisibleState
        var topBarGeneration by topBarGenerationState
        var quickStatus by quickStatusState
        var pauseTimeLeft by pauseTimeLeftState
        var pauseStatus by pauseStatusState
        var controlSize by controlSizeState
        var leftControl by leftControlState
        // 2.0, feature 4 — the input display: the recent-input
        // events the strip shows, and whether it is on.
        var inputHistoryEvents by inputHistoryState
        var inputDisplayOn by inputDisplayOnState
        var stickOpacity by stickOpacityState
        var buttonsOpacity by buttonsOpacityState
        var ffSpeed by ffSpeedState
        var slowOn by slowOnState
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
        // Phase 9, item 7 — auto-hide timer: while the bar
        // is visible, the game is running (not paused), and
        // the controls are not being adjusted, hide the bar
        // after the machine's delay (2 s after start or
        // resume, 4 s after a swipe-down reveal or leaving
        // edit mode). The effect restarts whenever the bar
        // is revealed (generation), hides, edit mode
        // toggles, or the game pauses/resumes — so a reveal
        // always gets its full idle window.
        LaunchedEffect(topBarVisible, editControls, paused, topBarGeneration) {
            if (topBarVisible && !editControls && !paused) {
                delay(topBar.hideDelayMs)
                topBar.onHideDue()
                syncTopBar()
            }
        }
        // Phase 9, item 7 — swipe down from the top edge
        // reveals the bar. The detector is observe-only
        // (see detectTopEdgeSwipeDown): it never consumes a
        // touch, so game input, the stick, and the buttons
        // all receive their touches untouched. The callback
        // is read through rememberUpdatedState, so the
        // detector — keyed on the stable `topBar` — never
        // goes stale.
        val density = LocalDensity.current
        val topEdgePx = with(density) { TopBarVisibility.EDGE_HEIGHT_DP.dp.toPx() }
        val swipeMinPx = with(density) { TopBarVisibility.SWIPE_MIN_DP.dp.toPx() }
        val onTopEdgeSwipe = rememberUpdatedState {
            topBar.onSwipeDown()
            syncTopBar()
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(topBar) {
                    awaitPointerEventScope {
                        detectTopEdgeSwipeDown(topEdgePx, swipeMinPx) {
                            onTopEdgeSwipe.value()
                        }
                    }
                },
        ) {
            // Phase 9, item 7 — the title row auto-hides:
            // shown while playing only until the timer
            // fires, and always while adjusting controls
            // (the Together / Reset / Done buttons live
            // here — the bar must never hide under them)
            // or paused. Hiding frees the row's height for
            // the game view below. The SAVE / LOAD note
            // is a separate overlay over the game area and
            // is untouched by this.
            AnimatedVisibility(
                visible = topBarVisible || editControls || paused,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
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
                    playingGame?.effectiveName ?: "Game",
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
                        // 2.0, feature 2 — where the layout saves
                        // to. "This game only" writes the positions
                        // as this game's own override (the global
                        // layout stays the default for other
                        // games); "All games" writes them to the
                        // global settings, as before.
                        Button(onClick = { saveForThisGame = !saveForThisGame }) {
                            Text(if (saveForThisGame) "This game only" else "All games")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            val game = playingGame
                            if (saveForThisGame && game != null) {
                                // 2.0, feature 2 — Reset clears
                                // this game's saved layout, so it
                                // follows the global layout again;
                                // the global layout itself is
                                // untouched.
                                perGameSettings.clearControlLayout(game.mainFile)
                                stickPos = controlSettings.stickPosition()
                                buttonsPos = controlSettings.buttonsPosition()
                                stickScale = controlSettings.stickScale()
                                buttonsScale = controlSettings.buttonsScale()
                                dpadScale = controlSettings.dpadScale()
                                buttonOverrides = controlSettings.buttonOffsets()
                                buttonSizeMults = controlSettings.buttonSizeMults()
                            } else {
                                controlSettings.resetLayout()
                                stickPos = ControlSettings.DEFAULT_STICK_POS
                                buttonsPos = ControlSettings.DEFAULT_BUTTONS_POS
                                stickScale = ControlSettings.DEFAULT_GROUP_SCALE
                                buttonsScale = ControlSettings.DEFAULT_GROUP_SCALE
                                dpadScale = ControlSettings.DEFAULT_GROUP_SCALE
                                buttonOverrides = emptyMap()
                                buttonSizeMults = emptyMap()
                            }
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
                            // Phase 9, item 8 — a game's own
                            // shader / curvature choice wins
                            // over the per-system → master
                            // chain; unset falls back to it.
                            it.setPreset(
                                ShaderPresets.forMode(mode),
                                perGameSettings.resolveShader(
                                    entry.mainFile,
                                    shaderSettings.shaderOn(mode),
                                ),
                                perGameSettings.resolveCurvature(
                                    entry.mainFile,
                                    shaderSettings.curvatureOn(mode),
                                ),
                                // 2.0, feature 7 — the edge mode
                                // wins over the CRT (the renderer
                                // picks the edge program when it is
                                // on), so the CRT toggle above is
                                // only in effect when the edge mode
                                // is off.
                                shaderSettings.edgeMode(mode),
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
                // the button cluster (bottom-right). Each
                // unit takes its own stored opacity (Part 2)
                // — the stick/D-pad and the buttons can be
                // faded separately.
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize(),
                ) {
                    val density = LocalDensity.current
                    val boxW = with(density) { maxWidth.toPx() }
                    val boxH = with(density) { maxHeight.toPx() }
                    // Effective size = the Settings preset
                    // times the group's own resize scale
                    // (set by its corner handle in adjust
                    // mode; 1.0 = the preset size). The
                    // left control's scale is the active
                    // control's own — the stick and the
                    // D-pad are sized separately (Part 2).
                    val leftScaleEff = controlSize.scale *
                        if (leftControl == LeftControl.DPAD) dpadScale else stickScale
                    val buttonsScaleEff = controlSize.scale * buttonsScale
                    // Stick geometry (dp, scaled): base 140,
                    // grab margin 16, touch area 172.
                    val stickTouchDp = 172f * leftScaleEff
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
                        OverlayTheme.clusterSizeFor(buttonOffsets, buttonSizeMults)
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
                    // The left control's own scale (Part 2):
                    // the stick and the D-pad are sized
                    // separately, so the drag writes to the
                    // one that is showing.
                    val dpadScaleState = rememberUpdatedState(dpadScale)
                    val leftControlState = rememberUpdatedState(leftControl)
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
                    val buttonSizeMultsState = rememberUpdatedState(buttonSizeMults)
                    val dpPxState = rememberUpdatedState(with(density) { 1.dp.toPx() })
                    // A button's own resize grip (Part 2) is
                    // smaller than the cluster's 28dp grip —
                    // the button itself is only 56dp (48dp
                    // for Coin/Start) — so 20dp at its
                    // bottom-end corner.
                    val buttonGripPxState =
                        rememberUpdatedState(with(density) { 20.dp.toPx() })
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
                                    // 2.0, feature 2 — the drag
                                    // commits the whole layout to
                                    // the active scope (this game
                                    // or all games); writing every
                                    // value is the same as writing
                                    // just the changed one, since
                                    // the states are the source of
                                    // truth and the rest are
                                    // unchanged.
                                    saveLayoutToScope()
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (resizing) {
                                        val sizePx = stickTouchPxState.value
                                        if (sizePx > 0) {
                                            // The active control's
                                            // scale (Part 2): the
                                            // D-pad's when it is
                                            // showing, else the
                                            // stick's.
                                            val curScale =
                                                if (leftControlState.value == LeftControl.DPAD) {
                                                    dpadScaleState.value
                                                } else {
                                                    stickScaleState.value
                                                }
                                            val newScale = (curScale *
                                                (1f + dragAmount.x / sizePx))
                                                .coerceIn(
                                                    ControlSettings.MIN_GROUP_SCALE,
                                                    ControlSettings.MAX_GROUP_SCALE,
                                                )
                                            if (leftControlState.value == LeftControl.DPAD) {
                                                dpadScale = newScale
                                            } else {
                                                stickScale = newScale
                                            }
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
                            // The button being resized on its
                            // own (Part 2, null = none).
                            var resizeButton: String? = null
                            detectDragGestures(
                                onDragStart = { start ->
                                    val wPx = buttonsWPxState.value
                                    val hPx = buttonsHPxState.value
                                    val grip = handlePxState.value
                                    resizing = start.x > wPx - grip &&
                                        start.y > hPx - grip
                                    dragButton = null
                                    resizeButton = null
                                    if (!resizing && !moveTogetherState.value) {
                                        val offsets = buttonOffsetsState.value
                                        val scale = presetScaleState.value *
                                            buttonsScaleState.value
                                        val dpPx = dpPxState.value
                                        val buttonGrip = buttonGripPxState.value
                                        // Individual resize (Part 2):
                                        // which button's own ↘ grip
                                        // (if any) did the press land
                                        // on? The grip is the small
                                        // square at the button's
                                        // bottom-end corner. The
                                        // circle is checked first
                                        // below, so a press inside
                                        // the button always moves it
                                        // and only a press outside
                                        // the circle, in the grip
                                        // square, resizes it.
                                        for ((id, off) in offsets) {
                                            val cx = wPx / 2f +
                                                off.first * scale * dpPx
                                            val cy = hPx / 2f +
                                                off.second * scale * dpPx
                                            val r = OverlayTheme.buttonSize(id) *
                                                (buttonSizeMultsState.value[id] ?: 1f) *
                                                scale * dpPx / 2f
                                            val dx = start.x - cx
                                            val dy = start.y - cy
                                            if (dx * dx + dy * dy > r * r &&
                                                start.x > cx + r - buttonGrip &&
                                                start.x <= cx + r &&
                                                start.y > cy + r - buttonGrip &&
                                                start.y <= cy + r
                                            ) {
                                                resizeButton = id
                                                break
                                            }
                                        }
                                        if (resizeButton == null) {
                                            // Individual move: which
                                            // button (if any) did the
                                            // press land on? A press
                                            // counts when it is inside
                                            // the button's circle.
                                            for ((id, off) in offsets) {
                                                val cx = wPx / 2f +
                                                    off.first * scale * dpPx
                                                val cy = hPx / 2f +
                                                    off.second * scale * dpPx
                                                val r = OverlayTheme.buttonSize(id) *
                                                    (buttonSizeMultsState.value[id] ?: 1f) *
                                                    scale * dpPx / 2f
                                                val dx = start.x - cx
                                                val dy = start.y - cy
                                                if (dx * dx + dy * dy <= r * r) {
                                                    dragButton = id
                                                    break
                                                }
                                            }
                                        }
                                    }
                                },
                                onDragEnd = {
                                    // 2.0, feature 2 — the drag
                                    // commits the whole layout to
                                    // the active scope (this game
                                    // or all games), as above.
                                    saveLayoutToScope()
                                    dragButton = null
                                    resizeButton = null
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
                                    } else if (resizeButton != null) {
                                        // Resize that button alone
                                        // (Part 2): horizontal drag
                                        // changes its size multiplier
                                        // (same proportional rule as
                                        // the group resize), clamped
                                        // 0.7x..1.5x of the group size.
                                        val resizeId = resizeButton!!
                                        val cur = buttonSizeMultsState.value[resizeId] ?: 1f
                                        val scale = presetScaleState.value *
                                            buttonsScaleState.value
                                        val dpPx = dpPxState.value
                                        val btnPx = OverlayTheme.buttonSize(resizeId) *
                                            cur * scale * dpPx
                                        if (btnPx > 0) {
                                            val newMult = (cur *
                                                (1f + dragAmount.x / btnPx))
                                                .coerceIn(
                                                    ControlSettings.MIN_GROUP_SCALE,
                                                    ControlSettings.MAX_GROUP_SCALE,
                                                )
                                            val newMults = buttonSizeMultsState.value +
                                                (resizeId to newMult)
                                            buttonSizeMults = newMults
                                            // The cluster grew: its
                                            // bounding box at scale 1
                                            // comes from the updated
                                            // sizes, so re-clamp its
                                            // centre to the new size.
                                            val eff = presetScaleState.value *
                                                buttonsScaleState.value
                                            val (cw1, ch1) = OverlayTheme.clusterSizeFor(
                                                buttonOffsetsState.value,
                                                newMults,
                                            )
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
                                                // The button's own size
                                                // multiplier (Part 2):
                                                // its effective half-size.
                                                val halfBtn = OverlayTheme.buttonSize(id) *
                                                    (buttonSizeMultsState.value[id] ?: 1f) *
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
                            size = (140f * leftScaleEff).dp,
                            grabMargin = (16f * leftScaleEff).dp,
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
                            enabled = !editControls && !paused,
                            modifier = Modifier
                                .offset { stickOffset }
                                .size(stickTouchDp.dp)
                                .alpha(stickOpacity)
                                .then(stickDrag),
                        )
                    } else {
                        PsStick(
                            size = (140f * leftScaleEff).dp,
                            grabMargin = (16f * leftScaleEff).dp,
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
                            enabled = !editControls && !paused,
                            modifier = Modifier
                                .offset { stickOffset }
                                .size(stickTouchDp.dp)
                                .alpha(stickOpacity)
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
                            .alpha(buttonsOpacity)
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
                            // A button's own size multiplier
                            // (Part 2) times the group size.
                            val sizePx = with(density) {
                                (OverlayTheme.buttonSize(id) *
                                    (buttonSizeMults[id] ?: 1f) *
                                    buttonsScaleEff).dp.toPx()
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
                        }, modifier = Modifier.offset { place("A") }, scale = buttonsScaleEff * (buttonSizeMults["A"] ?: 1f), onHaptic = playHaptic, enabled = !editControls && !paused, color = styleA.background, labelColor = styleA.label)
                        HoldButton("B", { pressed ->
                            held = held.copy(b = pressed); pushMask()
                        }, modifier = Modifier.offset { place("B") }, scale = buttonsScaleEff * (buttonSizeMults["B"] ?: 1f), onHaptic = playHaptic, enabled = !editControls && !paused, color = styleB.background, labelColor = styleB.label)
                        HoldButton("C", { pressed ->
                            held = held.copy(c = pressed); pushMask()
                        }, modifier = Modifier.offset { place("C") }, scale = buttonsScaleEff * (buttonSizeMults["C"] ?: 1f), onHaptic = playHaptic, enabled = !editControls && !paused, color = styleC.background, labelColor = styleC.label)
                        HoldButton("D", { pressed ->
                            held = held.copy(d = pressed); pushMask()
                        }, modifier = Modifier.offset { place("D") }, scale = buttonsScaleEff * (buttonSizeMults["D"] ?: 1f), onHaptic = playHaptic, enabled = !editControls && !paused, color = styleD.background, labelColor = styleD.label)
                        HoldButton("Coin", { pressed ->
                            held = held.copy(coin = pressed); pushMask()
                            // 2.0, feature 5 — the coin drop gets
                            // its own thud, not a button click.
                        }, modifier = Modifier.offset { place("Coin") }, small = true, scale = buttonsScaleEff * (buttonSizeMults["Coin"] ?: 1f), onHaptic = playCoinHaptic, enabled = !editControls && !paused)
                        HoldButton("Start", { pressed ->
                            held = held.copy(start = pressed); pushMask()
                        }, modifier = Modifier.offset { place("Start") }, small = true, scale = buttonsScaleEff * (buttonSizeMults["Start"] ?: 1f), onHaptic = playHaptic, enabled = !editControls && !paused)
                        // Per-button resize grips (Part 2,
                        // edit mode, Together off only): a
                        // small gold "↘" at each button's
                        // bottom-end corner, marking where
                        // to grab to resize just that button.
                        // Purely visual, like the unit grips.
                        if (editControls && !moveTogether) {
                            for (id in ControlSettings.BUTTON_IDS) {
                                val btnPx = with(density) {
                                    (OverlayTheme.buttonSize(id) *
                                        (buttonSizeMults[id] ?: 1f) *
                                        buttonsScaleEff).dp.toPx()
                                }
                                val topLeft = place(id)
                                val gripPx = with(density) { 20.dp.toPx() }
                                Box(
                                    modifier = Modifier
                                        .offset {
                                            IntOffset(
                                                (topLeft.x + btnPx - gripPx).roundToInt(),
                                                (topLeft.y + btnPx - gripPx).roundToInt(),
                                            )
                                        }
                                        .size(20.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .background(
                                                Color(EraUi.Palette.GOLD),
                                                RoundedCornerShape(3.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "↘",
                                            color = Color(EraUi.Palette.ON_GOLD),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
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
                        "Drag a control to move it, drag its ↘ corner to resize it — buttons move together when Together is on, one at a time when it is off; with Together off, a button's own ↘ corner resizes just that button",
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
                // Phase 10 — RetroAchievements: the unlock
                // popup, a gold note over the game area
                // (the same treatment as the quick status
                // above), cleared after four seconds (see
                // the LaunchedEffect above). Hidden while
                // paused.
                val unlock = RaClient.lastUnlock
                if (unlock != null && !paused) {
                    Text(
                        "Achievement Unlocked: ${unlock.title} (+${unlock.points} pts)",
                        color = Color(EraUi.Palette.GOLD),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
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
                // v1.2.0 (item 2) — the leaderboard
                // scoreboard note, the same gold treatment
                // as the unlock popup: the submitted score
                // and the new rank. Hidden while paused.
                val scoreboard = RaClient.lastScoreboard
                if (scoreboard != null && !paused) {
                    Text(
                        "Score submitted: ${scoreboard.submittedScore} — Rank #${scoreboard.newRank} of ${scoreboard.numEntries}",
                        color = Color(EraUi.Palette.GOLD),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
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
                // v1.5, feature 1 — speed hold buttons: FF
                // and SLOW, hold to run. They are NOT part
                // of the overlay by default: each appears
                // only when its speed is enabled in Settings
                // (both off by default), so the overlay
                // stays clean until he turns one on.
                // Top-right of the game area, clear of the
                // stick (bottom-left) and the buttons
                // (bottom-right); hidden while paused or
                // adjusting controls, like the notes above.
                if (!paused && !editControls && (ffSpeed > SpeedControl.NORMAL || slowOn)) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (slowOn) {
                            HoldButton(
                                label = "SLOW",
                                small = true,
                                onHoldChange = { held ->
                                    slowHeld = held
                                    pushSpeed()
                                },
                            )
                        }
                        if (ffSpeed > SpeedControl.NORMAL) {
                            HoldButton(
                                label = "FF",
                                small = true,
                                onHoldChange = { held ->
                                    ffHeld = held
                                    pushSpeed()
                                },
                            )
                        }
                    }
                }
                // 2.0, feature 4 — the input display strip: the
                // recent inputs as they happen, top-left of the
                // game area (the FF/SLOW buttons take top-right),
                // floating over the game like the controls. Only
                // when the Settings toggle is on, and hidden while
                // paused or adjusting controls, like the notes above.
                if (inputDisplayOn && !paused && !editControls) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 8.dp, start = 8.dp),
                    ) {
                        InputDisplayStrip(inputHistoryEvents)
                    }
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
                            // The picker reads the saves
                            // folder fresh on each
                            // composition, so a slot saved
                            // in this pause shows occupied
                            // at once.
                            selectedSlot = saveSlot,
                            occupiedSlots = playingGame?.let {
                                QuickSave.occupiedSlots(
                                    File(context.filesDir, "saves"),
                                    it.mainFile,
                                )
                            } ?: emptyList(),
                            // v1.5, feature 2 — the slot
                            // thumbnails, read fresh on
                            // each composition like the
                            // occupied slots above.
                            slotThumbnails = playingGame?.let {
                                QuickSave.slotThumbnails(
                                    File(context.filesDir, "saves"),
                                    it.mainFile,
                                ).mapValues { (_, file) -> file.path }
                            } ?: emptyMap(),
                            onSelectSlot = { saveSlot = it },
                            onResume = { resumeGame() },
                            // Phase 9, item 9 — only under
                            // Universe BIOS: any other system
                            // has no UniBIOS menu to open.
                            showUniBiosMenu = playingGame?.let {
                                UniBiosMenu.isUniBiosMode(it.effectiveMode)
                            } ?: false,
                            onUniBiosMenu = { openUniBiosMenu() },
                            onSaveState = { saveState() },
                            onLoadState = { loadState() },
                            onCheats = { screen = Screen.Cheats },
                            // Phase 10 — RetroAchievements:
                            // the achievements list, only
                            // when RA has a game loaded. The
                            // game stays paused underneath.
                            showAchievements = RaClient.gameLoaded,
                            onAchievements = { screen = Screen.Achievements },
                            // v1.2.0 (item 2) — the
                            // leaderboards, only when the
                            // game has at least one.
                            showLeaderboards = RaClient.leaderboards.isNotEmpty(),
                            onLeaderboards = { screen = Screen.Leaderboards },
                            onSettings = {
                                settingsFrom = Screen.Playing
                                screen = Screen.Settings
                            },
                            onAdjustControls = {
                                // 2.0, feature 2 — the scope
                                // toggle starts on when this game
                                // already has a saved layout (the
                                // user is editing that layout),
                                // off otherwise (the user is
                                // editing the global one).
                                saveForThisGame = playingGame?.let {
                                    perGameSettings.hasControlLayoutOverride(it.mainFile)
                                } == true
                                editControls = true
                                // Phase 9, item 7 — entering
                                // edit mode always shows the
                                // bar and suspends auto-hide
                                // until Done.
                                topBar.onEditModeChanged(true)
                                syncTopBar()
                            },
                            onQuit = { quitToLibrary() },
                        )
                    }
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
        // The touch area, in px, for the out-of-bounds check: a
        // press that starts outside the button must never claim
        // the hold, and a hold ends when the finger slides out.
        val touchPx = with(LocalDensity.current) { size.toPx() }
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
                    // The hold is tracked per pointer change, not per batch
                    // event type (see PointerDrag.kt): a batch Release
                    // fires when ANY finger lifts, and the old handler
                    // released the button on it even when this button's
                    // finger was still down. releaseOnExit = true keeps
                    // the correct button behaviour — the hold ends when
                    // the finger slides off the button — without ending
                    // it on another finger's lift or exit.
                    val tracker = DragTracker(releaseOnExit = true)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                // Out of bounds = outside the
                                // button's touch square. There is
                                // no isOutOfBounds on
                                // PointerInputChange in this Compose
                                // version, so it is computed from
                                // the position and the touch size.
                                val out = change.position.x < 0f ||
                                    change.position.x > touchPx ||
                                    change.position.y < 0f ||
                                    change.position.y > touchPx
                                when (tracker.onChange(
                                    change.id.value,
                                    change.pressed,
                                    change.previousPressed,
                                    out,
                                )) {
                                    DragAction.CLAIM -> {
                                        held = true
                                        onHaptic?.invoke()
                                        onHoldChange(true)
                                    }
                                    DragAction.END -> {
                                        held = false
                                        onHoldChange(false)
                                    }
                                    DragAction.UPDATE,
                                    DragAction.IGNORE -> {}
                                }
                            }
                        }
                    } finally {
                        if (tracker.reset() == DragAction.END) {
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
                        var lastMask = 0
                        fun setDirection(mask: Int) {
                            if (mask != lastMask) {
                                val previous = lastMask
                                lastMask = mask
                                onDirection(mask)
                                // 2.0, feature 5 — the microswitch
                                // click fires only when the stick
                                // crosses an actuation threshold
                                // (neutral -> a direction, or one
                                // direction -> another). A release
                                // back to neutral stays silent.
                                if (ActuationDetector.isActuation(previous, mask)) onDirectionChange(mask)
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
                        // One tracker per scope: the drag lifecycle
                        // is decided per pointer change, not per
                        // batch event type (see PointerDrag.kt). The
                        // old batch Release handler ended the drag
                        // whenever ANY finger lifted — it only
                        // checked that the stick's id appeared in
                        // the event's changes — so a face button
                        // lifting dropped the stick mid-drag. Exit
                        // is still ignored (releaseOnExit = false):
                        // a drag that runs off the edge keeps its
                        // direction, clamped by updateKnob.
                        val tracker = DragTracker(releaseOnExit = false)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    // Out of bounds = outside the
                                    // touch square (touchPx). There
                                    // is no isOutOfBounds on
                                    // PointerInputChange in this
                                    // Compose version, so it is
                                    // computed from the position.
                                    val out = change.position.x < 0f ||
                                        change.position.x > touchPx ||
                                        change.position.y < 0f ||
                                        change.position.y > touchPx
                                    when (tracker.onChange(
                                        change.id.value,
                                        change.pressed,
                                        change.previousPressed,
                                        out,
                                    )) {
                                        DragAction.CLAIM,
                                        DragAction.UPDATE -> updateKnob(change.position)
                                        DragAction.END -> {
                                            knob = Offset.Zero
                                            setDirection(0)
                                        }
                                        DragAction.IGNORE -> {}
                                    }
                                }
                            }
                        } finally {
                            // A Cancel ends the drag through here;
                            // reset() only clears when one is live,
                            // so a scope restart with no drag is a
                            // no-op.
                            if (tracker.reset() == DragAction.END) {
                                if (knob != Offset.Zero) knob = Offset.Zero
                                setDirection(0)
                            }
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
                        var lastMask = 0
                        fun setDirection(mask: Int) {
                            if (mask != lastMask) {
                                val previous = lastMask
                                lastMask = mask
                                heldMask = mask
                                onDirection(mask)
                                // 2.0, feature 5 — the microswitch
                                // click fires only when the D-pad
                                // crosses an actuation threshold,
                                // as for the stick above.
                                if (ActuationDetector.isActuation(previous, mask)) onDirectionChange(mask)
                            }
                        }
                        // One tracker per scope, same as the stick
                        // (see PointerDrag.kt): the drag lifecycle is
                        // decided per pointer change, not per batch
                        // event type. The old batch Release handler
                        // ended the drag whenever ANY finger lifted,
                        // so a face button lifting dropped the
                        // D-pad's direction mid-drag. Exit is still
                        // ignored (releaseOnExit = false).
                        val tracker = DragTracker(releaseOnExit = false)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    // Out of bounds = outside the
                                    // touch square (touchPx). There
                                    // is no isOutOfBounds on
                                    // PointerInputChange in this
                                    // Compose version, so it is
                                    // computed from the position.
                                    val out = change.position.x < 0f ||
                                        change.position.x > touchPx ||
                                        change.position.y < 0f ||
                                        change.position.y > touchPx
                                    when (tracker.onChange(
                                        change.id.value,
                                        change.pressed,
                                        change.previousPressed,
                                        out,
                                    )) {
                                        DragAction.CLAIM,
                                        DragAction.UPDATE -> setDirection(
                                            DpadInput.directionFromPress(
                                                change.position.x,
                                                change.position.y,
                                                touchPx,
                                            ),
                                        )
                                        DragAction.END -> setDirection(0)
                                        DragAction.IGNORE -> {}
                                    }
                                }
                            }
                        } finally {
                            // A Cancel ends the drag through here;
                            // reset() only clears when one is live.
                            if (tracker.reset() == DragAction.END) {
                                setDirection(0)
                            }
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
 * The first-run SYSTEM SETUP screen (audit 2): the Neo Geo
 * debug-menu-style onboarding. The debug menu's layout — header,
 * rows with a status column, footer hint — rendered in HowlingDog's
 * maroon-and-gold palette with gold monospace text, not the real
 * Neo Geo's green-on-black. Four rows, each with a live status
 * from the real state; PLAY is only enabled once rows 1-3 are
 * done, so there is no way to blunder into a game that cannot
 * boot. Shown once on a genuinely fresh install, dismissible via
 * SKIP or back, and re-openable from Settings as the Setup guide.
 *
 * Extracted from MainActivity so the layout is testable in
 * isolation (a running game needs native code, which Robolectric
 * does not have).
 */
@Composable
private fun SetupScreen(
    state: SetupState,
    onSystemFiles: () -> Unit,
    onAddGame: () -> Unit,
    onAddFolder: () -> Unit,
    onPickGame: () -> Unit,
    onPlay: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddChoice by remember { mutableStateOf(false) }
    val mono = FontFamily.Monospace
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(EraUi.Palette.BACKGROUND))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(EraUi.Palette.GOLD), RoundedCornerShape(4.dp))
                .background(Color(EraUi.Palette.SURFACE_RAISED), RoundedCornerShape(4.dp))
                .padding(16.dp),
        ) {
            Text(
                "HOWLINGDOG — SYSTEM SETUP",
                color = Color(EraUi.Palette.GOLD),
                fontFamily = DisplayFontFamily,
                fontSize = DisplayFontSpec.SETUP_HEADING_SP.sp,
                fontWeight = FontWeight.Normal,
            )
            Spacer(Modifier.height(8.dp))
            SetupRow(
                number = "1",
                title = "SYSTEM FILES",
                status = state.systemFilesStatus(),
                done = state.systemFilesDone,
                subtext = "The system files a game needs to start. They come with your MAME set — pick the folder holding aes.zip, neogeo.zip, neocd.zip and neocdz.zip.",
                onClick = onSystemFiles,
            )
            SetupRow(
                number = "2",
                title = "ADD GAMES",
                status = state.addGamesStatus(),
                done = state.addGamesDone,
                subtext = "Cartridges are .NEO files. Disc games are .chd files, or a .cue with its data files.",
                onClick = { showAddChoice = true },
            )
            SetupRow(
                number = "3",
                title = "PICK A GAME",
                status = state.pickGameStatus(),
                done = state.pickGameDone,
                subtext = "Choose a game from the grid and press START.",
                onClick = onPickGame,
            )
            SetupRow(
                number = "4",
                title = "PLAY",
                status = state.playStatus(),
                done = state.playReady,
                enabled = state.playReady,
                subtext = "Everything is set. Press START to play.",
                onClick = onPlay,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSkip() }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SKIP",
                    color = Color(EraUi.Palette.TEXT),
                    fontFamily = mono,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Go to the library",
                    color = Color(EraUi.Palette.TEXT_MUTED),
                    fontFamily = mono,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "SELECT AN ITEM — PRESS START TO ENTER",
            color = Color(EraUi.Palette.TEXT_MUTED),
            fontFamily = mono,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "BACK TO LEAVE",
            color = Color(EraUi.Palette.TEXT_MUTED),
            fontFamily = mono,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (showAddChoice) {
        AlertDialog(
            onDismissRequest = { showAddChoice = false },
            title = { Text("Add games") },
            text = {
                Column {
                    Text(
                        "ONE GAME — one cartridge or disc image.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "A WHOLE FOLDER — a folder of games; a disc game needs its data files too.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAddChoice = false
                    onAddGame()
                }) { Text("One game") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddChoice = false
                    onAddFolder()
                }) { Text("A whole folder") }
            },
        )
    }
}

/** One row of the SYSTEM SETUP menu: number, title, status on the right, subtext below. */
@Composable
private fun SetupRow(
    number: String,
    title: String,
    status: String,
    done: Boolean,
    subtext: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val mono = FontFamily.Monospace
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$number  $title",
                color = Color(EraUi.Palette.TEXT),
                fontFamily = DisplayFontFamily,
                fontSize = DisplayFontSpec.SETUP_HEADING_SP.sp,
                fontWeight = FontWeight.Normal,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (done) {
                    Text(
                        "✓ ",
                        color = Color(EraUi.Palette.GOLD),
                        fontFamily = mono,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    status,
                    color = if (done) Color(EraUi.Palette.GOLD) else Color(EraUi.Palette.TEXT_MUTED),
                    fontFamily = mono,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            subtext,
            color = Color(EraUi.Palette.TEXT_MUTED),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The arcade "CONTINUE?" pause menu: header, decorative countdown,
 * and the options. The button list scrolls, so every option is
 * reachable even on a short landscape screen; the header, countdown,
 * spacing and button padding are tight, so the whole menu fits
 * without scrolling when the screen is tall enough. The buttons
 * stay full-size and comfortably tappable — the compact padding
 * only removes height above the Material3 40dp floor.
 *
 * Phase 9, item 9 adds one optional row: UNIVERSE BIOS MENU,
 * shown only when the game booted in Universe BIOS mode (it
 * resumes the game with the UniBIOS menu combo injected). The
 * six-button default menu still fits a landscape game area
 * without scrolling, as before; with the seventh row the menu
 * scrolls slightly on the shortest screens (see PauseMenuTest).
 *
 * Extracted from MainActivity so the layout is testable in
 * isolation (a running game needs native code, which Robolectric
 * does not have).
 */
@Composable
fun PauseMenu(
    timeLeft: Int,
    status: String = "",
    // Phase 9, item 3 — the save-state slot picker: the picked
    // slot is what SAVE STATE / LOAD STATE below act on, and a
    // filled dot marks a slot that holds a save.
    selectedSlot: Int = QuickSave.QUICK_SLOT,
    occupiedSlots: List<Int> = emptyList(),
    onSelectSlot: (Int) -> Unit = {},
    // v1.5, feature 2 — the thumbnails that exist for this
    // game, as slot -> thumbnail file path. A slot with a save
    // but no thumbnail is simply absent from the map.
    slotThumbnails: Map<Int, String> = emptyMap(),
    onResume: () -> Unit,
    // Phase 9, item 9 — the Universe BIOS menu row, shown only
    // under Universe BIOS mode; hidden otherwise (default).
    showUniBiosMenu: Boolean = false,
    onUniBiosMenu: () -> Unit = {},
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    // v1.5, feature 4 — cheats: the current game's codes. Always
    // shown (a game with no codes yet just opens an empty list
    // with ADD CODE); the codes themselves never appear on the
    // on-screen overlay.
    onCheats: () -> Unit = {},
    // Phase 10 — RetroAchievements: the achievements list,
    // shown only when RA has a game loaded for this game.
    showAchievements: Boolean = false,
    onAchievements: () -> Unit = {},
    // v1.2.0 (item 2) — the leaderboards for this game, shown
    // only when the game has at least one.
    showLeaderboards: Boolean = false,
    onLeaderboards: () -> Unit = {},
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
            fontFamily = DisplayFontFamily,
            fontSize = DisplayFontSpec.PAUSE_TITLE_SP.sp,
            fontWeight = FontWeight.Normal,
        )
        Text(
            "%d".format(timeLeft),
            color = Color(EraUi.Palette.TEXT),
            fontFamily = DisplayFontFamily,
            fontSize = DisplayFontSpec.COUNTDOWN_SP.sp,
            fontWeight = FontWeight.Normal,
        )
        Spacer(Modifier.height(2.dp))
        ArcadeButton(text = "RESUME", primary = true, compact = true, onClick = onResume)
        Spacer(Modifier.height(2.dp))
        // Phase 9, item 9 — Universe BIOS mode only: resumes the
        // game with START+SELECT injected, opening the UniBIOS
        // in-game menu (region / Arcade-or-home-console mode).
        if (showUniBiosMenu) {
            ArcadeButton(text = "UNIVERSE BIOS MENU", compact = true, onClick = onUniBiosMenu)
            Spacer(Modifier.height(2.dp))
        }
        // Save-state slot picker: three small chips, one per
        // slot — a filled dot means the slot holds a save, an
        // open dot means it is empty, and the picked slot is
        // gold. Chips, not full buttons, and the gaps between
        // rows are 2dp, not 4dp: three compact buttons would
        // push the menu past the ~355dp landscape game area and
        // the menu would scroll (see PauseMenuTest).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (slot in 1..QuickSave.SLOT_COUNT) {
                val picked = slot == selectedSlot
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clickable { onSelectSlot(slot) }
                        .background(
                            if (picked) Color(EraUi.Palette.GOLD)
                            else Color(EraUi.Palette.SURFACE_RAISED),
                            RoundedCornerShape(2.dp),
                        )
                        .border(
                            1.dp,
                            if (picked) Color(EraUi.Palette.GOLD)
                            else Color(EraUi.Palette.GOLD_MUTED),
                            RoundedCornerShape(2.dp),
                        )
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "SLOT $slot ${if (slot in occupiedSlots) "●" else "○"}",
                        color = if (picked) Color(EraUi.Palette.ON_GOLD)
                        else Color(EraUi.Palette.TEXT),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        // v1.5, feature 2 — the picked slot's thumbnail, when
        // the slot holds a save with one: a small picture of
        // where the game was. A slot with a save but no
        // thumbnail (saved before thumbnails, or the write
        // failed) shows nothing — the filled dot still marks
        // it occupied. Decoded once per path, so switching
        // slots swaps the picture.
        val thumbPath = slotThumbnails[selectedSlot].takeIf { selectedSlot in occupiedSlots }
        val thumbBitmap = remember(thumbPath) {
            thumbPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        }
        if (thumbBitmap != null) {
            Image(
                bitmap = thumbBitmap.asImageBitmap(),
                contentDescription = "Save slot $selectedSlot",
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(thumbBitmap.width.toFloat() / thumbBitmap.height.toFloat())
                    .border(
                        1.dp,
                        Color(EraUi.Palette.GOLD_MUTED),
                        RoundedCornerShape(2.dp),
                    ),
                contentScale = ContentScale.FillWidth,
            )
            Spacer(Modifier.height(2.dp))
        }
        ArcadeButton(text = "SAVE STATE", compact = true, onClick = onSaveState)
        Spacer(Modifier.height(2.dp))
        ArcadeButton(text = "LOAD STATE", compact = true, onClick = onLoadState)
        Spacer(Modifier.height(2.dp))
        // Phase 10 — RetroAchievements: the achievements list for
        // this game, only when RA has one loaded.
        if (showAchievements) {
            ArcadeButton(text = "ACHIEVEMENTS", compact = true, onClick = onAchievements)
            Spacer(Modifier.height(2.dp))
        }
        // v1.2.0 (item 2) — the leaderboards for this game, only
        // when the game has at least one.
        if (showLeaderboards) {
            ArcadeButton(text = "LEADERBOARDS", compact = true, onClick = onLeaderboards)
            Spacer(Modifier.height(2.dp))
        }
        // v1.5, feature 4 — cheats: CHEATS and SETTINGS share one
        // row (half-width each) so the menu keeps its landscape
        // fit — a seventh full-width row would push QUIT off the
        // 360dp game area (see PauseMenuTest). Both open a
        // sub-screen from the pause menu, so the pairing is the
        // same kind of action.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArcadeButton(
                text = "CHEATS",
                modifier = Modifier.weight(1f),
                compact = true,
                onClick = onCheats,
            )
            ArcadeButton(
                text = "SETTINGS",
                modifier = Modifier.weight(1f),
                compact = true,
                onClick = onSettings,
            )
        }
        Spacer(Modifier.height(2.dp))
        ArcadeButton(text = "ADJUST CONTROLS", compact = true, onClick = onAdjustControls)
        Spacer(Modifier.height(2.dp))
        ArcadeButton(text = "QUIT TO LIBRARY", compact = true, onClick = onQuit)
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, color = Color(EraUi.Palette.TEXT))
        }
    }
}

/**
 * Phase 9, item 7 — top-edge swipe-down detector: reveals the
 * title row during play.
 *
 * Observe-only, by design: it NEVER consumes a change, so a swipe
 * (or any touch) still reaches the game view, the stick, and the
 * buttons untouched — the detector only watches. It fires once per
 * press, when a touch that started inside the top edge strip
 * (y <= [edgePx]) travels down past [minSwipePx]. A press that
 * starts below the strip (the game, the stick, the buttons) is
 * ignored entirely, and a tap in the strip never travels far
 * enough to fire.
 *
 * The caller re-arms the hide timer on every fire (a swipe while
 * the bar is already visible counts as interaction with it).
 */
private suspend fun AwaitPointerEventScope.detectTopEdgeSwipeDown(
    edgePx: Float,
    minSwipePx: Float,
    onSwipeDown: () -> Unit,
) {
    // A press that started in the top strip, by pointer id, and
    // whether it has already fired (one reveal per press).
    val startY = HashMap<PointerId, Float>()
    val fired = HashSet<PointerId>()
    while (true) {
        val event = awaitPointerEvent()
        for (change in event.changes) {
            when {
                // A fresh press: record it if it started in the
                // strip, and clear any stale entry (and the fired
                // flag) if it started below it — so a reused
                // pointer id can never fire on old state.
                change.pressed && !change.previousPressed -> {
                    if (change.position.y <= edgePx) {
                        startY[change.id] = change.position.y
                    } else {
                        startY.remove(change.id)
                    }
                    fired.remove(change.id)
                }
                !change.pressed && change.previousPressed -> {
                    startY.remove(change.id)
                    fired.remove(change.id)
                }
                else -> {
                    val start = startY[change.id] ?: continue
                    if (change.id !in fired &&
                        change.position.y - start >= minSwipePx
                    ) {
                        fired.add(change.id)
                        onSwipeDown()
                    }
                }
            }
        }
    }
}
