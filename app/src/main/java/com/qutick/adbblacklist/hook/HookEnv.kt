package com.qutick.adbblacklist.hook

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

internal class HookEnv(
    val xposed: XposedInterface,
    val module: XposedModule,
    val classLoader: ClassLoader,
) {
    @Volatile var context: Context? = null

    fun log(level: Int, message: String, throwable: Throwable? = null) {
        if (throwable == null) module.log(level, TAG, message)
        else module.log(level, TAG, message, throwable)
    }

    fun info(message: String) = log(Log.INFO, message)
    fun warn(message: String, throwable: Throwable? = null) = log(Log.WARN, message, throwable)
    fun error(message: String, throwable: Throwable? = null) = log(Log.ERROR, message, throwable)

    companion object {
        const val TAG = "AdbBlacklist"
    }
}
