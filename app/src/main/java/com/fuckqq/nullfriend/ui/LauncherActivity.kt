package com.fuckqq.nullfriend.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fuckqq.nullfriend.BuildConfig
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.hook.SettingsInjectHook

/**
 * Desktop launcher. Opens QQ and requests the in-process panel.
 * Detection data lives in QQ process — always open UI from QQ.
 */
class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val tv = TextView(this).apply {
            text = buildString {
                appendLine("去TM的单向好友")
                appendLine("v${BuildConfig.VERSION_NAME}")
                appendLine()
                appendLine("1. LSPosed 启用本模块，作用域只勾选 QQ")
                appendLine("2. 强制停止并重新打开 QQ")
                appendLine("3. 在 QQ 主界面右下角点橙色「单向好友」")
                appendLine("4. 或点下方按钮尝试自动打开")
                appendLine()
                appendLine("检测与历史数据在 QQ 进程内，必须从 QQ 里打开面板。")
            }
            textSize = 15f
        }
        val btn = Button(this).apply {
            text = "打开 QQ 并唤起面板"
            setOnClickListener { openQqAndRequestPanel() }
        }
        val btn2 = Button(this).apply {
            text = "仅打开 QQ"
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
        root.addView(tv)
        root.addView(btn)
        root.addView(btn2)
        setContentView(root)
    }

    private fun openQqAndRequestPanel() {
        try {
            // Prefer splash with extra; module hooks onResume and opens panel
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
            Toast.makeText(this, "已请求打开 QQ；若无面板，请在主界面右下角点「单向好友」", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "已打开 QQ，请点右下角橙色「单向好友」", Toast.LENGTH_LONG).show()
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
