package com.brewprint.cloud

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.brewprint.BuildConfig
import com.brewprint.data.BrewRepository
import com.brewprint.data.RemoteItem
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

/** Signs in with Google through Supabase and keeps this device's library and brews in step with the account. */
object CloudSync {
    private const val PAGE = 1000L
    private val mutable = MutableStateFlow(CloudState())
    val state: StateFlow<CloudState> = mutable.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var repository: BrewRepository
    private val client: SupabaseClient by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            install(Auth)
            install(Postgrest)
        }
    }

    /** Starts once per process: restores the session, then syncs on sign-in and after each local change. */
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        if (::repository.isInitialized || BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) return
        repository = BrewRepository.get(context)
        mutable.update { it.copy(available = true) }
        scope.launch {
            client.auth.sessionStatus.collect { status ->
                if (status is SessionStatus.Authenticated) {
                    val user = status.session.user ?: return@collect
                    // Refused while another account's changes wait; signIn then ends this session.
                    if (repository.syncWith(user.id).isFailure) return@collect
                    mutable.update { it.copy(email = user.email) }
                    sync()
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
    }

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
        mutable.update { it.copy(syncing = false, failed = result.isFailure, syncedAt = if (result.isSuccess) System.currentTimeMillis() else it.syncedAt) }
    }

    private suspend fun push(userId: String) {
        val state = repository.state.value
        val pushed = state.sync.pending.associateWith { state.syncData(it) }
        if (pushed.isEmpty()) return
        pushed.entries.chunked(500).forEach { chunk ->
            val rows = chunk.map { (key, data) ->
                val (kind, id) = key.split('/', limit = 2)
                RecordRow(userId, kind, id, data, deleted = data == null)
            }
            client.from("records").upsert(rows) { onConflict = "user_id,kind,id" }
        }
        repository.markPushed(pushed).getOrThrow()
    }

    private suspend fun pull() {
        // A minute of overlap catches changes that committed late; merging the same item twice is harmless.
        val since = repository.state.value.sync.pulledUpTo?.let { OffsetDateTime.parse(it).minusMinutes(1).toString() }
        val rows = mutableListOf<RecordRow>()
        do {
            val page = client.from("records").select {
                if (since != null) filter { gte("updated_at", since) }
                order("updated_at", Order.ASCENDING)
                order("kind", Order.ASCENDING)
                order("id", Order.ASCENDING)
                range(rows.size.toLong(), rows.size + PAGE - 1)
            }.decodeList<RecordRow>()
            rows += page
        } while (page.size.toLong() == PAGE)
        val newest = rows.mapNotNull { it.updatedAt?.let(OffsetDateTime::parse) }.maxOrNull()?.toString()
        repository.applyRemote(rows.map { RemoteItem(it.kind, it.id, if (it.deleted) null else it.data) }, newest).getOrThrow()
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
