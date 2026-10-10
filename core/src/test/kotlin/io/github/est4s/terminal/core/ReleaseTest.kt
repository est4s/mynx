package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun releaseJson(
    tag: String = "v0.2.0",
    draft: Boolean = false,
    prerelease: Boolean = false,
    assets: String = apkAsset("mynx-0.2.0.apk"),
    body: String = "\"## What's new\\n- Updates\"",
    published: String = "\"2026-10-08T12:30:00Z\"",
) = """
    {"url": "https://api.github.com/repos/est4s/mynx/releases/1", "tag_name": "$tag", "name": "mynx $tag",
     "draft": $draft, "prerelease": $prerelease, "published_at": $published, "body": $body,
     "assets": [$assets]}
""".trimIndent()

internal fun apkAsset(name: String, size: Long = 1234, digest: String? = "sha256:" + "ab".repeat(32), url: String = "https://github.com/est4s/mynx/releases/download/v0.2.0/$name") =
    """{"name": "$name", "size": $size, "content_type": "application/vnd.android.package-archive",
       "browser_download_url": "$url"${digest?.let { ", \"digest\": \"$it\"" } ?: ""}}"""

class ReleaseTest {
    @Test
    fun `reads a release and its APK`() {
        val other = apkAsset("mynx-0.2.0.apk.sig", size = 10)
        val release = parseRelease(releaseJson(assets = "$other, ${apkAsset("mynx-0.2.0.apk")}"))
        assertEquals(Version(0, 2, 0), release.version)
        assertEquals("v0.2.0", release.tag)
        assertEquals("## What's new\n- Updates", release.notes)
        assertEquals(java.time.Instant.parse("2026-10-08T12:30:00Z").toEpochMilli(), release.published)
        assertEquals(
            ReleaseApk("mynx-0.2.0.apk", "https://github.com/est4s/mynx/releases/download/v0.2.0/mynx-0.2.0.apk", 1234, "ab".repeat(32)),
            release.apk,
        )
    }

    @Test
    fun `a pre-release keeps its suffix in the APK's name`() {
        val release = parseRelease(releaseJson(tag = "v0.3.0-rc.1", prerelease = true, assets = apkAsset("mynx-0.3.0-rc.1.apk")))
        assertEquals(Version(0, 3, 0, "rc.1"), release.version)
        assertTrue(release.prerelease)
        assertEquals("mynx-0.3.0-rc.1.apk", release.apk.name)
    }

    @Test
    fun `missing notes, time and digest are fine`() {
        val release = parseRelease(releaseJson(body = "null", published = "null", assets = apkAsset("mynx-0.2.0.apk", digest = null)))
        assertEquals("", release.notes)
        assertNull(release.published)
        assertNull(release.apk.sha256)
    }

    @Test
    fun `digests in other algorithms are ignored, and the hex is lowercased`() {
        assertNull(parseRelease(releaseJson(assets = apkAsset("mynx-0.2.0.apk", digest = "sha512:00"))).apk.sha256)
        assertEquals("ab".repeat(32), parseRelease(releaseJson(assets = apkAsset("mynx-0.2.0.apk", digest = "sha256:" + "AB".repeat(32)))).apk.sha256)
    }

    @Test
    fun `refuses what it can't install`() {
        val bad = mapOf(
            "not JSON" to "<html>",
            "not an object" to "[]",
            "a draft" to releaseJson(draft = true),
            "no tag" to releaseJson().replace("\"tag_name\": \"v0.2.0\",", ""),
            "a tag that isn't a version" to releaseJson(tag = "nightly"),
            "no APK" to releaseJson(assets = ""),
            "another version's APK" to releaseJson(assets = apkAsset("mynx-0.1.0.apk")),
            "no download URL" to releaseJson(assets = """{"name": "mynx-0.2.0.apk", "size": 5}"""),
            "no size" to releaseJson(assets = """{"name": "mynx-0.2.0.apk", "browser_download_url": "https://x/y"}"""),
            "a bad sha256" to releaseJson(assets = apkAsset("mynx-0.2.0.apk", digest = "sha256:xyz")),
            "a bad time" to releaseJson(published = "\"yesterday\""),
        )
        for ((what, json) in bad) {
            val e = assertFailsWith<BadRelease>(what) { parseRelease(json) }
            assertTrue(e.message!!.isNotBlank(), what)
        }
    }
}
