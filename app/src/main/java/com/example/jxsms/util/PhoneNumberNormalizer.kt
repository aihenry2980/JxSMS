package com.example.jxsms.util

object PhoneNumberNormalizer {
    fun normalize(value: String): String {
        val raw = value.trim()
        if (raw.any(Char::isLetter)) return raw.lowercase()
        val digits = raw.filter(Char::isDigit)
        return when {
            raw.startsWith("+82") && digits.startsWith("82") -> "0" + digits.drop(2)
            else -> digits
        }
    }
}
