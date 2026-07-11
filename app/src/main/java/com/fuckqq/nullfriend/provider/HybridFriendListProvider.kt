package com.fuckqq.nullfriend.provider

import android.content.Context
import com.fuckqq.nullfriend.domain.FriendEntry
import com.fuckqq.nullfriend.domain.FriendListResult
import com.fuckqq.nullfriend.domain.FriendSource
import com.fuckqq.nullfriend.util.Log
import com.fuckqq.nullfriend.util.UinUtil
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-strategy friend list reader for classic + NT QQ.
 * Pattern reference: QAuxiliary ExfriendManager (FriendsManager map + FriendListHandler),
 * plus aggressive reflection/DB because NT often leaves classic FriendsManager empty.
 */
class HybridFriendListProvider(
    private val context: Context,
    private val hostClassLoader: ClassLoader?
) : FriendListProvider {

    private val cl: ClassLoader
        get() = hostClassLoader ?: context.classLoader
            ?: throw IllegalStateException("No ClassLoader")

    override fun currentOwnerUin(): String? = runCatching { resolveOwnerUin() }.getOrNull()

    override fun fetch(): Result<FriendListResult> {
        return runCatching {
            val owner = resolveOwnerUin()
                ?: throw IllegalStateException("Cannot resolve account uin")

            // Always request a full list refresh first (QA: FriendListHandler true,true)
            // GetFriendListResp comes in chunks; wait and merge.
            FriendListRespCache.markRefreshStart()
            requestFriendListRefresh()

            val merged = LinkedHashMap<String, FriendEntry>()
            val tags = mutableListOf<String>()

            fun absorb(list: List<FriendEntry>?, tag: String) {
                if (list.isNullOrEmpty()) return
                var add = 0
                for (f in list) {
                    if (f.uin == owner) continue
                    if (merged.putIfAbsent(f.uin, f) == null) add++
                    else {
                        // upgrade name if we only had uin
                        val old = merged[f.uin]!!
                        if (old.name == old.uin && f.name != f.uin) {
                            merged[f.uin] = f
                        }
                    }
                }
                tags += "$tag+${list.size}/new$add"
                Log.i("absorb $tag list=${list.size} merged=${merged.size}")
            }

            // Round 1: whatever is already in memory
            absorb(runCatching { loadFromFriendsManager() }.getOrNull(), "FriendsManager")
            absorb(FriendListRespCache.snapshot(), "RespCache0")
            absorb(runCatching { scanAllManagersForFriendMaps(owner) }.getOrNull(), "MgrScan")
            absorb(runCatching { loadFromRuntimeServices() }.getOrNull(), "Svc")
            absorb(runCatching { scanDatabases(owner) }.getOrNull(), "DB")

            // Round 2: wait for network chunks (friend list is paginated)
            val deadline = System.currentTimeMillis() + 8_000
            var lastSize = merged.size
            var stable = 0
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(400)
                absorb(FriendListRespCache.snapshot(), "RespCache")
                absorb(runCatching { loadFromFriendsManager() }.getOrNull(), "FriendsManager2")
                if (merged.size == lastSize) {
                    stable++
                    // if we already have a large list and no growth for ~1.6s, stop early
                    if (stable >= 4 && merged.size >= 50) break
                } else {
                    stable = 0
                    lastSize = merged.size
                }
            }

            // Round 3: last-chance walks
            absorb(runCatching { walkRuntimeForFriends(owner) }.getOrNull(), "Walk")
            absorb(runCatching { scanDatabases(owner) }.getOrNull(), "DB2")

            if (merged.isEmpty()) {
                val diag = buildString {
                    append("all sources empty. ")
                    append(dumpFriendsManagerDiag())
                    append(" cache=").append(FriendListRespCache.snapshot().size)
                }
                Log.w(diag)
                throw IllegalStateException(diag.take(400))
            }

            val source = when {
                tags.any { it.startsWith("DB") } &&
                    !tags.any { it.startsWith("FriendsManager") || it.startsWith("Resp") } ->
                    FriendSource.DB
                else -> FriendSource.API
            }
            Log.i("Friend fetch merged=${merged.size} via ${tags.joinToString(",")}")
            FriendListResult(
                ownerUin = owner,
                friends = merged.values.sortedBy { it.uin },
                fetchedAt = System.currentTimeMillis(),
                source = source
            )
        }
    }

    private fun success(
        owner: String,
        friends: List<FriendEntry>,
        source: FriendSource,
        tag: String
    ): FriendListResult {
        Log.i("Friend fetch OK via $tag count=${friends.size}")
        return FriendListResult(owner, friends, System.currentTimeMillis(), source)
    }

    // ---------- runtime / account ----------

    private fun resolveOwnerUin(): String? {
        val rt = getAppRuntime() ?: return null
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

    private fun getAppRuntime(): Any? {
        try {
            val mobileQQ = XposedHelpers.findClass("mqq.app.MobileQQ", cl)
            val sMobileQQ = runCatching {
                XposedHelpers.getStaticObjectField(mobileQQ, "sMobileQQ")
            }.getOrNull() ?: runCatching {
                XposedHelpers.callStaticMethod(mobileQQ, "getMobileQQ")
            }.getOrNull()
            if (sMobileQQ != null) {
                val f = mobileQQ.getDeclaredField("mAppRuntime").apply { isAccessible = true }
                f.get(sMobileQQ)?.let { return it }
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

    private fun getQQAppInterface(): Any? = getAppRuntime()

    // ---------- FriendsManager (QA) ----------

    private fun getFriendsManager(): Any? {
        val app = getQQAppInterface() ?: return null
        val ids = mutableListOf<Int>()
        try {
            val factory = XposedHelpers.findClassIfExists(
                "com.tencent.mobileqq.app.QQManagerFactory", cl
            )
            if (factory != null) {
                for (fname in listOf("FRIENDS_MANAGER", "FRIEND_MANAGER", "BUDDYLIST_MANAGER")) {
                    runCatching {
                        ids += XposedHelpers.getStaticIntField(factory, fname)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        ids += listOf(50, 51, 49, 52, 53, 48)
        for (mid in ids.distinct()) {
            try {
                val mgr = XposedHelpers.callMethod(app, "getManager", mid) ?: continue
                val cn = mgr.javaClass.name
                if (cn.contains("FriendsManager") || cn.contains("FriendManager") || mid == 50) {
                    Log.d("FriendsManager candidate id=$mid class=$cn")
                    if (cn.contains("Friend", true) || mid == ids.firstOrNull()) {
                        if (cn.contains("FriendsManager") || cn.contains("Friend")) return mgr
                    }
                }
            } catch (_: Throwable) {
            }
        }
        for (mid in 0..100) {
            try {
                val mgr = XposedHelpers.callMethod(app, "getManager", mid) ?: continue
                if (mgr.javaClass.name.contains("FriendsManager")) {
                    Log.i("FriendsManager at id=$mid")
                    return mgr
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun loadFromFriendsManager(): List<FriendEntry>? {
        val mgr = getFriendsManager() ?: return null
        // Collect from ALL maps on the manager, not only Friends-typed
        val out = LinkedHashMap<String, FriendEntry>()
        collectFriendsFromObjectMaps(mgr, out, depth = 0)
        // Also try known methods
        for (name in listOf(
            "g", "h", "i", "j", "getFriendList", "getFriends",
            "d", "c", "b", "a"
        )) {
            try {
                val m = mgr.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterTypes.isEmpty()
                } ?: continue
                val raw = m.invoke(mgr) ?: continue
                mergeFriendLike(raw, out)
            } catch (_: Throwable) {
            }
        }
        return out.values.toList()
    }

    private fun dumpFriendsManagerDiag(): String {
        return try {
            val mgr = getFriendsManager() ?: return "mgr=null"
            val parts = mutableListOf<String>()
            parts += "cls=${mgr.javaClass.name}"
            var c: Class<*>? = mgr.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (!Map::class.java.isAssignableFrom(f.type) &&
                        f.type != ConcurrentHashMap::class.java
                    ) continue
                    try {
                        f.isAccessible = true
                        val map = f.get(mgr) as? Map<*, *> ?: continue
                        val sample = map.values.firstOrNull()?.javaClass?.simpleName ?: "-"
                        parts += "${f.name}.size=${map.size}/$sample"
                    } catch (_: Throwable) {
                    }
                }
                c = c.superclass
            }
            parts.joinToString(",")
        } catch (t: Throwable) {
            "diagErr=${t.message}"
        }
    }

    // ---------- scan all managers ----------

    private fun scanAllManagersForFriendMaps(owner: String): List<FriendEntry>? {
        val app = getQQAppInterface() ?: return null
        val out = LinkedHashMap<String, FriendEntry>()
        for (mid in 0..120) {
            val mgr = try {
                XposedHelpers.callMethod(app, "getManager", mid)
            } catch (_: Throwable) {
                null
            } ?: continue
            val before = out.size
            collectFriendsFromObjectMaps(mgr, out, depth = 0)
            if (out.size > before) {
                Log.i("Manager#$mid ${mgr.javaClass.simpleName} contributed ${out.size - before}")
            }
        }
        out.remove(owner)
        return out.values.toList().ifEmpty { null }
    }

    private fun collectFriendsFromObjectMaps(
        target: Any,
        out: LinkedHashMap<String, FriendEntry>,
        depth: Int
    ) {
        if (depth > 2) return
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                try {
                    if (Modifier.isStatic(f.modifiers)) continue
                    if (!Map::class.java.isAssignableFrom(f.type)) continue
                    f.isAccessible = true
                    val map = f.get(target) as? Map<*, *> ?: continue
                    if (map.isEmpty() || map.size > 200000) continue
                    // Prefer larger maps — classic full friend list is often hundreds+
                    val sample = map.values.firstOrNull()
                    val sampleKey = map.keys.firstOrNull()
                    val keyLooksUin = UinUtil.normalize(sampleKey?.toString()) != null
                    val valLooksFriend = sample != null && friendLikeness(sample) >= 1
                    if (!keyLooksUin && !valLooksFriend && map.size < 20) {
                        // skip tiny unrelated maps
                        continue
                    }
                    for ((k, v) in map.entries) {
                        if (v == null) continue
                        // key might be uin string
                        val keyUin = UinUtil.normalize(k?.toString())
                        val fromVal = extractUinFlexible(v)
                        val uin = fromVal ?: keyUin ?: continue
                        if (uin.length < 5) continue
                        if (out.containsKey(uin)) continue
                        // Prefer objects that look like contacts — but accept uin-keyed maps loosely
                        val score = friendLikeness(v)
                        val uinOk = uin.length in 5..12
                        if (!uinOk) continue
                        if (score < 1 && fromVal == null && keyUin == null) continue
                        // Accept any map entry with a valid uin key or value
                        if (score >= 1 || keyUin != null || fromVal != null) {
                            val remark = getStringField(v, "remark", "strRemark", "mRemark")
                            val nick = getStringField(v, "name", "nick", "nickname", "mName", "strNick")
                            val display = when {
                                !remark.isNullOrBlank() -> remark
                                !nick.isNullOrBlank() -> nick
                                v is String && v != uin -> v
                                v is CharSequence && v.toString() != uin -> v.toString()
                                else -> uin
                            }
                            out[uin] = FriendEntry(uin, display, nick, FriendSource.API)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            c = c.superclass
        }
    }

    private fun friendLikeness(obj: Any): Int {
        val cn = obj.javaClass.name
        var score = 0
        if (cn.contains("Friend", true) || cn.contains("Buddy", true) ||
            cn.contains("Contact", true) || cn.contains("Card", true)
        ) score += 2
        if (findField(obj.javaClass, "uin") != null ||
            findField(obj.javaClass, "friendUin") != null
        ) score += 2
        if (findField(obj.javaClass, "remark") != null ||
            findField(obj.javaClass, "name") != null
        ) score += 1
        return score
    }

    private fun extractUinFlexible(obj: Any): String? {
        if (obj is String) return UinUtil.normalize(obj)
        if (obj is Number) return UinUtil.normalize(obj.toLong().toString())
        for (n in listOf(
            "uin", "mUin", "friendUin", "uFriendUin", "lFriendUIN",
            "uint64_friend_uin", "friend_uin", "toUin"
        )) {
            getStringField(obj, n)?.let { UinUtil.normalize(it) }?.let { return it }
            try {
                val f = findField(obj.javaClass, n) ?: continue
                f.isAccessible = true
                val v = f.get(obj) ?: continue
                when (v) {
                    is Long -> UinUtil.normalize(v.toString())?.let { return it }
                    is Int -> UinUtil.normalize(v.toString())?.let { return it }
                    else -> UinUtil.normalize(v.toString())?.let { return it }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun mergeFriendLike(raw: Any, out: LinkedHashMap<String, FriendEntry>) {
        val col: Collection<*> = when (raw) {
            is Collection<*> -> raw
            is Array<*> -> raw.toList()
            is Map<*, *> -> raw.values
            else -> return
        }
        for (item in col) {
            if (item == null) continue
            val uin = extractUinFlexible(item) ?: continue
            val remark = getStringField(item, "remark", "strRemark")
            val nick = getStringField(item, "name", "nick", "nickname")
            val display = when {
                !remark.isNullOrBlank() -> remark
                !nick.isNullOrBlank() -> nick
                else -> uin
            }
            out.putIfAbsent(uin, FriendEntry(uin, display, nick, FriendSource.API))
        }
    }

    // ---------- services / walk ----------

    private fun loadFromRuntimeServices(): List<FriendEntry>? {
        val app = getQQAppInterface() ?: return null
        val names = listOf(
            "com.tencent.mobileqq.friend.api.IFriendDataService",
            "com.tencent.mobileqq.friend.api.IFriendHandlerService",
            "com.tencent.mobileqq.profilecard.api.IProfileDataService",
            "com.tencent.mobileqq.relation.api.IRelationService",
            "com.tencent.qqnt.kernel.api.IKernelService"
        )
        val out = LinkedHashMap<String, FriendEntry>()
        for (sn in names) {
            try {
                val iface = XposedHelpers.findClassIfExists(sn, cl) ?: continue
                val svc = runCatching {
                    XposedHelpers.callMethod(app, "getRuntimeService", iface, "all")
                }.getOrNull() ?: runCatching {
                    XposedHelpers.callMethod(app, "getRuntimeService", iface, "")
                }.getOrNull() ?: continue
                Log.d("RuntimeService $sn -> ${svc.javaClass.name}")
                invokeListMethods(svc, out)
                // kernel often has getWrapper
                runCatching {
                    val w = XposedHelpers.callMethod(svc, "getWrapper")
                    if (w != null) invokeListMethods(w, out)
                }
                runCatching {
                    val s = XposedHelpers.callMethod(svc, "getService")
                    if (s != null) invokeListMethods(s, out)
                }
            } catch (t: Throwable) {
                Log.d("svc $sn: ${t.message}")
            }
        }
        return out.values.toList().ifEmpty { null }
    }

    private fun invokeListMethods(target: Any, out: LinkedHashMap<String, FriendEntry>) {
        for (m in target.javaClass.methods) {
            if (m.parameterTypes.isNotEmpty()) continue
            if (Modifier.isStatic(m.modifiers)) continue
            val n = m.name.lowercase()
            val interesting = n.contains("friend") || n.contains("buddy") ||
                n.contains("contact") || n.contains("all")
            if (!interesting && !List::class.java.isAssignableFrom(m.returnType) &&
                !Map::class.java.isAssignableFrom(m.returnType)
            ) continue
            try {
                m.isAccessible = true
                val raw = m.invoke(target) ?: continue
                val before = out.size
                mergeFriendLike(raw, out)
                if (out.size > before) {
                    Log.i("method ${target.javaClass.simpleName}.${m.name} +${out.size - before}")
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun walkRuntimeForFriends(owner: String): List<FriendEntry>? {
        val rt = getAppRuntime() ?: return null
        val out = LinkedHashMap<String, FriendEntry>()
        collectFriendsFromObjectMaps(rt, out, depth = 0)
        // one level of non-map object fields named *Friend*
        var c: Class<*>? = rt.javaClass
        var n = 0
        while (c != null && c != Any::class.java && n < 40) {
            for (f in c.declaredFields) {
                try {
                    if (Modifier.isStatic(f.modifiers)) continue
                    val fn = f.name.lowercase()
                    if (!(fn.contains("friend") || fn.contains("buddy") || fn.contains("contact"))) {
                        continue
                    }
                    f.isAccessible = true
                    val v = f.get(rt) ?: continue
                    collectFriendsFromObjectMaps(v, out, depth = 0)
                    invokeListMethods(v, out)
                    n++
                } catch (_: Throwable) {
                }
            }
            c = c.superclass
        }
        out.remove(owner)
        return out.values.toList().ifEmpty { null }
    }

    // ---------- FriendListHandler refresh ----------

    private fun requestFriendListRefresh() {
        try {
            val handler = getFriendListHandler() ?: run {
                Log.d("FriendListHandler null")
                return
            }
            Log.i("FriendListHandler=${handler.javaClass.name}")
            // Prefer methods with (boolean, boolean)
            for (m in handler.javaClass.methods) {
                if (m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                    m.parameterTypes[1] == Boolean::class.javaPrimitiveType
                ) {
                    try {
                        m.isAccessible = true
                        m.invoke(handler, true, true)
                        Log.i("refresh ${m.name}(true,true)")
                    } catch (_: Throwable) {
                    }
                }
            }
            // methods containing Friend / GroupList
            for (m in handler.javaClass.methods) {
                val n = m.name
                if (!(n.contains("Friend", true) || n.contains("GroupList", true) ||
                        n.contains("Buddy", true))
                ) continue
                if (m.parameterTypes.size > 3) continue
                try {
                    m.isAccessible = true
                    val args = m.parameterTypes.map { p ->
                        when (p) {
                            Boolean::class.javaPrimitiveType, Boolean::class.java -> true
                            Int::class.javaPrimitiveType, Int::class.java -> 0
                            Long::class.javaPrimitiveType, Long::class.java -> 0L
                            String::class.java -> ""
                            else -> null
                        }
                    }.toTypedArray()
                    if (args.any { it == null } && m.parameterTypes.isNotEmpty()) continue
                    m.invoke(handler, *args)
                    Log.i("called $n")
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Log.d("requestFriendListRefresh: ${t.message}")
        }
    }

    private fun getFriendListHandler(): Any? {
        val app = getQQAppInterface() ?: return null
        val flh = "com.tencent.mobileqq.app.FriendListHandler"
        for (m in app.javaClass.methods) {
            if (m.name != "getBusinessHandler") continue
            try {
                m.isAccessible = true
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                    return m.invoke(app, flh)
                }
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    return m.invoke(app, 1)
                }
            } catch (_: Throwable) {
            }
        }
        return runCatching {
            XposedHelpers.callMethod(app, "getBusinessHandler", flh)
        }.getOrNull()
    }

    // ---------- SQLite ----------

    private fun scanDatabases(owner: String): List<FriendEntry>? {
        val dirs = linkedSetOf<File>()
        runCatching { context.getDatabasePath("x").parentFile?.let { dirs += it } }
        runCatching {
            context.filesDir.parentFile?.let { parent ->
                dirs += File(parent, "databases")
                dirs += File(parent, "files")
                dirs += parent
                // NT paths
                dirs += File(parent, "databases/nt_db")
                dirs += File(parent, "nt_data")
            }
        }
        val out = LinkedHashMap<String, FriendEntry>()
        for (dir in dirs) {
            if (!dir.exists()) continue
            walkDbFiles(dir, 0, out)
        }
        out.remove(owner)
        Log.i("DB scan friends=${out.size}")
        return out.values.toList().ifEmpty { null }
    }

    private fun walkDbFiles(dir: File, depth: Int, out: LinkedHashMap<String, FriendEntry>) {
        if (depth > 3) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                val n = f.name.lowercase()
                if (n.contains("friend") || n.contains("nt") || n.contains("db") ||
                    n.contains("contact") || n.contains("qq")
                ) {
                    walkDbFiles(f, depth + 1, out)
                }
                continue
            }
            if (!f.name.endsWith(".db") && !f.name.endsWith(".db-wal")) continue
            if (f.name.endsWith("-wal") || f.name.endsWith("-shm")) continue
            val ln = f.name.lowercase()
            // Don't skip msg dbs entirely — some slowtable hold friend cards
            if (ln.contains("msg") && !ln.contains("friend") && !ln.contains("slowtable") &&
                !ln.contains("troop") && f.length() > 80L * 1024 * 1024
            ) {
                continue
            }
            // Prefer friend-related names but also try all reasonably sized dbs
            val prefer = ln.contains("friend") || ln.contains("buddy") || ln.contains("slowtable") ||
                ln.contains("nt") || ln.contains("contact") || ln.startsWith("qq")
            if (!prefer && f.length() > 30L * 1024 * 1024) continue
            tryReadDb(f, out)
        }
    }

    private fun tryReadDb(file: File, out: LinkedHashMap<String, FriendEntry>) {
        var db: android.database.sqlite.SQLiteDatabase? = null
        try {
            db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY or
                    android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            val tables = mutableListOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) tables.add(c.getString(0))
            }
            for (table in tables) {
                val tl = table.lowercase()
                if (tl.startsWith("sqlite_") || tl.startsWith("android_")) continue
                if (tl.contains("troop") && !tl.contains("friend")) continue
                // Include friends/buddy/card/contact and also tables with uin columns we'll detect
                val interesting = tl.contains("friend") || tl.contains("buddy") ||
                    tl.contains("card") || tl.contains("contact") ||
                    tl.contains("buddylist") || tl == "friends" ||
                    tl.contains("group_friend") || tl.contains("stranger").not() &&
                    (tl.contains("uin") || tl.contains("remark"))
                if (!interesting) {
                    // still try if table name is short and generic
                    if (tl.length > 40) continue
                }
                readFriendTable(db, table, out)
            }
        } catch (t: Throwable) {
            Log.d("db ${file.name}: ${t.message}")
        } finally {
            runCatching { db?.close() }
        }
    }

    private fun readFriendTable(
        db: android.database.sqlite.SQLiteDatabase,
        table: String,
        out: LinkedHashMap<String, FriendEntry>
    ) {
        try {
            db.rawQuery("SELECT * FROM `$table` LIMIT 8000", null).use { c ->
                if (c.columnCount == 0) return
                val cols = (0 until c.columnCount).map { c.getColumnName(it) }
                val uinCol = cols.firstOrNull { n ->
                    val l = n.lowercase()
                    l == "uin" || l == "frienduin" || l == "friend_uin" ||
                        (l.endsWith("uin") && !l.contains("troop") && !l.contains("group"))
                } ?: return
                val nameCol = cols.firstOrNull { n ->
                    val l = n.lowercase()
                    l.contains("remark") || l == "name" || l.contains("nick")
                }
                val ui = c.getColumnIndex(uinCol)
                val ni = nameCol?.let { c.getColumnIndex(it) } ?: -1
                var added = 0
                while (c.moveToNext()) {
                    val uin = UinUtil.normalize(c.getString(ui)) ?: continue
                    if (uin.length < 5) continue
                    val name = if (ni >= 0) c.getString(ni) ?: "" else ""
                    if (out.putIfAbsent(
                            uin,
                            FriendEntry(uin, name.ifBlank { uin }, null, FriendSource.DB)
                        ) == null
                    ) {
                        added++
                    }
                }
                if (added > 0) Log.i("table $table +$added")
            }
        } catch (t: Throwable) {
            Log.d("table $table: ${t.message}")
        }
    }

    // ---------- reflect helpers ----------

    private fun getStringField(obj: Any, vararg names: String): String? {
        for (name in names) {
            try {
                val f = findField(obj.javaClass, name) ?: continue
                f.isAccessible = true
                val v = f.get(obj)?.toString()
                if (!v.isNullOrBlank()) return v
            } catch (_: Throwable) {
            }
        }
        return null
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
}
