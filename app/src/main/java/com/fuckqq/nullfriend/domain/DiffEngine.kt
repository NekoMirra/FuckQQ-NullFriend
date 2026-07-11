package com.fuckqq.nullfriend.domain

/**
 * Pure friend-list diff: only removals (previous − current).
 */
object DiffEngine {

    fun diff(previous: FriendSnapshot, current: FriendListResult): DiffResult {
        val prevMap = previous.asMap()
        val currentUins = current.uinSet()
        val removed = prevMap
            .filterKeys { it !in currentUins }
            .values
            .sortedBy { it.uin }
            .toList()
        return DiffResult(
            removed = removed,
            previousCount = previous.friends.size,
            currentCount = current.friends.size
        )
    }

    fun diff(
        previous: Collection<FriendEntry>,
        current: Collection<FriendEntry>
    ): DiffResult {
        val prevMap = previous.associateBy { it.uin }
        val currentUins = current.map { it.uin }.toSet()
        val removed = prevMap
            .filterKeys { it !in currentUins }
            .values
            .sortedBy { it.uin }
            .toList()
        return DiffResult(
            removed = removed,
            previousCount = previous.size,
            currentCount = current.size
        )
    }
}
