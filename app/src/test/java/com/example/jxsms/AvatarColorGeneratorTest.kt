package com.example.jxsms

import com.example.jxsms.util.AvatarColorGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarColorGeneratorTest {
    @Test fun colorAndInitialsAreStable() {
        assertEquals(AvatarColorGenerator.colorFor("Alice"), AvatarColorGenerator.colorFor("Alice"))
        assertTrue((0..30).map { AvatarColorGenerator.colorFor("sender-$it") }.distinct().size >= 8)
        assertEquals("?", AvatarColorGenerator.initials(""))
        assertEquals("张", AvatarColorGenerator.initials("张三"))
        assertEquals("김", AvatarColorGenerator.initials("김민수"))
        assertEquals("JD", AvatarColorGenerator.initials("John Doe"))
        assertEquals("78", AvatarColorGenerator.initials("+82 10-1234-5678"))
    }

    @Test fun foregroundHasUsefulContrast() {
        listOf("A", "B", "C", "D").forEach {
            val bg = AvatarColorGenerator.colorFor(it)
            val fg = AvatarColorGenerator.foregroundFor(bg)
            assertTrue(fg == 0xff000000.toInt() || fg == 0xffffffff.toInt())
        }
    }
}
