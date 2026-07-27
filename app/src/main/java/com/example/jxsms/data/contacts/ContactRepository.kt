package com.example.jxsms.data.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class ContactInfo(val displayName: String, val photoUri: Uri?)

class ContactRepository(private val context: Context, private val resolver: ContentResolver) {
    private val cache = ConcurrentHashMap<String, ContactInfo?>()

    suspend fun lookup(address: String): ContactInfo? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED || address.any(Char::isLetter)) return null
        return cache[address] ?: withContext(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            resolver.query(uri, arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
                ContactsContract.PhoneLookup.PHOTO_URI
            ), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else ContactInfo(
                    c.getString(0).orEmpty(),
                    (c.getString(1) ?: c.getString(2))?.let(Uri::parse)
                )
            }.also { if (it != null) cache[address] = it }
        }
    }
}
