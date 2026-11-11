package app.howlingdog

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Phase 9, item 10 — in-app updater, modelled on Mariner's
 * updater and adapted to HowlingDog (repo DocBobert6/HowlingDog,
 * stable releases only — HowlingDog has no beta/exp channels).
 *
 * The check reads the releases list — not the "/latest" endpoint,
 * so the stable-only filtering in [selectUpdate] (drafts,
 * prereleases, non-stable tags skipped) actually runs and stays
 * unit-tested. When a newer stable release is found, its APK can
 * be downloaded and handed to the system installer from the app;
 * the user confirms the install — there is no silent install.
 *
 * Data use: the check itself is one small GitHub API call. The
 * APK download only starts when the user taps Download, on their
 * current connection.
 */

/** What the last check learned, for the Settings status line. */
enum class CheckOutcome { NEVER, UP_TO_DATE, FAILED }

object UpdateChecker {

    var availableVersion by mutableStateOf<String?>(null)
        private set
    var downloadUrl by mutableStateOf<String?>(null)
        private set
    var expectedSha256 by mutableStateOf<String?>(null)
        private set
    var releasePageUrl by mutableStateOf<String?>(null)
        private set
    var releaseNotes by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var lastCheckOutcome by mutableStateOf(CheckOutcome.NEVER)
        private set

    /** -1 idle, 0..100 downloading percent, -2 failed,
     * -3 downloaded but failed its integrity check,
     * -4 release found but no APK attached to it. */
    var downloadProgress by mutableStateOf(-1)
        private set

    /** Short, sanitized reason for the last -2 download
     * failure, if any. Never carries a URL or token. */
    var lastDownloadError by mutableStateOf<String?>(null)
        private set

    private const val RELEASES_URL =
        "https://api.github.com/repos/DocBobert6/HowlingDog/releases?per_page=5"
    private const val CHECK_INTERVAL_MS = 3600L * 1000

    /**
     * Check for a newer stable release. Automatic checks run at
     * most once an hour and never nag: a dismissed version is not
     * re-offered by the UI (see MainActivity — the dismissal lives
     * in AppSettings, not here). A forced check (Settings) always
     * runs and always reports what it found.
     */
    fun checkAsync(appSettings: AppSettings, currentVersion: String, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - appSettings.lastUpdateCheck() < CHECK_INTERVAL_MS) return
        if (checking) return
        checking = true
        // A stale no-APK state clears while a fresh check runs;
        // the check re-sets it below if the release still has
        // no APK attached.
        if (downloadProgress == -4) downloadProgress = -1
        Thread {
            // Stamped only when a check actually completes with a
            // 200: a failed attempt must not suppress retries for
            // the whole interval when nothing was ever learned.
            var completed = false
            try {
                val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "HowlingDog")
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val found = selectUpdate(parseReleasesJson(body), currentVersion)
                    if (found != null) {
                        availableVersion = found.tagName.removePrefix("v")
                        releaseNotes = found.body.trim().take(4000).ifBlank { null }
                        downloadUrl = found.apkUrl?.takeIf { it.isNotBlank() }
                        expectedSha256 = found.digest
                        releasePageUrl = found.htmlUrl
                        // A release with no APK attached is a
                        // display state, not a download: the UI
                        // says so instead of offering a button.
                        if (downloadUrl == null && downloadProgress < 0) {
                            downloadProgress = -4
                        }
                    } else {
                        availableVersion = null
                        releaseNotes = null
                        downloadUrl = null
                        expectedSha256 = null
                        releasePageUrl = null
                    }
                    if (found == null) {
                        lastCheckOutcome = CheckOutcome.UP_TO_DATE
                    }
                    completed = true
                } else {
                    lastCheckOutcome = CheckOutcome.FAILED
                }
                conn.disconnect()
            } catch (_: Exception) {
                // Offline or no releases yet — stay silent, but let
                // the Settings status line say the check failed.
                lastCheckOutcome = CheckOutcome.FAILED
            } finally {
                if (completed) {
                    appSettings.setLastUpdateCheck(System.currentTimeMillis())
                }
                checking = false
            }
        }.start()
    }

    /** Parse the releases-list JSON into [ReleaseInfo]s (newest first). */
    fun parseReleasesJson(body: String): List<ReleaseInfo> {
        val out = mutableListOf<ReleaseInfo>()
        val releases = org.json.JSONArray(body)
        for (i in 0 until releases.length()) {
            val rel = releases.getJSONObject(i)
            var apk: String? = null
            var sha: String? = null
            val assets = rel.optJSONArray("assets")
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url")
                        sha = parseAssetDigest(a.optString("digest"))
                        break
                    }
                }
            }
            out += ReleaseInfo(
                tagName = rel.optString("tag_name"),
                draft = rel.optBoolean("draft"),
                prerelease = rel.optBoolean("prerelease"),
                apkUrl = apk,
                digest = sha,
                htmlUrl = rel.optString("html_url"),
                body = rel.optString("body"),
            )
        }
        return out
    }

    /** Sanitizes a download failure for display: the exception's
     * short message with any URL (signed download URLs carry
     * tokens) and query string stripped, capped in length. Falls
     * back to the exception class name when nothing usable
     * remains. */
    private fun sanitizeDownloadError(e: Exception): String {
        val raw = e.message?.takeIf { it.isNotBlank() }
            ?: return e.javaClass.simpleName
        val noUrls = raw.replace(Regex("https?://\\S+"), "[url]")
        return noUrls.substringBefore("?").trim().take(200)
            .ifBlank { e.javaClass.simpleName }
    }

    /** Opens the update download, following redirects only to
     * allowlisted hosts. GitHub's download URLs redirect to its
     * CDN, so the hop itself is expected — but only to the CDN
     * host. Returns the connection at a 200 response. */
    private fun openDownloadConnection(startUrl: String): HttpURLConnection {
        var current = startUrl
        repeat(6) {
            if (!isAllowedDownloadUrl(current)) {
                throw IllegalStateException(
                    "Update download URL is not an allowed host"
                )
            }
            val conn =
                URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "HowlingDog")
            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) {
                    throw IllegalStateException(
                        "Update redirect without a destination"
                    )
                }
                current = URL(URL(current), next).toString()
            } else if (code == 200) {
                return conn
            } else {
                conn.disconnect()
                throw IllegalStateException(
                    "Update download returned $code"
                )
            }
        }
        throw IllegalStateException(
            "Too many redirects fetching the update"
        )
    }

    /** Downloads the update APK, then opens the system installer for it. */
    fun downloadAndInstall(context: Context) {
        val url = downloadUrl?.takeIf { it.isNotBlank() } ?: run {
            // No APK attached to the release: never start a
            // download. The UI says so instead.
            downloadProgress = -4
            return
        }
        if (downloadProgress in 0..100) return
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // "Install unknown apps" is not granted for
            // HowlingDog: say so plainly and open the exact
            // system page for this app — never crash, never loop.
            // (canRequestPackageInstalls only exists from API 26;
            // below that there is no per-app install permission.)
            Toast.makeText(
                context,
                "Allow installs from HowlingDog to update in place",
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        downloadProgress = 0
        lastDownloadError = null
        Thread {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "howlingdog-update.apk")
                val conn = openDownloadConnection(url)
                val md = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    val total = conn.contentLengthLong
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            if (total > 0) {
                                downloadProgress = ((done * 100) / total).toInt().coerceIn(0, 100)
                            }
                        }
                    }
                }
                conn.disconnect()
                // Updater integrity (Mariner's fail-closed rule):
                // the file reaches the installer only if it
                // carries the same signing certificate as the app
                // already installed, AND its SHA-256 matches the
                // digest GitHub reported for the asset. With no
                // digest reported the install is refused outright
                // — the signer check alone no longer gates
                // install; anything failing either check is
                // deleted.
                val actualSha = md.digest()
                    .joinToString("") { "%02x".format(it) }
                val expected = expectedSha256
                val hashOk = expected != null &&
                    expected.equals(actualSha, ignoreCase = true)
                if (!hashOk || !signerMatches(context, out)) {
                    out.delete()
                    downloadProgress = -3
                } else {
                    downloadProgress = -1
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider", out
                    )
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                uri,
                                "application/vnd.android.package-archive"
                            )
                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            } catch (e: Exception) {
                lastDownloadError = sanitizeDownloadError(e)
                downloadProgress = -2
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun signerMatches(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        return try {
            val flags = if (android.os.Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val archive = pm.getPackageArchiveInfo(
                apk.absolutePath, flags
            ) ?: return false
            val installed = pm.getPackageInfo(
                context.packageName, flags
            ) ?: return false
            val mine = certBytes(installed)
            certBytes(archive).any { a ->
                mine.any { b -> a.contentEquals(b) }
            }
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun certBytes(info: PackageInfo): List<ByteArray> =
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
                ?.map { it.toByteArray() } ?: emptyList()
        } else {
            info.signatures?.map { it.toByteArray() } ?: emptyList()
        }
}
