package com.fuckqq.nullfriend.provider

import com.fuckqq.nullfriend.domain.FriendEntry
import com.fuckqq.nullfriend.domain.FriendSource
import com.fuckqq.nullfriend.util.Log
import com.fuckqq.nullfriend.util.UinUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full friend roster after QA ExfriendManager algorithm + NT fallbacks.
 *
 * Primary (classic, same as QA export seed):
 * - getManager(50) / FriendsManager → ConcurrentHashMap values = Friends(uin,remark,name)
 * - Hook friendlist.GetFriendListResp.readFrom; assemble chunks until complete
 * - FriendListHandler(true,true) refresh
 *
 * Fallback for NT (FriendsManager often empty, GetFriendListResp may be gone):
 * - Scan all managers for Maps of Friends-like entities
 * - IFriendDataService / runtime services zero-arg list methods
 * - Hook Friends entity readers
 * - EntityManagerFactory / raw friend table via reflection if available
 */
object FriendRoster {

    private val persons = ConcurrentHashMap<String, FriendEntry>()
    private val pendingChunks = CopyOnWriteArrayList<Chunk>()
    private val hooksReady = AtomicBoolean(false)

    @Volatile
    private var hostCl: ClassLoader? = null

    @Volatile
    var lastCompleteTotal: Int = -1
        private set

    @Volatile
    var lastSourceTag: String = "none"
        private set

    @Volatile
    var lastDiag: String = ""
        private set

    data class Chunk(
        val startIndex: Int,
        val friendCount: Int,
        val totalFriendCount: Int,
        val friends: List<FriendEntry>
    )

    fun size(): Int = persons.size

    fun snapshot(): List<FriendEntry> =
        persons.values.filter { !UinUtil.isSuspiciousLowSerial(it.uin) || persons.size < 20 }
            .sortedBy { it.uin }

    fun clearMemory() {
        persons.clear()
        pendingChunks.clear()
        lastCompleteTotal = -1
        lastSourceTag = "none"
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!hooksReady.compareAndSet(false, true)) {
            hostCl = lpparam.classLoader
            return
        }
        hostCl = lpparam.classLoader
        hookGetFriendListResp(lpparam.classLoader)
        hookFriendsEntity(lpparam.classLoader)
        Log.i("FriendRoster hooks installed")
    }

    fun fetchBlocking(timeoutMs: Long = 15_000L): List<FriendEntry> {
        val cl = hostCl ?: throw IllegalStateException("FriendRoster not installed")
        val owner = resolveOwnerUin(cl)
            ?: throw IllegalStateException("Cannot resolve account uin")

        val parts = mutableListOf<String>()
        persons.clear()
        pendingChunks.clear()
        lastCompleteTotal = -1

        // A) Classic FriendsManager seed
        val s1 = seedFromFriendsManager(cl)
        parts += "mgr=$s1"
        if (s1 > 0) lastSourceTag = "FriendsManager"

        // B) All-managers scan (NT often stores elsewhere)
        val s2 = seedScanAllManagers(cl, owner)
        parts += "scan=$s2"
        if (s2 > 0 && lastSourceTag == "none") lastSourceTag = "ManagerScan"

        // C) Runtime services
        val s3 = seedRuntimeServices(cl, owner)
        parts += "svc=$s3"
        if (s3 > 0 && lastSourceTag == "none") lastSourceTag = "RuntimeService"

        // D) Request FL refresh + wait chunks
        requestFriendListRefresh(cl)
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = persons.size
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(350)
            seedFromFriendsManager(cl)
            seedScanAllManagers(cl, owner)
            val now = persons.size
            if (now == last) {
                stable++
                if (lastCompleteTotal > 0 && now >= lastCompleteTotal - 2) break
                if (stable >= 8 && now >= 80) break
                if (stable >= 12 && now >= 20) break
            } else {
                stable = 0
                last = now
            }
        }

        // E) Entity table last resort
        if (persons.size < 20) {
            val s4 = seedEntityFriendsTable(cl, owner)
            parts += "ent=$s4"
            if (s4 > 0) lastSourceTag = "EntityTable"
        }

        persons.remove(owner)
        // drop garbage serials if list looks fake
        if (UinUtil.looksLikeSerialGarbage(persons.keys)) {
            Log.w("Dropping serial-garbage roster size=${persons.size}")
            persons.clear()
            parts += "droppedGarbage"
        } else {
            // still drop 10000–10099 if we have a large real list
            if (persons.size > 50) {
                val drop = persons.keys.filter { UinUtil.isSuspiciousLowSerial(it) }
                drop.forEach { persons.remove(it) }
                if (drop.isNotEmpty()) parts += "dropLow=${drop.size}"
            }
        }

        lastDiag = parts.joinToString(" ")
        Log.i("FriendRoster done size=${persons.size} tag=$lastSourceTag $lastDiag hint=$lastCompleteTotal")

        if (persons.isEmpty()) {
            throw IllegalStateException(
                "roster empty. $lastDiag hint=$lastCompleteTotal. " +
                    "Open 联系人, pull-to-refresh, then 立即刷新 again."
            )
        }
        return snapshot()
    }

    // ---------- put helper ----------

    private fun putFriend(uinRaw: String?, remark: String?, nick: String?): Boolean {
        val uin = UinUtil.normalize(uinRaw) ?: return false
        val display = when {
            !remark.isNullOrBlank() -> remark
            !nick.isNullOrBlank() -> nick
            else -> uin
        }
        persons[uin] = FriendEntry(uin, display, nick, FriendSource.API)
        return true
    }

    // ---------- hooks ----------

    private fun hookGetFriendListResp(cl: ClassLoader) {
        val names = listOf(
            "friendlist.GetFriendListResp",
            "friendlist.GetFriendListRespV2",
            "tencent.im.oidb.cmd0x5d2.Oidb_0x5d2\$RspBody"
        )
        var hooked = 0
        for (cn in names) {
            val clazz = XposedHelpers.findClassIfExists(cn, cl) ?: continue
            for (m in clazz.declaredMethods) {
                if (m.name != "readFrom" && m.name != "mergeFrom" && m.name != "parseFrom") continue
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching { onGetFriendListResp(param.thisObject) }
                        }
                    })
                    hooked++
                } catch (_: Throwable) {
                }
            }
            if (hooked > 0) {
                Log.i("Hooked $cn ($hooked methods)")
                break
            }
        }
        if (hooked == 0) Log.w("GetFriendListResp not found (NT may not use it)")
    }

    private fun hookFriendsEntity(cl: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists("com.tencent.mobileqq.data.Friends", cl) ?: return
        try {
            for (m in clazz.declaredMethods) {
                if (m.name != "readFrom" && m.name != "entityByCursor" && m.name != "readEntity") continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val o = param.thisObject
                            val uin = strField(o, "uin")
                            val remark = strField(o, "remark")
                            val nick = strField(o, "name")
                            putFriend(uin, remark, nick)
                        }
                    }
                })
            }
            Log.i("Hooked Friends entity readers")
        } catch (t: Throwable) {
            Log.d("Friends entity hook: ${t.message}")
        }
    }

    private fun onGetFriendListResp(resp: Any) {
        val startIndex = intField(resp, "startIndex")
        val friendCount = intField(resp, "friend_count")
        val total = intField(resp, "totoal_friend_count").let {
            if (it > 0) it else intField(resp, "total_friend_count")
        }
        val list = parseVecFriendInfo(resp)
        if (list.isEmpty() && friendCount <= 0) return

        val chunk = Chunk(
            startIndex = startIndex,
            friendCount = if (friendCount > 0) friendCount else list.size,
            totalFriendCount = total,
            friends = list
        )
        synchronized(pendingChunks) {
            if (chunk.startIndex == 0) pendingChunks.clear()
            pendingChunks.add(chunk)
            list.forEach { persons[it.uin] = it }
            lastSourceTag = "Resp"
            if (total > 0) lastCompleteTotal = total
            if (total > 0) {
                var left = total
                for (c in pendingChunks) left -= c.friendCount
                Log.i("FL chunk start=$startIndex n=${chunk.friendCount} total=$total left=$left size=${persons.size}")
                if (left == 0) {
                    lastSourceTag = "RespComplete"
                    lastCompleteTotal = total
                    pendingChunks.clear()
                }
            }
        }
    }

    private fun parseVecFriendInfo(resp: Any): List<FriendEntry> {
        val out = ArrayList<FriendEntry>()
        val vec = runCatching {
            XposedHelpers.getObjectField(resp, "vecFriendInfo") as? List<*>
        }.getOrNull()
        if (vec != null) {
            for (info in vec) {
                if (info == null) continue
                val uin = friendInfoUin(info) ?: continue
                val remark = strField(info, "remark")
                val nick = strField(info, "nick") ?: strField(info, "name")
                out.add(
                    FriendEntry(
                        uin,
                        remark?.takeIf { it.isNotBlank() } ?: nick?.takeIf { it.isNotBlank() } ?: uin,
                        nick,
                        FriendSource.API
                    )
                )
            }
            return out
        }
        for (f in resp.javaClass.declaredFields) {
            try {
                f.isAccessible = true
                val v = f.get(resp) as? List<*> ?: continue
                if (v.isEmpty()) continue
                if (friendInfoUin(v[0] ?: continue) == null) continue
                for (info in v) {
                    if (info == null) continue
                    val uin = friendInfoUin(info) ?: continue
                    val remark = strField(info, "remark")
                    val nick = strField(info, "nick")
                    out.add(
                        FriendEntry(
                            uin,
                            remark?.takeIf { it.isNotBlank() } ?: nick?.takeIf { it.isNotBlank() } ?: uin,
                            nick,
                            FriendSource.API
                        )
                    )
                }
                if (out.isNotEmpty()) return out
            } catch (_: Throwable) {
            }
        }
        return out
    }

    private fun friendInfoUin(info: Any): String? {
        for (n in listOf("friendUin", "uin", "lFriendUIN", "uFriendUin")) {
            try {
                val f = info.javaClass.getField(n)
                val v = f.get(info)
                when (v) {
                    is Long -> return UinUtil.normalize(v.toString())
                    is Number -> return UinUtil.normalize(v.toLong().toString())
                    else -> UinUtil.normalize(v?.toString())?.let { return it }
                }
            } catch (_: Throwable) {
            }
            strField(info, n)?.let { UinUtil.normalize(it) }?.let { return it }
        }
        return null
    }

    // ---------- seed paths ----------

    private fun seedFromFriendsManager(cl: ClassLoader): Int {
        return try {
            val map = getFriendsConcurrentHashMap(cl) ?: return 0
            val friendsClz = XposedHelpers.findClass("com.tencent.mobileqq.data.Friends", cl)
            val fUin = friendsClz.getField("uin").also { it.isAccessible = true }
            val fRemark = friendsClz.getField("remark").also { it.isAccessible = true }
            val fNick = friendsClz.getField("name").also { it.isAccessible = true }
            var n = 0
            for (fr in map.values) {
                if (fr == null || !friendsClz.isInstance(fr)) continue
                if (putFriend(fUin.get(fr)?.toString(), fRemark.get(fr)?.toString(), fNick.get(fr)?.toString())) {
                    n++
                }
            }
            n
        } catch (t: Throwable) {
            Log.d("seedFromFriendsManager: ${t.message}")
            0
        }
    }

    private fun getFriendsConcurrentHashMap(cl: ClassLoader): ConcurrentHashMap<*, *>? {
        val app = getAppRuntime(cl) ?: return null
        val mgr = try {
            XposedHelpers.callMethod(app, "getManager", 50)
        } catch (_: Throwable) {
            null
        } ?: scanFriendsManager(app) ?: return null

        val friendsClz = XposedHelpers.findClassIfExists("com.tencent.mobileqq.data.Friends", cl)
            ?: return null

        var c: Class<*>? = mgr.javaClass
        while (c != null && c != Any::class.java) {
            for (field in c.declaredFields) {
                try {
                    if (field.type != ConcurrentHashMap::class.java &&
                        !MutableMap::class.java.isAssignableFrom(field.type)
                    ) continue
                    field.isAccessible = true
                    val map = field.get(mgr) as? Map<*, *> ?: continue
                    if (map.isEmpty()) continue
                    val sample = map.values.firstOrNull() ?: continue
                    if (friendsClz.isInstance(sample)) {
                        Log.i("Friends map ${field.name} size=${map.size} on ${mgr.javaClass.simpleName}")
                        return if (map is ConcurrentHashMap<*, *>) map else ConcurrentHashMap(map)
                    }
                } catch (_: Throwable) {
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun scanFriendsManager(app: Any): Any? {
        for (id in 0..120) {
            try {
                val m = XposedHelpers.callMethod(app, "getManager", id) ?: continue
                if (m.javaClass.name.contains("FriendsManager")) return m
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun seedScanAllManagers(cl: ClassLoader, owner: String): Int {
        val app = getAppRuntime(cl) ?: return 0
        val friendsClz = XposedHelpers.findClassIfExists("com.tencent.mobileqq.data.Friends", cl)
        var added = 0
        for (id in 0..120) {
            val mgr = try {
                XposedHelpers.callMethod(app, "getManager", id)
            } catch (_: Throwable) {
                null
            } ?: continue
            added += collectFriendsFromObject(mgr, friendsClz, owner, depth = 0)
        }
        return added
    }

    private fun collectFriendsFromObject(
        target: Any,
        friendsClz: Class<*>?,
        owner: String,
        depth: Int
    ): Int {
        if (depth > 1) return 0
        var added = 0
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            for (field in c.declaredFields) {
                try {
                    if (Modifier.isStatic(field.modifiers)) continue
                    if (!MutableMap::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    val map = field.get(target) as? Map<*, *> ?: continue
                    if (map.isEmpty() || map.size > 200000) continue
                    val sample = map.values.firstOrNull() ?: continue
                    val isFriends = friendsClz != null && friendsClz.isInstance(sample)
                    val looksFriend = isFriends || sample.javaClass.name.contains("Friend", true)
                    if (!looksFriend && map.size < 30) continue
                    for ((k, v) in map) {
                        if (v == null) continue
                        val uin = when {
                            isFriends || friendsClz?.isInstance(v) == true ->
                                strField(v, "uin")
                            looksFriend ->
                                strField(v, "uin") ?: strField(v, "friendUin")
                            else -> null
                        } ?: continue
                        if (uin == owner) continue
                        val remark = strField(v, "remark")
                        val nick = strField(v, "name") ?: strField(v, "nick")
                        if (putFriend(uin, remark, nick)) added++
                    }
                } catch (_: Throwable) {
                }
            }
            c = c.superclass
        }
        return added
    }

    private fun seedRuntimeServices(cl: ClassLoader, owner: String): Int {
        val app = getAppRuntime(cl) ?: return 0
        val names = listOf(
            "com.tencent.mobileqq.friend.api.IFriendDataService",
            "com.tencent.mobileqq.friend.api.IFriendHandlerService",
            "com.tencent.mobileqq.relation.api.IRelationService"
        )
        var added = 0
        for (sn in names) {
            try {
                val iface = XposedHelpers.findClassIfExists(sn, cl) ?: continue
                val svc = runCatching {
                    XposedHelpers.callMethod(app, "getRuntimeService", iface, "all")
                }.getOrNull() ?: continue
                Log.d("svc $sn -> ${svc.javaClass.name}")
                for (m in svc.javaClass.methods) {
                    if (m.parameterTypes.isNotEmpty()) continue
                    if (Modifier.isStatic(m.modifiers)) continue
                    val n = m.name.lowercase()
                    if (!(n.contains("friend") || n.contains("buddy") || n.contains("all"))) continue
                    try {
                        m.isAccessible = true
                        val raw = m.invoke(svc) ?: continue
                        added += absorbAnyList(raw, owner)
                    } catch (_: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                Log.d("svc $sn: ${t.message}")
            }
        }
        return added
    }

    private fun absorbAnyList(raw: Any, owner: String): Int {
        val col: Collection<*> = when (raw) {
            is Collection<*> -> raw
            is Map<*, *> -> raw.values
            is Array<*> -> raw.toList()
            else -> return 0
        }
        var n = 0
        for (item in col) {
            if (item == null) continue
            val uin = strField(item, "uin") ?: strField(item, "friendUin") ?: continue
            if (uin == owner) continue
            val remark = strField(item, "remark")
            val nick = strField(item, "name") ?: strField(item, "nick")
            if (putFriend(uin, remark, nick)) n++
        }
        return n
    }

    /**
     * Try QQ EntityManager: select * style for Friends entity class.
     */
    private fun seedEntityFriendsTable(cl: ClassLoader, owner: String): Int {
        return try {
            val friendsClz = XposedHelpers.findClassIfExists("com.tencent.mobileqq.data.Friends", cl)
                ?: return 0
            val app = getAppRuntime(cl) ?: return 0
            // Common: EntityManagerFactoryHelper / getEntityManagerFactory
            val emCandidates = mutableListOf<Any?>()
            for (m in app.javaClass.methods) {
                if (m.parameterTypes.isNotEmpty()) continue
                val n = m.name.lowercase()
                if (!(n.contains("entity") || n.contains("em"))) continue
                try {
                    m.isAccessible = true
                    emCandidates += m.invoke(app)
                } catch (_: Throwable) {
                }
            }
            // also getManager factory patterns
            for (id in 0..80) {
                try {
                    val mgr = XposedHelpers.callMethod(app, "getManager", id) ?: continue
                    if (mgr.javaClass.name.contains("Entity", true) ||
                        mgr.javaClass.name.contains("SQLite", true)
                    ) {
                        emCandidates += mgr
                    }
                } catch (_: Throwable) {
                }
            }
            var added = 0
            for (em in emCandidates.filterNotNull()) {
                // query(Class) or query(Class, boolean)
                for (m in em.javaClass.methods) {
                    if (m.name != "query" && m.name != "findAll" && m.name != "tabQuery") continue
                    try {
                        m.isAccessible = true
                        val raw = when {
                            m.parameterTypes.size == 1 && m.parameterTypes[0] == Class::class.java ->
                                m.invoke(em, friendsClz)
                            m.parameterTypes.size == 2 && m.parameterTypes[0] == Class::class.java ->
                                m.invoke(em, friendsClz, true)
                            else -> null
                        } ?: continue
                        val n = absorbAnyList(raw, owner)
                        if (n > 0) {
                            Log.i("Entity query via ${em.javaClass.simpleName}.${m.name} +$n")
                            added += n
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
            added
        } catch (t: Throwable) {
            Log.d("seedEntityFriendsTable: ${t.message}")
            0
        }
    }

    // ---------- refresh ----------

    private fun requestFriendListRefresh(cl: ClassLoader) {
        try {
            val handler = getFriendListHandler(cl) ?: run {
                Log.w("FriendListHandler null")
                return
            }
            Log.i("FriendListHandler=${handler.javaClass.name}")
            for (m in handler.javaClass.methods) {
                if (!Modifier.isPublic(m.modifiers)) continue
                if (m.parameterTypes.size != 2) continue
                if (m.parameterTypes[0] != Boolean::class.javaPrimitiveType) continue
                if (m.parameterTypes[1] != Boolean::class.javaPrimitiveType) continue
                if (m.returnType != Void.TYPE) continue
                try {
                    m.isAccessible = true
                    m.invoke(handler, true, true)
                    Log.i("FL refresh ${m.name}(true,true)")
                } catch (t: Throwable) {
                    Log.d("invoke ${m.name}: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.e("requestFriendListRefresh", t)
        }
    }

    private fun getFriendListHandler(cl: ClassLoader): Any? {
        val app = getAppRuntime(cl) ?: return null
        val name = "com.tencent.mobileqq.app.FriendListHandler"
        for (m in app.javaClass.methods) {
            if (m.name != "getBusinessHandler") continue
            try {
                m.isAccessible = true
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                    return m.invoke(app, name)
                }
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    return m.invoke(app, 1)
                }
            } catch (_: Throwable) {
            }
        }
        return runCatching { XposedHelpers.callMethod(app, "getBusinessHandler", name) }.getOrNull()
    }

    // ---------- runtime ----------

    private fun getAppRuntime(cl: ClassLoader): Any? {
        try {
            val mobileQQ = XposedHelpers.findClass("mqq.app.MobileQQ", cl)
            val s = runCatching { XposedHelpers.getStaticObjectField(mobileQQ, "sMobileQQ") }.getOrNull()
                ?: runCatching { XposedHelpers.callStaticMethod(mobileQQ, "getMobileQQ") }.getOrNull()
            if (s != null) {
                val f = mobileQQ.getDeclaredField("mAppRuntime").apply { isAccessible = true }
                f.get(s)?.let { return it }
            }
        } catch (t: Throwable) {
            Log.d("mAppRuntime: ${t.message}")
        }
        return try {
            val mobileQQ = XposedHelpers.findClass("mqq.app.MobileQQ", cl)
            val app = XposedHelpers.callStaticMethod(mobileQQ, "getMobileQQ")
            XposedHelpers.callMethod(app, "waitAppRuntime", null as Any?)
        } catch (t: Throwable) {
            Log.d("waitAppRuntime: ${t.message}")
            null
        }
    }

    fun resolveOwnerUin(): String? {
        val cl = hostCl ?: return null
        return resolveOwnerUin(cl)
    }

    fun resolveOwnerUin(cl: ClassLoader): String? {
        val rt = getAppRuntime(cl) ?: return null
        runCatching {
            val v = XposedHelpers.callMethod(rt, "getLongAccountUin")
            UinUtil.normalize(
                when (v) {
                    is Long -> v.toString()
                    is Number -> v.toLong().toString()
                    else -> v?.toString()
                }
            )?.let { return it }
        }
        runCatching {
            UinUtil.normalize(XposedHelpers.callMethod(rt, "getAccount") as? String)?.let { return it }
        }
        return null
    }

    private fun intField(obj: Any, name: String): Int {
        return try {
            when (val v = obj.javaClass.getField(name).get(obj)) {
                is Int -> v
                is Short -> v.toInt()
                is Number -> v.toInt()
                else -> 0
            }
        } catch (_: Throwable) {
            try {
                when (val v = XposedHelpers.getObjectField(obj, name)) {
                    is Int -> v
                    is Number -> v.toInt()
                    else -> 0
                }
            } catch (_: Throwable) {
                0
            }
        }
    }

    private fun strField(obj: Any, name: String): String? {
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
