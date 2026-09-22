package io.snailrun.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.snailrun.MainActivity
import io.snailrun.R
import io.snailrun.SnailRunApp
import io.snailrun.data.haptics.buzz
import io.snailrun.data.prefs.DemoSettings
import io.snailrun.data.repo.SOURCE_DEMO
import io.snailrun.data.repo.SOURCE_RECORDED
import io.snailrun.ui.format.RunFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Keeps the run alive.
 *
 * The service exists to hold a foreground lifetime, a wake lock and a notification; all
 * the arithmetic lives in [RunRecorder]. It is never bound — the UI reads the recorder's
 * state flow directly.
 */
class RunRecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    private val container by lazy { (application as SnailRunApp).container }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be the very first thing done, inside five seconds, or the system kills us.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> scope.launch { container.runRecorder.pause() }
            ACTION_RESUME -> scope.launch { container.runRecorder.resume() }
            ACTION_FINISH -> finishRecording()
            ACTION_RECOVER -> recoverRecording(intent.getLongExtra(EXTRA_RUN_ID, -1))
            ACTION_NEXT_SEGMENT -> scope.launch { container.runRecorder.nextSegment() }
            ACTION_END_SESSION -> scope.launch { container.runRecorder.endSession() }
        }

        // Never sticky. A restart after process death would be a background start of a
        // location foreground service, which Android 14 refuses outright; the run is
        // recovered from Room on next launch instead, with the user's consent.
        return START_NOT_STICKY
    }

    private fun startRecording() {
        acquireWakeLock()
        observeState()
        scope.launch {
            // Read once, at start. A run never switches source halfway through, so the
            // trace a run holds always matches the tag written on it.
            val demo = container.settings.settings.first().demo
            container.runRecorder.start(
                source = if (demo.enabled) SOURCE_DEMO else SOURCE_RECORDED,
                // Taken rather than read: a session is armed for one run, and leaving it
                // armed would guide the next one through a workout nobody asked for.
                session = container.armedWorkout.also { container.armedWorkout = null },
            )
            collectFixes(demo)
        }
    }

    private fun recoverRecording(runId: Long) {
        if (runId <= 0) return
        acquireWakeLock()
        observeState()
        scope.launch {
            if (container.runRecorder.recover(runId)) {
                container.runRecorder.resume()
                // A recovered demo run would restart its synthetic trace from the
                // beginning and teleport; the real chip is the honest thing to continue
                // with, and the run keeps whatever tag it already carries.
                collectFixes(DemoSettings(enabled = false))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun collectFixes(demo: DemoSettings) {
        container.locationSourceFor(demo).fixes().collect { fix ->
            container.runRecorder.onFix(fix)
        }
    }

    private fun observeState() {
        container.runRecorder.onAnnouncement = { announcement ->
            container.voiceAnnouncer.speak(announcement)
        }
        container.runRecorder.onNotice = { notice ->
            container.voiceAnnouncer.speak(notice)
        }
        container.runRecorder.onCue = { cue ->
            // Both channels, always. The voice is the one that says what is happening and
            // the buzz is the one that gets through a pocket, and which of them a given
            // runner is relying on is not something this can know.
            container.voiceAnnouncer.speak(cue)
            cue.buzz()?.let(container.haptics::buzz)
        }
        container.runRecorder.state
            .onEach { state ->
                if (state is RecordingState.Active) {
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(summary(state)))
                }
            }
            .launchIn(scope)
    }

    private fun finishRecording() {
        scope.launch {
            // Finishing writes the run and derives its splits. Nothing else happens
            // here: a run used to be exported as GPX at this point, on a schedule the
            // user never saw, into a folder they picked once months earlier. Every
            // failure mode of that was a notification apologising for a file the user
            // had not asked for. Export is now only ever something you ask for, from
            // the run's own screen.
            container.runRecorder.finish()
            stopSelf()
        }
    }

    private fun summary(state: RecordingState.Active): String {
        val distance = RunFormat.distanceKm(state.metrics.distanceMeters)
        val duration = RunFormat.duration(state.metrics.activeDurationMs)
        val pace = RunFormat.pace(state.metrics.paceSecPerKm)
        val progress = getString(R.string.notification_progress, distance, duration, pace)

        // Where the runner is in the session goes first: it is the line they are pulling
        // the phone out to read, and the figures are the same three as ever.
        val workout = state.workout?.takeIf { !it.complete } ?: return progress
        val step = when {
            workout.segment.isRep ->
                getString(
                    R.string.notification_workout_rep,
                    workout.segment.label,
                    workout.segment.repIndex!!,
                    workout.segment.repCount!!,
                )
            else -> workout.segment.label
        }
        val left = workout.remainingMs?.let { RunFormat.duration(it) }
            ?: workout.remainingM?.let { "${RunFormat.distanceKm(it)} km" }
        return if (left == null) "$step · $progress" else "$step · $left · $progress"
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    /**
     * A foreground service keeps the process alive but not the CPU. Without this the
     * one-second tick and the time-based announcements stop firing once the screen is
     * off and the device suspends between fixes.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            // A bounded timeout so a crash in the release path can never leave the CPU
            // held awake. Twelve hours is far longer than any run this app will see.
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    override fun onDestroy() {
        try {
            container.runRecorder.onAnnouncement = null
            container.runRecorder.onNotice = null
            container.runRecorder.onCue = null
            scope.cancel()
        } finally {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
        super.onDestroy()
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "snail_run:recording"
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000

        const val ACTION_START = "io.snailrun.action.START"
        const val ACTION_PAUSE = "io.snailrun.action.PAUSE"
        const val ACTION_RESUME = "io.snailrun.action.RESUME"
        const val ACTION_FINISH = "io.snailrun.action.FINISH"
        const val ACTION_RECOVER = "io.snailrun.action.RECOVER"
        const val ACTION_NEXT_SEGMENT = "io.snailrun.action.NEXT_SEGMENT"
        const val ACTION_END_SESSION = "io.snailrun.action.END_SESSION"
        const val EXTRA_RUN_ID = "runId"

        fun start(context: Context) = send(context, ACTION_START)
        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun finish(context: Context) = send(context, ACTION_FINISH)
        fun nextSegment(context: Context) = send(context, ACTION_NEXT_SEGMENT)
        fun endSession(context: Context) = send(context, ACTION_END_SESSION)

        fun recover(context: Context, runId: Long) {
            context.startForegroundService(
                Intent(context, RunRecordingService::class.java)
                    .setAction(ACTION_RECOVER)
                    .putExtra(EXTRA_RUN_ID, runId)
            )
        }

        private fun send(context: Context, action: String) {
            context.startForegroundService(
                Intent(context, RunRecordingService::class.java).setAction(action)
            )
        }

        /** Declared for reference; the caller checks it before starting the service. */
        val RequiredPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
