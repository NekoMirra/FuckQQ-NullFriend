package com.fuckqq.nullfriend.hook

import android.app.Activity
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.ModuleMain
import com.fuckqq.nullfriend.ui.DetectorPanel
import com.fuckqq.nullfriend.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.WeakHashMap

/**
 * 桌面启动器经 Intent 唤起进程内面板的入口。
 *
 * - 桌面 LauncherActivity 携带 EXTRA_OPEN_PANEL 启动 QQ
 * - QQ 任一主界面 onResume 时消费该 extra 并打开进程内面板
 * - 联系人列表底部入口由 ContactsEntryHook 负责
 */
object SettingsInjectHook {

    private val handled = WeakHashMap<Activity, Boolean>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName != Constants.QQ_PACKAGE) return
                        maybeOpenFromIntent(activity)
                    }
                }
            )
            // onCreate 后也尝试一次，便于早绑定
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onPostCreate",
                android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.packageName != Constants.QQ_PACKAGE) return
                        maybeOpenFromIntent(activity)
                    }
                }
            )
            Log.i("SettingsInjectHook installed (intent-only, no FAB)")
        } catch (t: Throwable) {
            Log.e("SettingsInjectHook failed", t)
        }
    }

    private fun maybeOpenFromIntent(activity: Activity) {
        try {
            val intent = activity.intent ?: return
            val open = intent.getBooleanExtra(EXTRA_OPEN_PANEL, false) ||
                intent.action == ACTION_OPEN_PANEL ||
                intent.getStringExtra("open_nullfriend") == "1"
            if (!open) return
            if (handled.put(activity, true) == true) return
            // consume，避免反复弹
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
