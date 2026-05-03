package io.github.thevellichor.samsungopenring.app

import android.content.Context
import android.content.Intent
import android.os.PowerManager

object PowerSaverPolicy {
    private const val PREFS_NAME = "openring_prefs"
    const val KEY_PAUSE_IN_POWER_SAVER = "pause_in_power_saver"
    const val ACTION_POWER_STATE_CHANGED = "io.github.thevellichor.samsungopenring.intent.action.POWER_STATE_CHANGED"

    fun isPauseEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PAUSE_IN_POWER_SAVER, true)
    }

    fun setPauseEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSE_IN_POWER_SAVER, enabled).apply()
        context.applicationContext.sendBroadcast(Intent(ACTION_POWER_STATE_CHANGED).setPackage(context.packageName))
    }

    fun isPowerSaveMode(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isPowerSaveMode == true
    }

    fun shouldPause(context: Context): Boolean {
        return isPauseEnabled(context) && isPowerSaveMode(context)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}