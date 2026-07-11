package com.fuckqq.nullfriend.hook

import android.app.Activity
import android.content.Intent
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.ModuleMain
import com.fuckqq.nullfriend.ui.DetectorPanel
import com.fuckqq.nullfriend.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.WeakHashMap

/**
 * Injects a floating entry on common QQ activities and opens in-process panel.
 */
object SettingsInjectHook {

    /** Prefer main shell / me / contacts / settings; avoid pure AIO chat spam if possible */
    private val preferHints = listOf(
        "Splash", "Main", "Frame", "Home", "Tab",
        "Setting", "About", "Leba", "Contact", "Friend",
        "Mine", "Profile", "Account", "QQSetting", "Drawer",
        "Conversation", "Recent", "Login", "Gesture"
    )

    /** Always inject on these strong matches */
    private val alwaysHints = listOf(
        "SplashActivity", "MainActivity", "MainFragmentActivity",
        "QQSetting", "SettingsActivity", "SettingActivity"
    )

    private val attached = WeakHashMap<Activity, Boolean>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName != Constants.QQ_PACKAGE) return
                        maybeAttach(activity)
                        maybeOpenFromIntent(activity)
                    }
                }
            )
            // Also onCreate for early attach
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onPostCreate",
                android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName != Constants.QQ_PACKAGE) return
                        maybeAttach(activity)
                        maybeOpenFromIntent(activity)
                    }
                }
            )
            Log.i("SettingsInjectHook installed (FAB + intent)")
        } catch (t: Throwable) {
            Log.e("SettingsInjectHook failed", t)
        }
    }

    private fun maybeAttach(activity: Activity) {
        val name = activity.javaClass.name
        val noisy = name.contains("WebView", true) ||
            name.contains("Translucent", true) ||
            name.contains("Proxy", true) ||
            name.contains("Dialog", true) ||
            name.contains("Plugin", true)
        if (noisy) return

        val strong = alwaysHints.any { name.contains(it, ignoreCase = true) }
        val soft = preferHints.any { name.contains(it, ignoreCase = true) }
        // Only attach on main shell / settings-like pages (not every chat Activity)
        if (!strong && !soft) return
        if (attached.put(activity, true) == true) return
        ModuleMain.ensureInit(activity.applicationContext)
        DetectorPanel.attachFab(activity)
    }

    private fun maybeOpenFromIntent(activity: Activity) {
        try {
            val intent = activity.intent ?: return
            val open = intent.getBooleanExtra(EXTRA_OPEN_PANEL, false) ||
                intent.action == ACTION_OPEN_PANEL ||
                intent.getStringExtra("open_nullfriend") == "1"
            if (!open) return
            // consume
            intent.removeExtra(EXTRA_OPEN_PANEL)
            intent.removeExtra("open_nullfriend")
            ModuleMain.ensureInit(activity.applicationContext)
            DetectorPanel.show(activity)
            Log.i("Opened panel from intent on ${activity.javaClass.name}")
        } catch (t: Throwable) {
            Log.d("maybeOpenFromIntent: ${t.message}")
        }
    }

    const val EXTRA_OPEN_PANEL = "fuckqq_nullfriend_open"
    const val ACTION_OPEN_PANEL = "com.fuckqq.nullfriend.action.OPEN_PANEL"
}
