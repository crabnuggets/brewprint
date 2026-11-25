package com.brewprint.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.IntentCompat
import com.brewprint.BuildConfig
import com.brewprint.data.LOG_TAG
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Settings → Version shows about updates. */
sealed interface UpdateState {
    /** A debug build, which can't install a release signed with another key. */
    data object Unavailable : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object CheckFailed : UpdateState
    data class Available(val release: Release, val step: Step = Step.READY, val progress: Float = 0f, val problem: String? = null) : UpdateState

    enum class Step { READY, DOWNLOADING, INSTALLING }
}

/** Checks GitHub Releases for a newer Brewprint, then downloads it and hands it to Android to install. */
object Updater {
    private const val RELEASES_URL = "https://api.github.com/repos/crabnuggets/brewprint/releases?per_page=30"
    private const val DOWNLOAD_FAILED = "Couldn't download the update. Check your connection and try again."
    internal const val INSTALL_FAILED = "Couldn't install the update. Try again."
    internal const val EXTRA_ATTEMPT = "com.brewprint.update.ATTEMPT"

    /** Release builds only: a debug build is signed with another key, so Android refuses a release over it. */
    internal var enabled = !BuildConfig.DEBUG
    internal var installed: Version? = Version.parse(BuildConfig.VERSION_NAME)
    /** Opens a URL for reading; replaced in tests. */
    internal var open: (String) -> InputStream = ::connect
    /** Hands a downloaded APK to Android, tagging its answers with the attempt; replaced in tests. */
    internal var install: (Context, File, Int) -> Unit = ::commit

    private val mutable = MutableStateFlow<UpdateState>(if (enabled) UpdateState.Checking else UpdateState.Unavailable)
    val state: StateFlow<UpdateState> = mutable.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    private var started = false
    /** Counts checks, so only the latest one's answer is shown. */
    private var checks = 0
    /**
     * Counts installs handed to Android, so an answer about an earlier one is ignored. It starts at random, so a
     * session left from before the app last closed doesn't share a number with a new one.
     */
    private var attempt = Random.nextInt(1, Int.MAX_VALUE / 2)
    /** Android's install confirmation is showing, or was until the user left it. */
    private var confirming = false
    /** "Beta releases" changed during an update; check again with it once the update stops. */
    private var recheck: Boolean? = null

    /** Checks once per launch, when the app opens. */
    fun start(context: Context, betas: Boolean) {
        synchronized(lock) {
            if (started) return
            started = true
        }
        if (enabled) scope.launch { removeOldDownloads(context.applicationContext) }
        check(betas)
    }

    /** Looks for a newer release. During a download or install, waits until it stops. */
    fun check(betas: Boolean): Unit = synchronized(lock) {
        val installed = installed
        if (!enabled || installed == null) { mutable.value = UpdateState.Unavailable; return }
        if ((mutable.value as? UpdateState.Available)?.step?.let { it != UpdateState.Step.READY } == true) { recheck = betas; return }
        job?.cancel()
        val check = ++checks
        mutable.value = UpdateState.Checking
        job = scope.launch {
            val result = try {
                val releases = parseReleases(open(RELEASES_URL).use { String(it.readBytes()) })
                newestRelease(releases, installed, betas)?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't check for updates", e)
                UpdateState.CheckFailed
            }
            // A cancelled request can still answer; a later check or an update replaces it.
            synchronized(lock) { if (check == checks && mutable.value == UpdateState.Checking) mutable.value = result }
        }
    }

    /**
     * Downloads the release on offer, then asks Android to install it. Waits instead while [brewing], since installing
     * closes the app.
     */
    fun update(context: Context, brewing: () -> Boolean): Unit = synchronized(lock) {
        val offered = mutable.value as? UpdateState.Available ?: return
        if (offered.step != UpdateState.Step.READY) return
        val app = context.applicationContext
        mutable.value = offered.copy(step = UpdateState.Step.DOWNLOADING, progress = 0f, problem = null)
        job = scope.launch {
            val file = try {
                download(app, offered.release) { progress -> mutable.update { (it as? UpdateState.Available)?.copy(progress = progress) ?: it } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't download ${offered.release.version}", e)
                stopped(offered.copy(problem = DOWNLOAD_FAILED))
                return@launch
            }
            // A brew may have started during the download. The APK stays downloaded for later.
            if (brewing()) { stopped(offered); return@launch }
            val tag = synchronized(lock) {
                mutable.value = offered.copy(step = UpdateState.Step.INSTALLING)
                ++attempt
            }
            try {
                install(app, file, tag)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't start installing ${offered.release.version}", e)
                installEnded(tag, INSTALL_FAILED)
            }
        }
    }

    internal fun isCurrent(tag: Int): Boolean = synchronized(lock) { tag == attempt }

    internal fun confirmationShown(tag: Int): Unit = synchronized(lock) { if (tag == attempt) confirming = true }

    /**
     * The app is back in front. If Android's confirmation was showing, the user cancelled it or left it, so offer the
     * update again. A confirmed install closes the app instead.
     */
    fun resumed(): Unit = synchronized(lock) { if (confirming) installEnded(attempt, null) }

    /** Android finished or stopped install [tag] without closing the app. [problem] is null when the user cancelled. */
    internal fun installEnded(tag: Int, problem: String?): Unit = synchronized(lock) {
        if (tag != attempt) return
        confirming = false
        (mutable.value as? UpdateState.Available)?.takeIf { it.step == UpdateState.Step.INSTALLING }?.let { stopped(it.copy(problem = problem)) }
    }

    /** The update is offered again, as [offer], unless "Beta releases" changed meanwhile. */
    private fun stopped(offer: UpdateState.Available): Unit = synchronized(lock) {
        mutable.value = offer.copy(step = UpdateState.Step.READY, progress = 0f)
        recheck?.let { recheck = null; check(it) }
    }

    /** Puts the state back for the next test. */
    internal fun reset(): Unit = synchronized(lock) {
        job?.cancel()
        started = false
        confirming = false
        recheck = null
        mutable.value = if (enabled) UpdateState.Checking else UpdateState.Unavailable
    }

    private fun downloads(context: Context) = File(context.cacheDir, "updates")

    /** Deletes downloads of this version or older, such as the one that was just installed, and unfinished ones. */
    private fun removeOldDownloads(context: Context) {
        val installed = installed ?: return
        downloads(context).listFiles()?.forEach { file ->
            val version = Version.parse(file.name.removePrefix("brewprint-").removeSuffix(".apk"))
            if (version == null || version <= installed) file.delete()
        }
    }

    private fun download(context: Context, release: Release, progress: (Float) -> Unit): File {
        val directory = downloads(context).apply { mkdirs() }
        val file = File(directory, "brewprint-${release.version}.apk")
        if (complete(file, release)) return file
        val part = File(directory, "${file.name}.part")
        open(release.apkUrl).use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                    if (release.apkSize > 0) progress((total.toFloat() / release.apkSize).coerceAtMost(1f))
                }
            }
        }
        if (!complete(part, release)) { part.delete(); throw IOException("The download doesn't match the release") }
        if (!part.renameTo(file)) throw IOException("Couldn't keep the download")
        return file
    }

    private fun complete(file: File, release: Release): Boolean =
        file.isFile && (release.apkSize <= 0 || file.length() == release.apkSize) && (release.sha256 == null || sha256(file).equals(release.sha256, ignoreCase = true))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun connect(url: String): InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "Brewprint-Android")
        if (connection.url.host == "api.github.com") connection.setRequestProperty("Accept", "application/vnd.github+json")
        val code = connection.responseCode
        if (code !in 200..299) { connection.disconnect(); throw IOException("HTTP $code from ${connection.url.host}") }
        return connection.inputStream
    }

    /** Writes the APK into an install session. Android answers [UpdateReceiver], usually asking the user to confirm. */
    private fun commit(context: Context, file: File, tag: Int) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        // An earlier attempt the user left unanswered would otherwise wait in Android until it expires.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("brewprint.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                // Mutable, so Android can add the install's status to it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val answer = Intent(context, UpdateReceiver::class.java).putExtra(Updater.EXTRA_ATTEMPT, tag)
                session.commit(PendingIntent.getBroadcast(context, tag, answer, flags).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }
}

/** Hears back from Android's installer: shows its confirmation, or reports why the install stopped. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getIntExtra(Updater.EXTRA_ATTEMPT, -1)
        if (!Updater.isCurrent(tag)) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                try {
                    context.startActivity(requireNotNull(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Updater.confirmationShown(tag)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Couldn't show Android's install confirmation", e)
                    Updater.installEnded(tag, Updater.INSTALL_FAILED)
                }
            }
            // Android usually closes the app before it can hear this.
            PackageInstaller.STATUS_SUCCESS -> Updater.installEnded(tag, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.installEnded(tag, null)
            else -> {
                Log.w(LOG_TAG, "Install failed with status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                Updater.installEnded(
                    tag,
                    if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) "Android refused this update because it doesn't match the Brewprint on your phone."
                    else Updater.INSTALL_FAILED,
                )
            }
        }
    }
}
