package com.fuckqq.nullfriend.provider

import android.content.Context
import com.fuckqq.nullfriend.domain.FriendEntry
import com.fuckqq.nullfriend.domain.FriendListResult
import com.fuckqq.nullfriend.domain.FriendSource
import com.fuckqq.nullfriend.util.Log
import com.fuckqq.nullfriend.util.UinUtil
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Friend list access inspired by QAuxiliary ExfriendManager / ManagerHelper:
 * - AppRuntime from MobileQQ
 * - FriendsManager via getManager(50) (or FRIENDS_MANAGER constant)
 * - ConcurrentHashMap of com.tencent.mobileqq.data.Friends
 * - Optional FriendListHandler refresh request
 *
 * Does NOT copy QAuxiliary AGPL source; reimplements the public reverse-engineering pattern.
 */
class HybridFriendListProvider(
    private val context: Context,
    private val hostClassLoader: ClassLoader?
) : FriendListProvider {

    private val cl: ClassLoader
        get() = hostClassLoader
            ?: context.classLoader
            ?: throw IllegalStateException("No ClassLoader")

    override fun currentOwnerUin(): String? = runCatching { resolveOwnerUin() }.getOrNull()

    override fun fetch(): Result<FriendListResult> {
        return runCatching {
            val owner = resolveOwnerUin()
                ?: throw IllegalStateException("Cannot resolve account uin")
            // Prefer memory FriendsManager (same as QA init-from-internal)
            val fromMgr = loadFromFriendsManager()
            if (fromMgr != null && fromMgr.isNotEmpty()) {
                Log.i("Friends from FriendsManager: ${fromMgr.size}")
                return@runCatching FriendListResult(
                    ownerUin = owner,
                    friends = fromMgr,
                    fetchedAt = System.currentTimeMillis(),
                    source = FriendSource.API
                )
            }
            // Try request refresh then re-read
            requestFriendListRefresh()
            Thread.sleep(800)
            val again = loadFromFriendsManager()
            if (again != null && again.isNotEmpty()) {
                Log.i("Friends after refresh: ${again.size}")
                return@runCatching FriendListResult(
                    ownerUin = owner,
                    friends = again,
                    fetchedAt = System.currentTimeMillis(),
                    source = FriendSource.API
                )
            }
            // NT / service path
            val fromService = loadFromFriendDataService()
            if (fromService != null) {
                Log.i("Friends from IFriendDataService-like: ${fromService.size}")
                return@runCatching FriendListResult(
                    ownerUin = owner,
                    friends = fromService,
                    fetchedAt = System.currentTimeMillis(),
                    source = FriendSource.API
                )
            }
            // Cached network chunks (if DeletionObserver-style hook filled it)
            val cached = FriendListRespCache.snapshot()
            if (cached.isNotEmpty()) {
                Log.i("Friends from GetFriendListResp cache: ${cached.size}")
                return@runCatching FriendListResult(
                    ownerUin = owner,
                    friends = cached,
                    fetchedAt = System.currentTimeMillis(),
                    source = FriendSource.API
                )
            }
            throw IllegalStateException(
                "FriendsManager empty and no service/cache. " +
                    "Open QQ 联系人 tab once, then refresh. " +
                    "mgr=${fromMgr?.size ?: -1}"
            )
        }
    }

    private fun resolveOwnerUin(): String? {
        val rt = getAppRuntime() ?: return null
        // Prefer getLongAccountUin (QA)
        runCatching {
            val v = XposedHelpers.callMethod(rt, "getLongAccountUin")
            val n = when (v) {
                is Long -> v.toString()
                is Number -> v.toLong().toString()
                else -> v?.toString()
            }
            UinUtil.normalize(n)?.let { return it }
        }
        runCatching {
            UinUtil.normalize(XposedHelpers.callMethod(rt, "getAccount") as? String)?.let { return it }
        }
        return null
    }

    private fun getAppRuntime(): Any? {
        val loader = cl
        // MobileQQ.sMobileQQ.mAppRuntime (QA AppRuntimeHelper)
        try {
            val mobileQQ = XposedHelpers.findClass("mqq.app.MobileQQ", loader)
            val sMobileQQ = runCatching {
                XposedHelpers.getStaticObjectField(mobileQQ, "sMobileQQ")
            }.getOrNull() ?: runCatching {
                XposedHelpers.callStaticMethod(mobileQQ, "getMobileQQ")
            }.getOrNull()
            if (sMobileQQ != null) {
                val f = mobileQQ.getDeclaredField("mAppRuntime").apply { isAccessible = true }
                val rt = f.get(sMobileQQ)
                if (rt != null) return rt
            }
        } catch (t: Throwable) {
            Log.d("mAppRuntime: ${t.message}")
        }
        try {
            val mobileQQ = XposedHelpers.findClass("mqq.app.MobileQQ", loader)
            val app = XposedHelpers.callStaticMethod(mobileQQ, "getMobileQQ")
            return XposedHelpers.callMethod(app, "waitAppRuntime", null as Any?)
        } catch (t: Throwable) {
            Log.d("waitAppRuntime: ${t.message}")
        }
        return null
    }

    private fun getQQAppInterface(): Any? {
        val rt = getAppRuntime() ?: return null
        // On classic QQ main process, runtime IS QQAppInterface
        val name = rt.javaClass.name
        if (name.contains("QQAppInterface") || name.contains("AppRuntime")) {
            return rt
        }
        return rt
    }

    private fun getFriendsManager(): Any? {
        val app = getQQAppInterface() ?: return null
        // Try QQManagerFactory.FRIENDS_MANAGER
        var id: Int? = null
        try {
            val factory = XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.app.QQManagerFactory",
                cl
            )
            if (factory != null) {
                for (fname in listOf("FRIENDS_MANAGER", "FRIEND_MANAGER", "BUDDY_LIST_MANAGER")) {
                    try {
                        id = XposedHelpers.getStaticIntField(factory, fname)
                        Log.d("QQManagerFactory.$fname=$id")
                        break
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            Log.d("QQManagerFactory: ${t.message}")
        }
        val candidates = buildList {
            if (id != null) add(id!!)
            add(50) // classic FriendsManager id used by QA
            add(51)
            add(49)
            add(52)
        }.distinct()
        for (mid in candidates) {
            try {
                val mgr = XposedHelpers.callMethod(app, "getManager", mid)
                if (mgr != null) {
                    val cn = mgr.javaClass.name
                    Log.d("getManager($mid) -> $cn")
                    if (cn.contains("Friend", ignoreCase = true) ||
                        cn.contains("Buddy", ignoreCase = true) ||
                        mid == 50 || mid == id
                    ) {
                        // verify has friends map
                        if (findFriendsMap(mgr) != null || cn.contains("FriendsManager")) {
                            return mgr
                        }
                        // still return if FriendsManager name
                        if (cn.contains("FriendsManager")) return mgr
                    }
                }
            } catch (t: Throwable) {
                Log.d("getManager($mid): ${t.message}")
            }
        }
        // Scan managers 0..80 for FriendsManager
        for (mid in 0..80) {
            try {
                val mgr = XposedHelpers.callMethod(app, "getManager", mid) ?: continue
                if (mgr.javaClass.name.contains("FriendsManager")) {
                    Log.i("Found FriendsManager at id=$mid")
                    return mgr
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * QA: find ConcurrentHashMap field whose values are Friends entities.
     */
    private fun findFriendsMap(friendsManager: Any): ConcurrentHashMap<*, *>? {
        val friendsClass = XposedHelpers.findClassIfExists(
            "com.tencent.mobileqq.data.Friends",
            cl
        )
        var clazz: Class<*>? = friendsManager.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (field.type != ConcurrentHashMap::class.java &&
                    !ConcurrentHashMap::class.java.isAssignableFrom(field.type)
                ) {
                    // also HashMap
                    if (!MutableMap::class.java.isAssignableFrom(field.type)) continue
                }
                try {
                    field.isAccessible = true
                    val map = field.get(friendsManager) as? Map<*, *> ?: continue
                    if (map.isEmpty()) continue
                    val sample = map.values.firstOrNull() ?: continue
                    if (friendsClass != null && friendsClass.isInstance(sample)) {
                        @Suppress("UNCHECKED_CAST")
                        return map as ConcurrentHashMap<*, *>
                    }
                    // Heuristic: has uin field
                    if (extractFriendUin(sample) != null) {
                        @Suppress("UNCHECKED_CAST")
                        return if (map is ConcurrentHashMap<*, *>) map
                        else ConcurrentHashMap(map)
                    }
                } catch (_: Throwable) {
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun loadFromFriendsManager(): List<FriendEntry>? {
        val mgr = getFriendsManager() ?: return null
        val map = findFriendsMap(mgr) ?: return emptyList()
        val out = LinkedHashMap<String, FriendEntry>()
        for (value in map.values) {
            if (value == null) continue
            val uin = extractFriendUin(value) ?: continue
            if (uin == resolveOwnerUin()) continue
            val remark = getFieldString(value, "remark")
            val nick = getFieldString(value, "name")
                ?: getFieldString(value, "nick")
            val display = when {
                !remark.isNullOrBlank() -> remark
                !nick.isNullOrBlank() -> nick
                else -> uin
            }
            out[uin] = FriendEntry(
                uin = uin,
                name = display,
                nick = nick,
                source = FriendSource.API
            )
        }
        return out.values.toList()
    }

    private fun extractFriendUin(friendsObj: Any): String? {
        // QA: Friends.uin is String (sometimes long stored as string)
        for (name in listOf("uin", "mUin", "friendUin")) {
            try {
                val f = findField(friendsObj.javaClass, name) ?: continue
                f.isAccessible = true
                val v = f.get(friendsObj) ?: continue
                val n = UinUtil.normalize(v.toString())
                if (n != null) return n
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun getFieldString(obj: Any, name: String): String? {
        return try {
            val f = findField(obj.javaClass, name) ?: return null
            f.isAccessible = true
            f.get(obj)?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    private fun requestFriendListRefresh() {
        try {
            val handler = getFriendListHandler() ?: return
            // QA: Reflex.invokeVirtualAny(handler, true, true, boolean, boolean, void)
            for (m in handler.javaClass.methods) {
                if (m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    (m.returnType == Void.TYPE || m.returnType == Void::class.java)
                ) {
                    try {
                        m.isAccessible = true
                        m.invoke(handler, true, true)
                        Log.i("FriendListHandler refresh via ${m.name}")
                        return
                    } catch (_: Throwable) {
                    }
                }
            }
            // name guess
            for (name in listOf("getFriendGroupList", "getFriendList", "a", "b")) {
                try {
                    XposedHelpers.callMethod(handler, name, true, true)
                    Log.i("FriendListHandler.$name(true,true)")
                    return
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Log.d("requestFriendListRefresh: ${t.message}")
        }
    }

    private fun getFriendListHandler(): Any? {
        val app = getQQAppInterface() ?: return null
        try {
            // QQ 8.5+: getBusinessHandler(String className)
            val flhName = "com.tencent.mobileqq.app.FriendListHandler"
            return XposedHelpers.callMethod(app, "getBusinessHandler", flhName)
        } catch (_: Throwable) {
        }
        try {
            return XposedHelpers.callMethod(app, "getBusinessHandler", 1)
        } catch (_: Throwable) {
        }
        // scan methods named getBusinessHandler
        for (m in app.javaClass.methods) {
            if (m.name != "getBusinessHandler") continue
            try {
                m.isAccessible = true
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                    return m.invoke(app, "com.tencent.mobileqq.app.FriendListHandler")
                }
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    return m.invoke(app, 1)
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun loadFromFriendDataService(): List<FriendEntry>? {
        val app = getQQAppInterface() ?: return null
        val serviceNames = listOf(
            "com.tencent.mobileqq.friend.api.IFriendDataService",
            "com.tencent.mobileqq.profilecard.api.IProfileDataService"
        )
        for (sn in serviceNames) {
            try {
                val iface = XposedHelpers.findClassIfExists(sn, cl) ?: continue
                val svc = XposedHelpers.callMethod(app, "getRuntimeService", iface, "all")
                    ?: continue
                val list = invokeAnyFriendList(svc) ?: continue
                if (list.isNotEmpty()) return list
            } catch (t: Throwable) {
                Log.d("service $sn: ${t.message}")
            }
        }
        return null
    }

    private fun invokeAnyFriendList(manager: Any): List<FriendEntry>? {
        val prefer = listOf(
            "getAllFriends", "getFriendList", "getAllFriendList",
            "getFriends", "getAllBuddyList", "getFriendListSnapshot"
        )
        for (name in prefer) {
            try {
                val raw = XposedHelpers.callMethod(manager, name) ?: continue
                coerceToFriends(raw)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        for (m in manager.javaClass.methods) {
            if (m.parameterTypes.isNotEmpty()) continue
            if (Modifier.isStatic(m.modifiers)) continue
            val rt = m.returnType
            if (!List::class.java.isAssignableFrom(rt) &&
                !Map::class.java.isAssignableFrom(rt) &&
                !rt.name.contains("List")
            ) continue
            try {
                m.isAccessible = true
                val raw = m.invoke(manager) ?: continue
                coerceToFriends(raw)?.let { if (it.isNotEmpty()) return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun coerceToFriends(raw: Any): List<FriendEntry>? {
        val collection: Collection<*> = when (raw) {
            is Collection<*> -> raw
            is Array<*> -> raw.toList()
            is Map<*, *> -> raw.values
            else -> return null
        }
        val out = LinkedHashMap<String, FriendEntry>()
        for (item in collection) {
            if (item == null) continue
            val uin = extractFriendUin(item) ?: continue
            val remark = getFieldString(item, "remark")
            val nick = getFieldString(item, "name") ?: getFieldString(item, "nick")
            val display = when {
                !remark.isNullOrBlank() -> remark
                !nick.isNullOrBlank() -> nick
                else -> uin
            }
            out[uin] = FriendEntry(uin, display, nick, FriendSource.API)
        }
        return out.values.toList()
    }
}
