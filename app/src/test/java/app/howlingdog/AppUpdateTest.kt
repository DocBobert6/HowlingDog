package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the in-app updater's version comparison and release filtering (Phase 9, item 10). */
class AppUpdateTest {

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        apkUrl: String? = "https://github.com/DocBobert6/HowlingDog/releases/download/$tag/howlingdog.apk",
    ) = ReleaseInfo(
        tagName = tag,
        draft = draft,
        prerelease = prerelease,
        apkUrl = apkUrl,
        digest = "sha256:" + "a".repeat(64),
        htmlUrl = "https://github.com/DocBobert6/HowlingDog/releases/tag/$tag",
        body = "Notes for $tag",
    )

    // --- version comparison: full numeric major.minor.patch ---

    @Test
    fun newerMajorMinorPatch() {
        assertTrue(isNewerVersion("1.0.0", "0.1.1"))
        assertTrue(isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(isNewerVersion("1.1.0", "1.0.9"))
        assertTrue(isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun numericNotLexicographic() {
        // The classic trap: "10" < "9" as text, but 10 > 9 as a number.
        assertTrue(isNewerVersion("1.0.10", "1.0.9"))
        assertFalse(isNewerVersion("1.0.9", "1.0.10"))
        assertTrue(isNewerVersion("1.10.0", "1.9.0"))
    }

    @Test
    fun equalVersionIsNotNewer() {
        assertFalse(isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", "v1.0.0"))
    }

    @Test
    fun olderVersionIsNotNewer() {
        assertFalse(isNewerVersion("0.1.1", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", "1.0.1"))
        assertFalse(isNewerVersion("1.0.0", "1.1.0"))
    }

    @Test
    fun leadingVIsIgnored() {
        assertTrue(isNewerVersion("v1.1.0", "v1.0.0"))
        assertTrue(isNewerVersion("v2.0.0", "1.9.9"))
    }

    @Test
    fun missingComponentsCountAsZero() {
        assertTrue(isNewerVersion("1.1", "1.0.9"))
        assertFalse(isNewerVersion("1.0", "1.0.0"))
    }

    @Test
    fun unparseableVersionIsNeverNewer() {
        assertFalse(isNewerVersion("banana", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", "banana"))
        assertFalse(isNewerVersion("", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", ""))
        assertFalse(isNewerVersion("1.0.x", "1.0.0"))
    }

    // --- stable tags ---

    @Test
    fun stableTagsAccepted() {
        assertTrue(isStableTag("v1.0.0"))
        assertTrue(isStableTag("1.1.0"))
        assertTrue(isStableTag("v2.0.0"))
    }

    @Test
    fun unstableTagsRejected() {
        assertFalse(isStableTag("v1.0.0-beta.1"))
        assertFalse(isStableTag("v1.0.0-rc.1"))
        assertFalse(isStableTag("v1.0"))
        assertFalse(isStableTag("v1.0.0.1"))
        assertFalse(isStableTag("v1.0.x"))
        assertFalse(isStableTag(""))
        assertFalse(isStableTag("v1.0.0-"))
    }

    // --- release filtering: published, not draft, not prerelease, stable tag only ---

    @Test
    fun picksNewestStableRelease() {
        val found = selectUpdate(
            listOf(release("v1.1.0"), release("v1.0.0")),
            "1.0.0",
        )
        assertEquals("v1.1.0", found?.tagName)
    }

    @Test
    fun skipsDrafts() {
        val found = selectUpdate(
            listOf(release("v1.2.0", draft = true), release("v1.1.0")),
            "1.0.0",
        )
        assertEquals("v1.1.0", found?.tagName)
    }

    @Test
    fun skipsPrereleases() {
        val found = selectUpdate(
            listOf(release("v1.2.0", prerelease = true), release("v1.1.0")),
            "1.0.0",
        )
        assertEquals("v1.1.0", found?.tagName)
    }

    @Test
    fun skipsNonStableTags() {
        val found = selectUpdate(
            listOf(release("v1.2.0-beta.1"), release("v1.1.0")),
            "1.0.0",
        )
        assertEquals("v1.1.0", found?.tagName)
    }

    @Test
    fun skipsReleasesThatAreNotNewer() {
        assertNull(selectUpdate(listOf(release("v1.0.0")), "1.0.0"))
        assertNull(selectUpdate(listOf(release("v0.1.1")), "1.0.0"))
    }

    @Test
    fun emptyListAndAllSkippedGiveNull() {
        assertNull(selectUpdate(emptyList(), "1.0.0"))
        assertNull(
            selectUpdate(
                listOf(
                    release("v2.0.0", draft = true),
                    release("v2.0.0", prerelease = true),
                    release("v2.0.0-beta.1"),
                ),
                "1.0.0",
            )
        )
    }

    @Test
    fun newerReleaseWithoutApkIsStillSelected() {
        // The UI then says the release has no APK instead of
        // offering a download — older releases are not offered
        // in its place.
        val found = selectUpdate(
            listOf(release("v1.1.0", apkUrl = null), release("v1.0.1")),
            "1.0.0",
        )
        assertEquals("v1.1.0", found?.tagName)
        assertNull(found?.apkUrl)
    }

    // --- download URL allowlist ---

    @Test
    fun allowedDownloadHosts() {
        assertTrue(isAllowedDownloadUrl("https://github.com/DocBobert6/HowlingDog/releases/download/v1.1.0/howlingdog.apk"))
        assertTrue(isAllowedDownloadUrl("https://objects.githubusercontent.com/abc/howlingdog.apk"))
        assertTrue(isAllowedDownloadUrl("https://release-assets.githubusercontent.com/abc/howlingdog.apk"))
    }

    @Test
    fun disallowedDownloadUrls() {
        assertFalse(isAllowedDownloadUrl("http://github.com/x.apk"))
        assertFalse(isAllowedDownloadUrl("https://evil-github.com/x.apk"))
        assertFalse(isAllowedDownloadUrl("https://github.com.evil.com/x.apk"))
        assertFalse(isAllowedDownloadUrl("https://example.com/x.apk"))
        assertFalse(isAllowedDownloadUrl("not a url"))
        assertFalse(isAllowedDownloadUrl(""))
    }

    // --- asset digest ---

    @Test
    fun assetDigestParsing() {
        val hex = "a".repeat(64)
        assertEquals(hex, parseAssetDigest("sha256:$hex"))
        assertEquals(hex, parseAssetDigest("sha256:${"A".repeat(64)}"))
        assertNull(parseAssetDigest(null))
        assertNull(parseAssetDigest(""))
        assertNull(parseAssetDigest("sha256:xyz"))
        assertNull(parseAssetDigest("sha256:${"a".repeat(63)}"))
        assertNull(parseAssetDigest("md5:${"a".repeat(32)}"))
    }
}
