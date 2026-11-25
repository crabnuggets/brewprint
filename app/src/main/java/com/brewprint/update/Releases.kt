package com.brewprint.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A version such as 1.2.0 or 1.2.0-beta.3. A beta comes before the release it leads to. */
data class Version(val major: Int, val minor: Int, val patch: Int, val beta: Int? = null) : Comparable<Version> {
    override fun compareTo(other: Version): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.beta ?: Int.MAX_VALUE })

    override fun toString() = "$major.$minor.$patch" + (beta?.let { "-beta.$it" } ?: "")

    companion object {
        private val pattern = Regex("""v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-beta\.([1-9]\d*))?""")

        /** Reads a tag such as "v1.2.0" or a version name such as "1.2.0-beta.1"; null for anything else. */
        fun parse(text: String): Version? = pattern.matchEntire(text.trim())?.destructured?.let { (major, minor, patch, beta) ->
            Version(major.toInt(), minor.toInt(), patch.toInt(), beta.takeIf { it.isNotEmpty() }?.toInt())
        }
    }
}

/** A GitHub release with an APK to install. */
data class Release(
    val version: Version,
    val beta: Boolean,
    val apkUrl: String,
    val apkSize: Long,
    /** The APK's SHA-256 in hex, when GitHub lists it. */
    val sha256: String?,
    /** The release's page on GitHub, with its notes. */
    val pageUrl: String,
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val pageUrl: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
    val digest: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

/** The releases in GitHub's list of releases that can be installed: published, with a recognised tag and one APK. */
fun parseReleases(body: String): List<Release> = json.decodeFromString<List<GitHubRelease>>(body).mapNotNull { release ->
    val version = Version.parse(release.tag) ?: return@mapNotNull null
    val apk = release.assets.singleOrNull { it.name.endsWith(".apk") } ?: return@mapNotNull null
    if (release.draft) return@mapNotNull null
    Release(version, release.prerelease || version.beta != null, apk.url, apk.size, apk.digest?.removePrefix("sha256:")?.takeIf { apk.digest.startsWith("sha256:") }, release.pageUrl)
}

/** The newest release above [installed], counting betas only when [betas] is on. Null when the app is up to date. */
fun newestRelease(releases: List<Release>, installed: Version, betas: Boolean): Release? =
    releases.filter { betas || !it.beta }.maxByOrNull { it.version }?.takeIf { it.version > installed }
