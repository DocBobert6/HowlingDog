package app.howlingdog

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HowlingDogUi()
                }
            }
        }
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }

    private enum class Screen { Library, Playing }

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
        var held by remember { mutableStateOf(Held()) }
        var gameView by remember { mutableStateOf<GameView?>(null) }

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
                ),
            )
        }

        fun stopGame() {
            session?.stop()
            session = null
            held = Held()
            playingGame = null
            screen = Screen.Library
        }

        fun startGame(entry: GameEntry) {
            val b = bios ?: library.biosStatus()
            if (!library.biosReadyFor(entry.mainFile, b)) {
                status = "This game needs its system files — pick the BIOS folder."
                return
            }
            val saveDir = File(context.filesDir, "saves").apply { mkdirs() }
            status = "Starting ${entry.displayName}…"
            session = PlayerSession(
                systemDir = biosDir.absolutePath,
                saveDir = saveDir.absolutePath,
                gamePath = entry.mainPath(gamesDir).absolutePath,
                onFrame = { bitmap -> gameView?.setBitmap(bitmap) },
                onError = { message ->
                    status = message
                    session = null
                    playingGame = null
                    screen = Screen.Library
                },
            )
            playingGame = entry
            screen = Screen.Playing
            session?.start()
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
                                        .clickable { startGame(game) }
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        game.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
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
            }

            Screen.Playing -> {
                BackHandler { stopGame() }
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
                        Button(onClick = { stopGame() }) { Text("Stop") }
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
                        // fills the whole Box and the buttons overlay
                        // its lower part instead of taking their own
                        // row. In landscape the old row ate ~240dp
                        // and left the game ~125dp tall.
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // D-pad
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                HoldButton("▲", { pressed ->
                                    held = held.copy(up = pressed); pushMask()
                                })
                                Row {
                                    HoldButton("◀", { pressed ->
                                        held = held.copy(left = pressed); pushMask()
                                    })
                                    Spacer(Modifier.width(56.dp))
                                    HoldButton("▶", { pressed ->
                                        held = held.copy(right = pressed); pushMask()
                                    })
                                }
                                HoldButton("▼", { pressed ->
                                    held = held.copy(down = pressed); pushMask()
                                })
                            }
                            // Start / Coin
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                HoldButton("Coin", { pressed ->
                                    held = held.copy(coin = pressed); pushMask()
                                }, small = true)
                                Spacer(Modifier.height(8.dp))
                                HoldButton("Start", { pressed ->
                                    held = held.copy(start = pressed); pushMask()
                                }, small = true)
                            }
                            // Neo Geo buttons: A B / C D
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row {
                                    HoldButton("A", { pressed ->
                                        held = held.copy(a = pressed); pushMask()
                                    })
                                    Spacer(Modifier.width(8.dp))
                                    HoldButton("B", { pressed ->
                                        held = held.copy(b = pressed); pushMask()
                                    })
                                }
                                Spacer(Modifier.height(8.dp))
                                Row {
                                    HoldButton("C", { pressed ->
                                        held = held.copy(c = pressed); pushMask()
                                    })
                                    Spacer(Modifier.width(8.dp))
                                    HoldButton("D", { pressed ->
                                        held = held.copy(d = pressed); pushMask()
                                    })
                                }
                            }
                        }
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
    ) {
        var held by remember { mutableStateOf(false) }
        val size = if (small) 48.dp else 56.dp
        Box(
            modifier = modifier
                .size(size)
                .background(
                    if (held) Color(0xFF880000).copy(alpha = 0.6f)
                    else Color(0xFF333333).copy(alpha = 0.6f),
                    CircleShape,
                )
                .pointerInput(Unit) {
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
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White)
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
