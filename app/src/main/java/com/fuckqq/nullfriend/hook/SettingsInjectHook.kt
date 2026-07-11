package com.fuckqq.nullfriend.hook

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.fuckqq.nullfriend.ui.DetectorActivity
import com.fuckqq.nullfriend.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Best-effort: when QQ About/Settings activity resumes, offer a floating entry.
 * Full settings-list inject is version-fragile; activity entry is more reliable for v0.1.
 */
object SettingsInjectHook {

    private val settingsHints = listOf(
        "Settings",
        "Setting",
        "About",
        "QQSetting",
        "SettingsActivity",
        "SettingActivity"
    )

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        if (activity.packageName != "com.tencent.mobileqq") return
                        val name = activity.javaClass.name
                        if (settingsHints.none { name.contains(it, ignoreCase = true) }) return
                        injectEntryButton(activity)
                    }
                }
            )
            Log.i("SettingsInjectHook installed")
        } catch (t: Throwable) {
            Log.e("SettingsInjectHook failed", t)
        }
    }

    private fun injectEntryButton(activity: Activity) {
        try {
            val decor = activity.window.decorView as? ViewGroup ?: return
            if (decor.findViewWithTag<View>(TAG) != null) return
            val tv = TextView(activity).apply {
                tag = TAG
                text = "去TM的单向好友"
                textSize = 14f
                setPadding(28, 20, 28, 20)
                setBackgroundColor(0xE0FF5722.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    val i = Intent(activity, DetectorActivity::class.java).apply {
                        action = "com.fuckqq.nullfriend.OPEN_DETECTOR"
                    }
                    // Load activity from module — may need module classloader context
                    try {
                        activity.startActivity(i)
                    } catch (t: Throwable) {
                        Log.e("start DetectorActivity", t)
                        // Fallback: explicit component from module package
                        try {
                            val alt = Intent().setClassName(
                                "com.fuckqq.nullfriend",
                                "com.fuckqq.nullfriend.ui.DetectorActivity"
                            )
                            activity.startActivity(alt)
                        } catch (t2: Throwable) {
                            Log.e("fallback start activity", t2)
                        }
                    }
                }
            }
            val lp = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 120
                marginStart = 24
            }
            decor.addView(tv, lp)
            Log.d("Injected entry on ${activity.javaClass.name}")
        } catch (t: Throwable) {
            Log.d("injectEntry: ${t.message}")
        }
    }

    private const val TAG = "fuckqq_nullfriend_entry"
}
