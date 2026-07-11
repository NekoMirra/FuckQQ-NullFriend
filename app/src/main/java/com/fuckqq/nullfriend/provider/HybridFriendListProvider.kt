package com.fuckqq.nullfriend.provider

import android.content.Context
import com.fuckqq.nullfriend.domain.FriendListResult
import com.fuckqq.nullfriend.domain.FriendSource
import com.fuckqq.nullfriend.util.Log

/**
 * Thin provider: always use [FriendRoster] (QA export-style full list).
 */
class HybridFriendListProvider(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val hostClassLoader: ClassLoader?
) : FriendListProvider {

    override fun currentOwnerUin(): String? {
        val cl = hostClassLoader ?: return FriendRoster.resolveOwnerUin()
        return FriendRoster.resolveOwnerUin(cl)
    }

    override fun fetch(): Result<FriendListResult> {
        return runCatching {
            if (hostClassLoader != null && FriendRoster.size() == 0) {
                // ensure classloader known if install order was weird
            }
            val friends = FriendRoster.fetchBlocking(12_000L)
            val owner = currentOwnerUin()
                ?: throw IllegalStateException("no owner uin")
            val source = if (FriendRoster.lastSourceTag.contains("Resp")) {
                FriendSource.API
            } else {
                FriendSource.API
            }
            Log.i(
                "Provider OK count=${friends.size} tag=${FriendRoster.lastSourceTag} " +
                    "hint=${FriendRoster.lastCompleteTotal}"
            )
            FriendListResult(
                ownerUin = owner,
                friends = friends,
                fetchedAt = System.currentTimeMillis(),
                source = source
            )
        }
    }
}
