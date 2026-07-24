package com.heirloom.app.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.heirloom.app.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Owns the long restoration request independently of the activity lifecycle.
 *
 * A foreground data-sync service keeps the process and network request alive
 * when the user minimizes Heirloom or switches to another app.
 */
class RestorationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var restorationJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Photo restoration",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress while Heirloom restores a photo"
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra(EXTRA_SOURCE)?.let(Uri::parse)
        if (source == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (restorationJob?.isActive == true) return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, progressNotification(Stage.Uploading))
        restorationJob = serviceScope.launch {
            restore(source)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun restore(source: Uri) {
        val startedAt = System.currentTimeMillis()
        val stageResults = linkedMapOf<Stage, StageResult>()
        publish(RestoreState.Processing(source, Stage.Uploading), Stage.Uploading)

        try {
            val result = RestoreApi.restore(applicationContext, source) { event ->
                when (event) {
                    is PipelineEvent.StageStarted -> {
                        stageResults.putIfAbsent(Stage.Uploading, StageResult.Completed)
                        publish(
                            RestoreState.Processing(
                                source,
                                event.stage,
                                stageResults.toMap(),
                            ),
                            event.stage,
                        )
                    }

                    is PipelineEvent.StageFinished -> {
                        stageResults[event.stage] = event.result
                        publish(
                            RestoreState.Processing(
                                source,
                                event.stage,
                                stageResults.toMap(),
                            ),
                            event.stage,
                        )
                    }

                    is PipelineEvent.Final -> publish(
                        RestoreState.Processing(
                            source,
                            Stage.Finalizing,
                            stageResults.toMap(),
                        ),
                        Stage.Finalizing,
                    )

                    else -> Unit
                }
            }
            RestorationSession.update(
                RestoreState.Done(
                    source = source,
                    restoredUrl = result.restoredUrl,
                    cosineSimilarity = result.cosineSimilarity,
                    identityWarning = result.identityWarning,
                    wasColorized = result.wasColorized,
                    identityUnverified = result.identityUnverified,
                    elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1_000,
                    stageResults = stageResults.toMap(),
                ),
            )
            finishNotification("Restoration complete", "Tap to view your restored photo")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e("HeirloomRestore", "Restoration failed", error)
            val message = userMessage(error)
            RestorationSession.update(RestoreState.Failed(source, message))
            finishNotification("Restoration failed", message)
        } finally {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
    }

    private fun publish(state: RestoreState, stage: Stage) {
        RestorationSession.update(state)
        if (canPostNotifications()) {
            notificationManager.notify(NOTIFICATION_ID, progressNotification(stage))
        }
    }

    private fun progressNotification(stage: Stage): Notification =
        baseNotification()
            .setContentTitle("Restoring your photo")
            .setContentText(stageText(stage))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()

    private fun finishNotification(title: String, text: String) {
        if (canPostNotifications()) {
            notificationManager.notify(
                NOTIFICATION_ID,
                baseNotification()
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun baseNotification(): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

    private fun stageText(stage: Stage): String = when (stage) {
        Stage.Uploading -> "Preparing your photo"
        Stage.RepairingDamage -> "Repairing damage"
        Stage.RestoringFaces -> "Restoring faces"
        Stage.Upscaling -> "Improving detail"
        Stage.CheckingIdentity -> "Checking identity"
        Stage.Colorizing -> "Restoring color"
        Stage.Finalizing -> "Finishing the restored photo"
    }

    private fun userMessage(error: Throwable): String = when (error) {
        is UnknownHostException -> "No internet connection. Reconnect and try again."
        is SocketTimeoutException -> "The restoration took too long. Please try again."
        is RestoreHttpException -> when (error.statusCode) {
            401 -> "This installation needs to be refreshed. Please update Heirloom."
            429 -> "The restoration lab is busy. Try again in a moment."
            503 -> "The restoration lab is warming up. Try again in a moment."
            else -> "The restoration could not finish. Please try again."
        }

        else -> "The restoration could not finish. Please try again."
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "heirloom_restoration"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_SOURCE = "source"

        fun start(context: Context, source: Uri) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RestorationService::class.java)
                    .putExtra(EXTRA_SOURCE, source.toString()),
            )
        }
    }
}
