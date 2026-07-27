package com.example.jxsms.data.sms

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.example.jxsms.domain.classifier.SmsClassifier
import com.example.jxsms.domain.model.SmsCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

data class SmsMessageModel(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long?,
    val read: Boolean,
    val seen: Boolean,
    val type: Int,
    val subscriptionId: Int?,
    val category: SmsCategory = SmsCategory.UNKNOWN,
    val contactName: String? = null,
    val contactPhotoUri: Uri? = null
)

interface SmsDataSource {
    suspend fun inbox(): List<SmsMessageModel>
    suspend fun conversationMessages(): List<SmsMessageModel> = inbox()
    suspend fun byId(id: Long): SmsMessageModel?
    suspend fun insertInbox(message: SmsMessageModel): Long?
    suspend fun delete(id: Long): Boolean
    suspend fun setRead(id: Long, read: Boolean): Boolean
}

class AndroidSmsDataSource(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : SmsDataSource {
    private val projection = arrayOf(
        Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
        Telephony.Sms.DATE_SENT, Telephony.Sms.READ, Telephony.Sms.SEEN, Telephony.Sms.TYPE,
        Telephony.Sms.SUBSCRIPTION_ID
    )

    override suspend fun inbox() = query(Telephony.Sms.Inbox.CONTENT_URI, null, null,
        "${Telephony.Sms.DATE} DESC")

    override suspend fun conversationMessages() = query(
        Telephony.Sms.CONTENT_URI,
        "${Telephony.Sms.TYPE} IN (?, ?)",
        arrayOf(
            Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
            Telephony.Sms.MESSAGE_TYPE_SENT.toString()
        ),
        "${Telephony.Sms.DATE} DESC"
    )

    override suspend fun byId(id: Long): SmsMessageModel? =
        query(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), null, null, null).firstOrNull()

    private suspend fun query(uri: Uri, selection: String?, args: Array<String>?, order: String?) =
        withContext(io) {
            val result = mutableListOf<SmsMessageModel>()
            try {
                resolver.query(uri, projection, selection, args, order)?.use { cursor ->
                    val id = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                    val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val sent = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
                    val read = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                    val seen = cursor.getColumnIndexOrThrow(Telephony.Sms.SEEN)
                    val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                    val sub = cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)
                    while (cursor.moveToNext()) result += SmsMessageModel(
                        cursor.getLong(id), cursor.getString(address).orEmpty(), cursor.getString(body).orEmpty(),
                        cursor.getLong(date), cursor.getLong(sent).takeIf { it > 0 }, cursor.getInt(read) != 0,
                        cursor.getInt(seen) != 0, cursor.getInt(type),
                        cursor.getInt(sub).takeUnless { cursor.isNull(sub) }
                    )
                }
            } catch (_: SecurityException) {
                // A role or runtime permission can be revoked while the app is active.
                // Restricted mode represents this as an empty readable result rather than crashing.
            }
            result
        }

    override suspend fun insertInbox(message: SmsMessageModel): Long? = withContext(io) {
        val restoredType = if (message.type == Telephony.Sms.MESSAGE_TYPE_SENT) {
            Telephony.Sms.MESSAGE_TYPE_SENT
        } else {
            Telephony.Sms.MESSAGE_TYPE_INBOX
        }
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, message.address)
            put(Telephony.Sms.BODY, message.body)
            put(Telephony.Sms.DATE, message.date)
            message.dateSent?.let { put(Telephony.Sms.DATE_SENT, it) }
            put(Telephony.Sms.READ, if (message.read) 1 else 0)
            put(Telephony.Sms.SEEN, if (message.seen) 1 else 0)
            put(Telephony.Sms.TYPE, restoredType)
            message.subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        }
        val target = if (restoredType == Telephony.Sms.MESSAGE_TYPE_SENT) {
            Telephony.Sms.Sent.CONTENT_URI
        } else {
            Telephony.Sms.Inbox.CONTENT_URI
        }
        resolver.insert(target, values)?.lastPathSegment?.toLongOrNull()
    }

    override suspend fun delete(id: Long): Boolean = withContext(io) {
        val itemUri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
        try {
            resolver.delete(itemUri, null, null)
            if (!smsExists(itemUri)) return@withContext true

            // Some OEM providers (including Samsung builds) ignore the item URI
            // or return an unreliable row count. Retry against the collection URI.
            resolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms._ID}=?",
                arrayOf(id.toString())
            )
            !smsExists(itemUri)
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun smsExists(itemUri: Uri): Boolean =
        resolver.query(
            itemUri,
            arrayOf(Telephony.Sms._ID),
            null,
            null,
            null
        )?.use { it.moveToFirst() } ?: true

    override suspend fun setRead(id: Long, read: Boolean): Boolean = withContext(io) {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, if (read) 1 else 0)
            put(Telephony.Sms.SEEN, if (read) 1 else 0)
        }
        resolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), values, null, null) > 0
    }
}

class SmsRepository(
    private val resolver: ContentResolver,
    private val source: SmsDataSource,
    private val classifier: SmsClassifier,
    private val contacts: com.example.jxsms.data.contacts.ContactRepository
) {
    fun messages(): Flow<List<SmsMessageModel>> = observedMessages { source.inbox() }

    fun conversationMessages(): Flow<List<SmsMessageModel>> =
        observedMessages { source.conversationMessages() }

    suspend fun inboxSnapshot(): List<SmsMessageModel> = source.inbox().map { sms ->
        val contact = contacts.lookup(sms.address)
        sms.copy(
            contactName = contact?.displayName,
            contactPhotoUri = contact?.photoUri,
            category = classifier.classify(sms.address, sms.body, contact != null)
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observedMessages(
        loader: suspend () -> List<SmsMessageModel>
    ): Flow<List<SmsMessageModel>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate().mapLatest {
        loader().map { sms ->
            val contact = contacts.lookup(sms.address)
            sms.copy(
                contactName = contact?.displayName,
                contactPhotoUri = contact?.photoUri,
                category = classifier.classify(sms.address, sms.body, contact != null)
            )
        }
    }

    suspend fun byId(id: Long): SmsMessageModel? = source.byId(id)?.let { sms ->
        val contact = contacts.lookup(sms.address)
        sms.copy(contactName = contact?.displayName, contactPhotoUri = contact?.photoUri,
            category = classifier.classify(sms.address, sms.body, contact != null))
    }
    suspend fun setRead(id: Long, read: Boolean) = source.setRead(id, read)
}
