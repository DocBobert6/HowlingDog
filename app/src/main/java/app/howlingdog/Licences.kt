package app.howlingdog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The Licences / About screen (Phase 8): the full licence texts
 * for every library compiled into the APK, plus the frontend's
 * GPL-3.0 note and the SNK disclaimer.
 *
 * The text is the repo-root NOTICE file, copied verbatim into
 * `res/raw/notice.txt` — CI diffs the two, so they cannot drift
 * apart. The NOTICE preamble states the frontend is GPL-3.0 and
 * that HowlingDog is not affiliated with or endorsed by SNK, so
 * the screen itself only adds a title and a short intro.
 *
 * Reached from Settings (About section, "Licences" button). Back
 * returns to Settings.
 */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val noticeText = remember {
        context.resources.openRawResource(R.raw.notice).bufferedReader().use { it.readText() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Licences", style = MaterialTheme.typography.headlineMedium)
        Text(
            "The licences for every library in HowlingDog. The full texts are below.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text(noticeText, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}
