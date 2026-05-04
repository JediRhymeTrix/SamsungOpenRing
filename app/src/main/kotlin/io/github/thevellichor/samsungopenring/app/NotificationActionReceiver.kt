package io.github.thevellichor.samsungopenring.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TOGGLE_MANUAL = "io.github.thevellichor.samsungopenring.intent.action.TOGGLE_MANUAL"
        const val ACTION_TOGGLE_TRIGGERS = "io.github.thevellichor.samsungopenring.intent.action.TOGGLE_TRIGGERS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val result = when (intent.action) {
            ACTION_TOGGLE_MANUAL -> MonitoringControl.toggleManual(context)
            ACTION_TOGGLE_TRIGGERS -> MonitoringControl.toggleTriggers(context)
            else -> return
        }
        Toast.makeText(context.applicationContext, result.message, Toast.LENGTH_SHORT).show()
    }
}