package com.example.jxsms.data.trash

import com.example.jxsms.data.sms.SmsDataSource
import com.example.jxsms.data.sms.SmsMessageModel
import com.example.jxsms.domain.classifier.SmsClassifier
import kotlinx.coroutines.flow.Flow

sealed interface TrashResult {
    data class Success(val trashId: Long) : TrashResult
    data class Failure(val message: String) : TrashResult
}

class TrashRepository(
    private val dao: TrashSmsDao,
    private val source: SmsDataSource,
    private val classifier: SmsClassifier,
    private val now: () -> Long = System::currentTimeMillis
) {
    val items: Flow<List<TrashSmsEntity>> = dao.observeAll()

    suspend fun moveSmsToTrash(smsId: Long): TrashResult {
        val existing = dao.byOriginalId(smsId)
        if (existing?.providerDeleted == true) return TrashResult.Success(existing.trashId)
        val sms = source.byId(smsId)
            ?: return existing?.let { TrashResult.Success(it.trashId) }
                ?: TrashResult.Failure("找不到该短信")
        val time = now()
        val entity = existing ?: TrashSmsEntity(
            originalSmsId = sms.id, address = sms.address, body = sms.body, date = sms.date,
            dateSent = sms.dateSent, read = sms.read, seen = sms.seen, messageType = sms.type,
            subscriptionId = sms.subscriptionId,
            category = classifier.classify(sms.address, sms.body, sms.contactName != null),
            contactName = sms.contactName, deletedAt = time, expiresAt = time + RETENTION_MILLIS
        )
        val inserted = if (existing == null) dao.insert(entity) else existing.trashId
        val backup = dao.byOriginalId(smsId)
            ?: return TrashResult.Failure("无法创建垃圾箱备份")
        if (!source.delete(smsId)) {
            return TrashResult.Failure("系统短信删除失败；备份已保留，可重试")
        }
        dao.markProviderDeleted(smsId)
        return TrashResult.Success(if (inserted > 0) inserted else backup.trashId)
    }

    suspend fun restore(trashId: Long): Boolean {
        val item = dao.byTrashId(trashId) ?: return true
        // A failed Provider delete leaves the original intact; restoring means discarding
        // the compensating backup rather than creating a duplicate Inbox row.
        if (!item.providerDeleted && source.byId(item.originalSmsId) != null) {
            dao.delete(item)
            return true
        }
        val newId = source.insertInbox(item.toSms()) ?: return false
        if (newId <= 0) return false
        dao.delete(item)
        return true
    }

    suspend fun permanentlyDelete(trashId: Long) = dao.deleteById(trashId)
    suspend fun clear() = dao.clear()
    suspend fun cleanupExpired() = dao.deleteExpired(now())

    private fun TrashSmsEntity.toSms() = SmsMessageModel(
        id = 0, address = address, body = body, date = date, dateSent = dateSent,
        read = read, seen = seen, type = messageType, subscriptionId = subscriptionId,
        category = category, contactName = contactName
    )

    companion object { const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000 }
}
