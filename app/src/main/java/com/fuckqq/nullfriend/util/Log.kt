package com.fuckqq.nullfriend.util

import android.util.Log as AndroidLog
import com.fuckqq.nullfriend.Constants
import de.robv.android.xposed.XposedBridge

object Log {
    @Volatile
    var verbose: Boolean = false

    fun i(msg: String) {
        AndroidLog.i(Constants.TAG, msg)
        runCatching { XposedBridge.log("${Constants.TAG}: $msg") }
    }

    fun w(msg: String) {
        AndroidLog.w(Constants.TAG, msg)
        runCatching { XposedBridge.log("${Constants.TAG}/W: $msg") }
    }

    fun e(msg: String, t: Throwable? = null) {
        if (t != null) {
            AndroidLog.e(Constants.TAG, msg, t)
            runCatching { XposedBridge.log("${Constants.TAG}/E: $msg\n${t.stackTraceToString()}") }
        } else {
            AndroidLog.e(Constants.TAG, msg)
            runCatching { XposedBridge.log("${Constants.TAG}/E: $msg") }
        }
    }

    fun d(msg: String) {
        if (!verbose) return
        AndroidLog.d(Constants.TAG, msg)
        runCatching { XposedBridge.log("${Constants.TAG}/D: $msg") }
    }

    fun maskUin(uin: String): String {
        if (uin.length <= 4) return "****"
        return uin.take(3) + "****" + uin.takeLast(2)
    }
}
