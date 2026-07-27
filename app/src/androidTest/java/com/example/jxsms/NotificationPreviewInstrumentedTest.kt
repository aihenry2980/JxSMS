package com.example.jxsms

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.jxsms.data.sms.SmsMessageModel
import com.example.jxsms.domain.model.SmsCategory
import com.example.jxsms.notification.SmsNotificationManager
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class NotificationPreviewInstrumentedTest {
    @Test
    fun postActionPreviewForDeviceQa() {
        val app = ApplicationProvider.getApplicationContext<JxSmsApplication>()
        runBlocking { app.container.notifications.notifySms(
            SmsMessageModel(
                id = PREVIEW_ID,
                address = "JX",
                body = "通知操作预览：请确认“标为已读”和红色“删除”容易区分。",
                date = System.currentTimeMillis(),
                dateSent = null,
                read = false,
                seen = false,
                type = 1,
                subscriptionId = null,
                category = SmsCategory.NOTICE,
                contactName = "JX 测试通知"
            )
        ) }
    }

    companion object {
        const val PREVIEW_ID = 9_000_000_001L
    }
}
