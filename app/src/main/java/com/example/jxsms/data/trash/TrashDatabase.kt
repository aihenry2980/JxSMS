package com.example.jxsms.data.trash

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.jxsms.data.backup.EmailBackupBatchEntity
import com.example.jxsms.data.backup.EmailBackupDao
import com.example.jxsms.data.backup.EmailBackupMessageRefEntity
import com.example.jxsms.data.backup.EmailBackupPartEntity
import com.example.jxsms.domain.model.SmsCategory
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trash_sms", indices = [Index(value = ["originalSmsId"], unique = true)])
data class TrashSmsEntity(
    @PrimaryKey(autoGenerate = true) val trashId: Long = 0,
    val originalSmsId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long?,
    val read: Boolean,
    val seen: Boolean,
    val messageType: Int,
    val subscriptionId: Int?,
    val category: SmsCategory,
    val contactName: String?,
    val deletedAt: Long,
    val expiresAt: Long,
    val providerDeleted: Boolean = false
)

@Dao
interface TrashSmsDao {
    @Query("SELECT * FROM trash_sms ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashSmsEntity>>
    @Query("SELECT * FROM trash_sms WHERE trashId = :id")
    suspend fun byTrashId(id: Long): TrashSmsEntity?
    @Query("SELECT * FROM trash_sms WHERE originalSmsId = :id")
    suspend fun byOriginalId(id: Long): TrashSmsEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(value: TrashSmsEntity): Long
    @Query("UPDATE trash_sms SET providerDeleted = 1 WHERE originalSmsId = :id")
    suspend fun markProviderDeleted(id: Long)
    @Delete
    suspend fun delete(value: TrashSmsEntity)
    @Query("DELETE FROM trash_sms WHERE trashId = :id")
    suspend fun deleteById(id: Long)
    @Query("DELETE FROM trash_sms")
    suspend fun clear()
    @Query("DELETE FROM trash_sms WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int
}

@Database(
    entities = [
        TrashSmsEntity::class,
        EmailBackupBatchEntity::class,
        EmailBackupPartEntity::class,
        EmailBackupMessageRefEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class SmsReaderDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashSmsDao
    abstract fun emailBackupDao(): EmailBackupDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `email_backup_batches` (" +
                        "`backupId` TEXT NOT NULL, `recipient` TEXT NOT NULL, " +
                        "`rangeStart` INTEGER NOT NULL, `rangeEnd` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `totalMessageCount` INTEGER NOT NULL, " +
                        "`partCount` INTEGER NOT NULL, `settingsJson` TEXT NOT NULL, " +
                        "`contentHash` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "PRIMARY KEY(`backupId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_email_backup_batches_contentHash` " +
                        "ON `email_backup_batches` (`contentHash`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `email_backup_parts` (" +
                        "`partId` TEXT NOT NULL, `backupId` TEXT NOT NULL, " +
                        "`partNumber` INTEGER NOT NULL, `messageCount` INTEGER NOT NULL, " +
                        "`subject` TEXT NOT NULL, `bodyFileName` TEXT NOT NULL, " +
                        "`attachmentFileName` TEXT NOT NULL, `bodySizeBytes` INTEGER NOT NULL, " +
                        "`attachmentSizeBytes` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`confirmedAt` INTEGER, PRIMARY KEY(`partId`), " +
                        "FOREIGN KEY(`backupId`) REFERENCES `email_backup_batches`(`backupId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_email_backup_parts_backupId` " +
                        "ON `email_backup_parts` (`backupId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `email_backup_message_refs` (" +
                        "`backupId` TEXT NOT NULL, `partId` TEXT NOT NULL, " +
                        "`messageFingerprint` TEXT NOT NULL, `deviceSmsId` INTEGER, " +
                        "`receivedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`backupId`, `messageFingerprint`), " +
                        "FOREIGN KEY(`backupId`) REFERENCES `email_backup_batches`(`backupId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_email_backup_message_refs_messageFingerprint` " +
                        "ON `email_backup_message_refs` (`messageFingerprint`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_email_backup_message_refs_backupId` " +
                        "ON `email_backup_message_refs` (`backupId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_email_backup_message_refs_partId` " +
                        "ON `email_backup_message_refs` (`partId`)"
                )
            }
        }

        @Volatile private var instance: SmsReaderDatabase? = null
        fun get(context: Context): SmsReaderDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, SmsReaderDatabase::class.java, "jx_sms_reader.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
