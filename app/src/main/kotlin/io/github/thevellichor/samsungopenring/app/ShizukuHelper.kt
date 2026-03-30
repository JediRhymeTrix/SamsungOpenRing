package io.github.thevellichor.samsungopenring.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast

object ShizukuHelper {

    private const val TAG = "OpenRing.Shizuku"

    /**
     * READ_LOGS is needed only for the logcat fallback monitoring path.
     * The primary BLE gesture detection does NOT require this permission.
     *
     * Granting READ_LOGS requires shell-level access via either:
     * - ADB: `adb shell pm grant <pkg> android.permission.READ_LOGS`
     * - Shizuku (future): when Shizuku exposes a stable public process API
     *
     * For now, this helper provides the ADB command and copies it to clipboard.
     */

    fun hasReadLogs(context: Context): Boolean {
        return context.checkSelfPermission("android.permission.READ_LOGS") ==
            PackageManager.PERMISSION_GRANTED
    }

    fun getAdbCommand(packageName: String): String {
        return "adb shell pm grant $packageName android.permission.READ_LOGS"
    }

    fun copyAdbCommandToClipboard(context: Context) {
        val command = getAdbCommand(context.packageName)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ADB command", command))
        Toast.makeText(context, "ADB command copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun getStatusText(context: Context): String {
        return if (hasReadLogs(context)) {
            "READ_LOGS: granted"
        } else {
            "READ_LOGS: not granted (optional — needed only for logcat fallback)"
        }
    }
}
