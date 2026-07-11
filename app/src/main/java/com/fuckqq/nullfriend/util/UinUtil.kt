package com.fuckqq.nullfriend.util

object UinUtil {
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 5 || digits.length > 13) return null
        return digits
    }

    fun isLikelyUin(s: String): Boolean = normalize(s) != null
}
