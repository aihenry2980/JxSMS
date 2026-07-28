package com.example.jxsms.data.sms

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object MmsMessageIds {
    fun toUiId(providerId: Long): Long = providerId xor Long.MIN_VALUE
    fun toProviderId(uiId: Long): Long = uiId xor Long.MIN_VALUE
    fun isMms(uiId: Long): Boolean = uiId < 0 && uiId != -1L
}

interface MmsDataSource {
    suspend fun inbox(): List<SmsMessageModel>
    suspend fun conversationMessages(): List<SmsMessageModel>
    suspend fun byUiId(uiId: Long): SmsMessageModel?
}

class AndroidMmsDataSource(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : MmsDataSource {
    private val projection = arrayOf(
        BaseColumns._ID,
        Telephony.Mms.DATE,
        Telephony.Mms.DATE_SENT,
        Telephony.Mms.READ,
        Telephony.Mms.SEEN,
        Telephony.Mms.SUBSCRIPTION_ID,
        Telephony.Mms.SUBJECT,
        Telephony.Mms.MESSAGE_BOX
    )

    override suspend fun inbox(): List<SmsMessageModel> =
        queryFolder(Telephony.Mms.Inbox.CONTENT_URI, outgoing = false)

    override suspend fun conversationMessages(): List<SmsMessageModel> =
        queryFolder(Telephony.Mms.Inbox.CONTENT_URI, outgoing = false) +
            queryFolder(Telephony.Mms.Sent.CONTENT_URI, outgoing = true)

    override suspend fun byUiId(uiId: Long): SmsMessageModel? = withContext(io) {
        if (!MmsMessageIds.isMms(uiId)) return@withContext null
        val providerId = MmsMessageIds.toProviderId(uiId)
        queryMms(
            ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, providerId),
            outgoingHint = null
        ).firstOrNull()
    }

    private suspend fun queryFolder(uri: Uri, outgoing: Boolean): List<SmsMessageModel> =
        withContext(io) {
            queryMms(uri, outgoing)
        }

    private fun queryMms(uri: Uri, outgoingHint: Boolean?): List<SmsMessageModel> {
        val result = mutableListOf<SmsMessageModel>()
        try {
            resolver.query(uri, projection, null, null, "${Telephony.Mms.DATE} DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val sentColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE_SENT)
                val readColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.READ)
                val seenColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.SEEN)
                val subscriptionColumn =
                    cursor.getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID)
                val subjectColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)
                val boxColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
                while (cursor.moveToNext()) {
                    val providerId = cursor.getLong(idColumn)
                    val outgoing = outgoingHint
                        ?: (cursor.getInt(boxColumn) == Telephony.Mms.MESSAGE_BOX_SENT)
                    val parts = readParts(providerId)
                    val address = readAddresses(providerId, outgoing)
                    result += SmsMessageModel(
                        id = MmsMessageIds.toUiId(providerId),
                        address = address,
                        body = parts.text,
                        date = mmsTimeToMillis(cursor.getLong(dateColumn)),
                        dateSent = cursor.getLong(sentColumn)
                            .takeIf { it > 0 }
                            ?.let(::mmsTimeToMillis),
                        read = cursor.getInt(readColumn) != 0,
                        seen = cursor.getInt(seenColumn) != 0,
                        type = if (outgoing) {
                            Telephony.Sms.MESSAGE_TYPE_SENT
                        } else {
                            Telephony.Sms.MESSAGE_TYPE_INBOX
                        },
                        subscriptionId = cursor.getInt(subscriptionColumn)
                            .takeUnless { cursor.isNull(subscriptionColumn) },
                        transport = MessageTransport.MMS,
                        subject = cursor.getString(subjectColumn)?.takeIf {
                            it.isNotBlank() && it != "null"
                        },
                        attachments = parts.attachments
                    )
                }
            }
        } catch (_: SecurityException) {
            // READ_SMS can be revoked while the app is open.
        } catch (_: IllegalArgumentException) {
            // Some OEM providers omit optional MMS columns. Treat the source as unavailable.
        }
        return result
    }

    private fun readAddresses(messageId: Long, outgoing: Boolean): String {
        val wantedType = if (outgoing) ADDRESS_TYPE_TO else ADDRESS_TYPE_FROM
        val addresses = mutableListOf<String>()
        try {
            resolver.query(
                Telephony.Mms.Addr.getAddrUriForMessage(messageId.toString()),
                arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
                "${Telephony.Mms.Addr.TYPE}=?",
                arrayOf(wantedType.toString()),
                null
            )?.use { cursor ->
                val address = cursor.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS)
                while (cursor.moveToNext()) {
                    cursor.getString(address)?.takeIf {
                        it.isNotBlank() && !it.equals(INSERT_ADDRESS_TOKEN, ignoreCase = true)
                    }?.let(addresses::add)
                }
            }
        } catch (_: SecurityException) {
            return UNKNOWN_ADDRESS
        } catch (_: IllegalArgumentException) {
            return UNKNOWN_ADDRESS
        }
        return addresses.distinct().joinToString(", ").ifBlank { UNKNOWN_ADDRESS }
    }

    private fun readParts(messageId: Long): ParsedParts {
        val text = mutableListOf<String>()
        val attachments = mutableListOf<MmsAttachment>()
        try {
            resolver.query(
                Telephony.Mms.Part.getPartUriForMessage(messageId.toString()),
                arrayOf(
                    BaseColumns._ID,
                    Telephony.Mms.Part.CONTENT_TYPE,
                    Telephony.Mms.Part.TEXT,
                    Telephony.Mms.Part.CHARSET,
                    Telephony.Mms.Part.FILENAME,
                    Telephony.Mms.Part.NAME
                ),
                null,
                null,
                "${Telephony.Mms.Part.SEQ} ASC"
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                val contentType = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
                val textColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT)
                val charsetColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CHARSET)
                val fileNameColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.FILENAME)
                val nameColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.NAME)
                while (cursor.moveToNext()) {
                    val partId = cursor.getLong(id)
                    val mime = cursor.getString(contentType).orEmpty().lowercase()
                    val partUri = ContentUris.withAppendedId(Telephony.Mms.Part.CONTENT_URI, partId)
                    if (mime == "text/plain") {
                        val inline = cursor.getString(textColumn)
                        val value = inline?.takeIf(String::isNotBlank)
                            ?: readTextPart(
                                partUri,
                                cursor.getInt(charsetColumn).takeUnless {
                                    cursor.isNull(charsetColumn)
                                }
                            )
                        value?.takeIf(String::isNotBlank)?.let(text::add)
                    } else if (mime != "application/smil" && mime.isNotBlank()) {
                        attachments += MmsAttachment(
                            partId = partId,
                            contentUri = partUri,
                            contentType = mime,
                            fileName = cursor.getString(fileNameColumn)
                                ?: cursor.getString(nameColumn)
                        )
                    }
                }
            }
        } catch (_: SecurityException) {
            return ParsedParts()
        } catch (_: IllegalArgumentException) {
            return ParsedParts()
        }
        return ParsedParts(text.distinct().joinToString("\n"), attachments)
    }

    private fun readTextPart(uri: Uri, mibEnum: Int?): String? = runCatching {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        String(bytes, charsetFor(mibEnum))
    }.getOrNull()

    private fun charsetFor(mibEnum: Int?): Charset = when (mibEnum) {
        3 -> StandardCharsets.US_ASCII
        4 -> StandardCharsets.ISO_8859_1
        17 -> Charset.forName("Shift_JIS")
        38 -> Charset.forName("EUC-KR")
        106, null, 0 -> StandardCharsets.UTF_8
        113 -> Charset.forName("GBK")
        2026 -> Charset.forName("Big5")
        else -> StandardCharsets.UTF_8
    }

    private data class ParsedParts(
        val text: String = "",
        val attachments: List<MmsAttachment> = emptyList()
    )

    companion object {
        // WSP PduHeaders values used by the public Telephony.Mms.Addr.TYPE column.
        private const val ADDRESS_TYPE_FROM = 137
        private const val ADDRESS_TYPE_TO = 151
        private const val INSERT_ADDRESS_TOKEN = "insert-address-token"
        private const val UNKNOWN_ADDRESS = "MMS"

        fun mmsTimeToMillis(value: Long): Long =
            if (value in 1 until 10_000_000_000L) value * 1000L else value
    }
}
