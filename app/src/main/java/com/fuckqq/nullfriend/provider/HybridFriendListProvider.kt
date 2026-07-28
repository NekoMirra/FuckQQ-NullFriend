package com.fuckqq.nullfriend.provider

import android.content.Context
import com.fuckqq.nullfriend.domain.FriendListResult
import com.fuckqq.nullfriend.domain.FriendSource
import com.fuckqq.nullfriend.util.Log

/**
 * 混合取数：NT 架构优先 (NtBuddyProvider)，旧 QQ 8.x 路径 (FriendRoster) 兜底。
 *
 * QQ NT 9.x 把好友逻辑下沉到 C++ 内核，旧 Java FriendsManager / GetFriendListResp
 * 已失效。NtBuddyProvider 通过 IKernelService -> BuddyService.getBuddyListV2 +
 * ProfileService.getCoreAndBaseInfo 取数。
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
        val owner = currentOwnerUin()
            ?: return Result.failure(IllegalStateException("no owner uin"))

        // 1. 优先 NT 架构取数
        if (hostClassLoader != null) {
            val ntResult = runCatching {
                val friends = NtBuddyProvider.fetchBlocking(hostClassLoader!!, owner)
                FriendListResult(
                    ownerUin = owner,
                    friends = friends,
                    fetchedAt = System.currentTimeMillis(),
                    source = FriendSource.API
                )
            }
            ntResult.onSuccess {
                Log.i("Provider NT OK count=${it.friends.size}")
                return Result.success(it)
            }.onFailure {
                Log.w("Provider NT failed: ${it.message}, fallback to legacy FriendRoster")
            }
        }

        // 2. 旧路径兜底 (QQ 8.x 或 NT 缓存命中)
        return runCatching {
            val friends = FriendRoster.fetchBlocking(12_000L)
            Log.i(
                "Provider legacy OK count=${friends.size} tag=${FriendRoster.lastSourceTag} " +
                    "hint=${FriendRoster.lastCompleteTotal}"
            )
            FriendListResult(
                ownerUin = owner,
                friends = friends,
                fetchedAt = System.currentTimeMillis(),
                source = FriendSource.API
            )
        }
    }
}
