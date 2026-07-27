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

@Database(entities = [TrashSmsEntity::class], version = 1, exportSchema = false)
abstract class SmsReaderDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashSmsDao
    companion object {
        @Volatile private var instance: SmsReaderDatabase? = null
        fun get(context: Context): SmsReaderDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, SmsReaderDatabase::class.java, "jx_sms_reader.db"
            ).build().also { instance = it }
        }
    }
}
