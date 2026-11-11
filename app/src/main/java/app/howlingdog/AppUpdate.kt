package app.howlingdog

import java.net.URL

/**
 * Phase 9, item 10 — in-app updater, pure logic (Android-free,
 * unit-tested on the JVM). Modelled on Mariner's updater, adapted
 * to HowlingDog: stable releases only (no beta/exp channels), tags
 * `v1.0.0`, `v1.1.0`, and so on.
 *
 * The version comparison is a full numeric major.minor.patch
 * compare — every component counts. Mariner once had a bug where
 * its check ignored the number after "beta.", so no beta-to-beta
 * update was ever offered; this compare cannot repeat that class
 * of bug, and it is numeric, not lexicographic, so 1.0.10 beats
 * 1.0.9.
 */

/** One GitHub release, already parsed out of the API JSON (see [UpdateChecker.parseReleasesJson]). */
data class ReleaseInfo(
    val tagName: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val apkUrl: String?,
    val digest: String?,
    val htmlUrl: String,
    val body: String,
)

/**
 * True when [tag] is a stable release tag: an optional leading
 * "v", then exactly major.minor.patch, digits only. Anything with
 * a prerelease suffix ("v1.0.0-beta.1"), a missing component
 * ("v1.0"), or an extra one ("v1.0.0.1") is not stable.
 */
fun isStableTag(tag: String): Boolean {
    val core = tag.removePrefix("v")
    val parts = core.split(".")
    if (parts.size != 3) return false
    return parts.all { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
}

/**
 * Numeric version parts of [tag] ("v1.2.3" -> [1, 2, 3]), or null
 * when it is not a plain version. A prerelease suffix is ignored
 * for the comparison itself — the stable-tag filter keeps such
 * releases out of the offer entirely, so this only ever compares
 * stable tags against each other and against the installed
 * version.
 */
fun parseVersion(tag: String): List<Int>? {
    val core = tag.removePrefix("v").substringBefore("-")
    if (core.isBlank()) return null
    val parts = core.split(".")
    if (parts.isEmpty() || parts.size > 3) return null
    val nums = parts.map { it.toIntOrNull() ?: return null }
    if (nums.any { it < 0 }) return null
    return nums
}

/**
 * True when [candidate] is strictly newer than [current],
 * comparing the full version numerically, major then minor then
 * patch. Equal versions are not newer; an unparseable version on
 * either side is never newer.
 */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val a = parseVersion(candidate) ?: return false
    val b = parseVersion(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * The first release in [releases] (newest first, as the GitHub
 * releases list returns them) that is a published, stable, newer
 * release — or null when there is none. Drafts, prereleases, and
 * non-stable tags are skipped; a newer release with no APK
 * attached is still returned (the UI then says so instead of
 * offering a download — the older releases behind it are not
 * offered in its place).
 */
fun selectUpdate(releases: List<ReleaseInfo>, currentVersion: String): ReleaseInfo? {
    for (rel in releases) {
        if (rel.draft) continue
        if (rel.prerelease) continue
        if (!isStableTag(rel.tagName)) continue
        if (!isNewerVersion(rel.tagName, currentVersion)) continue
        return rel
    }
    return null
}

/**
 * True when [raw] is an allowed update-download URL (Mariner's
 * audit S-M3 allowlist, same hosts): https only, and only the
 * three GitHub hosts a release download can ever come from —
 * github.com for the release page link, and
 * objects.githubusercontent.com or
 * release-assets.githubusercontent.com for the asset itself
 * (GitHub redirects release downloads to the latter). Anything
 * else is refused before a byte is fetched.
 */
fun isAllowedDownloadUrl(raw: String): Boolean {
    return try {
        val u = URL(raw)
        u.protocol == "https" &&
            (u.host == "github.com" ||
                u.host == "objects.githubusercontent.com" ||
                u.host == "release-assets.githubusercontent.com")
    } catch (_: Exception) {
        false
    }
}

/**
 * The releases API reports each asset's digest as
 * "sha256:<hex>"; this returns the bare lowercase hex, or null
 * when the value is absent or malformed. A null digest refuses
 * the install outright — the signer check alone never gates an
 * install.
 */
fun parseAssetDigest(raw: String?): String? {
    val hex = raw?.trim()?.removePrefix("sha256:")?.trim()
    return if (hex != null && hex.length == 64 && hex.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }
    ) {
        hex.lowercase()
    } else {
        null
    }
}
