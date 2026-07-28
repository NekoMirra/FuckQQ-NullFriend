package com.fuckqq.nullfriend.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fuckqq.nullfriend.BuildConfig
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.hook.SettingsInjectHook

/**
 * 桌面启动器。打开 QQ 并请求进程内面板。
 * 检测数据在 QQ 进程内，必须从 QQ 里打开。
 */
class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val T = UiTheme
        val dp = { v: Float -> T.dp(this, v) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(T.INK)
            setPadding(dp(T.SP_5), dp(T.SP_6), dp(T.SP_5), dp(T.SP_6))
        }

        // 标题区
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleCol.addView(TextView(this).apply {
            text = "单向好友"
            setTextColor(T.TEXT)
            textSize = T.TEXT_DISPLAY_LG
            typeface = T.typefaceBold()
            letterSpacing = -0.02f
            setLineSpacing(dp(-4f).toFloat(), 1f)
        })
        titleCol.addView(TextView(this).apply {
            text = "DELETED FRIEND DETECTOR"
            setTextColor(T.SIGNAL)
            textSize = T.TEXT_MICRO
            typeface = T.typefaceMono()
            letterSpacing = 0.14f
            setPadding(0, dp(T.SP_1), 0, 0)
        })
        titleCol.addView(View(this).apply {
            setBackgroundColor(T.SIGNAL)
        }, LinearLayout.LayoutParams(dp(40f), dp(2f)).apply {
            topMargin = dp(T.SP_3)
        })
        root.addView(titleCol)

        // 版本信息
        root.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}  ·  ${Constants.QQ_PACKAGE}"
            setTextColor(T.TEXT_2)
            textSize = T.TEXT_CAPTION
            typeface = T.typefaceMono()
            setPadding(0, dp(T.SP_4), 0, dp(T.SP_5))
        })

        // 说明
        val info = TextView(this).apply {
            text = buildString {
                append("01  LSPosed 启用本模块，作用域只勾选 QQ\n")
                append("02  强制停止并重新打开 QQ\n")
                append("03  进入 QQ「联系人」页，列表底部出现「单向好友」入口\n")
                append("04  或点下方按钮尝试自动唤起\n")
            }
            setTextColor(T.TEXT_2)
            textSize = T.TEXT_BODY
            setLineSpacing(dp(2f).toFloat(), 1.2f)
        }
        root.addView(info)

        root.addView(View(this).apply { setBackgroundColor(T.RULE) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(T.SP_5); bottomMargin = dp(T.SP_5)
            })

        // 主按钮
        val btnPrimary = TextView(this).apply {
            text = "打开 QQ 并唤起面板"
            setTextColor(T.INK)
            textSize = T.TEXT_BODY_1
            typeface = T.typefaceMedium()
            gravity = Gravity.CENTER
            minHeight = dp(48f)
            background = T.ripple(T.SIGNAL, T.RADIUS_NONE, this@LauncherActivity)
            isClickable = true
            setOnClickListener { openQqAndRequestPanel() }
        }
        root.addView(btnPrimary, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(T.SP_2) })

        // 次按钮
        val btnSecondary = TextView(this).apply {
            text = "仅打开 QQ"
            setTextColor(T.TEXT)
            textSize = T.TEXT_BODY_1
            typeface = T.typefaceMedium()
            gravity = Gravity.CENTER
            minHeight = dp(48f)
            background = T.ripple(T.SURFACE_HI, T.RADIUS_NONE, this@LauncherActivity, T.RULE)
            isClickable = true
            setOnClickListener {
                try {
                    val launch = packageManager.getLaunchIntentForPackage(Constants.QQ_PACKAGE)
                    if (launch != null) startActivity(launch)
                    else Toast.makeText(this@LauncherActivity, "未安装 QQ", Toast.LENGTH_LONG).show()
                } catch (t: Throwable) {
                    Toast.makeText(this@LauncherActivity, t.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(btnSecondary, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
    }

    private fun openQqAndRequestPanel() {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    Constants.QQ_PACKAGE,
                    "com.tencent.mobileqq.activity.SplashActivity"
                )
                putExtra(SettingsInjectHook.EXTRA_OPEN_PANEL, true)
                putExtra("open_nullfriend", "1")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            Toast.makeText(this, "已请求打开 QQ；若无面板，请在「联系人」页底部点「单向好友」", Toast.LENGTH_LONG).show()
            finish()
        } catch (t: Throwable) {
            try {
                val launch = packageManager.getLaunchIntentForPackage(Constants.QQ_PACKAGE)?.apply {
                    putExtra(SettingsInjectHook.EXTRA_OPEN_PANEL, true)
                    putExtra("open_nullfriend", "1")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launch != null) {
                    startActivity(launch)
                    Toast.makeText(this, "已打开 QQ，请在「联系人」页底部点「单向好友」", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "无法启动 QQ: ${t.message}", Toast.LENGTH_LONG).show()
                }
            } catch (t2: Throwable) {
                Toast.makeText(this, "失败: ${t2.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
