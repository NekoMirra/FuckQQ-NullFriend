package com.fuckqq.nullfriend.domain

enum class FriendSource {
    API,
    DB,
    UNKNOWN
}

data class FriendEntry(
    val uin: String,
    val name: String,
    val nick: String? = null,
    val source: FriendSource = FriendSource.UNKNOWN
) {
    fun displayName(): String = name.ifBlank { nick ?: uin }
}

data class FriendListResult(
    val ownerUin: String,
    val friends: List<FriendEntry>,
    val fetchedAt: Long,
    val source: FriendSource
) {
    fun asMap(): Map<String, FriendEntry> = friends.associateBy { it.uin }
    fun uinSet(): Set<String> = friends.map { it.uin }.toSet()
}

data class FriendSnapshot(
    val ownerUin: String,
    val friends: List<FriendEntry>,
    val updatedAt: Long,
    val source: FriendSource
) {
    fun uinSet(): Set<String> = friends.map { it.uin }.toSet()
    fun asMap(): Map<String, FriendEntry> = friends.associateBy { it.uin }
}

data class DiffResult(
    val removed: List<FriendEntry>,
    val previousCount: Int,
    val currentCount: Int
)

data class DeletionRecord(
    val id: Long = 0,
    val ownerUin: String,
    val friendUin: String,
    val friendName: String,
    val detectedAt: Long,
    val checkSource: FriendSource,
    val note: String,
    val read: Boolean = false
)

data class AccountState(
    val ownerUin: String,
    val displayName: String? = null,
    val baselineAt: Long? = null,
    val lastCheckAt: Long? = null,
    val lastSource: FriendSource? = null,
    val lastError: String? = null
)

sealed class DetectionOutcome {
    data class BaselineCreated(
        val ownerUin: String,
        val count: Int,
        val source: FriendSource
    ) : DetectionOutcome()

    data class Checked(
        val ownerUin: String,
        val previousCount: Int,
        val currentCount: Int,
        val removed: List<FriendEntry>,
        val source: FriendSource
    ) : DetectionOutcome()

    data class Failed(val reason: String) : DetectionOutcome()

    data class Skipped(val reason: String) : DetectionOutcome()
}
