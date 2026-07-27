package com.example.jxsms.data.backup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.jxsms.domain.backup.EmailBackupOptions
import com.example.jxsms.domain.backup.EmailBackupRange
import com.example.jxsms.domain.backup.RedactionOptions
import com.example.jxsms.domain.model.SmsCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.emailBackupDataStore by preferencesDataStore("email_backup_settings")

class EmailBackupPreferencesRepository(private val context: Context) {
    private val recipient = stringPreferencesKey("recipient")
    private val range = stringPreferencesKey("range")
    private val categories = stringSetPreferencesKey("categories")
    private val categoriesInitialized = booleanPreferencesKey("categories_initialized")
    private val redactOtp = booleanPreferencesKey("redact_otp")
    private val redactLongNumbers = booleanPreferencesKey("redact_long_numbers")
    private val includeOriginal = booleanPreferencesKey("include_original")
    private val maxMessages = intPreferencesKey("max_messages")
    private val maxBodyBytes = intPreferencesKey("max_body_bytes")

    val preferences: Flow<EmailBackupOptions> = context.emailBackupDataStore.data.map { values ->
        val defaultCategories = EmailBackupOptions().includedCategories
        EmailBackupOptions(
            recipient = values[recipient].orEmpty(),
            range = values[range]?.let {
                runCatching { EmailBackupRange.valueOf(it) }.getOrNull()
            } ?: EmailBackupRange.UNBACKED,
            includedCategories = if (values[categoriesInitialized] == true) {
                values[categories].orEmpty().mapNotNullTo(mutableSetOf()) {
                    runCatching { SmsCategory.valueOf(it) }.getOrNull()
                }
            } else {
                defaultCategories
            },
            redaction = RedactionOptions(
                redactOtp = values[redactOtp] ?: true,
                redactLongNumbers = values[redactLongNumbers] ?: true,
                includeOriginalBodyInJson = values[includeOriginal] ?: false
            ),
            maxMessagesPerPart = (values[maxMessages] ?: 300).coerceIn(1, 1000),
            maxBodyBytesPerPart = (values[maxBodyBytes] ?: 200 * 1024)
                .coerceIn(32 * 1024, 512 * 1024)
        )
    }

    suspend fun save(options: EmailBackupOptions) = context.emailBackupDataStore.edit { values ->
        values[recipient] = options.recipient.trim()
        values[range] = options.range.name
        values[categories] = options.includedCategories.mapTo(mutableSetOf(), SmsCategory::name)
        values[categoriesInitialized] = true
        values[redactOtp] = options.redaction.redactOtp
        values[redactLongNumbers] = options.redaction.redactLongNumbers
        values[includeOriginal] = options.redaction.includeOriginalBodyInJson
        values[maxMessages] = options.maxMessagesPerPart
        values[maxBodyBytes] = options.maxBodyBytesPerPart
    }
}
