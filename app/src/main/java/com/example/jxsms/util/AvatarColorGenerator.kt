package com.example.jxsms.util

import java.util.Locale

object AvatarColorGenerator {
    private val palette = intArrayOf(
        0xff466580.toInt(), 0xff356859.toInt(), 0xff725a7a.toInt(), 0xff74594b.toInt(),
        0xff4f637c.toInt(), 0xff3f6d74.toInt(), 0xff695f43.toInt(), 0xff586b4d.toInt(),
        0xff735566.toInt(), 0xff4d6470.toInt(), 0xff665d79.toInt(), 0xff3f6a63.toInt()
    )

    fun colorFor(key: String): Int = palette[(stableHash(key) and Int.MAX_VALUE) % palette.size]

    fun foregroundFor(background: Int): Int {
        val red = background ushr 16 and 0xff
        val green = background ushr 8 and 0xff
        val blue = background and 0xff
        val luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255.0
        return if (luminance > 0.58) 0xff000000.toInt() else 0xffffffff.toInt()
    }

    fun initials(name: String): String {
        val value = name.trim()
        if (value.isEmpty()) return "?"
        val digits = value.filter(Char::isDigit)
        if (digits.length >= 2 && value.all { it.isDigit() || it in " +-()" }) return digits.takeLast(2)
        val words = value.split(Regex("""[\s_-]+""")).filter(String::isNotBlank)
        if (words.size > 1 && words.all { it.first().isLetter() && it.first().code < 128 }) {
            return words.take(2).joinToString("") { it.first().uppercaseChar().toString() }
        }
        return value.first().toString().uppercase(Locale.ROOT)
    }

    private fun stableHash(value: String): Int {
        var result = 0
        value.trim().lowercase(Locale.ROOT).forEach { result = 31 * result + it.code }
        return result
    }
}
