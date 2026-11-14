package com.brewprint.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.RequiresApi
import com.brewprint.MainActivity
import com.brewprint.R
import com.brewprint.data.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val CHANNEL = "brew-timer"
private const val NOTIFICATION_ID = 1
const val EXTRA_OPEN_DRAFT = "com.brewprint.OPEN_DRAFT"
const val ACTION_PAUSE = "com.brewprint.timer.PAUSE"
const val ACTION_RESUME = "com.brewprint.timer.RESUME"
const val ACTION_SKIP = "com.brewprint.timer.SKIP"
const val ACTION_UNDO = "com.brewprint.timer.UNDO"
const val ACTION_FINISH = "com.brewprint.timer.FINISH"
const val ACTION_UNFINISH = "com.brewprint.timer.UNFINISH"

fun vibrateStep(context: Context) {
    context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
}

/** What a notification action does to the draft. Stale taps, such as Undo after its 5 s, change nothing. */
internal fun applyTimerAction(action: String?, draft: BrewDraft, timer: BrewTimer, recipe: Recipe, now: Long): BrewDraft = when (action) {
    ACTION_PAUSE -> draft.copy(timer = timer.pause(recipe, now))
    ACTION_RESUME -> draft.copy(timer = timer.resume(recipe, now))
    // The third action shows Undo and then turns back into the skip; ignore a tap meant for Undo that lands just after.
    ACTION_SKIP -> if (timer.undo != null && now < timer.undoUntil + SKIP_GUARD_MILLIS) draft else draft.copy(timer = timer.skip(recipe, now))
    ACTION_UNDO -> draft.copy(timer = timer.undone(now))
    // The draft may have been left on setup by going Back to the Brew screen; a timed draft always has a valid plan.
    ACTION_FINISH -> timer.finish(recipe, now).let { finished ->
        if (finished.finished && draft.stage != DraftStage.REVIEW) draft.copy(timer = finished, stage = DraftStage.REVIEW) else draft
    }
    ACTION_UNFINISH -> if (timer.finishedAt != null && now < timer.finishedAt + UNDO_MILLIS && draft.stage == DraftStage.REVIEW) {
        draft.copy(timer = timer.unfinished(), stage = DraftStage.SHEET)
    } else draft
    else -> draft
}

class BrewTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        BrewRepository.get(context).updateTimer { draft, timer, recipe -> applyTimerAction(intent.action, draft, timer, recipe, now) }
        BrewTimerService.sync(context)
    }
}

/**
 * Shows the running brew as one ongoing notification. All timing comes from the saved timer,
 * so the service only redraws: when the timer changes, at each step change, and once a second while someone could see it.
 */
class BrewTimerService : Service() {
    companion object {
        @Volatile var running = false
            private set

        private var instance: BrewTimerService? = null

        /**
         * True while the activity is on screen. Finishing there needs no "Finished" notification, and the
         * notification is not redrawn on the tick: the status-bar capsule is hidden then, and it replays every
         * update it missed when it appears. Leaving the app redraws once and starts the tick.
         */
        @Volatile var appOpen = false
            set(value) {
                field = value
                instance?.render()
            }

        private fun live(context: Context, state: AppState, now: Long): TimerNow? {
            val timer = state.draft?.timer ?: return null
            val recipe = state.draft.recipe ?: return null
            if (!state.timerSettings.liveNotification) return null
            if (context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == false) return null
            return TimerNow(recipe, timer, now)
        }

        /** Starts the service when a timer should be showing, and clears a leftover notification when none should. */
        fun sync(context: Context) {
            val view = live(context, BrewRepository.get(context).state.value, System.currentTimeMillis())
            if (running) return
            if (view?.active == true) runCatching { context.startForegroundService(Intent(context, BrewTimerService::class.java)) }
            else if (view?.phase != TimerPhase.FINISHED) clearFinished(context)
        }

        /** Removes the "Finished" notification left behind after Finish brew from the lock screen. */
        fun clearFinished(context: Context) {
            if (!running) context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val wake = Runnable { render() }
    private var lastStep: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // With the screen off nothing is visible; redraw at once when it comes back on.
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Brew timer", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The running brew, with its controls"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        // Step changes must arrive on time with the screen off. The timer stops itself, so this is bounded.
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "brewprint:timer").apply { acquire(45 * 60_000L) }
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        scope.launch { BrewRepository.get(this@BrewTimerService).state.collect { render() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        render()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        handler.removeCallbacks(wake)
        unregisterReceiver(screen)
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun render() {
        handler.removeCallbacks(wake)
        val now = System.currentTimeMillis()
        val view = live(this, BrewRepository.get(this).state.value, now)
        if (view == null || view.phase == TimerPhase.STOPPED) return leave(null)
        if (view.phase == TimerPhase.FINISHED) {
            val undoUntil = view.timer.finishedAt!! + UNDO_MILLIS
            if (appOpen) return leave(null)
            if (now >= undoUntil) return leave(finished(view, undo = false))
            if (foreground(finished(view, undo = true))) handler.postDelayed(wake, undoUntil - now)
            return
        }
        if (lastStep != null && lastStep != view.step && BrewRepository.get(this).state.value.timerSettings.vibrate) vibrateStep(this)
        lastStep = view.step
        if (!foreground(live(view))) return
        // The capsule's seconds are our own text, so redraw each second, in step with the countdown. Skip it while
        // nobody can see the capsule: the screen is off, or the app is open (the capsule then replays missed updates).
        val drawdown = view.phase == TimerPhase.DRAWDOWN
        val origin = if (drawdown) view.timer.startedAt else view.timer.stepAt
        val visible = getSystemService(PowerManager::class.java).isInteractive && !appOpen
        val next = listOfNotNull(
            (now + 1000 - (now - origin) % 1000).takeIf { !view.paused && visible },
            // Step changes are always drawn, for the vibration and so the state is right whenever it is seen.
            (view.timer.stepAt + view.stepDuration).takeIf { !view.paused && !drawdown },
            view.timer.undoUntil.takeIf { view.canUndo },
            view.timer.stopsAt(view.recipe),
        ).filter { it > now }.min()
        handler.postDelayed(wake, next - now + 20)
    }

    /** Becomes foreground with [notification]. False if Android refused, in which case the service stops. */
    private fun foreground(notification: Notification): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }.onFailure { stopSelf() }.isSuccess

    /** Stops the service, leaving [last] behind as an ordinary notification if given. */
    private fun leave(last: Notification?) {
        // A service started with startForegroundService must become foreground once before it may stop.
        if (foreground(last ?: base().setContentTitle("Brew timer").build())) {
            stopForeground(if (last != null) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
            if (last != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, last)
        }
        stopSelf()
    }

    private fun base(): Notification.Builder = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_timer)
        .setOnlyAlertOnce(true)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_STOPWATCH)
        .setShowWhen(false)
        .setContentIntent(PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_DRAFT, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ))

    private fun Notification.Builder.action(label: String, icon: Int, action: String): Notification.Builder {
        val intent = PendingIntent.getBroadcast(this@BrewTimerService, 0, Intent(action).setClass(this@BrewTimerService, BrewTimerReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return addAction(Notification.Action.Builder(Icon.createWithResource(this@BrewTimerService, icon), label, intent).build())
    }

    private fun Notification.Builder.undoOr(label: String, icon: Int, action: String, view: TimerNow): Notification.Builder =
        if (view.canUndo) action("Undo", R.drawable.ic_undo, ACTION_UNDO) else action(label, icon, action)

    // Tapping the notification opens the review, so the only action is the one that cannot wait.
    private fun finished(view: TimerNow, undo: Boolean): Notification = base()
        .setContentTitle("Brew time ${formatDuration(view.brewSeconds)}")
        .setContentText("Tap to add tasting notes")
        .setAutoCancel(true)
        .apply { if (undo) action("Undo", R.drawable.ic_undo, ACTION_UNFINISH) }
        .build()

    /**
     * One job per surface. The small icon says the state. The status-bar chip adds the number to act on:
     * the scale target and seconds left. The title is the instruction, the header clock counts the step,
     * and the bar shows the whole brew. Tapping opens the sheet, so the actions are only pause and skip.
     */
    private fun live(view: TimerNow): Notification {
        val grams = { index: Int -> "${formatAmount(view.schedule[index].cumulativeWater)} g" }
        val pouring = view.phase == TimerPhase.POUR
        val builder = base().setOngoing(true)
        val chip: String
        when {
            view.phase == TimerPhase.DRAWDOWN -> {
                chip = formatDuration(view.brewSeconds)
                builder.setSmallIcon(R.drawable.ic_timer)
                    .setContentTitle("Drawdown")
                    .setContentText(view.skipSummary ?: "Pours ended at ${formatDuration(view.poursEndSeconds)}")
                    .setWhen(view.timer.startedAt).setShowWhen(true).setUsesChronometer(true)
                    .action("Finish", R.drawable.ic_done, ACTION_FINISH)
                    .apply { if (view.canUndo) action("Undo", R.drawable.ic_undo, ACTION_UNDO) }
            }
            view.paused -> {
                chip = "Paused"
                builder.setSmallIcon(R.drawable.ic_pause)
                    .setContentTitle("Paused")
                    .setContentText("${view.leftSeconds} s of ${if (pouring) "pour to ${grams(view.pulse)}" else "wait"} left")
                    .action("Resume", R.drawable.ic_play, ACTION_RESUME)
                    .undoOr(view.skipLabel!!, R.drawable.ic_skip, ACTION_SKIP, view)
            }
            else -> {
                // Kept as short as possible: ColorOS sizes the capsule to this text.
                chip = if (pouring) "${grams(view.pulse).replace(" ", "")} ${view.leftSeconds}s" else "${view.leftSeconds}s"
                builder.setSmallIcon(if (pouring) R.drawable.ic_pour else R.drawable.ic_wait)
                    .setContentTitle(if (pouring) "Pour to ${grams(view.pulse)}" else "Wait, then ${grams(view.pulse + 1)}")
                    .setContentText(view.skipSummary ?: if (pouring) "Pulse ${view.pulse + 1} of ${view.pulseCount}" else "${view.pulseName(view.pulse + 1)} at ${formatDuration(view.startSeconds(view.pulse + 1))}")
                    .setWhen(view.timer.stepAt + view.stepDuration).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                    .action("Pause", R.drawable.ic_pause, ACTION_PAUSE)
                    .undoOr(view.skipLabel!!, R.drawable.ic_skip, ACTION_SKIP, view)
            }
        }
        if (Build.VERSION.SDK_INT >= 36) promote(builder, view, chip)
        else if (view.phase != TimerPhase.DRAWDOWN) builder.setProgress((view.plannedTotalMillis / 1000).toInt(), (view.plannedMillis / 1000).toInt(), false)
        return builder.build()
    }

    /** Android 16: ask for a Live Update, set the status-bar chip text, and draw one bar segment per pulse. */
    @RequiresApi(36)
    private fun promote(builder: Notification.Builder, view: TimerNow, chip: String) {
        builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        builder.setShortCriticalText(chip)
        if (view.phase == TimerPhase.DRAWDOWN) return
        builder.setStyle(
            Notification.ProgressStyle()
                .setProgressSegments(view.recipe.pulses.map { Notification.ProgressStyle.Segment(it.pourSeconds + it.waitSeconds) })
                .setProgress((view.plannedMillis / 1000).toInt())
                .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_pour)),
        )
    }
}
