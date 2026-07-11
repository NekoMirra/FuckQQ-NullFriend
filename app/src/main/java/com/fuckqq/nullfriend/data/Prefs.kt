package com.fuckqq.nullfriend.data

import android.content.Context
import android.content.SharedPreferences
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.util.Log

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    var notifyEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIFY, false)
        set(v) = sp.edit().putBoolean(KEY_NOTIFY, v).apply()

    /** 0 = off; otherwise minutes (30/60/180) */
    var intervalMinutes: Int
        get() = sp.getInt(KEY_INTERVAL, 0)
        set(v) = sp.edit().putInt(KEY_INTERVAL, v).apply()

    var startupDelaySec: Int
        get() = sp.getInt(KEY_STARTUP_DELAY, 5)
        set(v) = sp.edit().putInt(KEY_STARTUP_DELAY, v).apply()

    var verboseLog: Boolean
        get() = sp.getBoolean(KEY_VERBOSE, false)
        set(v) {
            sp.edit().putBoolean(KEY_VERBOSE, v).apply()
            Log.verbose = v
        }

    /** Last selected owner uin in UI (for multi-account view) */
    var uiSelectedOwnerUin: String?
        get() = sp.getString(KEY_UI_OWNER, null)
        set(v) = sp.edit().putString(KEY_UI_OWNER, v).apply()

    init {
        Log.verbose = verboseLog
    }

    companion object {
        private const val KEY_NOTIFY = "notify_enabled"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_STARTUP_DELAY = "startup_delay_sec"
        private const val KEY_VERBOSE = "verbose_log"
        private const val KEY_UI_OWNER = "ui_selected_owner"
    }
}
