package com.brewprint.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdaterTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val requested = CopyOnWriteArrayList<String>()
    /** Each APK handed to Android, with its attempt. */
    private val installs = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
    @Volatile private var releases = ""
    @Volatile private var apkBody = apk
    /** Holds the next request for the list of releases, or for an APK, until opened. */
    @Volatile private var releasesGate: CountDownLatch? = null
    @Volatile private var apkGate: CountDownLatch? = null

    private fun releasesJson(vararg tags: String, sha: String = apkSha) = tags.joinToString(",", "[", "]") { tag ->
        """{"tag_name": "$tag", "prerelease": ${"-" in tag}, "html_url": "https://example.com/$tag",
            "assets": [{"name": "brewprint-$tag.apk", "browser_download_url": "https://example.com/$tag.apk", "size": ${apk.size}, "digest": "sha256:$sha"}]}"""
    }

    @Before fun setUp() {
        Updater.enabled = true
        Updater.installed = Version(1, 1, 0)
        Updater.open = { url ->
            // A request takes its gate before it counts, so a test that waits for the count knows which one holds it.
            when {
                url.startsWith("https://api.github.com/") -> { val body = releases; val gate = releasesGate.also { releasesGate = null }; requested += url; gate?.await(); body.byteInputStream() }
                url.endsWith(".apk") -> { val gate = apkGate.also { apkGate = null }; requested += url; gate?.await(); apkBody.inputStream() }
                else -> { requested += url; throw IOException("Offline") }
            }
        }
        Updater.install = { _, file, attempt -> installs += attempt to file.readBytes() }
        Updater.reset()
        File(context.cacheDir, "updates").deleteRecursively()
    }

    @After fun tearDown() {
        Updater.enabled = false
        Updater.reset()
    }

    private fun until(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Still ${Updater.state.value}" }
            Thread.sleep(10)
        }
    }

    private fun waitFor(condition: (UpdateState) -> Boolean): UpdateState {
        until { condition(Updater.state.value) }
        return Updater.state.value
    }

    private val offered get() = Updater.state.value as UpdateState.Available

    private fun offer(vararg tags: String) {
        releases = releasesJson(*tags)
        Updater.check(betas = false)
        waitFor { it is UpdateState.Available }
    }

    /** Starts an update and waits until Android has the APK. */
    private fun handOver(brewing: () -> Boolean = { false }): Int {
        val before = installs.size
        Updater.update(context, brewing)
        until { installs.size > before }
        assertEquals(UpdateState.Step.INSTALLING, offered.step)
        return installs.last().first
    }

    private fun answer(attempt: Int, status: Int, confirm: Intent? = null) = UpdateReceiver().onReceive(context,
        Intent().putExtra(Updater.EXTRA_ATTEMPT, attempt).putExtra(PackageInstaller.EXTRA_STATUS, status).apply { if (confirm != null) putExtra(Intent.EXTRA_INTENT, confirm) })

    @Test fun `opening the app finds a newer release once`() {
        releases = releasesJson("v1.2.0", "v1.1.0")
        Updater.start(context, betas = false)
        val found = waitFor { it is UpdateState.Available } as UpdateState.Available
        assertEquals(Version(1, 2, 0), found.release.version)
        Updater.start(context, betas = false)
        Thread.sleep(50)
        assertEquals(1, requested.size)
    }

    @Test fun `the latest release is up to date`() {
        releases = releasesJson("v1.1.0", "v1.0.0")
        Updater.check(betas = false)
        waitFor { it == UpdateState.UpToDate }
    }

    @Test fun `turning on betas offers the newer beta`() {
        releases = releasesJson("v1.2.0-beta.1", "v1.1.0")
        Updater.check(betas = false)
        waitFor { it == UpdateState.UpToDate }
        Updater.check(betas = true)
        val found = waitFor { it is UpdateState.Available } as UpdateState.Available
        assertEquals(Version(1, 2, 0, 1), found.release.version)
        assertTrue(found.release.beta)
    }

    @Test fun `a slow check doesn't overwrite a later one`() {
        releases = releasesJson("v1.2.0-beta.1", "v1.1.0")
        val slow = CountDownLatch(1).also { releasesGate = it }
        Updater.check(betas = true)
        until { requested.size == 1 }
        Updater.check(betas = false)
        waitFor { it == UpdateState.UpToDate }
        slow.countDown()
        Thread.sleep(200)
        assertEquals(UpdateState.UpToDate, Updater.state.value)
    }

    @Test fun `no connection says the check failed`() {
        Updater.open = { throw IOException("Offline") }
        Updater.check(betas = false)
        waitFor { it == UpdateState.CheckFailed }
    }

    @Test fun `debug builds never check`() {
        Updater.enabled = false
        Updater.check(betas = true)
        assertEquals(UpdateState.Unavailable, Updater.state.value)
        assertEquals(emptyList<String>(), requested)
    }

    @Test fun `updating downloads the APK and hands it to Android`() {
        offer("v1.2.0")
        val first = handOver()
        assertArrayEquals(apk, installs.single().second)
        assertEquals("https://example.com/v1.2.0.apk", requested.last())

        // Cancelling Android's dialog offers the update again, without downloading it a second time.
        answer(first, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals(UpdateState.Available(offered.release), Updater.state.value)
        handOver()
        assertEquals(1, requested.count { it.endsWith(".apk") })
    }

    @Test fun `a brew started during the download holds the install`() {
        offer("v1.2.0")
        Updater.update(context) { true }
        waitFor { it is UpdateState.Available && it.step == UpdateState.Step.READY }
        assertEquals(null, offered.problem)
        assertEquals(emptyList<Pair<Int, ByteArray>>(), installs)

        handOver { false }
        assertEquals(1, requested.count { it.endsWith(".apk") })
    }

    @Test fun `changing beta releases during an update checks again once it stops`() {
        offer("v1.2.0")
        val download = CountDownLatch(1).also { apkGate = it }
        Updater.update(context) { false }
        waitFor { it is UpdateState.Available && it.step == UpdateState.Step.DOWNLOADING }
        releases = releasesJson("v1.3.0-beta.1", "v1.2.0")
        Updater.check(betas = true)
        assertEquals(UpdateState.Step.DOWNLOADING, offered.step)
        download.countDown()
        until { installs.size == 1 }

        answer(installs.single().first, PackageInstaller.STATUS_FAILURE_ABORTED)
        val found = waitFor { it is UpdateState.Available && it.release.version == Version(1, 3, 0, 1) } as UpdateState.Available
        assertEquals(UpdateState.Step.READY, found.step)
    }

    @Test fun `a download that doesn't match the release isn't installed`() {
        offer("v1.2.0")
        apkBody = apk.copyOf().also { it[0] = 99 }
        Updater.update(context) { false }
        val failed = waitFor { it is UpdateState.Available && it.problem != null } as UpdateState.Available
        assertEquals(UpdateState.Step.READY, failed.step)
        assertEquals("Couldn't download the update. Check your connection and try again.", failed.problem)
        assertEquals(emptyList<Pair<Int, ByteArray>>(), installs)
        assertEquals(emptyList<String>(), File(context.cacheDir, "updates").list().orEmpty().toList())
    }

    @Test fun `Android's answers reach Settings`() {
        offer("v1.2.0")
        val attempt = handOver()
        answer(attempt, PackageInstaller.STATUS_FAILURE_CONFLICT)
        assertEquals("Android refused this update because it doesn't match the Brewprint on your phone.", offered.problem)
        val second = handOver()
        answer(second, PackageInstaller.STATUS_FAILURE)
        assertEquals("Couldn't install the update. Try again.", offered.problem)
        val third = handOver()
        answer(third, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals(null, offered.problem)
        assertEquals(UpdateState.Step.READY, offered.step)
    }

    @Test fun `leaving Android's confirmation unanswered offers the update again`() {
        offer("v1.2.0")
        val first = handOver()
        Updater.resumed()
        assertEquals("Nothing was shown yet", UpdateState.Step.INSTALLING, offered.step)
        answer(first, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("android.content.pm.action.CONFIRM_INSTALL"))
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.action)
        Updater.resumed()
        assertEquals(UpdateState.Step.READY, offered.step)
        assertEquals(null, offered.problem)

        // A late answer about the first attempt leaves the second alone.
        val second = handOver()
        answer(first, PackageInstaller.STATUS_FAILURE)
        assertEquals(UpdateState.Step.INSTALLING, offered.step)
        answer(second, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals(UpdateState.Step.READY, offered.step)
    }

    @Test fun `opening the app removes downloads it no longer needs`() {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        listOf("brewprint-1.1.0.apk", "brewprint-1.0.0.apk", "brewprint-1.2.0.apk.part", "brewprint-1.2.0.apk").forEach { File(directory, it).writeText("x") }
        releases = releasesJson("v1.1.0")
        Updater.start(context, betas = false)
        waitFor { it == UpdateState.UpToDate }
        until { directory.list()!!.size == 1 }
        assertEquals(listOf("brewprint-1.2.0.apk"), directory.list()!!.toList())
    }
}
