package com.example.dubbing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.DubbingApplication
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DubbingForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotification = buildNotification("AI dubbing in progress", 0)
        startForeground(NOTIFICATION_ID, initialNotification)

        val app = application as? DubbingApplication
        val pipeline = app?.dubbingPipeline

        serviceScope.launch {
            pipeline?.pipelineState?.collect { progress ->
                if (progress != null) {
                    if (progress.stage == ProcessingStage.COMPLETE) {
                        val doneNotif = buildNotification("AI Dubbing Completed", 100, isFinished = true)
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(NOTIFICATION_ID, doneNotif)
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    } else if (progress.stage == ProcessingStage.FAILED || progress.stage == ProcessingStage.CANCELLED) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val notif = buildNotification("AI dubbing in progress: ${progress.statusMessage}", progress.overallProgressPercent)
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(NOTIFICATION_ID, notif)
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(message: String, progress: Int, isFinished: Boolean = false): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Offline AI Dubbing")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(!isFinished)
            .setOnlyAlertOnce(true)

        if (!isFinished) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Offline AI Dubbing Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows offline dubbing progress for videos"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "offline_dubbing_channel"
        const val ACTION_STOP = "com.example.dubbing.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, DubbingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DubbingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
