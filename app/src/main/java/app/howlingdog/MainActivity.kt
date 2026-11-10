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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
 * Phase 2 spike UI. Plain Material3, no era skin yet (the
 * fighting-game select grid arrives with the library in Phase 3+).
 *
 * Setup screen: pick a game file (.neo) and a folder holding the
 * BIOS zips. Both are COPIED into app-private storage (the core only
 * reads real file paths): the game to cacheDir/games/, the zips to
 * filesDir/bios/. Nothing is uploaded anywhere.
 *
 * Game screen: the frame, plus minimal touch controls (directions,
 * A/B/C/D, Start, Coin) — just enough to prove input reaches the
 * core and the game can be steered.
 *
 * No native code is touched here: Bridge loads its libraries lazily
 * when a game starts, so this activity launches with no native code
 * present (the Robolectric startup gate depends on that).
 *
 * Rotation: MainActivity declares configChanges in the manifest, so
 * entering the game screen's forced landscape does not recreate the
 * activity. The picked game/BIOS state is rememberSaveable as a
 * second brace — the Phase 2 device test showed the picks vanishing
 * when the rotation recreated the activity (Start flashed back to a
 * fresh setup screen). screen/held/gameView stay plain remember:
 * if the activity is ever recreated, the safe fallback is the setup
 * screen with the session stopped.
 */
class MainActivity : ComponentActivity() {

    private var session: PlayerSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeUi()
                }
            }
        }
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }

    private enum class Screen { Setup, Playing }

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
    private fun SpikeUi() {
        val context = LocalContext.current
        val activity = context as Activity
        var screen by remember { mutableStateOf(Screen.Setup) }
        // Picked state survives recreation (rememberSaveable). File is
        // not saveable, so the path is what is saved and the File is
        // derived from it. The copied files themselves live on disk
        // (cacheDir/games, filesDir/bios), so a restored path is valid.
        var gamePath by rememberSaveable { mutableStateOf<String?>(null) }
        var gameName by rememberSaveable { mutableStateOf<String?>(null) }
        var biosCount by rememberSaveable { mutableStateOf(0) }
        var status by rememberSaveable { mutableStateOf("Pick a game file and a BIOS folder, then Start.") }
        val gameFile: File? = gamePath?.let { File(it) }
        var held by remember { mutableStateOf(Held()) }
        var gameView by remember { mutableStateOf<GameView?>(null) }

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
            screen = Screen.Setup
        }

        val gamePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val name = displayName(uri) ?: "game.neo"
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("neo", "cue", "chd")) {
                    withContext(Dispatchers.Main) {
                        status = "That file is not a game this spike plays (need .neo, .cue or .chd)."
                    }
                    return@launch
                }
                val dir = File(cacheDir, "games").apply { mkdirs() }
                val dest = File(dir, sanitize(name))
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    withContext(Dispatchers.Main) {
                        gamePath = dest.absolutePath
                        gameName = name
                        status = "Game ready: $name"
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        status = "Could not copy the game file: ${e.message}"
                    }
                }
            }
        }

        val biosPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val dir = File(filesDir, "bios").apply { mkdirs() }
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
                    withContext(Dispatchers.Main) {
                        biosCount = count
                        status = if (count > 0) {
                            "$count BIOS file(s) ready."
                        } else {
                            "No .zip files in that folder. Pick the folder holding the BIOS zips."
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
            Screen.Setup -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("HowlingDog", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Phase 2 spike — bare player",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { gamePicker.launch(arrayOf("*/*")) }) {
                        Text("Pick game file")
                    }
                    Text(gameName ?: "No game picked yet")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { biosPicker.launch(null) }) {
                        Text("Pick BIOS folder")
                    }
                    Text(if (biosCount > 0) "$biosCount BIOS file(s) ready" else "No BIOS files yet")
                    Text(
                        "The BIOS folder needs the BIOS zips (aes.zip, neogeo.zip).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            val file = gameFile ?: return@Button
                            val biosDir = File(filesDir, "bios")
                            val saveDir = File(filesDir, "saves").apply { mkdirs() }
                            status = "Starting ${gameName ?: "game"}…"
                            session = PlayerSession(
                                systemDir = biosDir.absolutePath,
                                saveDir = saveDir.absolutePath,
                                gamePath = file.absolutePath,
                                onFrame = { bitmap -> gameView?.setBitmap(bitmap) },
                                onError = { message ->
                                    status = message
                                    session = null
                                    screen = Screen.Setup
                                },
                            )
                            screen = Screen.Playing
                            session?.start()
                        },
                        enabled = gameFile != null && biosCount > 0,
                    ) {
                        Text("Start")
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
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
                            gameName ?: "Game",
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

    private fun sanitize(name: String): String {
        return name.map { c ->
            if (c.isLetterOrDigit() || c in "._-") c else '_'
        }.joinToString("").take(120).ifBlank { "game.neo" }
    }
}
