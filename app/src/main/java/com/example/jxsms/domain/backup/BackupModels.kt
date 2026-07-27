package com.example.jxsms.domain.backup

import com.example.jxsms.data.sms.SmsMessageModel
import com.example.jxsms.domain.model.SmsCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

enum class EmailBackupRange {
    UNBACKED, LAST_7_DAYS, LAST_30_DAYS, THIS_MONTH, CUSTOM
}

enum class EmailBackupBatchStatus {
    GENERATED, GMAIL_OPENED, PARTIALLY_CONFIRMED, CONFIRMED_SENT,
    USER_REPORTED_NOT_SENT, FAILED
}

enum class EmailBackupPartStatus {
    GENERATED, GMAIL_OPENED, CONFIRMED_SENT, USER_REPORTED_NOT_SENT, FAILED
}

object EmailBackupStatusResolver {
    fun batchStatus(parts: List<EmailBackupPartStatus>): EmailBackupBatchStatus {
        if (parts.isEmpty()) return EmailBackupBatchStatus.FAILED
        val confirmed = parts.count { it == EmailBackupPartStatus.CONFIRMED_SENT }
        return when {
            confirmed == parts.size -> EmailBackupBatchStatus.CONFIRMED_SENT
            confirmed > 0 -> EmailBackupBatchStatus.PARTIALLY_CONFIRMED
            parts.any { it == EmailBackupPartStatus.GMAIL_OPENED } ->
                EmailBackupBatchStatus.GMAIL_OPENED
            parts.all { it == EmailBackupPartStatus.USER_REPORTED_NOT_SENT } ->
                EmailBackupBatchStatus.USER_REPORTED_NOT_SENT
            parts.all { it == EmailBackupPartStatus.FAILED } ->
                EmailBackupBatchStatus.FAILED
            else -> EmailBackupBatchStatus.GENERATED
        }
    }
}

data class RedactionOptions(
    val redactOtp: Boolean = true,
    val redactLongNumbers: Boolean = true,
    val includeOriginalBodyInJson: Boolean = false
)

data class EmailBackupOptions(
    val recipient: String = "",
    val range: EmailBackupRange = EmailBackupRange.UNBACKED,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
    val includedCategories: Set<SmsCategory> = setOf(
        SmsCategory.PERSON,
        SmsCategory.NOTICE,
        SmsCategory.DELIVERY,
        SmsCategory.UNKNOWN
    ),
    val redaction: RedactionOptions = RedactionOptions(),
    val maxMessagesPerPart: Int = 300,
    val maxBodyBytesPerPart: Int = 200 * 1024
)

data class ResolvedBackupRange(
    val startInclusive: Long,
    val endInclusive: Long,
    val unbackedOnly: Boolean = false
)

object EmailBackupRangeCalculator {
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    fun resolve(
        type: EmailBackupRange,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        customStart: LocalDate? = null,
        customEnd: LocalDate? = null
    ): ResolvedBackupRange {
        val nowMillis = now.toEpochMilli()
        return when (type) {
            EmailBackupRange.UNBACKED ->
                ResolvedBackupRange(0L, nowMillis, unbackedOnly = true)
            EmailBackupRange.LAST_7_DAYS ->
                ResolvedBackupRange(nowMillis - 7 * DAY_MILLIS, nowMillis)
            EmailBackupRange.LAST_30_DAYS ->
                ResolvedBackupRange(nowMillis - 30 * DAY_MILLIS, nowMillis)
            EmailBackupRange.THIS_MONTH -> {
                val zoned = now.atZone(zoneId)
                val start = zoned.toLocalDate().withDayOfMonth(1)
                    .atStartOfDay(zoneId).toInstant().toEpochMilli()
                ResolvedBackupRange(start, nowMillis)
            }
            EmailBackupRange.CUSTOM -> {
                require(customStart != null && customEnd != null) {
                    "Custom range requires both dates"
                }
                require(!customStart.isAfter(customEnd)) {
                    "Start date cannot be after end date"
                }
                require(customStart.plusYears(5).isAfter(customEnd) ||
                    customStart.plusYears(5).isEqual(customEnd)) {
                    "Custom range cannot exceed five years"
                }
                val start = customStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = customEnd.plusDays(1).atStartOfDay(zoneId)
                    .toInstant().toEpochMilli() - 1
                ResolvedBackupRange(start, minOf(end, nowMillis))
            }
        }
    }
}

data class RedactionResult(
    val body: String,
    val otpRedacted: Boolean,
    val longNumberRedacted: Boolean
)

data class PreparedBackupMessage(
    val sms: SmsMessageModel,
    val fingerprint: String,
    val redactedBody: String
)

data class BackupPartPreview(
    val partNumber: Int,
    val messages: List<PreparedBackupMessage>,
    val bodySizeBytes: Int
)

data class EmailBackupPreview(
    val options: EmailBackupOptions,
    val resolvedRange: ResolvedBackupRange,
    val messages: List<PreparedBackupMessage>,
    val parts: List<BackupPartPreview>,
    val categoryCounts: Map<SmsCategory, Int>,
    val estimatedAttachmentBytes: Long,
    val firstSubject: String
)

data class GeneratedBackupPart(
    val partId: String,
    val partNumber: Int,
    val partCount: Int,
    val subject: String,
    val bodyFileName: String,
    val attachmentFileName: String,
    val messageCount: Int
)

data class GeneratedEmailBackup(
    val backupId: String,
    val parts: List<GeneratedBackupPart>
)

fun Long.toZonedDateTime(zoneId: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
    Instant.ofEpochMilli(this).atZone(zoneId)
