package app.howlingdog

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Phase 3 library UI. Plain Material3, no era skin yet (the
 * fighting-game select grid arrives in Phase 5).
 *
 * Library screen (the home screen): the persistent game list, the
 * system-files status, and import — one game file, or a whole folder
 * (a folder import brings a cue's data files with it). Games are
 * COPIED into app-private storage (the core only reads real file
 * paths): games to filesDir/games/, system files to filesDir/bios/.
 * Nothing is uploaded anywhere. A game is a game: the list shows
 * names only, never a media type, and the UI never says the banned
 * words for the 1990s systems.
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
            MaterialTheme {
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
        pushPadMask()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputActive || event.repeatCount != 0) return super.onKeyDown(keyCode, event)
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

    private enum class Screen { Library, Playing, Settings }

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
        var screen by remember { mutableStateOf(Screen.Library) }
        var games by remember { mutableStateOf<List<GameEntry>>(emptyList()) }
        var bios by remember { mutableStateOf<BiosStatus?>(null) }
        var status by remember { mutableStateOf("") }
        var playingGame by remember { mutableStateOf<GameEntry?>(null) }
        var confirmRemove by remember { mutableStateOf<GameEntry?>(null) }
        var modePickerFor by remember { mutableStateOf<GameEntry?>(null) }
        var settingsFrom by remember { mutableStateOf(Screen.Library) }
        var paused by remember { mutableStateOf(false) }
        var pauseStatus by remember { mutableStateOf("") }
        var padMask by remember { mutableStateOf(0) }
        var held by remember { mutableStateOf(Held()) }
        var gameView by remember { mutableStateOf<GameView?>(null) }

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
        var stickPos by remember { mutableStateOf(controlSettings.stickPosition()) }
        var buttonsPos by remember { mutableStateOf(controlSettings.buttonsPosition()) }
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
                stickPos = controlSettings.stickPosition()
                buttonsPos = controlSettings.buttonsPosition()
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
            session?.pause()
            paused = true
            pauseStatus = ""
        }

        fun resumeGame() {
            if (!paused) return
            session?.resume()
            paused = false
            pauseStatus = ""
            inputActive = true
        }

        /** One save slot per game, in the saves folder. */
        fun stateFileFor(entry: GameEntry): File =
            File(File(context.filesDir, "saves"), "${entry.mainFile}.state")

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
                onFrame = { bitmap -> gameView?.setBitmap(bitmap) },
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
            onDispose {
                onPadMask = null
                onPauseHotkey = null
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

        when (screen) {
            Screen.Library -> {
                // The whole screen scrolls (a small window or a long
                // library must never push content out of reach).
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("HowlingDog", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Tap a game to play.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(12.dp),
                    ) {
                        Text("System files", style = MaterialTheme.typography.titleSmall)
                        val b = bios
                        if (b == null) {
                            Text("Checking…", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(
                                "System files: ${b.readyCount} of ${b.totalCount} ready",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            BiosRow("Home console", b.home)
                            BiosRow("Arcade", b.arcade)
                            BiosRow("Neo Geo CD", b.system1994)
                            BiosRow("Neo Geo CDZ", b.system1995)
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { biosPicker.launch(null) }) {
                            Text("Pick BIOS folder")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { gamePicker.launch(arrayOf("*/*")) }) {
                            Text("Add game")
                        }
                        Button(onClick = { folderPicker.launch(null) }) {
                            Text("Add folder")
                        }
                        Button(onClick = {
                            settingsFrom = Screen.Library
                            screen = Screen.Settings
                        }) {
                            Text("Settings")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (games.isEmpty()) {
                        Text(
                            "No games yet. Add a game to start.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        // Plain Column, not a lazy list: the screen
                        // itself scrolls, so every row is composed
                        // and reachable. Fine for a v1 library;
                        // revisit if libraries grow very large.
                        Column(modifier = Modifier.fillMaxWidth()) {
                            for (game in games) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        game.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { startGame(game) },
                                    )
                                    TextButton(onClick = { modePickerFor = game }) {
                                        Text(modeLabel(game.effectiveMode))
                                    }
                                    TextButton(onClick = { confirmRemove = game }) {
                                        Text("✕")
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
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
                BackHandler { if (paused) resumeGame() else pauseGame() }
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
                        Text(
                            playingGame?.displayName ?: "Game",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { pauseGame() }) { Text("Pause") }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            factory = { ctx ->
                                GameView(ctx).also { gameView = it }
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
                        // the flat-outline stick (bottom-left) and
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
                            val scale = controlSize.scale
                            // Stick geometry (dp, scaled): base 140,
                            // grab margin 16, touch area 172.
                            val stickTouchDp = 172f * scale
                            val stickTouchPx = with(density) { stickTouchDp.dp.toPx() }
                            val stickOffset = IntOffset(
                                (stickPos.x * boxW - stickTouchPx / 2).roundToInt(),
                                (stickPos.y * boxH - stickTouchPx / 2).roundToInt(),
                            )
                            // Button cluster geometry (dp, scaled):
                            // 2x2 of 56dp buttons, 8dp gaps, then a
                            // 12dp gap and the 48dp Coin/Start row.
                            val buttonDp = 56f * scale
                            val smallDp = 48f * scale
                            val gapDp = 8f * scale
                            val rowGapDp = 12f * scale
                            val buttonsWdp = 2 * buttonDp + gapDp
                            val buttonsHdp = 2 * buttonDp + gapDp + rowGapDp + smallDp
                            val buttonsWPx = with(density) { buttonsWdp.dp.toPx() }
                            val buttonsHPx = with(density) { buttonsHdp.dp.toPx() }
                            val buttonsOffset = IntOffset(
                                (buttonsPos.x * boxW - buttonsWPx / 2).roundToInt(),
                                (buttonsPos.y * boxH - buttonsHPx / 2).roundToInt(),
                            )
                            OutlineStick(
                                size = (140f * scale).dp,
                                grabMargin = (16f * scale).dp,
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
                                modifier = Modifier
                                    .offset { stickOffset }
                                    .size(stickTouchDp.dp),
                            )
                            // Neo Geo buttons (A B / C D) with
                            // Start / Coin in a row beneath them.
                            Column(
                                modifier = Modifier.offset { buttonsOffset },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Row {
                                    HoldButton("A", { pressed ->
                                        held = held.copy(a = pressed); pushMask()
                                    }, scale = scale, onHaptic = ::playHaptic)
                                    Spacer(Modifier.width(gapDp.dp))
                                    HoldButton("B", { pressed ->
                                        held = held.copy(b = pressed); pushMask()
                                    }, scale = scale, onHaptic = ::playHaptic)
                                }
                                Spacer(Modifier.height(gapDp.dp))
                                Row {
                                    HoldButton("C", { pressed ->
                                        held = held.copy(c = pressed); pushMask()
                                    }, scale = scale, onHaptic = ::playHaptic)
                                    Spacer(Modifier.width(gapDp.dp))
                                    HoldButton("D", { pressed ->
                                        held = held.copy(d = pressed); pushMask()
                                    }, scale = scale, onHaptic = ::playHaptic)
                                }
                                Spacer(Modifier.height(rowGapDp.dp))
                                Row {
                                    HoldButton("Coin", { pressed ->
                                        held = held.copy(coin = pressed); pushMask()
                                    }, small = true, scale = scale, onHaptic = ::playHaptic)
                                    Spacer(Modifier.width(gapDp.dp))
                                    HoldButton("Start", { pressed ->
                                        held = held.copy(start = pressed); pushMask()
                                    }, small = true, scale = scale, onHaptic = ::playHaptic)
                                }
                            }
                        }
                        // Pause overlay: covers the game and the
                        // controls (drawn last, so it is on top) and
                        // swallows touches, so no button can be
                        // pressed while paused.
                        if (paused) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.7f))
                                    .clickable(enabled = true, onClick = {}),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "Paused",
                                        color = Color.White,
                                        style = MaterialTheme.typography.headlineMedium,
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Button(onClick = { resumeGame() }) { Text("Resume") }
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { saveState() }) { Text("Save state") }
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { loadState() }) { Text("Load state") }
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = {
                                        settingsFrom = Screen.Playing
                                        screen = Screen.Settings
                                    }) { Text("Settings") }
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { quitToLibrary() }) { Text("Quit to library") }
                                    if (pauseStatus.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(pauseStatus, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Screen.Settings -> {
                BackHandler { screen = settingsFrom }
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

    @Composable
    private fun BiosRow(label: String, ready: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (ready) "✓" else "✗",
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
                    if (held) Color(0xFF880000) else Color(0xFF333333),
                    CircleShape,
                )
                .then(inputModifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White)
        }
    }

    /**
     * The flat-outline arcade stick (replaces the 4-way D-pad).
     *
     * Look: thin white outlines only — a ring for the base, a ring
     * for the knob that follows the thumb, and a small centre dot.
     * No fill, no gradient, no shading, so the game shows through;
     * the overlay's opacity comes from the container's alpha.
     *
     * Behaviour: fixed in place (it never follows the thumb around
     * the screen). Touch down inside the base (plus the grab margin)
     * and drag; the knob follows, clamped to the base, and the
     * direction comes from StickInput's 8 sectors. Release snaps
     * the knob back to centre and clears the direction. A cancelled
     * gesture clears it too, in the finally block — a stuck
     * direction is never left behind. Only one pointer is tracked,
     * so a second thumb on the buttons cannot steal the stick.
     */
    @Composable
    private fun OutlineStick(
        size: Dp, // base diameter
        grabMargin: Dp,
        onDirection: (Int) -> Unit,
        onDirectionChange: (Int) -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
    ) {
        val density = LocalDensity.current
        val touchPx = with(density) { (size + grabMargin * 2).toPx() }
        val grabPx = with(density) { grabMargin.toPx() }
        val baseR = touchPx / 2f - grabPx
        val knobR = baseR * 0.38f
        val maxTravel = baseR - knobR
        var knob by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = modifier
                .pointerInput(enabled, maxTravel) {
                    if (!enabled) return@pointerInput
                    awaitPointerEventScope {
                        // The pointer-input area size (IntSize, px).
                        // Captured here because the local helpers
                        // below do not see the scope's receiver:
                        // an unqualified `size` inside them would
                        // resolve to the stick's Dp parameter.
                        val areaSize = size
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
                            val centre = Offset(
                                areaSize.width / 2f,
                                areaSize.height / 2f,
                            )
                            val raw = position - centre
                            val (cx, cy) = StickInput.clamp(
                                raw.x / maxTravel,
                                raw.y / maxTravel,
                            )
                            knob = Offset(cx * maxTravel, cy * maxTravel)
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
                                    PointerEventType.Release,
                                    PointerEventType.Exit -> {
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
                // Base ring + centre dot.
                drawCircle(
                    color = Color.White,
                    radius = baseR,
                    center = centre,
                    style = Stroke(width = 2.5.dp.toPx()),
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.dp.toPx(),
                    center = centre,
                )
                // Knob ring, following the thumb.
                drawCircle(
                    color = Color.White,
                    radius = knobR,
                    center = centre + knob,
                    style = Stroke(width = 3.5.dp.toPx()),
                )
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
