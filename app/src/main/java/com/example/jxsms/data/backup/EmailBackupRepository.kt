package com.example.jxsms.data.backup

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.jxsms.R
import com.example.jxsms.data.preferences.SwipePreferencesRepository
import com.example.jxsms.data.sms.SmsRepository
import com.example.jxsms.domain.backup.BackupPartPreview
import com.example.jxsms.domain.backup.DefaultSmsBackupRedactor
import com.example.jxsms.domain.backup.EmailAddressValidator
import com.example.jxsms.domain.backup.EmailBackupBatchStatus
import com.example.jxsms.domain.backup.EmailBackupChunker
import com.example.jxsms.domain.backup.EmailBackupOptions
import com.example.jxsms.domain.backup.EmailBackupPartStatus
import com.example.jxsms.domain.backup.EmailBackupPreview
import com.example.jxsms.domain.backup.EmailBackupRange
import com.example.jxsms.domain.backup.EmailBackupRangeCalculator
import com.example.jxsms.domain.backup.EmailBackupStatusResolver
import com.example.jxsms.domain.backup.GeneratedBackupPart
import com.example.jxsms.domain.backup.GeneratedEmailBackup
import com.example.jxsms.domain.backup.JsonBackupExporter
import com.example.jxsms.domain.backup.PreparedBackupMessage
import com.example.jxsms.domain.backup.RedactionOptions
import com.example.jxsms.domain.backup.ResolvedBackupRange
import com.example.jxsms.domain.backup.SmsBackupFormatter
import com.example.jxsms.domain.backup.SmsFingerprintGenerator
import com.example.jxsms.domain.backup.toZonedDateTime
import com.example.jxsms.domain.model.SmsCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

class EmailBackupException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

data class EmailPartOpenData(
    val batch: EmailBackupBatchEntity,
    val part: EmailBackupPartEntity,
    val body: String,
    val attachment: File
)

@Serializable
private data class PersistedBackupSettings(
    val range: String,
    val includedCategories: List<String>,
    val redactOtp: Boolean,
    val redactLongNumbers: Boolean,
    val includeOriginalBody: Boolean,
    val maxMessages: Int,
    val maxBodyBytes: Int
)

class EmailBackupRepository(
    private val context: Context,
    private val dao: EmailBackupDao,
    private val smsRepository: SmsRepository,
    private val smsPreferences: SwipePreferencesRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val redactor: DefaultSmsBackupRedactor = DefaultSmsBackupRedactor(),
    private val fingerprintGenerator: SmsFingerprintGenerator = SmsFingerprintGenerator(),
    private val formatter: SmsBackupFormatter = SmsBackupFormatter(),
    private val chunker: EmailBackupChunker = EmailBackupChunker(),
    private val exporter: JsonBackupExporter = JsonBackupExporter()
) {
    private val json = Json { encodeDefaults = true }
    private val exportDirectory get() = File(context.cacheDir, "backup_exports")

    val batches: Flow<List<EmailBackupBatchEntity>> = dao.observeBatches()
    val confirmedMessageCount: Flow<Int> = dao.observeConfirmedMessageCount()
    val latestConfirmedAt: Flow<Long?> = dao.observeLatestConfirmedAt()

    fun parts(backupId: String): Flow<List<EmailBackupPartEntity>> = dao.observeParts(backupId)
    suspend fun partList(backupId: String): List<EmailBackupPartEntity> = dao.parts(backupId)
    suspend fun part(partId: String): EmailBackupPartEntity? = dao.part(partId)
    suspend fun batch(backupId: String): EmailBackupBatchEntity? = dao.batch(backupId)

    suspend fun preview(
        requestedOptions: EmailBackupOptions,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): EmailBackupPreview = withContext(io) {
        val recipient = EmailAddressValidator.normalizeAndValidate(requestedOptions.recipient)
            ?: throw EmailBackupException("invalid_recipient")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw EmailBackupException("sms_permission_missing")
        }
        val options = requestedOptions.copy(recipient = recipient)
        val requestedRange = EmailBackupRangeCalculator.resolve(
            options.range,
            Instant.ofEpochMilli(now()),
            zoneId,
            options.customStart,
            options.customEnd
        )
        val confirmed = if (requestedRange.unbackedOnly) {
            dao.confirmedFingerprints().toHashSet()
        } else {
            emptySet()
        }
        val overrides = smsPreferences.preferences.first().categoryOverrides
        val source = try {
            smsRepository.inboxSnapshot()
        } catch (error: Exception) {
            throw EmailBackupException("sms_query_failed", error)
        }
        val prepared = ArrayList<PreparedBackupMessage>()
        source.sortedBy { it.date }.forEachIndexed { index, original ->
            val message = overrides[original.id]?.let { original.copy(category = it) } ?: original
            if (message.date in requestedRange.startInclusive..requestedRange.endInclusive &&
                message.category in options.includedCategories
            ) {
                val fingerprint = fingerprintGenerator.generate(message)
                if (fingerprint !in confirmed) {
                    val redacted = redactor.redact(message.body, message.category, options.redaction)
                    prepared += PreparedBackupMessage(message, fingerprint, redacted.body)
                }
            }
            if (index % 25 == 0 || index == source.lastIndex) onProgress(index + 1, source.size)
        }
        if (prepared.isEmpty()) throw EmailBackupException("no_matching_messages")
        val effectiveRange = if (requestedRange.unbackedOnly) {
            ResolvedBackupRange(
                prepared.minOf { it.sms.date },
                prepared.maxOf { it.sms.date },
                unbackedOnly = true
            )
        } else {
            requestedRange
        }
        val blocks = prepared.map {
            formatter.messageBlock(it, categoryLabel(it.sms.category))
        }
        val parts = chunker.chunk(
            prepared,
            blocks,
            options.maxMessagesPerPart,
            options.maxBodyBytesPerPart
        )
        val firstSubject = formatter.subject(effectiveRange, prepared.size, 1, parts.size)
        val attachmentEstimate = parts.sumOf { part ->
            exporter.export(
                "JX-PREVIEW",
                part.partNumber,
                parts.size,
                now(),
                effectiveRange,
                options,
                part.messages
            ).toByteArray(StandardCharsets.UTF_8).size.toLong()
        }
        EmailBackupPreview(
            options = options,
            resolvedRange = effectiveRange,
            messages = prepared,
            parts = parts,
            categoryCounts = prepared.groupingBy { it.sms.category }.eachCount(),
            estimatedAttachmentBytes = attachmentEstimate,
            firstSubject = firstSubject
        )
    }

    suspend fun contentHash(preview: EmailBackupPreview): String = withContext(io) {
        val canonical = buildString {
            append(preview.options.recipient).append('\n')
            append(preview.resolvedRange.startInclusive).append(':')
                .append(preview.resolvedRange.endInclusive).append('\n')
            append(preview.options.includedCategories.map(SmsCategory::name).sorted().joinToString(","))
            append('\n').append(preview.options.redaction).append('\n')
            preview.messages.forEach { append(it.fingerprint).append('\n') }
        }
        sha256(canonical)
    }

    suspend fun duplicates(preview: EmailBackupPreview): List<EmailBackupBatchEntity> =
        dao.byContentHash(contentHash(preview))

    suspend fun generate(
        preview: EmailBackupPreview,
        onProgress: (part: Int, total: Int) -> Unit = { _, _ -> }
    ): GeneratedEmailBackup = withContext(io) {
        cleanupOldExports()
        val createdAt = now()
        val backupId = createBackupId(createdAt)
        val directory = File(exportDirectory, backupId).apply {
            if (!exists() && !mkdirs()) throw EmailBackupException("attachment_write_failed")
        }
        val settingsJson = json.encodeToString(
            PersistedBackupSettings(
                preview.options.range.name,
                preview.options.includedCategories.map(SmsCategory::name).sorted(),
                preview.options.redaction.redactOtp,
                preview.options.redaction.redactLongNumbers,
                preview.options.redaction.includeOriginalBodyInJson,
                preview.options.maxMessagesPerPart,
                preview.options.maxBodyBytesPerPart
            )
        )
        val dateName = DateTimeFormatter.ofPattern("yyyyMMdd")
        val startName = preview.resolvedRange.startInclusive.toZonedDateTime(zoneId).format(dateName)
        val endName = preview.resolvedRange.endInclusive.toZonedDateTime(zoneId).format(dateName)
        val generatedFiles = mutableListOf<File>()
        try {
            val partEntities = mutableListOf<EmailBackupPartEntity>()
            val refs = mutableListOf<EmailBackupMessageRefEntity>()
            val generatedParts = preview.parts.map { part ->
                val partId = "$backupId-P${part.partNumber.toString().padStart(2, '0')}"
                val subject = formatter.subject(
                    preview.resolvedRange,
                    preview.messages.size,
                    part.partNumber,
                    preview.parts.size
                )
                val blocks = part.messages.map {
                    formatter.messageBlock(it, categoryLabel(it.sms.category))
                }
                val body = formatter.body(
                    backupId,
                    createdAt,
                    preview.resolvedRange,
                    preview.options,
                    part.partNumber,
                    preview.parts.size,
                    preview.messages.size,
                    blocks,
                    preview.options.includedCategories.sortedBy(SmsCategory::ordinal)
                        .map(::categoryLabel),
                    "${Build.MANUFACTURER} ${Build.MODEL}"
                )
                val paddedPart = part.partNumber.toString().padStart(2, '0')
                val paddedCount = preview.parts.size.toString().padStart(2, '0')
                val attachmentBaseName =
                    "jx_sms_backup_${startName}_${endName}_part_${paddedPart}_of_${paddedCount}.json"
                val bodyBaseName = "${partId}_body.txt"
                val attachmentFile = File(directory, attachmentBaseName)
                val bodyFile = File(directory, bodyBaseName)
                val attachmentName = "$backupId/$attachmentBaseName"
                val bodyName = "$backupId/$bodyBaseName"
                bodyFile.outputStream().buffered().use {
                    it.write(body.toByteArray(StandardCharsets.UTF_8))
                }
                generatedFiles += bodyFile
                val jsonBody = exporter.export(
                    backupId,
                    part.partNumber,
                    preview.parts.size,
                    createdAt,
                    preview.resolvedRange,
                    preview.options,
                    part.messages
                )
                attachmentFile.outputStream().buffered().use {
                    it.write(jsonBody.toByteArray(StandardCharsets.UTF_8))
                }
                generatedFiles += attachmentFile
                partEntities += EmailBackupPartEntity(
                    partId,
                    backupId,
                    part.partNumber,
                    part.messages.size,
                    subject,
                    bodyName,
                    attachmentName,
                    bodyFile.length(),
                    attachmentFile.length(),
                    EmailBackupPartStatus.GENERATED,
                    null
                )
                refs += part.messages.map { message ->
                    EmailBackupMessageRefEntity(
                        backupId,
                        partId,
                        message.fingerprint,
                        message.sms.id,
                        message.sms.date
                    )
                }
                onProgress(part.partNumber, preview.parts.size)
                GeneratedBackupPart(
                    partId,
                    part.partNumber,
                    preview.parts.size,
                    subject,
                    bodyName,
                    attachmentName,
                    part.messages.size
                )
            }
            val batch = EmailBackupBatchEntity(
                backupId,
                preview.options.recipient,
                preview.resolvedRange.startInclusive,
                preview.resolvedRange.endInclusive,
                createdAt,
                preview.messages.size,
                preview.parts.size,
                settingsJson,
                contentHash(preview),
                EmailBackupBatchStatus.GENERATED
            )
            dao.insertBackup(batch, partEntities, refs)
            GeneratedEmailBackup(backupId, generatedParts)
        } catch (cancelled: CancellationException) {
            generatedFiles.forEach { it.delete() }
            directory.delete()
            throw cancelled
        } catch (error: Exception) {
            generatedFiles.forEach { it.delete() }
            directory.delete()
            if (error is EmailBackupException) throw error
            throw EmailBackupException("attachment_write_failed", error)
        }
    }

    suspend fun openData(partId: String): EmailPartOpenData = withContext(io) {
        val part = dao.part(partId) ?: throw EmailBackupException("part_missing")
        val batch = dao.batch(part.backupId) ?: throw EmailBackupException("batch_missing")
        val bodyFile = File(exportDirectory, part.bodyFileName)
        val attachment = File(exportDirectory, part.attachmentFileName)
        if (!bodyFile.isFile || !attachment.isFile) {
            throw EmailBackupException("export_files_missing")
        }
        EmailPartOpenData(batch, part, bodyFile.readText(StandardCharsets.UTF_8), attachment)
    }

    suspend fun markOpened(partId: String) = withContext(io) {
        val part = dao.part(partId) ?: return@withContext
        if (part.status == EmailBackupPartStatus.CONFIRMED_SENT) return@withContext
        dao.updatePartStatus(partId, EmailBackupPartStatus.GMAIL_OPENED, null)
        val parts = dao.parts(part.backupId)
        val batchStatus = EmailBackupStatusResolver.batchStatus(parts.map { it.status })
        dao.updateBatchStatus(part.backupId, batchStatus)
    }

    suspend fun confirmSent(partId: String) = updatePartAndBatch(
        partId,
        EmailBackupPartStatus.CONFIRMED_SENT
    )

    suspend fun reportNotSent(partId: String) = updatePartAndBatch(
        partId,
        EmailBackupPartStatus.USER_REPORTED_NOT_SENT
    )

    private suspend fun updatePartAndBatch(
        partId: String,
        status: EmailBackupPartStatus
    ) = withContext(io) {
        val part = dao.part(partId) ?: return@withContext
        dao.updatePartStatus(
            partId,
            status,
            now().takeIf { status == EmailBackupPartStatus.CONFIRMED_SENT }
        )
        val parts = dao.parts(part.backupId)
        val batchStatus = EmailBackupStatusResolver.batchStatus(parts.map { it.status })
        dao.updateBatchStatus(part.backupId, batchStatus)
    }

    suspend fun markBatchSent(backupId: String) = withContext(io) {
        dao.parts(backupId).forEach {
            dao.updatePartStatus(it.partId, EmailBackupPartStatus.CONFIRMED_SENT, now())
        }
        dao.updateBatchStatus(backupId, EmailBackupBatchStatus.CONFIRMED_SENT)
    }

    suspend fun markBatchNotSent(backupId: String) = withContext(io) {
        dao.parts(backupId).filter {
            it.status != EmailBackupPartStatus.CONFIRMED_SENT
        }.forEach {
            dao.updatePartStatus(it.partId, EmailBackupPartStatus.USER_REPORTED_NOT_SENT, null)
        }
        val parts = dao.parts(backupId)
        dao.updateBatchStatus(backupId, EmailBackupStatusResolver.batchStatus(parts.map { it.status }))
    }

    suspend fun deleteRecord(backupId: String) = withContext(io) {
        val parts = dao.parts(backupId)
        dao.deleteBatch(backupId)
        parts.forEach {
            File(exportDirectory, it.bodyFileName).delete()
            File(exportDirectory, it.attachmentFileName).delete()
        }
    }

    suspend fun cleanupOldExports() = withContext(io) {
        val active = dao.activeExportFileNames().toHashSet()
        val cutoff = now() - 7L * 24 * 60 * 60 * 1000
        exportDirectory.walkBottomUp().forEach { file ->
            if (file == exportDirectory) return@forEach
            if (file.isFile) {
                val relativeName = file.relativeTo(exportDirectory).invariantSeparatorsPath
                if (relativeName !in active && file.lastModified() < cutoff) file.delete()
            } else if (file.listFiles().isNullOrEmpty()) {
                file.delete()
            }
        }
    }

    suspend fun optionsForBatch(batch: EmailBackupBatchEntity): EmailBackupOptions = withContext(io) {
        val settings = json.decodeFromString<PersistedBackupSettings>(batch.settingsJson)
        val range = EmailBackupRange.valueOf(settings.range)
        EmailBackupOptions(
            recipient = batch.recipient,
            range = range,
            customStart = batch.rangeStart.toZonedDateTime(zoneId).toLocalDate()
                .takeIf { range == EmailBackupRange.CUSTOM },
            customEnd = batch.rangeEnd.toZonedDateTime(zoneId).toLocalDate()
                .takeIf { range == EmailBackupRange.CUSTOM },
            includedCategories = settings.includedCategories.mapTo(mutableSetOf()) {
                SmsCategory.valueOf(it)
            },
            redaction = RedactionOptions(
                settings.redactOtp,
                settings.redactLongNumbers,
                settings.includeOriginalBody
            ),
            maxMessagesPerPart = settings.maxMessages,
            maxBodyBytesPerPart = settings.maxBodyBytes
        )
    }

    private fun createBackupId(time: Long): String {
        val stamp = time.toZonedDateTime(zoneId)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val suffix = Random.nextInt(0x10000).toString(16).uppercase(Locale.US).padStart(4, '0')
        return "JX-$stamp-$suffix"
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it) }

    private fun categoryLabel(category: SmsCategory): String = context.getString(when (category) {
        SmsCategory.OTP -> R.string.category_otp
        SmsCategory.ADVERTISEMENT -> R.string.category_ad
        SmsCategory.DELIVERY -> R.string.category_delivery
        SmsCategory.PERSON -> R.string.category_person
        SmsCategory.NOTICE -> R.string.category_notice
        SmsCategory.UNKNOWN -> R.string.category_other
    })
}
