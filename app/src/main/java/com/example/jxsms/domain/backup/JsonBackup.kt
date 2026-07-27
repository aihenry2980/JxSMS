package com.example.jxsms.domain.backup

import android.os.Build
import com.example.jxsms.domain.model.SmsCategory
import com.example.jxsms.util.PhoneNumberNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class JsonBackupDocument(
    val format: String = "jx-sms-backup",
    val formatVersion: Int = 1,
    val backupId: String,
    val partNumber: Int,
    val partCount: Int,
    val createdAt: String,
    val device: JsonDeviceInfo,
    val range: JsonRange,
    val options: JsonBackupOptions,
    val messages: List<JsonBackupMessage>
)

@Serializable
data class JsonDeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String
)

@Serializable
data class JsonRange(val start: String, val end: String)

@Serializable
data class JsonBackupOptions(
    val includedCategories: List<String>,
    val otpRedacted: Boolean,
    val longNumbersRedacted: Boolean,
    val originalBodyIncluded: Boolean
)

@Serializable
data class JsonBackupMessage(
    val fingerprint: String,
    val deviceSmsId: Long?,
    val sender: String,
    val normalizedSender: String,
    val contactName: String?,
    val category: String,
    val body: String,
    val originalBody: String? = null,
    val receivedAt: String,
    val dateSent: String?,
    val subscriptionId: Int?,
    val read: Boolean,
    val seen: Boolean
)

class JsonBackupExporter(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    },
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val deviceInfo: () -> JsonDeviceInfo = {
        JsonDeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE
        )
    }
) {
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun export(
        backupId: String,
        partNumber: Int,
        partCount: Int,
        createdAt: Long,
        range: ResolvedBackupRange,
        options: EmailBackupOptions,
        messages: List<PreparedBackupMessage>
    ): String {
        val document = JsonBackupDocument(
            backupId = backupId,
            partNumber = partNumber,
            partCount = partCount,
            createdAt = createdAt.toZonedDateTime(zoneId).format(iso),
            device = deviceInfo(),
            range = JsonRange(
                range.startInclusive.toZonedDateTime(zoneId).format(iso),
                range.endInclusive.toZonedDateTime(zoneId).format(iso)
            ),
            options = JsonBackupOptions(
                includedCategories = options.includedCategories.map(SmsCategory::name).sorted(),
                otpRedacted = options.redaction.redactOtp,
                longNumbersRedacted = options.redaction.redactLongNumbers,
                originalBodyIncluded = options.redaction.includeOriginalBodyInJson
            ),
            messages = messages.map { message ->
                JsonBackupMessage(
                    fingerprint = message.fingerprint,
                    deviceSmsId = message.sms.id,
                    sender = message.sms.address,
                    normalizedSender = PhoneNumberNormalizer.normalize(message.sms.address),
                    contactName = message.sms.contactName,
                    category = message.sms.category.name,
                    body = message.redactedBody,
                    originalBody = message.sms.body.takeIf {
                        options.redaction.includeOriginalBodyInJson
                    },
                    receivedAt = message.sms.date.toZonedDateTime(zoneId).format(iso),
                    dateSent = message.sms.dateSent?.toZonedDateTime(zoneId)?.format(iso),
                    subscriptionId = message.sms.subscriptionId,
                    read = message.sms.read,
                    seen = message.sms.seen
                )
            }
        )
        return json.encodeToString(document)
    }

    fun decode(value: String): JsonBackupDocument = json.decodeFromString(value)
}
