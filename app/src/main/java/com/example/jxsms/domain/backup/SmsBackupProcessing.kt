package com.example.jxsms.domain.backup

import com.example.jxsms.data.sms.SmsMessageModel
import com.example.jxsms.domain.model.SmsCategory
import com.example.jxsms.util.PhoneNumberNormalizer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object EmailAddressValidator {
    private val pattern = Regex(
        "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$",
        RegexOption.IGNORE_CASE
    )
    fun normalizeAndValidate(value: String): String? =
        value.trim().takeIf { it.length <= 254 && pattern.matches(it) }
}

class SmsFingerprintGenerator {
    fun generate(sms: SmsMessageModel): String {
        val normalized = PhoneNumberNormalizer.normalize(sms.address)
        val canonical = buildString {
            append(normalized)
            append('\u001f')
            append(sms.date)
            append('\u001f')
            append(sms.body)
            append('\u001f')
            append(sms.subscriptionId ?: -1)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it) }
    }
}

interface SmsBackupRedactor {
    fun redact(
        body: String,
        category: SmsCategory,
        options: RedactionOptions
    ): RedactionResult
}

class DefaultSmsBackupRedactor : SmsBackupRedactor {
    private val otpKeyword = Regex(
        "(验证码|認證碼|认证码|动态码|安全码|登录码|校验码|一次性密码|" +
            "인증\\s*번호|인증\\s*코드|보안\\s*코드|일회용\\s*비밀번호|" +
            "verification\\s*code|authentication\\s*code|security\\s*code|" +
            "login\\s*code|one[- ]time\\s*password|\\botp\\b)",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val shortCode = Regex("(?<![A-Z0-9])[A-Z0-9]{4,8}(?![A-Z0-9])",
        RegexOption.IGNORE_CASE)
    private val longNumber = Regex("(?<!\\d)(?:\\d[ -]?){11,18}\\d(?!\\d)")

    override fun redact(
        body: String,
        category: SmsCategory,
        options: RedactionOptions
    ): RedactionResult {
        var value = body
        var otpChanged = false
        var longChanged = false
        if (options.redactOtp) {
            val keywordRanges = otpKeyword.findAll(value).map { it.range }.toList()
            if (keywordRanges.isNotEmpty() || category == SmsCategory.OTP) {
                value = shortCode.replace(value) { match ->
                    val nearKeyword = category == SmsCategory.OTP || keywordRanges.any { range ->
                        match.range.first <= range.last + 48 && match.range.last >= range.first - 48
                    }
                    val token = match.value
                    val looksLikeDate = token.matches(Regex("(19|20)\\d{2}")) ||
                        token.matches(Regex("(19|20)\\d{6}"))
                    if (nearKeyword && token.any(Char::isDigit) && !looksLikeDate) {
                        otpChanged = true
                        "•".repeat(token.length)
                    } else token
                }
            }
        }
        if (options.redactLongNumbers) {
            value = longNumber.replace(value) { match ->
                val digitCount = match.value.count(Char::isDigit)
                if (digitCount < 12) return@replace match.value
                var remainingVisible = 4
                val chars = match.value.toCharArray()
                for (index in chars.indices.reversed()) {
                    if (!chars[index].isDigit()) continue
                    if (remainingVisible > 0) remainingVisible--
                    else chars[index] = '•'
                }
                longChanged = true
                String(chars)
            }
        }
        return RedactionResult(value, otpChanged, longChanged)
    }
}

class SmsBackupFormatter(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun messageBlock(message: PreparedBackupMessage, categoryLabel: String): String = buildString {
        append(message.sms.date.toZonedDateTime(zoneId).format(dateTime)).append('\n')
        append('[').append(categoryLabel).append("]\n")
        message.sms.contactName?.takeIf { it.isNotBlank() }?.let {
            append("Contact: ").append(it).append('\n')
        }
        append("Sender: ").append(message.sms.address).append('\n')
        message.sms.subscriptionId?.let { append("SIM: ").append(it).append('\n') }
        append('\n').append(message.redactedBody).append('\n')
        append("\n------------------------------------------------------------\n")
    }

    fun body(
        backupId: String,
        createdAt: Long,
        range: ResolvedBackupRange,
        options: EmailBackupOptions,
        partNumber: Int,
        partCount: Int,
        totalMessages: Int,
        messageBlocks: List<String>,
        categoryLabels: List<String>,
        deviceName: String
    ): String = buildString {
        append("JX SMS BACKUP\n\n")
        append("Backup ID: ").append(backupId).append('\n')
        append("Device: ").append(deviceName).append('\n')
        append("Created: ").append(createdAt.toZonedDateTime(zoneId).format(dateTime)).append(' ')
            .append(createdAt.toZonedDateTime(zoneId).offset).append('\n')
        append("Range: ").append(range.startInclusive.toZonedDateTime(zoneId).format(dateTime))
            .append(" ~ ").append(range.endInclusive.toZonedDateTime(zoneId).format(dateTime)).append('\n')
        append("Messages: ").append(totalMessages).append('\n')
        append("Part: ").append(partNumber).append(" / ").append(partCount).append('\n')
        append("Categories: ").append(categoryLabels.joinToString(", ")).append('\n')
        val redactions = buildList {
            if (options.redaction.redactOtp) add("OTP codes")
            if (options.redaction.redactLongNumbers) add("long account numbers")
        }
        append("Redaction: ").append(if (redactions.isEmpty()) "none" else redactions.joinToString(", "))
            .append("\n\n============================================================\n\n")
        messageBlocks.forEach(::append)
    }

    fun subject(
        range: ResolvedBackupRange,
        totalMessages: Int,
        partNumber: Int,
        partCount: Int
    ): String {
        val start = range.startInclusive.toZonedDateTime(zoneId).format(day)
        val end = range.endInclusive.toZonedDateTime(zoneId).format(day)
        return buildString {
            append("[JX SMS Backup] ").append(start).append('–').append(end)
                .append(" · ").append(totalMessages).append(" SMS")
            if (partCount > 1) append(" · ").append(partNumber).append('/').append(partCount)
        }
    }
}

class EmailBackupChunker {
    fun chunk(
        messages: List<PreparedBackupMessage>,
        renderedBlocks: List<String>,
        maxMessages: Int,
        maxBodyBytes: Int,
        headerReserveBytes: Int = 4096
    ): List<BackupPartPreview> {
        require(maxMessages > 0 && maxBodyBytes > 0)
        if (messages.isEmpty()) return emptyList()
        val parts = mutableListOf<BackupPartPreview>()
        var currentMessages = mutableListOf<PreparedBackupMessage>()
        var currentBytes = headerReserveBytes
        messages.zip(renderedBlocks).forEach { (message, block) ->
            val blockBytes = block.toByteArray(StandardCharsets.UTF_8).size
            val mustSplit = currentMessages.isNotEmpty() &&
                (currentMessages.size >= maxMessages || currentBytes + blockBytes > maxBodyBytes)
            if (mustSplit) {
                parts += BackupPartPreview(parts.size + 1, currentMessages, currentBytes)
                currentMessages = mutableListOf()
                currentBytes = headerReserveBytes
            }
            currentMessages += message
            currentBytes += blockBytes
        }
        if (currentMessages.isNotEmpty()) {
            parts += BackupPartPreview(parts.size + 1, currentMessages, currentBytes)
        }
        return parts
    }
}
