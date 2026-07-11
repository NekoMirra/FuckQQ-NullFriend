package com.fuckqq.nullfriend.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffEngineTest {

    private fun f(uin: String, name: String = "n$uin") =
        FriendEntry(uin = uin, name = name, source = FriendSource.API)

    @Test
    fun emptyPrevious_noRemovals() {
        val r = DiffEngine.diff(emptyList(), listOf(f("1"), f("2")))
        assertTrue(r.removed.isEmpty())
        assertEquals(0, r.previousCount)
        assertEquals(2, r.currentCount)
    }

    @Test
    fun detectsRemovalsOnly() {
        val prev = listOf(f("1", "A"), f("2", "B"), f("3", "C"))
        val curr = listOf(f("1", "A"), f("3", "C"), f("9", "New"))
        val r = DiffEngine.diff(prev, curr)
        assertEquals(1, r.removed.size)
        assertEquals("2", r.removed[0].uin)
        assertEquals("B", r.removed[0].name)
        assertEquals(3, r.previousCount)
        assertEquals(3, r.currentCount)
    }

    @Test
    fun allRemoved() {
        val prev = listOf(f("1"), f("2"))
        val r = DiffEngine.diff(prev, emptyList())
        assertEquals(2, r.removed.size)
    }

    @Test
    fun snapshotOverload() {
        val snap = FriendSnapshot(
            ownerUin = "10000",
            friends = listOf(f("1", "A"), f("2", "B")),
            updatedAt = 1L,
            source = FriendSource.API
        )
        val curr = FriendListResult(
            ownerUin = "10000",
            friends = listOf(f("1", "A")),
            fetchedAt = 2L,
            source = FriendSource.DB
        )
        val r = DiffEngine.diff(snap, curr)
        assertEquals(listOf("2"), r.removed.map { it.uin })
    }
}
