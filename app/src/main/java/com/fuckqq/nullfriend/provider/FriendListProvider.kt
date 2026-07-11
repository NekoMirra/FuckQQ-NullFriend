package com.fuckqq.nullfriend.provider

import com.fuckqq.nullfriend.domain.FriendListResult

interface FriendListProvider {
    /**
     * Fetch current friends for the logged-in account.
     * @return success result or failure message
     */
    fun fetch(): Result<FriendListResult>

    fun currentOwnerUin(): String?
}

class CompositeFriendListProvider(
    private val primary: FriendListProvider,
    private val fallback: FriendListProvider
) : FriendListProvider {

    override fun currentOwnerUin(): String? =
        primary.currentOwnerUin() ?: fallback.currentOwnerUin()

    override fun fetch(): Result<FriendListResult> {
        val first = primary.fetch()
        if (first.isSuccess) return first
        val second = fallback.fetch()
        if (second.isSuccess) return second
        val r1 = first.exceptionOrNull()?.message ?: "API failed"
        val r2 = second.exceptionOrNull()?.message ?: "DB failed"
        return Result.failure(IllegalStateException("$r1 | $r2"))
    }
}
