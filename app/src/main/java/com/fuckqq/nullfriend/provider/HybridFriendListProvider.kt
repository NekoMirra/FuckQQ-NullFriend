package com.fuckqq.nullfriend.provider

import android.content.Context
import com.fuckqq.nullfriend.domain.FriendEntry
import com.fuckqq.nullfriend.domain.FriendListResult
import com.fuckqq.nullfriend.domain.FriendSource
import com.fuckqq.nullfriend.util.Log
import com.fuckqq.nullfriend.util.UinUtil
import de.robv.android.xposed.XposedHelpers
import java.io.File

/**
 * Hybrid provider: try reflective QQ APIs, then local DB heuristics.
 * Version-specific signatures may need updates after QQ upgrades.
 */
class HybridFriendListProvider(
    private val context: Context,
    private val hostClassLoader: ClassLoader?
) : FriendListProvider {

    private val api = ApiFriendSource(hostClassLoader)
    private val db = DbFriendSource(context, hostClassLoader)

    override fun currentOwnerUin(): String? =
        api.currentOwnerUin() ?: db.currentOwnerUin()

    override fun fetch(): Result<FriendListResult> {
        val apiResult = api.fetch()
        if (apiResult.isSuccess) return apiResult
        Log.w("API friend fetch failed: ${apiResult.exceptionOrNull()?.message}")
        val dbResult = db.fetch()
        if (dbResult.isSuccess) return dbResult
        val msg =
            "API: ${apiResult.exceptionOrNull()?.message}; DB: ${dbResult.exceptionOrNull()?.message}"
        return Result.failure(IllegalStateException(msg))
    }
}

class ApiFriendSource(private val cl: ClassLoader?) : FriendListProvider {

    override fun currentOwnerUin(): String? {
        cl ?: return null
        return resolveOwnerUin(cl)
    }

    override fun fetch(): Result<FriendListResult> {
        val loader = cl ?: return Result.failure(IllegalStateException("No classLoader"))
        return runCatching {
            val owner = resolveOwnerUin(loader)
                ?: throw IllegalStateException("Cannot resolve owner uin")
            val friends = loadFriendsViaReflection(loader, owner)
            FriendListResult(
                ownerUin = owner,
                friends = friends,
                fetchedAt = System.currentTimeMillis(),
                source = FriendSource.API
            )
        }
    }

    companion object {
        fun resolveOwnerUin(cl: ClassLoader): String? {
            val candidates = listOf(
                "com.tencent.common.app.BaseApplicationImpl",
                "mqq.app.MobileQQ",
                "com.tencent.mobileqq.app.QQAppInterface"
            )
            for (className in candidates) {
                try {
                    val clazz = XposedHelpers.findClassIfExists(className, cl) ?: continue
                    when (className) {
                        "mqq.app.MobileQQ", "com.tencent.common.app.BaseApplicationImpl" -> {
                            val inst = runCatching {
                                XposedHelpers.callStaticMethod(clazz, "getMobileQQ")
                            }.getOrNull() ?: runCatching {
                                XposedHelpers.getStaticObjectField(clazz, "sApplication")
                            }.getOrNull() ?: runCatching {
                                XposedHelpers.callStaticMethod(clazz, "getContext")
                            }.getOrNull()
                            if (inst != null) {
                                val uin = extractUinFromApp(inst)
                                if (uin != null) return uin
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            // Common field patterns on runtime app
            try {
                val mobileQQ = XposedHelpers.findClassIfExists("mqq.app.MobileQQ", cl)
                if (mobileQQ != null) {
                    val app = XposedHelpers.callStaticMethod(mobileQQ, "getMobileQQ")
                    val waitApp = XposedHelpers.callMethod(app, "waitAppRuntime", null as Any?)
                    val uin = XposedHelpers.callMethod(waitApp, "getAccount") as? String
                    val n = UinUtil.normalize(uin)
                    if (n != null) return n
                }
            } catch (t: Throwable) {
                Log.d("resolveOwnerUin: ${t.message}")
            }
            return null
        }

        private fun extractUinFromApp(app: Any): String? {
            return try {
                val runtime = XposedHelpers.callMethod(app, "waitAppRuntime", null as Any?)
                UinUtil.normalize(XposedHelpers.callMethod(runtime, "getAccount") as? String)
            } catch (_: Throwable) {
                null
            }
        }

        private fun loadFriendsViaReflection(cl: ClassLoader, owner: String): List<FriendEntry> {
            // NT / classic managers — best-effort; expand with DexKit on device.
            val managerNames = listOf(
                "com.tencent.mobileqq.friend.api.IFriendDataService",
                "com.tencent.mobileqq.app.FriendsManager",
                "com.tencent.mobileqq.activity.contacts.base.FriendListInfo"
            )
            for (name in managerNames) {
                try {
                    val clazz = XposedHelpers.findClassIfExists(name, cl) ?: continue
                    Log.d("Trying friend manager $name")
                    // Service-style: getService from runtime
                    val mobileQQ = XposedHelpers.findClass("mqq.app.MobileQQ", cl)
                    val app = XposedHelpers.callStaticMethod(mobileQQ, "getMobileQQ")
                    val runtime = XposedHelpers.callMethod(app, "waitAppRuntime", null as Any?)
                    val service = runCatching {
                        XposedHelpers.callMethod(runtime, "getRuntimeService", clazz, "all")
                    }.getOrNull()
                    if (service != null) {
                        val list = invokeFriendList(service)
                        if (list != null) return list
                    }
                    val mgr = runCatching {
                        XposedHelpers.callMethod(runtime, "getManager", 50)
                    }.getOrNull()
                    if (mgr != null && mgr.javaClass.name.contains("Friend", ignoreCase = true)) {
                        val list = invokeFriendList(mgr)
                        if (list != null) return list
                    }
                } catch (t: Throwable) {
                    Log.d("manager $name: ${t.message}")
                }
            }
            throw IllegalStateException("No friend API available for this QQ version (owner=$owner)")
        }

        private fun invokeFriendList(manager: Any): List<FriendEntry>? {
            val methodNames = listOf(
                "getAllFriends",
                "getFriendList",
                "getAllFriendList",
                "getFriends",
                "getAllBuddyList"
            )
            for (m in methodNames) {
                try {
                    val raw = XposedHelpers.callMethod(manager, m) ?: continue
                    val parsed = coerceToFriends(raw)
                    if (parsed != null) return parsed
                } catch (_: Throwable) {
                }
            }
            // zero-arg get methods returning List
            manager.javaClass.methods
                .filter {
                    it.parameterTypes.isEmpty() &&
                        (List::class.java.isAssignableFrom(it.returnType) ||
                            it.returnType.name.contains("List"))
                }
                .forEach { method ->
                    try {
                        val raw = method.invoke(manager) ?: return@forEach
                        val parsed = coerceToFriends(raw)
                        if (parsed != null && parsed.isNotEmpty()) return parsed
                        if (parsed != null) return parsed
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
            val out = ArrayList<FriendEntry>()
            for (item in collection) {
                if (item == null) continue
                val uin = extractUin(item) ?: continue
                if (uin.length < 5) continue
                val name = extractName(item)
                out.add(
                    FriendEntry(
                        uin = uin,
                        name = name,
                        source = FriendSource.API
                    )
                )
            }
            // Dedup by uin
            return out.associateBy { it.uin }.values.toList()
        }

        private fun extractUin(item: Any): String? {
            val keys = listOf("uin", "mUin", "friendUin", "uint64_friend_uin", "uUin")
            for (k in keys) {
                val v = runCatching { XposedHelpers.getObjectField(item, k) }.getOrNull()
                val n = UinUtil.normalize(v?.toString())
                if (n != null) return n
            }
            for (m in listOf("getUin", "getFriendUin", "getUinString")) {
                val v = runCatching { XposedHelpers.callMethod(item, m) }.getOrNull()
                val n = UinUtil.normalize(v?.toString())
                if (n != null) return n
            }
            return null
        }

        private fun extractName(item: Any): String {
            val keys = listOf("remark", "name", "mName", "nick", "nickname", "displayName")
            for (k in keys) {
                val v = runCatching { XposedHelpers.getObjectField(item, k) }.getOrNull()?.toString()
                if (!v.isNullOrBlank()) return v
            }
            for (m in listOf("getRemark", "getName", "getNick", "getFriendNick")) {
                val v = runCatching { XposedHelpers.callMethod(item, m) }.getOrNull()?.toString()
                if (!v.isNullOrBlank()) return v
            }
            return ""
        }
    }
}

class DbFriendSource(
    private val context: Context,
    private val cl: ClassLoader?
) : FriendListProvider {

    override fun currentOwnerUin(): String? {
        cl ?: return ApiFriendSource.resolveOwnerUin(
            context.classLoader
        )
        return ApiFriendSource.resolveOwnerUin(cl)
    }

    override fun fetch(): Result<FriendListResult> {
        return runCatching {
            val owner = currentOwnerUin()
                ?: throw IllegalStateException("Cannot resolve owner uin for DB path")
            val friends = scanContactDatabases(owner)
            if (friends.isEmpty()) {
                // Empty may be valid, but if no DB found throw
                throw IllegalStateException("No contact rows found in local DBs")
            }
            FriendListResult(
                ownerUin = owner,
                friends = friends,
                fetchedAt = System.currentTimeMillis(),
                source = FriendSource.DB
            )
        }
    }

    private fun scanContactDatabases(owner: String): List<FriendEntry> {
        val dirs = listOf(
            context.getDatabasePath("noop").parentFile,
            File(context.filesDir.parentFile, "databases"),
            context.filesDir,
            context.filesDir.parentFile
        ).filterNotNull().distinct()

        val out = LinkedHashMap<String, FriendEntry>()
        val nameHints = listOf("friend", "buddy", "contact", "troop", "slowtable", "nt_msg")
        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".db") }
                ?.filter { f ->
                    val n = f.name.lowercase()
                    nameHints.any { n.contains(it) } || n.contains(owner) || n.startsWith("qq")
                }
                ?.forEach { file ->
                    tryReadFriends(file, out)
                }
        }
        return out.values.toList()
    }

    private fun tryReadFriends(file: File, out: MutableMap<String, FriendEntry>) {
        var db: android.database.sqlite.SQLiteDatabase? = null
        try {
            db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            val tables = mutableListOf<String>()
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'",
                null
            ).use { c ->
                while (c.moveToNext()) tables.add(c.getString(0))
            }
            for (table in tables) {
                val tl = table.lowercase()
                if (tl.contains("android_") || tl.startsWith("sqlite_")) continue
                if (!tl.contains("friend") && !tl.contains("buddy")) continue
                readTable(db, table, out)
            }
        } catch (t: Throwable) {
            Log.d("DB ${file.name}: ${t.message}")
        } finally {
            runCatching { db?.close() }
        }
    }

    private fun readTable(
        db: android.database.sqlite.SQLiteDatabase,
        table: String,
        out: MutableMap<String, FriendEntry>
    ) {
        try {
            db.rawQuery("SELECT * FROM `$table` LIMIT 1", null).use { probe ->
                if (probe.columnCount == 0) return
            }
            db.rawQuery("SELECT * FROM `$table` LIMIT 5000", null).use { c ->
                val colNames = (0 until c.columnCount).map { c.getColumnName(it) }
                val uinCol = colNames.firstOrNull { n ->
                    val l = n.lowercase()
                    l == "uin" || l == "frienduin" || l.endsWith("uin") && !l.contains("troop")
                } ?: return
                val nameCol = colNames.firstOrNull { n ->
                    val l = n.lowercase()
                    l.contains("remark") || l == "name" || l.contains("nick")
                }
                val uinIdx = c.getColumnIndex(uinCol)
                val nameIdx = nameCol?.let { c.getColumnIndex(it) } ?: -1
                while (c.moveToNext()) {
                    val uin = UinUtil.normalize(c.getString(uinIdx)) ?: continue
                    if (uin.length < 5) continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                    if (!out.containsKey(uin)) {
                        out[uin] = FriendEntry(
                            uin = uin,
                            name = name,
                            source = FriendSource.DB
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.d("table $table: ${t.message}")
        }
    }
}
