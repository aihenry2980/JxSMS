package com.example.jxsms.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.jxsms.R
import com.example.jxsms.data.backup.EmailPartOpenData

data class BackupEmailIntent(
    val intent: Intent,
    val gmailTargeted: Boolean
)

class GmailBackupIntentFactory(private val context: Context) {
    fun create(data: EmailPartOpenData): BackupEmailIntent {
        val attachmentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            data.attachment
        )
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(data.batch.recipient))
            putExtra(Intent.EXTRA_SUBJECT, data.part.subject)
            putExtra(Intent.EXTRA_TEXT, data.body)
            putExtra(Intent.EXTRA_STREAM, attachmentUri)
            clipData = ClipData.newUri(
                context.contentResolver,
                "JX SMS Backup",
                attachmentUri
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val gmailIntent = Intent(base).setPackage(GMAIL_PACKAGE)
        return if (gmailIntent.resolveActivity(context.packageManager) != null) {
            BackupEmailIntent(gmailIntent, gmailTargeted = true)
        } else {
            BackupEmailIntent(
                Intent.createChooser(base, context.getString(R.string.choose_email_app)),
                gmailTargeted = false
            )
        }
    }

    companion object {
        const val GMAIL_PACKAGE = "com.google.android.gm"
    }
}
