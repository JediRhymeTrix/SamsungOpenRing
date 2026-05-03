package io.github.thevellichor.samsungopenring.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.thevellichor.samsungopenring.core.*

class GestureService : Service() {

    companion object {
        private const val TAG = "OpenRing.Service"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "openring_gesture"
        private const val PREFS_NAME = "openring_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        const val KEY_VERBOSE_LOGGING = "verbose_logging"
        private const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"

        const val ACTION_GESTURE = "io.github.thevellichor.samsungopenring.intent.action.GESTURE"
        const val EXTRA_GESTURE_TYPE = "gesture_type"
        const val EXTRA_GESTURE_ID = "gesture_id"
        const val EXTRA_TIMESTAMP_MS = "timestamp_ms"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GestureService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GestureService::class.java))
        }
    }

    private var webhookUrl: String = ""
    private var verboseLogging: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        OpenRing.logger = OpenRingLogger { message ->
            verboseLog("Core: $message")
        }

        webhookUrl = getWebhookUrl()
        verboseLogging = isVerboseLoggingEnabled()
        log("Service started (webhook: ${if (webhookUrl.isNotBlank()) "configured" else "none"}, verbose: $verboseLogging)")
        logTaskerDiagnostics()

        connectAndEnable()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun connectAndEnable() {
        log("Connecting to Galaxy Ring...")
        updateNotification("Connecting to ring...")

        OpenRing.connect(applicationContext, object : ConnectionCallback {
            override fun onConnected() {
                log("BLE connected — enabling gestures")
                updateNotification("Connected — enabling gestures")

                OpenRing.enableGestures { event ->
                    log("GESTURE #${event.gestureId} detected")
                    updateNotification("Gesture #${event.gestureId} detected")
                    broadcastGesture(event)

                    if (webhookUrl.isNotBlank()) {
                        log("Sending webhook -> $webhookUrl")
                        WebhookSender.send(webhookUrl, event) { success, detail ->
                            if (success) {
                                log("Webhook OK ($detail)")
                            } else {
                                log("Webhook FAILED: $detail")
                            }
                        }
                    }
                }
            }

            override fun onDisconnected() {
                log("BLE disconnected — auto-reconnect will be attempted by core")
                updateNotification("Disconnected — reconnecting...")
            }

            override fun onError(error: OpenRingError) {
                log("ERROR: ${error.message}")
                updateNotification("Error: ${error.message}")
            }
        })
    }

    private fun broadcastGesture(event: GestureEvent) {
        val timestamp = System.currentTimeMillis()

        val broadcast = Intent(ACTION_GESTURE).apply {
            putExtra(EXTRA_GESTURE_TYPE, "double_pinch")
            putExtra(EXTRA_GESTURE_ID, event.gestureId)
            putExtra(EXTRA_TIMESTAMP_MS, timestamp)
        }

        verboseLog(
            "Tasker broadcast prepared: action=$ACTION_GESTURE, " +
                "$EXTRA_GESTURE_TYPE=double_pinch, $EXTRA_GESTURE_ID=${event.gestureId}, " +
                "$EXTRA_TIMESTAMP_MS=$timestamp"
        )

        try {
            sendBroadcast(broadcast)
            log("Tasker intent broadcast sent: $ACTION_GESTURE (gesture #${event.gestureId})")
            verboseLog("Tasker setup: Profile > Event > System > Intent Received > Action = $ACTION_GESTURE")
        } catch (exception: Exception) {
            log("Tasker intent broadcast FAILED: ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    private fun logTaskerDiagnostics() {
        val taskerInstalled = isPackageInstalled(TASKER_PACKAGE)
        log("Tasker diagnostic: package $TASKER_PACKAGE installed=$taskerInstalled")
        verboseLog("Tasker diagnostic: using implicit broadcast action $ACTION_GESTURE")
        verboseLog("Tasker diagnostic: expected extras $EXTRA_GESTURE_TYPE, $EXTRA_GESTURE_ID, $EXTRA_TIMESTAMP_MS")
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        log("Disabling gestures...")
        OpenRing.disableGestures()
        log("Disconnecting...")
        OpenRing.disconnect()
        log("Service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun log(message: String) {
        Log.d(TAG, message)
        EventLog.log(this, message)
    }

    private fun verboseLog(message: String) {
        Log.d(TAG, message)
        if (verboseLogging) {
            EventLog.log(this, "VERBOSE: $message")
        }
    }

    private fun getWebhookUrl(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_WEBHOOK_URL, "") ?: ""
    }

    private fun isVerboseLoggingEnabled(): Boolean {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_VERBOSE_LOGGING, false)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gesture Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when SamsungOpenRing is monitoring gestures"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SamsungOpenRing")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
