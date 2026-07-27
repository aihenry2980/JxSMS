package com.example.jxsms

import com.example.jxsms.data.sms.SmsMessageModel
import com.example.jxsms.domain.backup.DefaultSmsBackupRedactor
import com.example.jxsms.domain.backup.EmailBackupBatchStatus
import com.example.jxsms.domain.backup.EmailBackupChunker
import com.example.jxsms.domain.backup.EmailAddressValidator
import com.example.jxsms.domain.backup.EmailBackupOptions
import com.example.jxsms.domain.backup.EmailBackupPartStatus
import com.example.jxsms.domain.backup.EmailBackupRange
import com.example.jxsms.domain.backup.EmailBackupRangeCalculator
import com.example.jxsms.domain.backup.EmailBackupStatusResolver
import com.example.jxsms.domain.backup.JsonBackupExporter
import com.example.jxsms.domain.backup.JsonDeviceInfo
import com.example.jxsms.domain.backup.PreparedBackupMessage
import com.example.jxsms.domain.backup.RedactionOptions
import com.example.jxsms.domain.backup.ResolvedBackupRange
import com.example.jxsms.domain.backup.SmsBackupFormatter
import com.example.jxsms.domain.backup.SmsFingerprintGenerator
import com.example.jxsms.domain.model.SmsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EmailBackupDomainTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = Instant.parse("2026-07-27T11:30:00Z")

    @Test
    fun rangesRespectSevenThirtyMonthAndInclusiveCustomEnd() {
        val seven = EmailBackupRangeCalculator.resolve(EmailBackupRange.LAST_7_DAYS, now, zone)
        val thirty = EmailBackupRangeCalculator.resolve(EmailBackupRange.LAST_30_DAYS, now, zone)
        val month = EmailBackupRangeCalculator.resolve(EmailBackupRange.THIS_MONTH, now, zone)
        val custom = EmailBackupRangeCalculator.resolve(
            EmailBackupRange.CUSTOM,
            now,
            zone,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 2)
        )
        assertEquals(7L * 24 * 60 * 60 * 1000, now.toEpochMilli() - seven.startInclusive)
        assertEquals(30L * 24 * 60 * 60 * 1000, now.toEpochMilli() - thirty.startInclusive)
        assertEquals(
            LocalDate.of(2026, 7, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            month.startInclusive
        )
        assertEquals(
            LocalDate.of(2026, 7, 3).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
            custom.endInclusive
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun customRangeRejectsReversedDates() {
        EmailBackupRangeCalculator.resolve(
            EmailBackupRange.CUSTOM,
            now,
            zone,
            LocalDate.of(2026, 7, 3),
            LocalDate.of(2026, 7, 1)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun customRangeRejectsMoreThanFiveYears() {
        EmailBackupRangeCalculator.resolve(
            EmailBackupRange.CUSTOM,
            now,
            zone,
            LocalDate.of(2020, 1, 1),
            LocalDate.of(2025, 1, 2)
        )
    }

    @Test
    fun rangeCalculationSurvivesDstBoundary() {
        val newYork = ZoneId.of("America/New_York")
        val resolved = EmailBackupRangeCalculator.resolve(
            EmailBackupRange.CUSTOM,
            Instant.parse("2026-03-10T12:00:00Z"),
            newYork,
            LocalDate.of(2026, 3, 7),
            LocalDate.of(2026, 3, 9)
        )
        assertTrue(resolved.endInclusive > resolved.startInclusive)
    }

    @Test
    fun emailValidationTrimsAndAcceptsPlusTags() {
        assertEquals(
            "backup+phone@example.co.kr",
            EmailAddressValidator.normalizeAndValidate("  backup+phone@example.co.kr  ")
        )
        assertEquals(null, EmailAddressValidator.normalizeAndValidate("not-an-email"))
    }

    @Test
    fun fingerprintIsStableAndDistinguishesBodyTimeAndSim() {
        val generator = SmsFingerprintGenerator()
        val base = sms(address = "+82 10-1234-5678", body = "hello", date = 10, sim = 1)
        assertEquals(generator.generate(base), generator.generate(base.copy(address = "01012345678")))
        assertNotEquals(generator.generate(base), generator.generate(base.copy(body = "other")))
        assertNotEquals(generator.generate(base), generator.generate(base.copy(date = 11)))
        assertNotEquals(generator.generate(base), generator.generate(base.copy(subscriptionId = 2)))
    }

    @Test
    fun redactsChineseKoreanEnglishOtpAndPreservesDatesAmountsAndEmoji() {
        val redactor = DefaultSmsBackupRedactor()
        val options = RedactionOptions()
        assertEquals(
            "您的验证码是 ••••••",
            redactor.redact("您的验证码是 583921", SmsCategory.NOTICE, options).body
        )
        assertEquals(
            "인증번호는 ••••입니다.",
            redactor.redact("인증번호는 9274입니다.", SmsCategory.NOTICE, options).body
        )
        assertEquals(
            "Your verification code is ••••••••.",
            redactor.redact(
                "Your verification code is A58B921Q.",
                SmsCategory.NOTICE,
                options
            ).body
        )
        val ordinary = "2026-07-27 결제 5,000원 주문 583921 🚚"
        assertEquals(ordinary, redactor.redact(ordinary, SmsCategory.NOTICE, options).body)
    }

    @Test
    fun redactsLongNumbersKeepingLastFourAndSeparators() {
        val redactor = DefaultSmsBackupRedactor()
        val value = redactor.redact(
            "카드 1234-5678-9012-3456 / 계좌 1234 5678 9012",
            SmsCategory.NOTICE,
            RedactionOptions(redactOtp = false)
        ).body
        assertTrue(value.contains("••••-••••-••••-3456"))
        assertTrue(value.contains("•••• •••• 9012"))
    }

    @Test
    fun otpRedactionCoversFourSixEightDigitsAndCanBeDisabled() {
        val redactor = DefaultSmsBackupRedactor()
        listOf("1234", "123456", "12345678").forEach { code ->
            val result = redactor.redact(
                "Your verification code is $code.",
                SmsCategory.NOTICE,
                RedactionOptions()
            ).body
            assertFalse(result.contains(code))
            assertTrue(result.contains("•".repeat(code.length)))
        }
        val original = "Your verification code is 123456."
        assertEquals(
            original,
            redactor.redact(
                original,
                SmsCategory.NOTICE,
                RedactionOptions(redactOtp = false, redactLongNumbers = false)
            ).body
        )
    }

    @Test
    fun chunkerSplitsByCountAndBytesWithoutLoss() {
        val messages = (1..7).map {
            PreparedBackupMessage(sms(id = it.toLong(), body = "message-$it"), "$it", "message-$it")
        }
        val blocks = messages.map { it.redactedBody.repeat(20) }
        val parts = EmailBackupChunker().chunk(messages, blocks, 3, 100_000, 0)
        assertEquals(listOf(3, 3, 1), parts.map { it.messages.size })
        assertEquals(messages.map { it.fingerprint }, parts.flatMap { it.messages }.map { it.fingerprint })

        val byteParts = EmailBackupChunker().chunk(messages.take(2), listOf("中".repeat(100), "韩".repeat(100)), 300, 400, 0)
        assertEquals(2, byteParts.size)
    }

    @Test
    fun oversizedSingleMessageGetsItsOwnPartUntruncated() {
        val message = PreparedBackupMessage(sms(body = "x".repeat(1000)), "one", "x".repeat(1000))
        val parts = EmailBackupChunker().chunk(listOf(message), listOf(message.redactedBody), 300, 100, 0)
        assertEquals(1, parts.size)
        assertEquals(1000, parts.single().messages.single().redactedBody.length)
    }

    @Test
    fun chunkPartNumbersAndOrderAreStable() {
        val messages = (1..5).map {
            PreparedBackupMessage(sms(id = it.toLong(), body = "$it"), "$it", "$it")
        }
        val parts = EmailBackupChunker().chunk(messages, messages.map { it.redactedBody }, 2, 10_000, 0)
        assertEquals(listOf(1, 2, 3), parts.map { it.partNumber })
        assertEquals(listOf("1", "2", "3", "4", "5"), parts.flatMap { it.messages }.map { it.fingerprint })
    }

    @Test
    fun formatterKeepsUnicodeMultilineAndSubjectPrefix() {
        val formatter = SmsBackupFormatter(zone)
        val message = PreparedBackupMessage(
            sms(body = "中文\n한글 😀", date = now.toEpochMilli()),
            "fingerprint",
            "中文\n한글 😀"
        )
        val block = formatter.messageBlock(message, "通知")
        assertTrue(block.contains("中文\n한글 😀"))
        assertTrue(block.contains("Sender:"))
        val subject = formatter.subject(
            ResolvedBackupRange(now.minusSeconds(60).toEpochMilli(), now.toEpochMilli()),
            10,
            1,
            2
        )
        assertTrue(subject.startsWith("[JX SMS Backup]"))
        assertTrue(subject.endsWith("1/2"))
    }

    @Test
    fun jsonRoundTripsAndOriginalBodyIsOptIn() {
        val exporter = JsonBackupExporter(
            zoneId = zone,
            deviceInfo = { JsonDeviceInfo("Samsung", "Test", "16") }
        )
        val message = PreparedBackupMessage(
            sms(body = "secret \"line\"\n😀", date = now.toEpochMilli()),
            "abc",
            "•••••• \"line\"\n😀"
        )
        val base = EmailBackupOptions()
        val without = exporter.export(
            "JX-TEST",
            1,
            1,
            now.toEpochMilli(),
            ResolvedBackupRange(now.minusSeconds(10).toEpochMilli(), now.toEpochMilli()),
            base,
            listOf(message)
        )
        val decodedWithout = exporter.decode(without)
        assertEquals(1, decodedWithout.formatVersion)
        assertEquals("abc", decodedWithout.messages.single().fingerprint)
        assertEquals(null, decodedWithout.messages.single().originalBody)
        val withOriginal = exporter.export(
            "JX-TEST",
            1,
            1,
            now.toEpochMilli(),
            ResolvedBackupRange(now.minusSeconds(10).toEpochMilli(), now.toEpochMilli()),
            base.copy(redaction = base.redaction.copy(includeOriginalBodyInJson = true)),
            listOf(message)
        )
        assertEquals("secret \"line\"\n😀", exporter.decode(withOriginal).messages.single().originalBody)
    }

    @Test
    fun statusOnlyConfirmsWholeBatchAfterEveryPart() {
        assertEquals(
            EmailBackupBatchStatus.GMAIL_OPENED,
            EmailBackupStatusResolver.batchStatus(
                listOf(EmailBackupPartStatus.GMAIL_OPENED, EmailBackupPartStatus.GENERATED)
            )
        )
        assertEquals(
            EmailBackupBatchStatus.PARTIALLY_CONFIRMED,
            EmailBackupStatusResolver.batchStatus(
                listOf(EmailBackupPartStatus.CONFIRMED_SENT, EmailBackupPartStatus.GMAIL_OPENED)
            )
        )
        assertEquals(
            EmailBackupBatchStatus.CONFIRMED_SENT,
            EmailBackupStatusResolver.batchStatus(
                listOf(EmailBackupPartStatus.CONFIRMED_SENT, EmailBackupPartStatus.CONFIRMED_SENT)
            )
        )
        assertFalse(
            EmailBackupStatusResolver.batchStatus(
                listOf(EmailBackupPartStatus.GMAIL_OPENED)
            ) == EmailBackupBatchStatus.CONFIRMED_SENT
        )
        assertEquals(
            EmailBackupBatchStatus.USER_REPORTED_NOT_SENT,
            EmailBackupStatusResolver.batchStatus(
                listOf(
                    EmailBackupPartStatus.USER_REPORTED_NOT_SENT,
                    EmailBackupPartStatus.USER_REPORTED_NOT_SENT
                )
            )
        )
    }

    private fun sms(
        id: Long = 1,
        address: String = "1588-1255",
        body: String = "body",
        date: Long = 1,
        sim: Int? = 1
    ) = SmsMessageModel(
        id = id,
        address = address,
        body = body,
        date = date,
        dateSent = null,
        read = true,
        seen = true,
        type = 1,
        subscriptionId = sim,
        category = SmsCategory.NOTICE
    )
}
