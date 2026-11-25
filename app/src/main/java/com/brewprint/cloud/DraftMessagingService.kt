package com.brewprint.cloud

import com.brewprint.data.BrewRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the server's "drafts changed" message, which carries no draft content: the phone syncs, as it would on
 * opening, and shows its notifications from its own copy of the drafts. See docs/adr/0006.
 */
class DraftMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = CloudSync.tokenChanged(applicationContext)

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "drafts") return
        // Android gives a message about 20 seconds. A sync that takes longer runs on, and its drafts are announced
        // when it's done, for as long as Android keeps the app's process.
        val sync = CloudSync.syncNow(applicationContext)
        runBlocking { withTimeoutOrNull(15_000) { sync?.join() } }
        DraftNotifications.update(applicationContext, BrewRepository.get(applicationContext).state.value)
    }
}
