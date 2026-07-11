package com.fuckqq.nullfriend.provider

import com.fuckqq.nullfriend.domain.FriendEntry
import com.fuckqq.nullfriend.domain.FriendSource
import com.fuckqq.nullfriend.util.Log
import com.fuckqq.nullfriend.util.UinUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full friend roster, reimplemented after studying QAuxiliary's ExfriendManager algorithm
 * (NOT a source copy):
 *
 * 1) Seed from FriendsManager: appRuntime.getManager(50) → ConcurrentHashMap whose values are
 *    com.tencent.mobileqq.data.Friends with public fields uin(String), remark, name.
 * 2) Live update from friendlist.GetFriendListResp.readFrom chunks until
 *    friend_count + startIndex == totoal_friend_count (QA integrity check).
 * 3) Force refresh: FriendListHandler method(boolean,boolean) with (true, true).
 *
 * Export/diff always reads [snapshot], which is this roster — same idea as QA export
 * using ExfriendManager.getPersons().
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

    data class Chunk(
        val ownerUin: Long,
        val startIndex: Int,
        val friendCount: Int,
        val totalFriendCount: Int,
        val serverTime: Long,
        val friends: List<FriendEntry>
    )

    fun size(): Int = persons.size

    fun snapshot(): List<FriendEntry> = persons.values.sortedBy { it.uin }

    fun clearMemory() {
        persons.clear()
        pendingChunks.clear()
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!hooksReady.compareAndSet(false, true)) return
        hostCl = lpparam.classLoader
        hookGetFriendListResp(lpparam.classLoader)
        Log.i("FriendRoster hooks installed")
    }

    /**
     * Synchronously build best roster: seed manager → request FL → wait for complete chunks.
     */
    fun fetchBlocking(timeoutMs: Long = 12_000L): List<FriendEntry> {
        val cl = hostCl ?: throw IllegalStateException("FriendRoster not installed (no ClassLoader)")
        val owner = resolveOwnerUin(cl)
            ?: throw IllegalStateException("Cannot resolve account uin")

        // 1) Seed from FriendsManager map (QA init-from-internal)
        val seeded = seedFromFriendsManager(cl)
        if (seeded > 0) {
            lastSourceTag = "FriendsManager"
            Log.i("FriendRoster seed FriendsManager +$seeded total=${persons.size}")
        }

        // 2) Request full list refresh (QA doRequestFlRefresh)
        pendingChunks.clear()
        requestFriendListRefresh(cl)

        // 3) Wait for complete chunk assembly OR timeout with growth
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = persons.size
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300)
            // re-seed manager in case QQ filled it after refresh
            seedFromFriendsManager(cl)
            val now = persons.size
            if (now == last) {
                stable++
                // complete if we hit reported total, or large stable list
                if (lastCompleteTotal > 0 && now >= lastCompleteTotal - 2) break
                if (stable >= 6 && now >= 50) break
            } else {
                stable = 0
                last = now
            }
        }

        // filter self
        persons.remove(owner)

        if (persons.isEmpty()) {
            throw IllegalStateException(
                "roster empty after refresh. " +
                    "seeded=$seeded completeHint=$lastCompleteTotal " +
                    "try open 联系人 then refresh"
            )
        }
        if (lastSourceTag == "FriendsManager" && pendingChunks.isNotEmpty()) {
            lastSourceTag = "FriendsManager+Resp"
        } else if (persons.isNotEmpty() && lastSourceTag == "none") {
            lastSourceTag = "Resp"
        }
        Log.i("FriendRoster fetch done size=${persons.size} tag=$lastSourceTag hint=$lastCompleteTotal")
        return snapshot()
    }

    // ---------------- hooks ----------------

    private fun hookGetFriendListResp(cl: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists("friendlist.GetFriendListResp", cl)
            ?: XposedHelpers.findClassIfExists("Lfriendlist/GetFriendListResp;", cl)
        if (clazz == null) {
            Log.w("GetFriendListResp class not found")
            return
        }
        var n = 0
        for (m in clazz.declaredMethods) {
            if (m.name != "readFrom") continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { onGetFriendListResp(param.thisObject) }
                            .onFailure { Log.d("onGetFriendListResp: ${it.message}") }
                    }
                })
                n++
            } catch (t: Throwable) {
                Log.d("hook readFrom: ${t.message}")
            }
        }
        Log.i("Hooked GetFriendListResp.readFrom x$n")
    }

    /**
     * Mirror QA FriendChunk.fromGetFriendListResp + ExfriendManager.recordFriendChunk.
     */
    private fun onGetFriendListResp(resp: Any) {
        val startIndex = intField(resp, "startIndex")
        val friendCount = intField(resp, "friend_count")
        val total = intField(resp, "totoal_friend_count").let {
            // handle possible spelling variants
            if (it > 0) it else intField(resp, "total_friend_count")
        }
        val owner = longField(resp, "uin")
        val serverTime = longField(resp, "serverTime").let {
            if (it > 0) it else System.currentTimeMillis() / 1000
        }
        if (friendCount <= 0 && total <= 0) return

        val list = parseVecFriendInfo(resp)
        if (list.isEmpty() && friendCount > 0) {
            Log.d("GetFriendListResp chunk empty parse start=$startIndex count=$friendCount")
            return
        }

        val chunk = Chunk(
            ownerUin = owner,
            startIndex = startIndex,
            friendCount = if (friendCount > 0) friendCount else list.size,
            totalFriendCount = total,
            serverTime = serverTime,
            friends = list
        )

        synchronized(pendingChunks) {
            if (chunk.startIndex == 0) {
                pendingChunks.clear()
            }
            pendingChunks.add(chunk)
            // merge into persons immediately (progressive)
            for (f in chunk.friends) {
                persons[f.uin] = f
            }
            lastSourceTag = "Resp"
            if (chunk.totalFriendCount > 0) lastCompleteTotal = chunk.totalFriendCount

            // integrity: sum(friend_count) == total (QA: subtract each chunk from total → 0)
            if (chunk.totalFriendCount > 0) {
                var left = chunk.totalFriendCount
                for (c in pendingChunks) left -= c.friendCount
                Log.i(
                    "FL chunk start=${chunk.startIndex} n=${chunk.friendCount} " +
                        "total=${chunk.totalFriendCount} left=$left roster=${persons.size}"
                )
                if (left == 0) {
                    // complete page set
                    lastCompleteTotal = chunk.totalFriendCount
                    lastSourceTag = "RespComplete"
                    Log.i("Friend list COMPLETE roster=${persons.size} expected=$lastCompleteTotal")
                    pendingChunks.clear()
                }
            } else {
                Log.i("FL chunk start=${chunk.startIndex} n=${list.size} roster=${persons.size}")
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
                val display = when {
                    !remark.isNullOrBlank() -> remark
                    !nick.isNullOrBlank() -> nick
                    else -> uin
                }
                out.add(FriendEntry(uin, display, nick, FriendSource.API))
            }
            return out
        }
        // fallback field walk
        for (f in resp.javaClass.declaredFields) {
            try {
                f.isAccessible = true
                val v = f.get(resp) as? List<*> ?: continue
                if (v.isEmpty()) continue
                val sample = v[0] ?: continue
                if (friendInfoUin(sample) == null) continue
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
        // QA: FriendInfo.friendUin is long
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

    // ---------------- FriendsManager seed (exact QA algorithm) ----------------

    private fun seedFromFriendsManager(cl: ClassLoader): Int {
        return try {
            val map = getFriendsConcurrentHashMap(cl) ?: return 0
            val friendsClz = XposedHelpers.findClass("com.tencent.mobileqq.data.Friends", cl)
            val fUin = friendsClz.getField("uin") // String in QA comments "long!!!" but used as String parse
            fUin.isAccessible = true
            val fRemark = friendsClz.getField("remark").also { it.isAccessible = true }
            val fNick = friendsClz.getField("name").also { it.isAccessible = true }
            var added = 0
            for (entry in map.entries) {
                val fr = entry.value ?: continue
                if (!friendsClz.isInstance(fr)) continue
                val rawUin = fUin.get(fr)?.toString() ?: continue
                val uin = UinUtil.normalize(rawUin) ?: continue
                val remark = fRemark.get(fr)?.toString()
                val nick = fNick.get(fr)?.toString()
                val display = when {
                    !remark.isNullOrBlank() -> remark
                    !nick.isNullOrBlank() -> nick
                    else -> uin
                }
                if (persons.putIfAbsent(uin, FriendEntry(uin, display, nick, FriendSource.API)) == null) {
                    added++
                } else {
                    // refresh names
                    persons[uin] = FriendEntry(uin, display, nick, FriendSource.API)
                }
            }
            added
        } catch (t: Throwable) {
            Log.d("seedFromFriendsManager: ${t.message}")
            0
        }
    }

    /**
     * QA:
     * getFriendsManager = appRuntime.getManager(50)
     * getFriendsConcurrentHashMap: first ConcurrentHashMap field whose values are Friends.
     */
    private fun getFriendsConcurrentHashMap(cl: ClassLoader): ConcurrentHashMap<*, *>? {
        val app = getAppRuntime(cl) ?: return null
        val mgr = try {
            XposedHelpers.callMethod(app, "getManager", 50)
        } catch (t: Throwable) {
            Log.d("getManager(50): ${t.message}")
            // scan for FriendsManager
            scanFriendsManager(app)
        } ?: return null

        val friendsClz = XposedHelpers.findClassIfExists("com.tencent.mobileqq.data.Friends", cl)
            ?: return null

        // Prefer declared type ConcurrentHashMap exactly like QA
        var c: Class<*>? = mgr.javaClass
        while (c != null && c != Any::class.java) {
            for (field in c.declaredFields) {
                try {
                    if (field.type != ConcurrentHashMap::class.java) continue
                    field.isAccessible = true
                    val map = field.get(mgr) as? ConcurrentHashMap<*, *> ?: continue
                    if (map.isEmpty()) continue
                    val sample = map[map.keys.first()] ?: continue
                    if (friendsClz.isInstance(sample)) {
                        Log.i("Friends map field=${field.name} size=${map.size} cls=${mgr.javaClass.name}")
                        return map
                    }
                } catch (_: Throwable) {
                }
            }
            c = c.superclass
        }
        // Any Map with Friends values
        c = mgr.javaClass
        while (c != null && c != Any::class.java) {
            for (field in c.declaredFields) {
                try {
                    if (!MutableMap::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    val map = field.get(mgr) as? Map<*, *> ?: continue
                    if (map.isEmpty()) continue
                    val sample = map.values.firstOrNull() ?: continue
                    if (friendsClz.isInstance(sample)) {
                        Log.i("Friends map(any) field=${field.name} size=${map.size}")
                        return if (map is ConcurrentHashMap<*, *>) map else ConcurrentHashMap(map)
                    }
                } catch (_: Throwable) {
                }
            }
            c = c.superclass
        }
        Log.d("No Friends ConcurrentHashMap on ${mgr.javaClass.name}")
        return null
    }

    private fun scanFriendsManager(app: Any): Any? {
        for (id in 0..100) {
            try {
                val m = XposedHelpers.callMethod(app, "getManager", id) ?: continue
                if (m.javaClass.name.contains("FriendsManager")) {
                    Log.i("FriendsManager found id=$id")
                    return m
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    // ---------------- refresh ----------------

    /**
     * QA ManagerHelper.getFriendListHandler + Reflex.invokeVirtualAny(h, true, true, boolean, boolean, void)
     */
    private fun requestFriendListRefresh(cl: ClassLoader) {
        try {
            val handler = getFriendListHandler(cl) ?: run {
                Log.w("FriendListHandler null")
                return
            }
            Log.i("FriendListHandler=${handler.javaClass.name}")
            // Exact: public void xxx(boolean, boolean)
            for (m in handler.javaClass.methods) {
                if (!Modifier.isPublic(m.modifiers)) continue
                if (m.parameterTypes.size != 2) continue
                if (m.parameterTypes[0] != Boolean::class.javaPrimitiveType) continue
                if (m.parameterTypes[1] != Boolean::class.javaPrimitiveType) continue
                if (m.returnType != Void.TYPE) continue
                try {
                    m.isAccessible = true
                    m.invoke(handler, true, true)
                    Log.i("FL refresh invoked ${m.declaringClass.simpleName}.${m.name}(true,true)")
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
        // getBusinessHandler(String)
        for (m in app.javaClass.methods) {
            if (m.name != "getBusinessHandler") continue
            try {
                m.isAccessible = true
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                    return m.invoke(app, name)
                }
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    // classic id 1
                    return m.invoke(app, 1)
                }
            } catch (_: Throwable) {
            }
        }
        return runCatching { XposedHelpers.callMethod(app, "getBusinessHandler", name) }.getOrNull()
    }

    // ---------------- runtime helpers ----------------

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
            val f = obj.javaClass.getField(name)
            when (val v = f.get(obj)) {
                is Int -> v
                is Short -> v.toInt()
                is Number -> v.toInt()
                else -> 0
            }
        } catch (_: Throwable) {
            try {
                when (val v = XposedHelpers.getObjectField(obj, name)) {
                    is Int -> v
                    is Short -> v.toInt()
                    is Number -> v.toInt()
                    else -> 0
                }
            } catch (_: Throwable) {
                0
            }
        }
    }

    private fun longField(obj: Any, name: String): Long {
        return try {
            val f = obj.javaClass.getField(name)
            when (val v = f.get(obj)) {
                is Long -> v
                is Number -> v.toLong()
                else -> 0L
            }
        } catch (_: Throwable) {
            try {
                when (val v = XposedHelpers.getObjectField(obj, name)) {
                    is Long -> v
                    is Number -> v.toLong()
                    else -> 0L
                }
            } catch (_: Throwable) {
                0L
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
