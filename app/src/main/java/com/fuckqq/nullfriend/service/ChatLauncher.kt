package com.fuckqq.nullfriend.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.widget.Toast
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.util.Log
import de.robv.android.xposed.XposedHelpers

/**
 * Open QQ chat / profile. Intent patterns inspired by public QQ module practice
 * (e.g. QAuxiliary openUserProfileCard idea: AllInOne + FriendProfileCardActivity).
 */
object ChatLauncher {

    fun openChat(context: Context, friendUin: String, classLoader: ClassLoader? = null) {
        if (openChatInternal(context, friendUin, classLoader)) return
        if (openChatDeepLink(context, friendUin)) return
        failCopy(context, friendUin, "无法打开本地聊天")
    }

    fun openProfile(context: Context, friendUin: String, classLoader: ClassLoader? = null) {
        if (openProfileInternal(context, friendUin, classLoader)) return
        if (openProfileDeepLink(context, friendUin)) return
        failCopy(context, friendUin, "无法打开资料卡")
    }

    private fun openChatInternal(context: Context, friendUin: String, cl: ClassLoader?): Boolean {
        return try {
            val intent = Intent().apply {
                setClassName(Constants.QQ_PACKAGE, "com.tencent.mobileqq.activity.SplashActivity")
                putExtra("uin", friendUin)
                putExtra("uintype", 0)
                putExtra("open_chatfragment", true)
                putExtra("open_chatfragment_fromphoto", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.d("openChatInternal: ${t.message}")
            // try ChatActivity
            try {
                val intent = Intent().apply {
                    setClassName(Constants.QQ_PACKAGE, "com.tencent.mobileqq.activity.ChatActivity")
                    putExtra("uin", friendUin)
                    putExtra("uintype", 0)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                true
            } catch (t2: Throwable) {
                Log.d("ChatActivity: ${t2.message}")
                false
            }
        }
    }

    private fun openChatDeepLink(context: Context, friendUin: String): Boolean {
        val uris = listOf(
            "mqqwpa://im/chat?chat_type=wpa&uin=$friendUin",
            "mqqapi://im/chat?chat_type=wpa&version=1&src_type=web&uin=$friendUin"
        )
        for (u in uris) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply {
                    setPackage(Constants.QQ_PACKAGE)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return true
            } catch (t: Throwable) {
                Log.d("chat deeplink: ${t.message}")
            }
        }
        return false
    }

    private fun openProfileInternal(context: Context, friendUin: String, cl: ClassLoader?): Boolean {
        cl ?: return false
        return try {
            // AllInOne(uin, type) + FriendProfileCardActivity — same idea as QA
            val allInOneClz = XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.activity.ProfileActivity\$AllInOne",
                cl
            ) ?: XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.profilecard.data.AllInOne",
                cl
            )
            val profileAct = XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.activity.FriendProfileCardActivity",
                cl
            ) ?: XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.profilecard.activity.FriendProfileCardActivity",
                cl
            )
            if (allInOneClz != null && profileAct != null) {
                val allInOne = try {
                    // ctor(String uin, int type) type 35 = stranger/default open
                    XposedHelpers.newInstance(allInOneClz, friendUin, 35) as Parcelable
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.newInstance(allInOneClz, friendUin, 1) as Parcelable
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (allInOne != null) {
                    val intent = Intent(context, profileAct).apply {
                        putExtra("AllInOne", allInOne)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    return true
                }
            }
            // Fallback: component name only
            for (cn in listOf(
                "com.tencent.mobileqq.activity.FriendProfileCardActivity",
                "com.tencent.mobileqq.profilecard.activity.FriendProfileCardActivity"
            )) {
                try {
                    val intent = Intent().apply {
                        setClassName(Constants.QQ_PACKAGE, cn)
                        putExtra("uin", friendUin)
                        putExtra("friendUin", friendUin)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    return true
                } catch (_: Throwable) {
                }
            }
            false
        } catch (t: Throwable) {
            Log.d("openProfileInternal: ${t.message}")
            false
        }
    }

    private fun openProfileDeepLink(context: Context, friendUin: String): Boolean {
        val uris = listOf(
            "mqqapi://card/show_pslcard?src_type=internal&source=sharecard&version=1&uin=$friendUin",
            "mqqapi://card/show_pslcard?src_type=internal&version=1&card_type=friend&uin=$friendUin"
        )
        for (u in uris) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply {
                    setPackage(Constants.QQ_PACKAGE)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return true
            } catch (t: Throwable) {
                Log.d("profile deeplink: ${t.message}")
            }
        }
        return false
    }

    private fun failCopy(context: Context, friendUin: String, prefix: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("uin", friendUin))
        } catch (_: Throwable) {
        }
        Toast.makeText(context, "$prefix，已复制 QQ 号 $friendUin", Toast.LENGTH_LONG).show()
    }
}
