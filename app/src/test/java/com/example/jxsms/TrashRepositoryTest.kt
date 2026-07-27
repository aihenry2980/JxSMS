package com.example.jxsms

import com.example.jxsms.data.sms.SmsDataSource
import com.example.jxsms.data.sms.SmsMessageModel
import com.example.jxsms.data.trash.TrashRepository
import com.example.jxsms.data.trash.TrashResult
import com.example.jxsms.data.trash.TrashSmsDao
import com.example.jxsms.data.trash.TrashSmsEntity
import com.example.jxsms.domain.classifier.RuleBasedSmsClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashRepositoryTest {
    private val sms = SmsMessageModel(42, "01012345678", "hello?", 100, 90, false, false, 1, 1)

    @Test fun backupOccursBeforeDeleteAndMoveIsIdempotent() = runBlocking {
        val events = mutableListOf<String>()
        val dao = FakeDao(events)
        val source = FakeSource(mutableMapOf(42L to sms), events)
        val repo = TrashRepository(dao, source, RuleBasedSmsClassifier()) { 1_000 }
        assertTrue(repo.moveSmsToTrash(42) is TrashResult.Success)
        assertEquals(listOf("backup", "provider-delete", "mark-deleted"), events)
        assertTrue(repo.moveSmsToTrash(42) is TrashResult.Success)
        assertEquals(1, dao.values.size)
    }

    @Test fun providerDeleteFailureKeepsRecoverableBackup() = runBlocking {
        val dao = FakeDao()
        val source = FakeSource(mutableMapOf(42L to sms), deleteWorks = false)
        val repo = TrashRepository(dao, source, RuleBasedSmsClassifier())
        assertTrue(repo.moveSmsToTrash(42) is TrashResult.Failure)
        assertNotNull(dao.byOriginalId(42))
        assertFalse(dao.byOriginalId(42)!!.providerDeleted)
    }

    @Test fun restoreDeletesRoomOnlyAfterProviderInsert() = runBlocking {
        val dao = FakeDao()
        val source = FakeSource(mutableMapOf(42L to sms))
        val repo = TrashRepository(dao, source, RuleBasedSmsClassifier())
        val moved = repo.moveSmsToTrash(42) as TrashResult.Success
        source.insertWorks = false
        assertFalse(repo.restore(moved.trashId))
        assertNotNull(dao.byTrashId(moved.trashId))
        source.insertWorks = true
        assertTrue(repo.restore(moved.trashId))
        assertEquals(null, dao.byTrashId(moved.trashId))
    }

    @Test fun restorePreservesSentMessageType() = runBlocking {
        val sent = sms.copy(type = 2)
        val dao = FakeDao()
        val source = FakeSource(mutableMapOf(42L to sent))
        val repo = TrashRepository(dao, source, RuleBasedSmsClassifier())
        val moved = repo.moveSmsToTrash(42) as TrashResult.Success

        assertTrue(repo.restore(moved.trashId))
        assertEquals(2, source.lastInserted?.type)
    }

    @Test fun expiryUsesThirtyDayBoundary() = runBlocking {
        val dao = FakeDao()
        val now = 10_000L
        dao.insert(entity(1, now - 1))
        dao.insert(entity(2, now + 1))
        val repo = TrashRepository(dao, FakeSource(mutableMapOf()), RuleBasedSmsClassifier()) { now }
        assertEquals(1, repo.cleanupExpired())
        assertEquals(1, dao.values.size)
        repo.permanentlyDelete(dao.values.first().trashId)
        assertTrue(dao.values.isEmpty())
    }

    private fun entity(id: Long, expires: Long) = TrashSmsEntity(
        originalSmsId = id, address = "x", body = "x", date = 0, dateSent = null,
        read = false, seen = false, messageType = 1, subscriptionId = null,
        category = com.example.jxsms.domain.model.SmsCategory.UNKNOWN, contactName = null,
        deletedAt = 0, expiresAt = expires
    )
}

private class FakeSource(
    private val messages: MutableMap<Long, SmsMessageModel>,
    private val events: MutableList<String> = mutableListOf(),
    private val deleteWorks: Boolean = true
) : SmsDataSource {
    var insertWorks = true
    var lastInserted: SmsMessageModel? = null
    override suspend fun inbox() = messages.values.toList()
    override suspend fun byId(id: Long) = messages[id]
    override suspend fun insertInbox(message: SmsMessageModel): Long? {
        if (!insertWorks) return null
        lastInserted = message
        val id = (messages.keys.maxOrNull() ?: 0) + 1
        messages[id] = message.copy(id = id)
        return id
    }
    override suspend fun delete(id: Long): Boolean {
        events += "provider-delete"
        if (!deleteWorks) return false
        return messages.remove(id) != null
    }
    override suspend fun setRead(id: Long, read: Boolean) = true
}

private class FakeDao(private val events: MutableList<String> = mutableListOf()) : TrashSmsDao {
    val values = mutableListOf<TrashSmsEntity>()
    private val flow = MutableStateFlow<List<TrashSmsEntity>>(emptyList())
    override fun observeAll(): Flow<List<TrashSmsEntity>> = flow
    override suspend fun byTrashId(id: Long) = values.firstOrNull { it.trashId == id }
    override suspend fun byOriginalId(id: Long) = values.firstOrNull { it.originalSmsId == id }
    override suspend fun insert(value: TrashSmsEntity): Long {
        if (values.any { it.originalSmsId == value.originalSmsId }) return -1
        events += "backup"
        val id = (values.maxOfOrNull { it.trashId } ?: 0) + 1
        values += value.copy(trashId = id)
        emit()
        return id
    }
    override suspend fun markProviderDeleted(id: Long) {
        events += "mark-deleted"
        val i = values.indexOfFirst { it.originalSmsId == id }
        values[i] = values[i].copy(providerDeleted = true)
        emit()
    }
    override suspend fun delete(value: TrashSmsEntity) { values.removeIf { it.trashId == value.trashId }; emit() }
    override suspend fun deleteById(id: Long) { values.removeIf { it.trashId == id }; emit() }
    override suspend fun clear() { values.clear(); emit() }
    override suspend fun deleteExpired(now: Long): Int {
        val before = values.size
        values.removeIf { it.expiresAt <= now }
        emit()
        return before - values.size
    }
    private fun emit() { flow.value = values.toList() }
}
