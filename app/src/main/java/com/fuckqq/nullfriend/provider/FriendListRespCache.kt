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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Accumulates friend list from network JCE responses (chunked).
 * Pattern from QAuxiliary DeletionObserver + FriendChunk.
 */
object FriendListRespCache {
    private val byUin = ConcurrentHashMap<String, FriendEntry>()
    private val chunkCount = AtomicInteger(0)
    private val lastUpdateMs = AtomicLong(0)
    @Volatile
    private var lastTotalHint: Int = -1

    fun snapshot(): List<FriendEntry> = byUin.values.toList()

    fun size(): Int = byUin.size

    fun clear() {
        byUin.clear()
        chunkCount.set(0)
        lastTotalHint = -1
    }

    fun markRefreshStart() {
        // Keep old data; new chunks merge. Optionally soft-clear if very stale.
        Log.i("FriendListRespCache refresh start size=${byUin.size}")
    }

    fun put(uin: String, name: String, nick: String? = null) {
        val n = UinUtil.normalize(uin) ?: return
        val display = name.ifBlank { nick?.ifBlank { n } ?: n }
        byUin[n] = FriendEntry(n, display, nick, FriendSource.API)
        lastUpdateMs.set(System.currentTimeMillis())
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var hooked = false
        for (cn in listOf(
            "friendlist.GetFriendListResp",
            "friendlist.GetFriendListRespV2"
        )) {
            val clazz = XposedHelpers.findClassIfExists(cn, cl) ?: continue
            for (m in clazz.declaredMethods) {
                if (m.name != "readFrom" && m.name != "readFromCache") continue
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching { parseResp(param.thisObject) }
                                .onFailure { Log.d("parseResp: ${it.message}") }
                        }
                    })
                    hooked = true
                } catch (_: Throwable) {
                }
            }
            try {
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { parseResp(param.thisObject) }
                    }
                })
            } catch (_: Throwable) {
            }
            if (hooked) {
                Log.i("Hooked $cn for friend chunks")
                break
            }
        }
        // Also hook FriendInfo materialization if present
        val fi = XposedHelpers.findClassIfExists("friendlist.FriendInfo", cl)
        if (fi != null) {
            try {
                for (m in fi.declaredMethods) {
                    if (m.name != "readFrom") continue
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                val uin = extractUin(param.thisObject) ?: return@runCatching
                                val remark = fieldStr(param.thisObject, "remark")
                                val nick = fieldStr(param.thisObject, "nick")
                                    ?: fieldStr(param.thisObject, "name")
                                put(
                                    uin,
                                    when {
                                        !remark.isNullOrBlank() -> remark
                                        !nick.isNullOrBlank() -> nick
                                        else -> uin
                                    },
                                    nick
                                )
                            }
                        }
                    })
                    Log.i("Hooked friendlist.FriendInfo.readFrom")
                }
            } catch (t: Throwable) {
                Log.d("FriendInfo hook: ${t.message}")
            }
        }
        if (!hooked) Log.w("GetFriendListResp not found")
    }

    private fun parseResp(resp: Any) {
        chunkCount.incrementAndGet()
        // total hint
        for (fn in listOf("totoal_friend_count", "total_friend_count", "friend_count", "getfriendCount")) {
            try {
                val v = XposedHelpers.getObjectField(resp, fn)
                val n = when (v) {
                    is Number -> v.toInt()
                    else -> v?.toString()?.toIntOrNull()
                }
                if (n != null && n > lastTotalHint) lastTotalHint = n
            } catch (_: Throwable) {
            }
        }

        // QA path: vecFriendInfo ArrayList of FriendInfo
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
                put(
                    uin,
                    when {
                        !remark.isNullOrBlank() -> remark
                        !nick.isNullOrBlank() -> nick
                        else -> uin
                    },
                    nick
                )
                n++
            }
            Log.i(
                "GetFriendListResp chunk +$n totalCache=${byUin.size} " +
                    "hintTotal=$lastTotalHint chunks=${chunkCount.get()}"
            )
            return
        }

        // Field walk: any List of objects with friendUin
        for (f in resp.javaClass.declaredFields) {
            try {
                f.isAccessible = true
                val v = f.get(resp) ?: continue
                if (v is List<*> && v.isNotEmpty()) {
                    val sample = v.firstOrNull() ?: continue
                    if (extractUin(sample) == null) continue
                    var n = 0
                    for (item in v) {
                        if (item == null) continue
                        val uin = extractUin(item) ?: continue
                        val remark = fieldStr(item, "remark") ?: fieldStr(item, "strRemark")
                        val nick = fieldStr(item, "nick") ?: fieldStr(item, "name")
                        put(
                            uin,
                            when {
                                !remark.isNullOrBlank() -> remark
                                !nick.isNullOrBlank() -> nick
                                else -> uin
                            },
                            nick
                        )
                        n++
                    }
                    if (n > 0) {
                        Log.i("GetFriendListResp field ${f.name} +$n total=${byUin.size}")
                    }
                }
                if (v is LongArray && v.isNotEmpty() && f.name.contains("uin", true)) {
                    for (x in v) {
                        UinUtil.normalize(x.toString())?.let { put(it, it) }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun extractUin(obj: Any): String? {
        for (n in listOf(
            "friendUin", "uin", "lFriendUIN", "uFriendUin",
            "uint64_friend_uin", "friend_uin"
        )) {
            try {
                val f = obj.javaClass.getField(n)
                UinUtil.normalize(f.get(obj)?.toString())?.let { return it }
            } catch (_: Throwable) {
            }
            fieldStr(obj, n)?.let { UinUtil.normalize(it) }?.let { return it }
        }
        return null
    }

    private fun fieldStr(obj: Any, name: String): String? {
        return try {
            obj.javaClass.getField(name).get(obj)?.toString()
        } catch (_: Throwable) {
            try {
                XposedHelpers.getObjectField(obj, name)?.toString()
            } catch (_: Throwable) {
                null
            }
        }
    }
}
