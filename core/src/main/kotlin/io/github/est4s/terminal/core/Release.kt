package io.github.est4s.terminal.core

import java.time.Instant
import java.time.format.DateTimeParseException

/** Where release builds look for updates; 404 until the first release. */
const val LATEST_RELEASE_URL = "https://api.github.com/repos/est4s/mynx/releases/latest"

/** A release's APK; [sha256] is GitHub's digest in lowercase hex, null on assets that have none. */
data class ReleaseApk(val name: String, val url: String, val size: Long, val sha256: String?)

/** A GitHub release of the app; [published] is ms since 1970, [notes] Markdown. */
data class Release(
    val version: Version,
    val tag: String,
    val notes: String,
    val published: Long?,
    val prerelease: Boolean,
    val apk: ReleaseApk,
)

/** A release the app can't update to, and why. */
class BadRelease(message: String) : Exception(message)

/** The APK a release of [version] carries, as the release workflow names it. */
fun apkName(version: Version) = "mynx-$version.apk"

private val SHA256 = Regex("[0-9a-f]{64}")

/**
 * Reads one release from GitHub's API. Drafts and releases without
 * their `mynx-X.Y.Z.apk` throw [BadRelease] like broken data does:
 * either way there's nothing to update to.
 */
fun parseRelease(json: String): Release {
    val release = try {
        parseJson(json)
    } catch (e: JsonException) {
        throw BadRelease("not JSON: ${e.message}")
    } as? Map<*, *> ?: throw BadRelease("not a release")
    if (release["draft"] == true) throw BadRelease("a draft")
    val tag = release["tag_name"] as? String ?: throw BadRelease("no tag")
    val version = parseVersion(tag) ?: throw BadRelease("tag $tag isn't a version")
    val name = apkName(version)
    val asset = (release["assets"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull { it["name"] == name }
        ?: throw BadRelease("no $name in $tag")
    val url = asset["browser_download_url"] as? String ?: throw BadRelease("no download link for $name")
    val size = (asset["size"] as? Long)?.takeIf { it >= 0 } ?: throw BadRelease("no size for $name")
    val sha256 = (asset["digest"] as? String)?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
    if (sha256 != null && !SHA256.matches(sha256)) throw BadRelease("bad sha256 for $name")
    val published = (release["published_at"] as? String)?.let {
        try {
            Instant.parse(it).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw BadRelease("bad time $it")
        }
    }
    return Release(
        version = version,
        tag = tag,
        notes = release["body"] as? String ?: "",
        published = published,
        prerelease = release["prerelease"] == true,
        apk = ReleaseApk(name, url, size, sha256),
    )
}
