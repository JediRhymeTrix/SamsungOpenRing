package io.github.thevellichor.samsungopenring.app.triggers

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.github.thevellichor.samsungopenring.app.NotificationAccessService

class MediaActiveTrigger : Trigger {

    companion object {
        private const val TAG = "OpenRing.MediaTrigger"
        private const val ACTIVE_POLL_INTERVAL_MS = 5_000L
        private const val IDLE_POLL_INTERVAL_MS = 15_000L
    }

    override val id = "media_active"
    override val name = "Media active"
    override val description = "Activate when any media session is active or paused"

    private var armed = false
    private var callback: TriggerCallback? = null
    private var active = false
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var armContext: Context? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!armed) return
            checkMediaSessions()
            handler?.postDelayed(this, if (active) ACTIVE_POLL_INTERVAL_MS else IDLE_POLL_INTERVAL_MS)
        }
    }

    override fun arm(context: Context, callback: TriggerCallback) {
        if (armed) return
        this.callback = callback
        this.armContext = context.applicationContext

        armed = true
        handlerThread = HandlerThread("MediaActiveTrigger").also { it.start() }
        handler = Handler(handlerThread!!.looper)
        handler?.post(pollRunnable)

        Log.d(TAG, "Armed")
    }

    override fun disarm(context: Context) {
        if (!armed) return
        armed = false
        handler?.removeCallbacks(pollRunnable)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        callback = null
        armContext = null
        active = false
        Log.d(TAG, "Disarmed")
    }

    override fun isArmed() = armed

    private fun checkMediaSessions() {
        val ctx = armContext ?: return
        val hasMedia = hasActiveMediaSession(ctx)

        if (hasMedia && !active) {
            active = true
            Log.d(TAG, "Media session active")
            callback?.onActivated(this)
        } else if (!hasMedia && active) {
            active = false
            Log.d(TAG, "No active media sessions")
            callback?.onDeactivated(this)
        }
    }

    private fun hasActiveMediaSession(context: Context): Boolean {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return false
        return try {
            val listener = ComponentName(context, NotificationAccessService::class.java)
            manager.getActiveSessions(listener).any { controller ->
                controller.packageName.isNotBlank() && controller.playbackState != null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification listener access required for media trigger")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Media session check failed: ${e.message}")
            false
        }
    }

}