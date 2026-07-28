package com.example.jxsms

import com.example.jxsms.data.sms.AndroidMmsDataSource
import com.example.jxsms.data.sms.MmsMessageIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsDataTest {
    @Test
    fun syntheticMmsIdsAreNegativeUniqueAndReversible() {
        val providerIds = listOf(1L, 42L, 9_223_372_036L)
        val uiIds = providerIds.map(MmsMessageIds::toUiId)

        assertTrue(uiIds.all { it < 0 && it != -1L })
        assertEquals(uiIds.size, uiIds.distinct().size)
        assertEquals(providerIds, uiIds.map(MmsMessageIds::toProviderId))
        assertTrue(uiIds.all(MmsMessageIds::isMms))
        assertFalse(MmsMessageIds.isMms(1L))
        assertFalse(MmsMessageIds.isMms(-1L))
    }

    @Test
    fun mmsEpochSecondsAreConvertedButMillisArePreserved() {
        assertEquals(
            1_721_234_567_000L,
            AndroidMmsDataSource.mmsTimeToMillis(1_721_234_567L)
        )
        assertEquals(
            1_721_234_567_890L,
            AndroidMmsDataSource.mmsTimeToMillis(1_721_234_567_890L)
        )
        assertEquals(0L, AndroidMmsDataSource.mmsTimeToMillis(0L))
    }
}
