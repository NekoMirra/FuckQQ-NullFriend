package com.fuckqq.nullfriend.provider

import com.fuckqq.nullfriend.domain.FriendEntry
import com.fuckqq.nullfriend.domain.FriendSource
import com.fuckqq.nullfriend.util.Log
import com.fuckqq.nullfriend.util.UinUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache friend list from network JCE response, same idea as QA DeletionObserver
 * hooking friendlist.GetFriendListResp.readFrom.
 */
object FriendListRespCache {
    private val byUin = ConcurrentHashMap<String, FriendEntry>()

    fun snapshot(): List<FriendEntry> = byUin.values.toList()

    fun clear() = byUin.clear()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val respNames = listOf(
            "friendlist.GetFriendListResp",
            "friendlist/GetFriendListResp",
            "com.tencent.mobileqq.friendlist.GetFriendListResp"
        )
        var hooked = false
        for (cn in respNames) {
            val clazz = XposedHelpers.findClassIfExists(cn.replace('/', '.'), cl) ?: continue
            try {
                // Hook all readFrom methods
                for (m in clazz.declaredMethods) {
                    if (m.name != "readFrom" && m.name != "readFromCache") continue
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                parseResp(param.thisObject)
                            } catch (t: Throwable) {
                                Log.d("GetFriendListResp parse: ${t.message}")
                            }
                        }
                    })
                    hooked = true
                }
                if (hooked) {
                    Log.i("Hooked GetFriendListResp: ${clazz.name}")
                    break
                }
            } catch (t: Throwable) {
                Log.d("hook $cn: ${t.message}")
            }
        }
        if (!hooked) {
            Log.w("GetFriendListResp not found — network cache disabled")
        }
    }

    private fun parseResp(resp: Any) {
        // Prefer vecFriendInfo list (QA FriendChunk)
        val list = runCatching {
            XposedHelpers.getObjectField(resp, "vecFriendInfo") as? List<*>
        }.getOrNull()
        if (list != null && list.isNotEmpty()) {
            var n = 0
            for (info in list) {
                if (info == null) continue
                val uin = extractUin(info) ?: continue
                val remark = fieldStr(info, "remark") ?: fieldStr(info, "strRemark")
                val nick = fieldStr(info, "nick") ?: fieldStr(info, "strNick")
                    ?: fieldStr(info, "name")
                val display = when {
                    !remark.isNullOrBlank() -> remark
                    !nick.isNullOrBlank() -> nick
                    else -> uin
                }
                byUin[uin] = FriendEntry(uin, display, nick, FriendSource.API)
                n++
            }
            if (n > 0) Log.i("GetFriendListResp cached +$n total=${byUin.size}")
            return
        }
        // Array style
        val arrUin = runCatching { XposedHelpers.getObjectField(resp, "arrUin") }.getOrNull()
        if (arrUin is LongArray) {
            val remarks = runCatching { XposedHelpers.getObjectField(resp, "arrRemark") as? Array<*> }
                .getOrNull()
            val nicks = runCatching { XposedHelpers.getObjectField(resp, "arrNick") as? Array<*> }
                .getOrNull()
            for (i in arrUin.indices) {
                val uin = UinUtil.normalize(arrUin[i].toString()) ?: continue
                val remark = remarks?.getOrNull(i)?.toString()
                val nick = nicks?.getOrNull(i)?.toString()
                val display = when {
                    !remark.isNullOrBlank() -> remark
                    !nick.isNullOrBlank() -> nick
                    else -> uin
                }
                byUin[uin] = FriendEntry(uin, display, nick, FriendSource.API)
            }
            Log.i("GetFriendListResp arrUin total=${byUin.size}")
        }
    }

    private fun extractUin(obj: Any): String? {
        for (n in listOf("friendUin", "uin", "lFriendUIN", "uFriendUin")) {
            fieldStr(obj, n)?.let { UinUtil.normalize(it) }?.let { return it }
            try {
                val f = obj.javaClass.getField(n)
                val v = f.get(obj)
                UinUtil.normalize(v?.toString())?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun fieldStr(obj: Any, name: String): String? {
        return try {
            val f = obj.javaClass.getField(name)
            f.get(obj)?.toString()
        } catch (_: Throwable) {
            try {
                XposedHelpers.getObjectField(obj, name)?.toString()
            } catch (_: Throwable) {
                null
            }
        }
    }
}
