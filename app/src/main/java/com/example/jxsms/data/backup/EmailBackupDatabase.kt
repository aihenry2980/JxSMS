package com.example.jxsms.data.backup

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.example.jxsms.domain.backup.EmailBackupBatchStatus
import com.example.jxsms.domain.backup.EmailBackupPartStatus
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "email_backup_batches",
    indices = [Index("contentHash")]
)
data class EmailBackupBatchEntity(
    @PrimaryKey val backupId: String,
    val recipient: String,
    val rangeStart: Long,
    val rangeEnd: Long,
    val createdAt: Long,
    val totalMessageCount: Int,
    val partCount: Int,
    val settingsJson: String,
    val contentHash: String,
    val status: EmailBackupBatchStatus
)

@Entity(
    tableName = "email_backup_parts",
    foreignKeys = [
        ForeignKey(
            entity = EmailBackupBatchEntity::class,
            parentColumns = ["backupId"],
            childColumns = ["backupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("backupId")]
)
data class EmailBackupPartEntity(
    @PrimaryKey val partId: String,
    val backupId: String,
    val partNumber: Int,
    val messageCount: Int,
    val subject: String,
    val bodyFileName: String,
    val attachmentFileName: String,
    val bodySizeBytes: Long,
    val attachmentSizeBytes: Long,
    val status: EmailBackupPartStatus,
    val confirmedAt: Long?
)

@Entity(
    tableName = "email_backup_message_refs",
    primaryKeys = ["backupId", "messageFingerprint"],
    foreignKeys = [
        ForeignKey(
            entity = EmailBackupBatchEntity::class,
            parentColumns = ["backupId"],
            childColumns = ["backupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("messageFingerprint"), Index("backupId"), Index("partId")]
)
data class EmailBackupMessageRefEntity(
    val backupId: String,
    val partId: String,
    val messageFingerprint: String,
    val deviceSmsId: Long?,
    val receivedAt: Long
)

@Dao
interface EmailBackupDao {
    @Query("SELECT * FROM email_backup_batches ORDER BY createdAt DESC")
    fun observeBatches(): Flow<List<EmailBackupBatchEntity>>

    @Query("SELECT * FROM email_backup_parts WHERE backupId = :backupId ORDER BY partNumber")
    fun observeParts(backupId: String): Flow<List<EmailBackupPartEntity>>

    @Query("SELECT * FROM email_backup_parts WHERE backupId = :backupId ORDER BY partNumber")
    suspend fun parts(backupId: String): List<EmailBackupPartEntity>

    @Query("SELECT * FROM email_backup_parts WHERE partId = :partId")
    suspend fun part(partId: String): EmailBackupPartEntity?

    @Query(
        "SELECT bodyFileName FROM email_backup_parts WHERE status IN ('GENERATED', 'GMAIL_OPENED') " +
            "UNION SELECT attachmentFileName FROM email_backup_parts " +
            "WHERE status IN ('GENERATED', 'GMAIL_OPENED')"
    )
    suspend fun activeExportFileNames(): List<String>

    @Query("SELECT * FROM email_backup_batches WHERE backupId = :backupId")
    suspend fun batch(backupId: String): EmailBackupBatchEntity?

    @Query("SELECT * FROM email_backup_batches WHERE contentHash = :contentHash ORDER BY createdAt DESC")
    suspend fun byContentHash(contentHash: String): List<EmailBackupBatchEntity>

    @Query(
        "SELECT DISTINCT r.messageFingerprint FROM email_backup_message_refs r " +
            "INNER JOIN email_backup_batches b ON b.backupId = r.backupId " +
            "WHERE b.status = 'CONFIRMED_SENT'"
    )
    suspend fun confirmedFingerprints(): List<String>

    @Query(
        "SELECT COUNT(DISTINCT r.messageFingerprint) FROM email_backup_message_refs r " +
            "INNER JOIN email_backup_batches b ON b.backupId = r.backupId " +
            "WHERE b.status = 'CONFIRMED_SENT'"
    )
    fun observeConfirmedMessageCount(): Flow<Int>

    @Query("SELECT MAX(createdAt) FROM email_backup_batches WHERE status = 'CONFIRMED_SENT'")
    fun observeLatestConfirmedAt(): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatch(batch: EmailBackupBatchEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParts(parts: List<EmailBackupPartEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRefs(refs: List<EmailBackupMessageRefEntity>)

    @Transaction
    suspend fun insertBackup(
        batch: EmailBackupBatchEntity,
        parts: List<EmailBackupPartEntity>,
        refs: List<EmailBackupMessageRefEntity>
    ) {
        insertBatch(batch)
        insertParts(parts)
        insertRefs(refs)
    }

    @Query(
        "UPDATE email_backup_parts SET status = :status, confirmedAt = :confirmedAt " +
            "WHERE partId = :partId"
    )
    suspend fun updatePartStatus(
        partId: String,
        status: EmailBackupPartStatus,
        confirmedAt: Long?
    )

    @Query("UPDATE email_backup_batches SET status = :status WHERE backupId = :backupId")
    suspend fun updateBatchStatus(backupId: String, status: EmailBackupBatchStatus)

    @Query("DELETE FROM email_backup_batches WHERE backupId = :backupId")
    suspend fun deleteBatch(backupId: String)
}
