package io.github.thevellichor.samsungopenring.app

import android.content.Context
import android.util.Log

class ShizukuShellService : IShizukuShellService.Stub {

    companion object {
        private const val TAG = "OpenRing.ShizukuSvc"
    }

    // Default constructor (required, used by Shizuku < v13)
    constructor() {
        Log.d(TAG, "ShizukuShellService created (default constructor)")
    }

    // Context constructor (v13+ tries this first)
    constructor(context: Context) {
        Log.d(TAG, "ShizukuShellService created (context constructor)")
    }

    override fun grantPermission(packageName: String, permission: String): Int {
        Log.d(TAG, "Granting $permission to $packageName")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pm grant $packageName $permission"))
            val exitCode = process.waitFor()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            if (exitCode != 0) {
                Log.e(TAG, "pm grant failed: exit=$exitCode err=$stderr")
            } else {
                Log.d(TAG, "pm grant succeeded")
            }
            exitCode
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}")
            -1
        }
    }

    override fun destroy() {
        Log.d(TAG, "Service destroyed")
        System.exit(0)
    }
}
