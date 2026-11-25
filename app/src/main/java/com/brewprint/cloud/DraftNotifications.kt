package com.brewprint.cloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.brewprint.MainActivity
import com.brewprint.R
import com.brewprint.data.AppState
import com.brewprint.ui.dateTime
import com.brewprint.ui.recipeSummary

/** Which review a draft notification opens: "beans", "recipes" or "journals". */
const val EXTRA_REVIEW_KIND = "com.brewprint.REVIEW_KIND"
/** The draft's id; for tasting notes, the brew's. */
const val EXTRA_REVIEW_ID = "com.brewprint.REVIEW_ID"

/** One waiting draft as its notification shows it. */
internal data class DraftNote(val id: String, val kind: String, val title: String, val text: String, val name: String)

/** Claude's drafts waiting on this phone, oldest first, as their notifications show them. */
internal fun draftNotes(state: AppState): List<DraftNote> =
    state.libraryDrafts.mapNotNull { draft ->
        draft.beans?.let { DraftNote(draft.id, "beans", "Review beans from Claude", listOf(it.name, it.roaster).filter(String::isNotBlank).joinToString(" · "), it.name) }
            ?: draft.recipe?.let { DraftNote(draft.id, "recipes", "Review recipe from Claude", "${it.name} · ${recipeSummary(it)}", it.name) }
    } + state.journalDrafts.mapNotNull { draft ->
        state.brews.find { it.id == draft.brewId }?.let { DraftNote(it.id, "journals", "Review tasting notes from Claude", "${it.beans.name} · ${dateTime(it.startedAt)}", "tasting notes") }
    }

/**
 * Shows a notification for each of Claude's drafts while "Drafts from Claude" is on and an account is signed in.
 * A draft is announced once: a correction updates a notification still showing, silently, and one the user dismissed
 * stays dismissed. Its notification goes once the draft is added, saved or discarded, here or on another phone.
 */
object DraftNotifications {
    const val CHANNEL = "claude-drafts"
    private const val GROUP = "claude-drafts"
    /** Every draft's notification has this id, told apart by the draft's id as its tag. */
    private const val ID = 2
    private const val SUMMARY = "summary"
    /** Drafts already announced, with what their notification said, so each alerts only once. */
    private const val SEEN = "draft-notifications"

    /** While the app is on screen its own cards show the drafts, so new ones aren't announced. */
    @Volatile var appVisible = false

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Drafts from Claude", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Beans, recipes and tasting notes Claude drafted for you to review"
            },
        )
    }

    /** True when Android blocks Brewprint's notifications, or this channel alone. */
    fun blocked(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return !manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
    }

    @Synchronized
    fun update(context: Context, state: AppState) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val seenPrefs = context.getSharedPreferences(SEEN, Context.MODE_PRIVATE)
        val seen = seenPrefs.all.mapValues { it.value as? String }
        val enabled = state.draftNotifications && state.sync.userId != null
        val notes = if (enabled) draftNotes(state) else emptyList()
        val edit = seenPrefs.edit()
        (seen.keys - notes.map { it.id }.toSet()).forEach { id ->
            manager.cancel(id, ID)
            edit.remove(id)
        }
        if (notes.isNotEmpty()) createChannel(context)
        val showing = showing(manager)
        notes.forEach { note ->
            val said = "${note.title}\n${note.text}"
            when {
                note.id !in seen -> if (!appVisible) manager.notify(note.id, ID, notification(context, note))
                seen[note.id] != said -> if (note.id in showing) manager.notify(note.id, ID, notification(context, note))
            }
            edit.putString(note.id, said)
        }
        edit.apply()
        val shown = notes.filter { it.id in showing(manager) }
        if (shown.size >= 2) manager.notify(SUMMARY, ID, summary(context, shown)) else manager.cancel(SUMMARY, ID)
    }

    private fun showing(manager: NotificationManager): Set<String> =
        manager.activeNotifications.filter { it.id == ID && it.tag != SUMMARY }.mapNotNullTo(mutableSetOf()) { it.tag }

    private fun builder(context: Context) = Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_draft)
        .setGroup(GROUP)
        .setOnlyAlertOnce(true)
        .setAutoCancel(true)
        .setCategory(Notification.CATEGORY_RECOMMENDATION)

    private fun notification(context: Context, note: DraftNote): Notification = builder(context)
        .setContentTitle(note.title)
        .setContentText(note.text)
        .setContentIntent(PendingIntent.getActivity(
            context, note.id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_REVIEW_KIND, note.kind).putExtra(EXTRA_REVIEW_ID, note.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ))
        .build()

    private fun summary(context: Context, notes: List<DraftNote>): Notification {
        val title = "${notes.size} drafts from Claude"
        val names = notes.joinToString(", ") { it.name }
        return builder(context)
            .setGroupSummary(true)
            // The drafts themselves alert; the summary that gathers them never does.
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setContentTitle(title)
            .setContentText(names)
            .setStyle(Notification.InboxStyle().setBigContentTitle(title).also { style -> notes.forEach { style.addLine(it.title) } })
            .setContentIntent(PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .build()
    }
}
