package io.github.thevellichor.samsungopenring.app

import android.content.Context
import android.content.Intent
import io.github.thevellichor.samsungopenring.app.triggers.TriggerManager

object MonitoringControl {
    private const val PREFS_NAME = "openring_prefs"
    private const val KEY_MANUAL_MONITORING_ACTIVE = "manual_monitoring_active"
    const val ACTION_STATE_CHANGED = "io.github.thevellichor.samsungopenring.intent.action.MONITORING_STATE_CHANGED"

    data class State(
        val manualActive: Boolean,
        val triggersArmed: Boolean,
        val pausedForPowerSaver: Boolean,
    )

    data class Result(
        val state: State,
        val message: String,
    )

    fun getState(context: Context): State {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return State(
            manualActive = prefs.getBoolean(KEY_MANUAL_MONITORING_ACTIVE, false),
            triggersArmed = TriggerManager(appContext).wereTriggersArmed(),
            pausedForPowerSaver = PowerSaverPolicy.shouldPause(appContext),
        )
    }

    fun toggleManual(context: Context): Result {
        val state = getState(context)
        return if (state.manualActive) stopManual(context) else startManual(context)
    }

    fun startManual(context: Context): Result {
        val appContext = context.applicationContext
        prefs(appContext).edit().putBoolean(KEY_MANUAL_MONITORING_ACTIVE, true).apply()
        val paused = PowerSaverPolicy.shouldPause(appContext)
        if (!paused) GestureService.start(appContext)
        return changed(appContext, if (paused) "Manual monitoring enabled; paused for Battery Saver" else "Manual monitoring started")
    }

    fun stopManual(context: Context): Result {
        val appContext = context.applicationContext
        prefs(appContext).edit().putBoolean(KEY_MANUAL_MONITORING_ACTIVE, false).apply()
        GestureService.stop(appContext)
        return changed(appContext, "Manual monitoring stopped")
    }

    fun toggleTriggers(context: Context): Result {
        val appContext = context.applicationContext
        return if (TriggerManager(appContext).wereTriggersArmed()) disarmTriggers(appContext) else armTriggers(appContext)
    }

    fun armTriggers(context: Context): Result {
        val appContext = context.applicationContext
        TriggerManager(appContext).armAll()
        val paused = PowerSaverPolicy.shouldPause(appContext)
        return changed(appContext, if (paused) "Triggers armed; paused for Battery Saver" else "Triggers armed")
    }

    fun disarmTriggers(context: Context): Result {
        val appContext = context.applicationContext
        TriggerManager(appContext).disarmAll()
        prefs(appContext).edit().putBoolean(KEY_MANUAL_MONITORING_ACTIVE, false).apply()
        GestureService.stop(appContext)
        return changed(appContext, "Triggers disarmed")
    }

    fun syncAfterPowerPolicy(context: Context): State {
        val appContext = context.applicationContext
        val triggerManager = TriggerManager(appContext)
        if (PowerSaverPolicy.shouldPause(appContext)) {
            triggerManager.pauseForPowerSaver()
            GestureService.stop(appContext)
        } else if (triggerManager.wereTriggersArmed()) {
            triggerManager.resumeAfterPowerSaver()
        } else if (prefs(appContext).getBoolean(KEY_MANUAL_MONITORING_ACTIVE, false)) {
            GestureService.start(appContext)
        }
        notifyStateChanged(appContext)
        return getState(appContext)
    }

    private fun changed(context: Context, message: String): Result {
        EventLog.log(context, message)
        notifyStateChanged(context)
        GestureService.refreshNotification(context)
        return Result(getState(context), message)
    }

    private fun notifyStateChanged(context: Context) {
        context.applicationContext.sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(context.packageName))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}