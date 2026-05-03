package io.github.thevellichor.samsungopenring.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import rikka.shizuku.Shizuku

object ShizukuHelper {

    private const val TAG = "OpenRing.Shizuku"
    private const val SHIZUKU_PERMISSION_CODE = 100
    private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"

    private var shellService: IShizukuShellService? = null
    private var serviceConnection: ServiceConnection? = null

    // --- Status checks ---

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun launchShizuku(context: Context): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
                ?: return false
            context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch Shizuku: ${e.message}")
            false
        }
    }

    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun hasReadLogs(context: Context): Boolean {
        return context.checkSelfPermission("android.permission.READ_LOGS") ==
            PackageManager.PERMISSION_GRANTED
    }

    // --- Permission request ---

    fun requestPermission(callback: (granted: Boolean) -> Unit) {
        try {
            if (hasShizukuPermission()) {
                callback(true)
                return
            }

            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    callback(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Permission request failed: ${e.message}")
            callback(false)
        }
    }

    // --- UserService binding ---

    fun bindAndGrant(packageName: String, callback: (success: Boolean, message: String) -> Unit) {
        if (!isShizukuRunning()) {
            callback(false, "Shizuku is not running")
            return
        }
        if (!hasShizukuPermission()) {
            callback(false, "Shizuku permission not granted")
            return
        }

        try {
            val userServiceArgs = Shizuku.UserServiceArgs(
                ComponentName(packageName, ShizukuShellService::class.java.name)
            )
                .daemon(false)
                .debuggable(true)
                .processNameSuffix("shell")
                .tag("openring_shell")
                .version(1)

            serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null || !binder.pingBinder()) {
                        callback(false, "Shizuku binder invalid")
                        return
                    }

                    shellService = IShizukuShellService.Stub.asInterface(binder)
                    Log.d(TAG, "UserService connected")

                    // Execute the grant
                    Thread {
                        try {
                            val result = shellService?.grantPermission(
                                packageName,
                                "android.permission.READ_LOGS"
                            ) ?: -1

                            if (result == 0) {
                                Log.d(TAG, "READ_LOGS granted successfully")
                                callback(true, "READ_LOGS permission granted via Shizuku")
                            } else {
                                Log.e(TAG, "pm grant returned $result")
                                callback(false, "pm grant failed (exit code $result)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Grant failed: ${e.message}")
                            callback(false, e.message ?: "Unknown error")
                        } finally {
                            unbindService()
                        }
                    }.start()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.d(TAG, "UserService disconnected")
                    shellService = null
                }
            }

            Shizuku.bindUserService(userServiceArgs, serviceConnection!!)
            Log.d(TAG, "Binding UserService...")

        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed: ${e.message}")
            callback(false, "Shizuku UserService failed: ${e.message}")
        }
    }

    private fun unbindService() {
        try {
            serviceConnection?.let {
                Shizuku.unbindUserService(
                    Shizuku.UserServiceArgs(
                        ComponentName("", ShizukuShellService::class.java.name)
                    ).tag("openring_shell"),
                    it,
                    true
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unbind failed: ${e.message}")
        }
        shellService = null
        serviceConnection = null
    }

    // --- ADB fallback ---

    fun getAdbCommand(packageName: String): String {
        return "adb shell pm grant $packageName android.permission.READ_LOGS"
    }

    fun copyAdbCommandToClipboard(context: Context) {
        val command = getAdbCommand(context.packageName)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ADB command", command))
        Toast.makeText(context, "ADB command copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // --- Status text ---

    fun getStatusText(context: Context): String {
        val readLogs = if (hasReadLogs(context)) "granted" else "not granted"
        val shizuku = when {
            !isShizukuInstalled(context) -> "not installed"
            !isShizukuRunning() -> "not running"
            !hasShizukuPermission() -> "needs permission"
            else -> "ready"
        }
        return "READ_LOGS: $readLogs | Shizuku: $shizuku"
    }
}
