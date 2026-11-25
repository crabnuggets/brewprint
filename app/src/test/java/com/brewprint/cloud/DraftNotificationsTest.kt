package com.brewprint.cloud

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.brewprint.data.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DraftNotificationsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private val guji = Beans(id = "beans-1", name = "Guji Hambela", roaster = "Onyx Coffee Lab")
    private val summer = Recipe(id = "recipe-1", name = "Summer V60", dose = 15.0, pulses = listOf(Pulse(water = 240.0, pourSeconds = 40)))
    private val brew = BrewRecord(id = "brew-1", startedAt = 1795400000000L, beans = guji, grinder = Grinder(name = "C40"), setting = 24, recipe = summer)

    private val on = AppState(brews = listOf(brew), draftNotifications = true, sync = SyncState(userId = "user-1"))

    @Before fun visible() { DraftNotifications.appVisible = false }
    @After fun hidden() { DraftNotifications.appVisible = false }

    private fun posted(): Map<String, Notification> =
        shadowOf(manager).activeNotifications.associate { it.tag to it.notification }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test fun `each draft gets a notification that opens its review`() {
        DraftNotifications.update(context, on.copy(libraryDrafts = listOf(LibraryDraft(summer.id, recipe = summer)), journalDrafts = listOf(JournalDraft(brew.id, Journal(impression = "Bright")))))
        val shown = posted()
        assertEquals(setOf(summer.id, brew.id, "summary"), shown.keys)
        val recipe = shown.getValue(summer.id)
        assertEquals("Review recipe from Claude", recipe.title())
        assertEquals("Summer V60 · 15 g · 1:16 · 240 g", recipe.text())
        assertEquals(DraftNotifications.CHANNEL, recipe.channelId)
        // A correction or a second draft never alerts twice.
        assertTrue(recipe.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        val opens = shadowOf(recipe.contentIntent).savedIntent
        assertEquals("recipes", opens.getStringExtra(EXTRA_REVIEW_KIND))
        assertEquals(summer.id, opens.getStringExtra(EXTRA_REVIEW_ID))

        val notes = shown.getValue(brew.id)
        assertEquals("Review tasting notes from Claude", notes.title())
        assertTrue(notes.text().startsWith("Guji Hambela · "))
        assertEquals("journals", shadowOf(notes.contentIntent).savedIntent.getStringExtra(EXTRA_REVIEW_KIND))

        val summary = shown.getValue("summary")
        assertEquals("2 drafts from Claude", summary.title())
        assertEquals("Summer V60, tasting notes", summary.text())
        assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
    }

    @Test fun `beans show their roaster, and one draft needs no summary`() {
        DraftNotifications.update(context, on.copy(libraryDrafts = listOf(LibraryDraft(guji.id, beans = guji))))
        val shown = posted()
        assertEquals(setOf(guji.id), shown.keys)
        assertEquals("Review beans from Claude", shown.getValue(guji.id).title())
        assertEquals("Guji Hambela · Onyx Coffee Lab", shown.getValue(guji.id).text())
    }

    @Test fun `a correction changes the notification in place, and a dismissed one stays dismissed`() {
        DraftNotifications.update(context, on.copy(libraryDrafts = listOf(LibraryDraft(summer.id, recipe = summer))))
        val first = posted().getValue(summer.id)
        val corrected = summer.copy(name = "Summer V60, iced")
        DraftNotifications.update(context, on.copy(libraryDrafts = listOf(LibraryDraft(summer.id, recipe = corrected))))
        val second = posted().getValue(summer.id)
        assertEquals("Summer V60, iced · 15 g · 1:16 · 240 g", second.text())
        assertNotEquals(first.text(), second.text())

        manager.cancelAll()
        DraftNotifications.update(context, on.copy(libraryDrafts = listOf(LibraryDraft(summer.id, recipe = corrected.copy(name = "Again")))))
        assertEquals(emptyMap<String, Notification>(), posted())
    }

    @Test fun `a draft added, saved or discarded takes its notification with it`() {
        DraftNotifications.update(context, on.copy(libraryDrafts = listOf(LibraryDraft(summer.id, recipe = summer), LibraryDraft(guji.id, beans = guji))))
        assertEquals(setOf(summer.id, guji.id, "summary"), posted().keys)
        DraftNotifications.update(context, on.copy(libraryDrafts = listOf(LibraryDraft(guji.id, beans = guji))))
        assertEquals(setOf(guji.id), posted().keys)
        DraftNotifications.update(context, on)
        assertEquals(emptySet<String>(), posted().keys)
    }

    @Test fun `nothing shows while the app is on screen, or later for the drafts it showed`() {
        DraftNotifications.appVisible = true
        DraftNotifications.update(context, on.copy(libraryDrafts = listOf(LibraryDraft(summer.id, recipe = summer))))
        assertEquals(emptySet<String>(), posted().keys)
        DraftNotifications.appVisible = false
        DraftNotifications.update(context, on.copy(libraryDrafts = listOf(LibraryDraft(summer.id, recipe = summer), LibraryDraft(guji.id, beans = guji))))
        assertEquals(setOf(guji.id), posted().keys)
    }

    @Test fun `turning the switch off, or signing out, takes every notification down`() {
        val waiting = on.copy(libraryDrafts = listOf(LibraryDraft(summer.id, recipe = summer), LibraryDraft(guji.id, beans = guji)))
        DraftNotifications.update(context, waiting)
        DraftNotifications.update(context, waiting.copy(draftNotifications = false))
        assertEquals(emptySet<String>(), posted().keys)

        DraftNotifications.update(context, waiting)
        assertEquals(setOf(summer.id, guji.id, "summary"), posted().keys)
        DraftNotifications.update(context, waiting.copy(sync = SyncState()))
        assertEquals(emptySet<String>(), posted().keys)
    }

    @Test fun `with the switch off nothing is posted`() {
        DraftNotifications.update(context, on.copy(draftNotifications = false, libraryDrafts = listOf(LibraryDraft(summer.id, recipe = summer))))
        assertEquals(emptySet<String>(), posted().keys)
    }

    @Test fun `notifications turned off in Android count as blocked`() {
        DraftNotifications.createChannel(context)
        assertEquals(false, DraftNotifications.blocked(context))
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(true, DraftNotifications.blocked(context))
    }
}
