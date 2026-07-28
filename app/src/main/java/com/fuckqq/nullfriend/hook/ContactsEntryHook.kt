package com.fuckqq.nullfriend.hook

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ExpandableListAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.ModuleMain
import com.fuckqq.nullfriend.ui.DetectorPanel
import com.fuckqq.nullfriend.ui.UiTheme
import com.fuckqq.nullfriend.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 在 QQ 联系人列表底部注入「单向好友 / 被删检测」入口。
 *
 * 参考 QNotified/QAuxiliary DeletionObserver 的做法：
 *  - Hook com.tencent.widget.PinnedHeaderExpandableListView.setAdapter(ExpandableListAdapter)
 *  - 在 beforeHookedMethod 中判定 thisObject 类名含 ContactsFPSPinnedHeaderExpandableListView
 *  - 调用 addFooterView 注入一个底部入口行
 *
 * 入口样式遵循 ark-ui 设计语言（ark 家族）：方形、1px 信号描边、信号青指示条、
 * 45° 切角未读角标。点击后打开进程内 DetectorPanel。
 */
object ContactsEntryHook {

    private const val CONTACTS_LV_CLASS = "com.tencent.widget.PinnedHeaderExpandableListView"
    private const val CONTACTS_LV_NAME_HINT = "ContactsFPSPinnedHeaderExpandableListView"
    private const val FOOTER_TAG = "fuckqq_nullfriend_contacts_footer"

    /** 已注入 footer 的 ListView，避免重复添加 */
    private val addedViews: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clz = XposedHelpers.findClass(CONTACTS_LV_CLASS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clz,
                "setAdapter",
                ExpandableListAdapter::class.java,
                object : XC_MethodHook(PRIORITY) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val lv = param.thisObject as? View ?: return
                            val name = lv.javaClass.name
                            if (!name.contains(CONTACTS_LV_NAME_HINT)) return
                            if (addedViews.contains(lv)) return

                            val ctx = lv.context
                            val activity = ctx as? Activity
                                ?: ctx.javaClass.methods.firstOrNull {
                                    it.name == "getActivity" && it.parameterTypes.isEmpty()
                                }?.let { runCatching { it.invoke(ctx) as? Activity }.getOrNull() }
                            if (activity == null) {
                                Log.d("ContactsEntryHook: no activity context, skip")
                                return
                            }
                            if (activity.packageName != Constants.QQ_PACKAGE) return

                            ModuleMain.ensureInit(activity.applicationContext)
                            val footer = buildFooter(activity)
                            // addFooterView 必须在 setAdapter 真正执行前调用，且只调一次
                            invokeAddFooterView(lv, footer)
                            addedViews.add(lv)
                            Log.i("ContactsEntryHook: footer injected on $name")
                        } catch (t: Throwable) {
                            Log.e("ContactsEntryHook beforeHookedMethod", t)
                        }
                    }
                }
            )
            Log.i("ContactsEntryHook installed")
        } catch (t: Throwable) {
            Log.e("ContactsEntryHook install failed", t)
        }
    }

    /**
     * 构造底部入口行 —— ark-ui 风格。
     *
     * 结构：左侧信号青竖条 + 标题列（中文标题 + 英文 micro-label）+ 右侧未读角标 / 箭头。
     */
    private fun buildFooter(activity: Activity): View {
        val T = UiTheme
        val dp = { v: Float -> T.dp(activity, v) }

        val host = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = FOOTER_TAG
            setBackgroundColor(T.INK)
            setPadding(dp(T.SP_4), dp(T.SP_3), dp(T.SP_4), dp(T.SP_3))
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = T.shape(T.SURFACE, T.RADIUS_NONE, activity, T.RULE)
            setPadding(dp(T.SP_3), dp(T.SP_3), dp(T.SP_3), dp(T.SP_3))
            isClickable = true
            isFocusable = true
        }

        // 左侧信号青竖条（状态指示）
        val indicator = View(activity).apply {
            setBackgroundColor(T.SIGNAL)
        }
        row.addView(indicator, LinearLayout.LayoutParams(dp(3f), dp(28f)).apply {
            rightMargin = dp(T.SP_3)
        })

        // 标题列
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(activity).apply {
            text = "单向好友 · 被删检测"
            setTextColor(T.TEXT)
            textSize = T.TEXT_BODY_1
            typeface = T.typefaceMedium()
            setLineSpacing(dp(1f).toFloat(), 1f)
        })
        titleCol.addView(TextView(activity).apply {
            text = "ONE-WAY FRIEND DETECTOR"
            setTextColor(T.SIGNAL)
            textSize = T.TEXT_MICRO
            typeface = T.typefaceMono()
            // 字距开敞，模拟 uppercase micro-label
            letterSpacing = 0.14f
            setPadding(0, dp(2f), 0, 0)
        })
        row.addView(titleCol)

        // 右侧未读计数角标
        val badge = TextView(activity).apply {
            id = View.generateViewId()
            tag = "fuckqq_nullfriend_badge"
            text = "0"
            setTextColor(T.INK)
            textSize = T.TEXT_MICRO
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = T.oval(T.SIGNAL, activity)
            visibility = View.GONE
            val s = dp(16f)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        row.addView(badge)

        // 右侧箭头（›）
        val arrow = TextView(activity).apply {
            text = "›"
            setTextColor(T.TEXT_2)
            textSize = T.TEXT_TITLE
            gravity = Gravity.CENTER
            setPadding(dp(T.SP_2), 0, 0, 0)
        }
        row.addView(arrow)

        host.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 底部 1px 规线收尾
        host.addView(View(activity).apply {
            setBackgroundColor(T.RULE)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        ))

        // 点击：打开检测面板
        row.setOnClickListener {
            try {
                ModuleMain.ensureInit(activity.applicationContext)
                DetectorPanel.show(activity)
            } catch (t: Throwable) {
                Log.e("ContactsEntryHook click", t)
            }
        }

        // 异步刷新未读角标
        refreshBadgeAsync(activity, badge)

        return host
    }

    private fun refreshBadgeAsync(activity: Activity, badge: TextView) {
        Thread {
            try {
                if (!ModuleMain.isReady()) return@Thread
                val owners = ModuleMain.repository.listAccounts().map { it.ownerUin }
                val unread = owners.sumOf { ModuleMain.repository.unreadCount(it) }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (unread > 0) {
                        badge.text = if (unread > 99) "99+" else unread.toString()
                        badge.visibility = View.VISIBLE
                    } else {
                        badge.visibility = View.GONE
                    }
                }
            } catch (t: Throwable) {
                Log.d("refreshBadgeAsync: ${t.message}")
            }
        }.start()
    }

    /** 反射调用 addFooterView(View)，逐级向上查找声明方法 */
    private fun invokeAddFooterView(lv: View, footer: View) {
        var clazz: Class<*>? = lv.javaClass
        while (clazz != null) {
            try {
                val m: Method = clazz.getDeclaredMethod("addFooterView", View::class.java)
                m.isAccessible = true
                m.invoke(lv, footer)
                return
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }
        // 兜底：ExpandableListView 本身可能没有 addFooterView，尝试父类 ListView
        try {
            val m = android.widget.ListView::class.java.getDeclaredMethod("addFooterView", View::class.java)
            m.isAccessible = true
            m.invoke(lv, footer)
        } catch (t: Throwable) {
            Log.e("invokeAddFooterView failed", t)
        }
    }

    /**
     * 面板关闭后调用，刷新所有已注入 footer 的未读角标。
     * 在 QQ 进程内任意 Activity 上触发，遍历缓存的 footer 视图更新角标。
     */
    fun refreshBadges() {
        try {
            for (lv in addedViews) {
                val foot = lv.findViewWithTag<View>(FOOTER_TAG) ?: continue
                val badge = foot.findViewWithTag<TextView>("fuckqq_nullfriend_badge") ?: continue
                refreshBadgeAsync(lv.context as? Activity ?: continue, badge)
            }
        } catch (t: Throwable) {
            Log.d("refreshBadges: ${t.message}")
        }
    }

    private const val PRIORITY = 55
}
