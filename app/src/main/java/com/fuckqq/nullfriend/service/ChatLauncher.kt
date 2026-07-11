package com.fuckqq.nullfriend.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.util.Log
import de.robv.android.xposed.XposedHelpers

object ChatLauncher {

    fun openChat(context: Context, friendUin: String, classLoader: ClassLoader? = null) {
        if (openViaInternal(context, friendUin, classLoader)) return
        if (openViaIntent(context, friendUin)) return
        copyAndToast(context, friendUin)
    }

    private fun openViaInternal(context: Context, friendUin: String, cl: ClassLoader?): Boolean {
        cl ?: return false
        return try {
            // Common QQ chat jump patterns — may break across versions
            val jumpClasses = listOf(
                "com.tencent.mobileqq.activity.ChatActivityUtils",
                "com.tencent.mobileqq.activity.aio.AIOUtils"
            )
            for (cn in jumpClasses) {
                val clazz = XposedHelpers.findClassIfExists(cn, cl) ?: continue
                val methods = clazz.declaredMethods.filter {
                    it.name.contains("start", ignoreCase = true) ||
                        it.name.contains("open", ignoreCase = true) ||
                        it.name.contains("Chat", ignoreCase = true)
                }
                Log.d("Chat class $cn methods=${methods.size}")
            }
            // Intent used by many versions
            val intent = Intent().apply {
                setClassName(
                    Constants.QQ_PACKAGE,
                    "com.tencent.mobileqq.activity.SplashActivity"
                )
                putExtra("uin", friendUin)
                putExtra("uintype", 0)
                putExtra("open_chatfragment", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.d("internal chat: ${t.message}")
            false
        }
    }

    private fun openViaIntent(context: Context, friendUin: String): Boolean {
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("mqqwpa://im/chat?chat_type=wpa&uin=$friendUin")),
            Intent(Intent.ACTION_VIEW, Uri.parse("mqqapi://im/chat?chat_type=wpa&version=1&src_type=web&uin=$friendUin"))
        )
        for (intent in attempts) {
            try {
                intent.setPackage(Constants.QQ_PACKAGE)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return true
            } catch (t: Throwable) {
                Log.d("intent chat: ${t.message}")
            }
        }
        return false
    }

    private fun copyAndToast(context: Context, friendUin: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("uin", friendUin))
        } catch (_: Throwable) {
        }
        Toast.makeText(
            context,
            "无法打开会话，已复制 QQ 号 $friendUin",
            Toast.LENGTH_LONG
        ).show()
    }
}
