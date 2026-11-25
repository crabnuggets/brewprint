package com.brewprint.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private fun release(tag: String, prerelease: Boolean = tag.contains("-"), draft: Boolean = false, assets: String? = null) = """
        {"tag_name": "$tag", "draft": $draft, "prerelease": $prerelease, "html_url": "https://github.com/crabnuggets/brewprint/releases/tag/$tag", "author": {"login": "someone"},
         "assets": ${assets ?: """[{"name": "brewprint-$tag.apk", "browser_download_url": "https://github.com/crabnuggets/brewprint/releases/download/$tag/brewprint-$tag.apk", "size": 1234, "digest": "sha256:ABC123"}]"""}}
    """.trimIndent()

    private fun list(vararg releases: String) = parseReleases(releases.joinToString(",", "[", "]"))

    @Test fun `a beta comes before its release and after the release before it`() {
        val order = listOf("1.1.0", "1.2.0-beta.1", "1.2.0-beta.2", "1.2.0-beta.10", "1.2.0", "1.10.0", "2.0.0").map { requireNotNull(Version.parse(it)) }
        assertEquals(order, order.shuffled().sorted())
        assertEquals(Version(1, 2, 0, 3), Version.parse("v1.2.0-beta.3"))
        assertEquals("1.2.0-beta.3", Version(1, 2, 0, 3).toString())
    }

    @Test fun `tags that aren't versions are not read as versions`() {
        listOf("", "1.2", "v1.2.0-rc.1", "01.2.0", "1.2.0-beta.0", "latest").forEach { assertNull(it, Version.parse(it)) }
    }

    @Test fun `a release is read with its APK and its checksum`() {
        val read = list(release("v1.2.0")).single()
        assertEquals(Version(1, 2, 0), read.version)
        assertEquals(false, read.beta)
        assertEquals("https://github.com/crabnuggets/brewprint/releases/download/v1.2.0/brewprint-v1.2.0.apk", read.apkUrl)
        assertEquals(1234L, read.apkSize)
        assertEquals("ABC123", read.sha256)
        assertEquals("https://github.com/crabnuggets/brewprint/releases/tag/v1.2.0", read.pageUrl)
    }

    @Test fun `drafts, odd tags and releases without one APK are left out`() {
        val read = list(
            release("v1.0.0"),
            release("v1.3.0", draft = true),
            release("nightly"),
            release("v1.4.0", assets = "[]"),
            release("v1.5.0", assets = """[{"name": "a.apk", "browser_download_url": "x"}, {"name": "b.apk", "browser_download_url": "y"}]"""),
            release("v1.6.0", assets = """[{"name": "notes.txt", "browser_download_url": "x"}, {"name": "brewprint.apk", "browser_download_url": "y"}]"""),
        )
        assertEquals(listOf(Version(1, 0, 0), Version(1, 6, 0)), read.map { it.version })
        assertNull("No digest, no checksum", read.last().sha256)
    }

    @Test fun `betas are offered only to those who want them`() {
        val releases = list(release("v1.2.0-beta.1"), release("v1.1.0"), release("v1.0.0"))
        assertEquals(Version(1, 1, 0), newestRelease(releases, Version(1, 0, 0), betas = false)?.version)
        assertEquals(Version(1, 2, 0, 1), newestRelease(releases, Version(1, 0, 0), betas = true)?.version)
        assertTrue(newestRelease(releases, Version(1, 2, 0, 1), betas = true) == null)
    }

    @Test fun `someone on a beta gets the release it leads to, and nothing older`() {
        val releases = list(release("v1.1.0"), release("v1.2.0-beta.1"))
        assertNull(newestRelease(releases, Version(1, 2, 0, 1), betas = false))
        val final = list(release("v1.2.0"), release("v1.2.0-beta.1"))
        assertEquals(Version(1, 2, 0), newestRelease(final, Version(1, 2, 0, 1), betas = false)?.version)
    }

    @Test fun `GitHub's own answer is read`() {
        // Saved from api.github.com/repos/crabnuggets/brewprint/releases, without the author and uploader details.
        val releases = parseReleases(requireNotNull(javaClass.getResource("/github-releases.json")).readText())
        assertEquals(listOf(Version(1, 2, 0, 1), Version(1, 1, 0), Version(1, 0, 0)), releases.map { it.version })
        val stable = releases[1]
        assertEquals("https://github.com/crabnuggets/brewprint/releases/download/v1.1.0/brewprint-v1.1.0.apk", stable.apkUrl)
        assertEquals(16146487L, stable.apkSize)
        assertEquals("b92245a56beebab22257e573451b85ee708c1aee5d1a0e35748e665d6307f122", stable.sha256)
        assertEquals(stable, newestRelease(releases, Version(1, 0, 0), betas = false))
        assertEquals(releases[0], newestRelease(releases, Version(1, 0, 0), betas = true))
    }

    @Test fun `a pre-release with a plain version counts as a beta`() {
        val releases = list(release("v1.3.0", prerelease = true))
        assertNull(newestRelease(releases, Version(1, 2, 0), betas = false))
        assertEquals(Version(1, 3, 0), newestRelease(releases, Version(1, 2, 0), betas = true)?.version)
    }
}
