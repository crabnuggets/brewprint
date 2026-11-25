package com.brewprint.cloud

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.brewprint.BuildConfig
import com.brewprint.data.BrewRepository
import com.brewprint.data.DRAFTS
import com.brewprint.data.LOG_TAG
import com.brewprint.data.RemoteItem
import com.brewprint.data.journalDraft
import com.brewprint.data.libraryDraft
import com.brewprint.data.syncData
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** What Settings shows about the Google account. [available] is false until the app starts syncing, and in tests. */
data class CloudState(
    val available: Boolean = false,
    val email: String? = null,
    val syncing: Boolean = false,
    val syncedAt: Long? = null,
    val failed: Boolean = false,
)

@Serializable
private data class RecordRow(
    @SerialName("user_id") val userId: String,
    val kind: String,
    val id: String,
    val data: JsonElement?,
    val deleted: Boolean,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** A phone that wants to hear when the account's drafts change. See docs/adr/0006. */
@Serializable
private data class DeviceRow(@SerialName("user_id") val userId: String, val token: String)

/** A library draft or tasting notes Claude wrote to the account. */
@Serializable
private data class DraftRow(val id: String, val kind: String, val data: JsonElement)

/** Signs in with Google through Supabase and keeps this device's library and brews in step with the account. */
object CloudSync {
    private const val PAGE = 1000L
    private val mutable = MutableStateFlow(CloudState())
    val state: StateFlow<CloudState> = mutable.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var repository: BrewRepository
    private lateinit var context: Context
    private val client: SupabaseClient by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            install(Auth)
            install(Postgrest)
            install(Functions)
        }
    }

    /** Starts once per process: restores the session, then syncs on sign-in and after each local change. */
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        if (::repository.isInitialized || BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) return
        this.context = context.applicationContext
        repository = BrewRepository.get(context)
        mutable.update { it.copy(available = true) }
        scope.launch {
            client.auth.sessionStatus.collect { status ->
                if (status is SessionStatus.Authenticated) {
                    val user = status.session.user ?: return@collect
                    // Under the lock, a late emission (such as a token refresh) can't rebind an account that just signed out.
                    // Binding is refused while another account's changes wait; signIn then ends this session.
                    val bound = lock.withLock { client.auth.currentUserOrNull()?.id == user.id && repository.syncWith(user.id).isSuccess }
                    if (!bound) return@collect
                    mutable.update { it.copy(email = user.email) }
                    sync()
                    registerDevice()
                } else if (status is SessionStatus.NotAuthenticated) {
                    mutable.update { CloudState(available = true) }
                }
            }
        }
        scope.launch {
            // Any edit to a synced item counts, even one already waiting, so an edit made during an upload is sent too.
            repository.state.distinctUntilChanged { a, b -> a.beans === b.beans && a.grinders === b.grinders && a.recipes === b.recipes && a.brews === b.brews && a.sync.pending == b.sync.pending }
                .debounce(2_000).filter { it.sync.pending.isNotEmpty() }.collect { sync() }
        }
        scope.launch {
            // Drafts arrive, change and go with each sync and each review, here or on another phone.
            repository.state.distinctUntilChanged { a, b -> a.libraryDrafts === b.libraryDrafts && a.journalDrafts === b.journalDrafts && a.brews === b.brews && a.draftNotifications == b.draftNotifications && a.sync.userId == b.sync.userId }
                .collect { DraftNotifications.update(this@CloudSync.context, it) }
        }
        scope.launch {
            // Turning "Drafts from Claude" on or off tells the server whether to reach this phone.
            repository.state.map { it.draftNotifications }.distinctUntilChanged().collect { registerDevice() }
        }
    }

    /**
     * Syncs at once, for a message from the server while the app may be closed. The sync runs on even if its caller
     * stops waiting, and the notifications follow it once it's done.
     */
    fun syncNow(context: Context): kotlinx.coroutines.Job? {
        start(context)
        if (!::repository.isInitialized) return null
        return scope.launch {
            client.auth.awaitInitialization()
            sync()
        }
    }

    /** Firebase gave this phone a new token; the server needs it to keep reaching the phone. */
    fun tokenChanged(context: Context) {
        start(context)
        if (::repository.isInitialized) scope.launch { registerDevice() }
    }

    /**
     * Gives the server this phone's Firebase token while "Drafts from Claude" is on and an account is signed in, and takes
     * it back when the switch is turned off. Firebase has no token for the phone until then. Failures wait for the next try.
     */
    private suspend fun registerDevice() = deviceLock.withLock { runCatching {
        client.auth.awaitInitialization()
        val user = client.auth.currentUserOrNull() ?: return@runCatching
        val state = repository.state.value
        if (state.sync.userId != user.id) return@runCatching
        val messaging = FirebaseMessaging.getInstance()
        if (state.draftNotifications) {
            // Noted before Firebase is involved, so turning the switch off cleans up after any step that got this far.
            devices().edit().putBoolean(ACTIVE, true).commit()
            messaging.isAutoInitEnabled = true
            val token = messaging.token.awaitResult()
            devices().edit().putString(TOKEN, token).apply()
            client.from("devices").upsert(DeviceRow(user.id, token)) { onConflict = "user_id,token" }
        } else {
            if (!devices().getBoolean(ACTIVE, false)) return@runCatching
            messaging.isAutoInitEnabled = false
            devices().getString(TOKEN, null)?.let { token -> client.from("devices").delete { filter { eq("token", token) } } }
            messaging.deleteToken().awaitResult()
            devices().edit().remove(TOKEN).remove(ACTIVE).apply()
        }
    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(LOG_TAG, "Couldn't update this phone's draft notifications", it) } }

    /** One registration at a time, so turning the switch on and off quickly ends as the switch does. */
    private val deviceLock = Mutex()

    private const val TOKEN = "token"
    /** Firebase has been given this phone since the switch was last turned off. */
    private const val ACTIVE = "active"
    private fun devices() = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    /** Uploads local changes, then merges the server's. Failures are retried on the next change or app start. */
    fun requestSync() {
        if (::repository.isInitialized) scope.launch { sync() }
    }

    private suspend fun sync() = lock.withLock { syncLocked() }

    private suspend fun syncLocked() {
        val user = client.auth.currentUserOrNull() ?: return
        if (repository.state.value.sync.userId != user.id) return
        mutable.update { it.copy(syncing = true) }
        val result = runCatching {
            push(user.id)
            pull()
        }
        result.exceptionOrNull()?.let { Log.w(LOG_TAG, "Sync failed", it) }
        mutable.update { it.copy(syncing = false, failed = result.isFailure, syncedAt = if (result.isSuccess) System.currentTimeMillis() else it.syncedAt) }
    }

    private suspend fun push(userId: String) {
        val state = repository.state.value
        val pushed = state.sync.pending.associateWith { state.syncData(it) }
        if (pushed.isEmpty()) return
        // Library drafts added or discarded here are deleted from the account, after the items added from them are uploaded.
        val (drafts, records) = pushed.entries.partition { it.key.startsWith("$DRAFTS/") }
        records.chunked(500).forEach { chunk ->
            val rows = chunk.map { (key, data) ->
                val (kind, id) = key.split('/', limit = 2)
                RecordRow(userId, kind, id, data, deleted = data == null)
            }
            client.from("records").upsert(rows) { onConflict = "user_id,kind,id" }
        }
        // Uploaded records are marked first, so a failed deletion can't make them upload again over a newer edit.
        repository.markPushed(records.associate { it.toPair() }).getOrThrow()
        drafts.map { it.key.substringAfter('/') }.chunked(500).forEach { ids ->
            client.from("drafts").delete { filter { isIn("id", ids) } }
        }
        repository.markPushed(drafts.associate { it.toPair() }).getOrThrow()
        // The account's other phones take down the notifications for drafts resolved here.
        if (drafts.isNotEmpty()) runCatching { client.functions.invoke("brewprint-mcp/drafts-changed") }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(LOG_TAG, "Couldn't tell other phones about drafts", it) }
    }

    private suspend fun pull() {
        // A minute of overlap catches changes that committed late; merging the same item twice is harmless.
        val since = repository.state.value.sync.pulledUpTo?.let { OffsetDateTime.parse(it).minusMinutes(1).toString() }
        val rows = pullPages(since, PAGE, { it.updatedAt }) { from, limit ->
            client.from("records").select {
                if (from != null) filter { gte("updated_at", from) }
                order("updated_at", Order.ASCENDING)
                order("kind", Order.ASCENDING)
                order("id", Order.ASCENDING)
                limit(limit)
            }.decodeList<RecordRow>()
        }
        val newest = rows.mapNotNull { it.updatedAt?.let(OffsetDateTime::parse) }.maxOrNull()?.toString()
        repository.applyRemote(rows.map { RemoteItem(it.kind, it.id, if (it.deleted) null else it.data) }, newest).getOrThrow()
        // Drafts are few and short-lived, so every sync fetches them all.
        val drafts = client.from("drafts").select { order("created_at", Order.ASCENDING) }.decodeList<DraftRow>()
        repository.applyDrafts(drafts.mapNotNull { libraryDraft(it.kind, it.id, it.data) }, drafts.mapNotNull { journalDraft(it.kind, it.id, it.data) }).getOrThrow()
    }

    /** Shows Google's account picker and signs in. Returns a message for the user, or null on success or cancel. */
    suspend fun signIn(activity: Context): String? {
        val nonce = UUID.randomUUID().toString()
        val hashed = MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray()).joinToString("") { "%02x".format(it) }
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).setNonce(hashed).build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
            client.auth.signInWith(IDToken) {
                idToken = token
                provider = Google
                this.nonce = nonce
            }
            val user = client.auth.currentUserOrNull() ?: return "Couldn't sign in. Check your connection and try again."
            repository.syncWith(user.id).exceptionOrNull()?.let { refused ->
                endSession(activity)
                return refused.message
            }
            null
        } catch (_: GetCredentialCancellationException) {
            null
        } catch (_: NoCredentialException) {
            "Add a Google account to this phone, then try again."
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(LOG_TAG, "Sign-in failed", error)
            "Couldn't sign in. Check your connection and try again."
        }
    }

    /**
     * Uploads every change, then signs out and removes the account's data from this phone.
     * Returns a message for the user when changes couldn't be uploaded; nothing is removed then.
     */
    suspend fun signOut(context: Context): String? = lock.withLock {
        syncLocked()
        // Checking for unsent changes and removing the data is one step, so an edit can't slip in between.
        repository.signedOut().exceptionOrNull()?.let { return it.message ?: "Couldn't sign out. Try again." }
        // The account stops reaching this phone; the data's gone, so its notifications went with it.
        devices().getString(TOKEN, null)?.let { token -> runCatching { client.from("devices").delete { filter { eq("token", token) } } } }
        endSession(context)
        null
    }

    private suspend fun endSession(context: Context) {
        // Offline, the server can't end the session, but this phone still forgets it.
        runCatching { client.auth.signOut() }.onFailure { client.auth.clearSession() }
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        mutable.update { CloudState(available = true) }
    }
}

/**
 * Fetches every row changed since [since], a page at a time. Each page starts from the last page's newest
 * change rather than an offset, so a row another phone edits mid-pull can't shift an unseen row past a page.
 * Rows repeat across pages; later copies are newer and win when merged.
 */
internal suspend fun <R> pullPages(since: String?, page: Long, updatedAt: (R) -> String?, fetch: suspend (from: String?, limit: Long) -> List<R>): List<R> {
    val rows = mutableListOf<R>()
    var from = since
    while (true) {
        val batch = fetch(from, page)
        rows += batch
        val newest = batch.lastOrNull()?.let(updatedAt)?.let { OffsetDateTime.parse(it).toString() }
        // A full page that doesn't move forward would repeat forever; uploads come in batches of 500, so this can't skip rows.
        if (batch.size < page || newest == null || newest == from) return rows
        from = newest
    }
}

/** The result of a Play services task, without blocking a thread. */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        if (error != null) continuation.resumeWithException(error)
        else if (task.isCanceled) continuation.cancel()
        else @Suppress("UNCHECKED_CAST") continuation.resume(task.result as T)
    }
}
