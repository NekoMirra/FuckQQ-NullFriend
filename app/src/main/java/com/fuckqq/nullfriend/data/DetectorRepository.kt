package com.fuckqq.nullfriend.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fuckqq.nullfriend.Constants
import com.fuckqq.nullfriend.domain.AccountState
import com.fuckqq.nullfriend.domain.DeletionRecord
import com.fuckqq.nullfriend.domain.FriendEntry
import com.fuckqq.nullfriend.domain.FriendSnapshot
import com.fuckqq.nullfriend.domain.FriendSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DetectorDatabase(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext,
        File(context.applicationContext.filesDir, "${Constants.DB_DIR}/${Constants.DB_NAME}").also {
            it.parentFile?.mkdirs()
        }.absolutePath,
        null,
        VERSION
    ) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE accounts (
              owner_uin TEXT PRIMARY KEY NOT NULL,
              display_name TEXT,
              baseline_at INTEGER,
              last_check_at INTEGER,
              last_source TEXT,
              last_error TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE snapshots (
              owner_uin TEXT PRIMARY KEY NOT NULL,
              payload_json TEXT NOT NULL,
              friend_count INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              source TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE deletion_history (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              owner_uin TEXT NOT NULL,
              friend_uin TEXT NOT NULL,
              friend_name TEXT NOT NULL,
              detected_at INTEGER NOT NULL,
              check_source TEXT NOT NULL,
              note TEXT NOT NULL,
              read INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_hist_owner_time ON deletion_history(owner_uin, detected_at DESC)"
        )
        db.execSQL(
            "CREATE INDEX idx_hist_owner_friend ON deletion_history(owner_uin, friend_uin)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 only
    }

    companion object {
        private const val VERSION = 1

        fun friendsToJson(friends: List<FriendEntry>): String {
            val arr = JSONArray()
            friends.forEach { f ->
                arr.put(
                    JSONObject()
                        .put("uin", f.uin)
                        .put("name", f.name)
                        .put("nick", f.nick)
                        .put("source", f.source.name)
                )
            }
            return arr.toString()
        }

        fun friendsFromJson(json: String): List<FriendEntry> {
            val arr = JSONArray(json)
            val out = ArrayList<FriendEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    FriendEntry(
                        uin = o.getString("uin"),
                        name = o.optString("name", ""),
                        nick = o.optString("nick", null).takeIf { !it.isNullOrEmpty() },
                        source = runCatching {
                            FriendSource.valueOf(o.optString("source", "UNKNOWN"))
                        }.getOrDefault(FriendSource.UNKNOWN)
                    )
                )
            }
            return out
        }
    }
}

class DetectorRepository(context: Context) {
    private val dbHelper = DetectorDatabase(context.applicationContext)
    private val noteDefault =
        "列表中消失可能是对方删除、自己删除或其他关系变化，模块无法区分。"

    fun getSnapshot(ownerUin: String): FriendSnapshot? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT payload_json, friend_count, updated_at, source FROM snapshots WHERE owner_uin=?",
            arrayOf(ownerUin)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val json = c.getString(0)
            val updated = c.getLong(2)
            val source = runCatching { FriendSource.valueOf(c.getString(3)) }
                .getOrDefault(FriendSource.UNKNOWN)
            return FriendSnapshot(
                ownerUin = ownerUin,
                friends = DetectorDatabase.friendsFromJson(json),
                updatedAt = updated,
                source = source
            )
        }
    }

    fun putSnapshot(snapshot: FriendSnapshot) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("owner_uin", snapshot.ownerUin)
            put("payload_json", DetectorDatabase.friendsToJson(snapshot.friends))
            put("friend_count", snapshot.friends.size)
            put("updated_at", snapshot.updatedAt)
            put("source", snapshot.source.name)
        }
        db.insertWithOnConflict("snapshots", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertAccount(state: AccountState) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("owner_uin", state.ownerUin)
            put("display_name", state.displayName)
            put("baseline_at", state.baselineAt)
            put("last_check_at", state.lastCheckAt)
            put("last_source", state.lastSource?.name)
            put("last_error", state.lastError)
        }
        db.insertWithOnConflict("accounts", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAccount(ownerUin: String): AccountState? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT owner_uin, display_name, baseline_at, last_check_at, last_source, last_error FROM accounts WHERE owner_uin=?",
            arrayOf(ownerUin)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return AccountState(
                ownerUin = c.getString(0),
                displayName = c.getString(1),
                baselineAt = if (c.isNull(2)) null else c.getLong(2),
                lastCheckAt = if (c.isNull(3)) null else c.getLong(3),
                lastSource = c.getString(4)?.let {
                    runCatching { FriendSource.valueOf(it) }.getOrNull()
                },
                lastError = c.getString(5)
            )
        }
    }

    fun listAccounts(): List<AccountState> {
        val db = dbHelper.readableDatabase
        val out = mutableListOf<AccountState>()
        db.rawQuery(
            "SELECT owner_uin, display_name, baseline_at, last_check_at, last_source, last_error FROM accounts ORDER BY last_check_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    AccountState(
                        ownerUin = c.getString(0),
                        displayName = c.getString(1),
                        baselineAt = if (c.isNull(2)) null else c.getLong(2),
                        lastCheckAt = if (c.isNull(3)) null else c.getLong(3),
                        lastSource = c.getString(4)?.let {
                            runCatching { FriendSource.valueOf(it) }.getOrNull()
                        },
                        lastError = c.getString(5)
                    )
                )
            }
        }
        return out
    }

    fun insertRemovals(
        ownerUin: String,
        removed: List<FriendEntry>,
        detectedAt: Long,
        checkSource: FriendSource
    ) {
        if (removed.isEmpty()) return
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            removed.forEach { f ->
                val values = ContentValues().apply {
                    put("owner_uin", ownerUin)
                    put("friend_uin", f.uin)
                    put("friend_name", f.displayName())
                    put("detected_at", detectedAt)
                    put("check_source", checkSource.name)
                    put("note", noteDefault)
                    put("read", 0)
                }
                db.insert("deletion_history", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listHistory(ownerUin: String, limit: Int = 500): List<DeletionRecord> {
        val db = dbHelper.readableDatabase
        val out = mutableListOf<DeletionRecord>()
        db.rawQuery(
            """
            SELECT id, owner_uin, friend_uin, friend_name, detected_at, check_source, note, read
            FROM deletion_history WHERE owner_uin=? ORDER BY detected_at DESC LIMIT ?
            """.trimIndent(),
            arrayOf(ownerUin, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DeletionRecord(
                        id = c.getLong(0),
                        ownerUin = c.getString(1),
                        friendUin = c.getString(2),
                        friendName = c.getString(3),
                        detectedAt = c.getLong(4),
                        checkSource = runCatching {
                            FriendSource.valueOf(c.getString(5))
                        }.getOrDefault(FriendSource.UNKNOWN),
                        note = c.getString(6),
                        read = c.getInt(7) != 0
                    )
                )
            }
        }
        return out
    }

    fun clearHistory(ownerUin: String) {
        dbHelper.writableDatabase.delete("deletion_history", "owner_uin=?", arrayOf(ownerUin))
    }

    fun markRead(id: Long) {
        val values = ContentValues().apply { put("read", 1) }
        dbHelper.writableDatabase.update("deletion_history", values, "id=?", arrayOf(id.toString()))
    }

    fun setLastError(ownerUin: String, error: String?) {
        val existing = getAccount(ownerUin)
        upsertAccount(
            (existing ?: AccountState(ownerUin = ownerUin)).copy(
                lastError = error,
                lastCheckAt = System.currentTimeMillis()
            )
        )
    }
}
