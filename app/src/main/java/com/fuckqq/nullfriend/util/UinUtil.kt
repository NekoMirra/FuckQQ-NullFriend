package com.fuckqq.nullfriend.util

object UinUtil {
    /**
     * Real QQ numbers: typically 5–12 digits, not pure serial indices.
     * Rejects empty / short / overlong / non-digit garbage.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 5 || digits.length > 12) return null
        // leading zeros only → invalid
        if (digits.all { it == '0' }) return null
        val n = digits.toLongOrNull() ?: return null
        // QQ numbers are at least 10000
        if (n < 10000L) return null
        return digits
    }

    fun isLikelyUin(s: String): Boolean = normalize(s) != null

    /**
     * Detect obviously fake lists from earlier bugs (e.g. map keys 10001,10002,... as "friends").
     */
    fun looksLikeSerialGarbage(uins: Collection<String>): Boolean {
        if (uins.size < 8) return false
        val nums = uins.mapNotNull { it.toLongOrNull() }.sorted()
        if (nums.size < 8) return false
        // almost all consecutive integers starting near 10000–100xx
        var consecutive = 0
        for (i in 1 until nums.size) {
            if (nums[i] == nums[i - 1] + 1) consecutive++
        }
        val ratio = consecutive.toDouble() / (nums.size - 1)
        val min = nums.first()
        // sequential keys starting in the 10000–10100 band
        return ratio > 0.85 && min in 10000L..20000L
    }

    /** Single uin that looks like a list index rather than a real friend number for display. */
    fun isSuspiciousLowSerial(uin: String): Boolean {
        val n = uin.toLongOrNull() ?: return false
        // 10000–10099 often appear as bogus sequential keys in our earlier map scan
        return n in 10000L..10099L
    }
}
