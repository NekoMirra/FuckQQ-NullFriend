package com.fuckqq.nullfriend.provider

/**
 * Deprecated path — network chunks handled by [FriendRoster].
 * Kept so old install() calls compile if any leftover references.
 */
object FriendListRespCache {
    fun snapshot() = FriendRoster.snapshot()
    fun size() = FriendRoster.size()
    fun clear() = FriendRoster.clearMemory()
    fun markRefreshStart() {}
    fun install(lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
        FriendRoster.install(lpparam)
    }
}
