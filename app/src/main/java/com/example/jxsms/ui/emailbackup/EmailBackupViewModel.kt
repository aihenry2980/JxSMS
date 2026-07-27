package com.example.jxsms.ui.emailbackup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.jxsms.JxSmsApplication
import com.example.jxsms.data.backup.EmailBackupBatchEntity
import com.example.jxsms.data.backup.EmailBackupPartEntity
import com.example.jxsms.domain.backup.EmailBackupOptions
import com.example.jxsms.domain.backup.EmailBackupPartStatus
import com.example.jxsms.domain.backup.EmailBackupPreview
import com.example.jxsms.domain.backup.EmailBackupRange
import com.example.jxsms.domain.backup.GeneratedEmailBackup
import com.example.jxsms.domain.backup.RedactionOptions
import com.example.jxsms.domain.model.SmsCategory
import com.example.jxsms.share.BackupEmailIntent
import com.example.jxsms.share.GmailBackupIntentFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class BackupProgress(
    val stage: Stage,
    val current: Int,
    val total: Int
) {
    enum class Stage { PREPARING, GENERATING }
}

data class BackupSummary(
    val latestConfirmedAt: Long? = null,
    val confirmedMessageCount: Int = 0
)

data class DuplicateBackupPrompt(
    val batch: EmailBackupBatchEntity,
    val confirmed: Boolean
)

class EmailBackupViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as JxSmsApplication).container
    private val repository = container.emailBackup
    private val preferences = container.emailBackupPreferences
    private val intentFactory = GmailBackupIntentFactory(application)

    private val _options = MutableStateFlow(EmailBackupOptions())
    val options: StateFlow<EmailBackupOptions> = _options.asStateFlow()
    private val _preview = MutableStateFlow<EmailBackupPreview?>(null)
    val preview: StateFlow<EmailBackupPreview?> = _preview.asStateFlow()
    private val _progress = MutableStateFlow<BackupProgress?>(null)
    val progress: StateFlow<BackupProgress?> = _progress.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _generated = MutableStateFlow<GeneratedEmailBackup?>(null)
    val generated: StateFlow<GeneratedEmailBackup?> = _generated.asStateFlow()
    private val _awaitingPart = MutableStateFlow<EmailBackupPartEntity?>(null)
    val awaitingPart: StateFlow<EmailBackupPartEntity?> = _awaitingPart.asStateFlow()
    private val _duplicate = MutableStateFlow<DuplicateBackupPrompt?>(null)
    val duplicate: StateFlow<DuplicateBackupPrompt?> = _duplicate.asStateFlow()
    private val _selectedHistoryParts = MutableStateFlow<List<EmailBackupPartEntity>>(emptyList())
    val selectedHistoryParts: StateFlow<List<EmailBackupPartEntity>> =
        _selectedHistoryParts.asStateFlow()
    private var work: Job? = null

    val batches: StateFlow<List<EmailBackupBatchEntity>> = repository.batches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val summary: StateFlow<BackupSummary> = combine(
        repository.latestConfirmedAt,
        repository.confirmedMessageCount
    ) { latest, count -> BackupSummary(latest, count) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupSummary())

    init {
        viewModelScope.launch { _options.value = preferences.preferences.first() }
    }

    fun updateRecipient(value: String) = update { copy(recipient = value) }
    fun updateRange(value: EmailBackupRange) = update { copy(range = value) }
    fun updateCustomRange(start: LocalDate, end: LocalDate) =
        update { copy(range = EmailBackupRange.CUSTOM, customStart = start, customEnd = end) }
    fun toggleCategory(category: SmsCategory) = update {
        copy(
            includedCategories = if (category in includedCategories) {
                includedCategories - category
            } else {
                includedCategories + category
            }
        )
    }
    fun updateRedaction(transform: RedactionOptions.() -> RedactionOptions) = update {
        copy(redaction = redaction.transform())
    }

    private fun update(transform: EmailBackupOptions.() -> EmailBackupOptions) {
        _options.value = _options.value.transform()
        _preview.value = null
    }

    fun clearError() { _error.value = null }
    fun clearDuplicate() { _duplicate.value = null }

    fun preview() {
        work?.cancel()
        work = viewModelScope.launch {
            _error.value = null
            _progress.value = BackupProgress(BackupProgress.Stage.PREPARING, 0, 0)
            try {
                preferences.save(_options.value.copy(recipient = _options.value.recipient.trim()))
                _options.value = _options.value.copy(recipient = _options.value.recipient.trim())
                _preview.value = repository.preview(_options.value) { current, total ->
                    _progress.value = BackupProgress(
                        BackupProgress.Stage.PREPARING,
                        current,
                        total
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = error.message ?: "preview_failed"
            } finally {
                _progress.value = null
            }
        }
    }

    fun requestGenerate() {
        val value = _preview.value ?: return
        work?.cancel()
        work = viewModelScope.launch {
            val duplicate = repository.duplicates(value).firstOrNull()
            if (duplicate != null) {
                _duplicate.value = DuplicateBackupPrompt(
                    duplicate,
                    duplicate.status.name == "CONFIRMED_SENT"
                )
            } else {
                generateNow(value)
            }
        }
    }

    fun generateAnyway() {
        _duplicate.value = null
        _preview.value?.let { preview ->
            work?.cancel()
            work = viewModelScope.launch { generateNow(preview) }
        }
    }

    fun continueDuplicate() {
        val batch = _duplicate.value?.batch ?: return
        _duplicate.value = null
        viewModelScope.launch {
            val parts = repository.partList(batch.backupId)
                .filter { it.status != EmailBackupPartStatus.CONFIRMED_SENT }
            _generated.value = GeneratedEmailBackup(
                batch.backupId,
                parts.map {
                    com.example.jxsms.domain.backup.GeneratedBackupPart(
                        it.partId,
                        it.partNumber,
                        batch.partCount,
                        it.subject,
                        it.bodyFileName,
                        it.attachmentFileName,
                        it.messageCount
                    )
                }
            )
        }
    }

    private suspend fun generateNow(value: EmailBackupPreview) {
        _error.value = null
        _progress.value = BackupProgress(BackupProgress.Stage.GENERATING, 0, value.parts.size)
        try {
            _generated.value = repository.generate(value) { current, total ->
                _progress.value = BackupProgress(
                    BackupProgress.Stage.GENERATING,
                    current,
                    total
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _error.value = error.message ?: "generation_failed"
        } finally {
            _progress.value = null
        }
    }

    fun cancelWork() {
        work?.cancel()
        _progress.value = null
    }

    suspend fun createIntent(partId: String): BackupEmailIntent =
        intentFactory.create(repository.openData(partId))

    fun markOpened(partId: String) {
        viewModelScope.launch {
            repository.markOpened(partId)
            _awaitingPart.value = repository.part(partId)
        }
    }

    fun setAwaitingPart(part: EmailBackupPartEntity?) {
        _awaitingPart.value = part
    }

    fun confirmPartSent(partId: String, onNext: (EmailBackupPartEntity?) -> Unit = {}) {
        viewModelScope.launch {
            val current = repository.part(partId) ?: return@launch
            repository.confirmSent(partId)
            val next = repository.partList(current.backupId)
                .firstOrNull {
                    it.status != EmailBackupPartStatus.CONFIRMED_SENT &&
                        it.partNumber > current.partNumber
                }
            _awaitingPart.value = null
            onNext(next)
        }
    }

    fun reportPartNotSent(partId: String) {
        viewModelScope.launch {
            repository.reportNotSent(partId)
            _awaitingPart.value = null
        }
    }

    fun loadHistoryParts(backupId: String) {
        viewModelScope.launch { _selectedHistoryParts.value = repository.partList(backupId) }
    }

    fun regenerate(batch: EmailBackupBatchEntity, onReady: () -> Unit) {
        viewModelScope.launch {
            _preview.value = null
            _generated.value = null
            _options.value = repository.optionsForBatch(batch)
            onReady()
            preview()
        }
    }

    fun markBatchSent(backupId: String) =
        viewModelScope.launch { repository.markBatchSent(backupId) }
    fun markBatchNotSent(backupId: String) =
        viewModelScope.launch { repository.markBatchNotSent(backupId) }
    fun deleteRecord(backupId: String) =
        viewModelScope.launch { repository.deleteRecord(backupId) }
}
