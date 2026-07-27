package com.example.jxsms

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jxsms.data.backup.EmailBackupBatchEntity
import com.example.jxsms.data.backup.EmailBackupPartEntity
import com.example.jxsms.data.backup.EmailPartOpenData
import com.example.jxsms.domain.backup.EmailBackupBatchStatus
import com.example.jxsms.domain.backup.EmailBackupPartStatus
import com.example.jxsms.share.GmailBackupIntentFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GmailBackupIntentInstrumentedTest {
    @Test
    fun intentContainsSearchableBodyAndReadableContentAttachment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val exportDir = File(context.cacheDir, "backup_exports/intent-test").apply { mkdirs() }
        val attachment = File(exportDir, "part.json").apply { writeText("""{"formatVersion":1}""") }
        val batch = EmailBackupBatchEntity(
            backupId = "JX-TEST",
            recipient = "backup+test@example.com",
            rangeStart = 1L,
            rangeEnd = 2L,
            createdAt = 3L,
            totalMessageCount = 1,
            partCount = 1,
            settingsJson = "{}",
            contentHash = "hash",
            status = EmailBackupBatchStatus.GENERATED
        )
        val part = EmailBackupPartEntity(
            partId = "JX-TEST-P01",
            backupId = batch.backupId,
            partNumber = 1,
            messageCount = 1,
            subject = "[JX SMS Backup] test",
            bodyFileName = "intent-test/body.txt",
            attachmentFileName = "intent-test/part.json",
            bodySizeBytes = 4,
            attachmentSizeBytes = attachment.length(),
            status = EmailBackupPartStatus.GENERATED,
            confirmedAt = null
        )

        val result = GmailBackupIntentFactory(context).create(
            EmailPartOpenData(batch, part, "中文 한글 emoji 😀", attachment)
        )
        val sharedIntent = if (result.gmailTargeted) {
            result.intent
        } else {
            result.intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        }
        val stream = sharedIntent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)

        assertEquals(Intent.ACTION_SEND, sharedIntent.action)
        assertEquals("text/plain", sharedIntent.type)
        assertEquals("backup+test@example.com", sharedIntent.getStringArrayExtra(Intent.EXTRA_EMAIL)!![0])
        assertEquals(part.subject, sharedIntent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("中文 한글 emoji 😀", sharedIntent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("content", stream!!.scheme)
        assertEquals(stream, sharedIntent.clipData!!.getItemAt(0).uri)
        assertTrue(sharedIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(context.contentResolver.openInputStream(stream)?.use { it.readBytes() })
        if (result.gmailTargeted) {
            assertEquals(GmailBackupIntentFactory.GMAIL_PACKAGE, sharedIntent.`package`)
        }

        attachment.delete()
        exportDir.delete()
    }
}
