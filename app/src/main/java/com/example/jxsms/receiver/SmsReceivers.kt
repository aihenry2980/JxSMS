package com.example.jxsms.receiver

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.jxsms.JxSmsApplication
import com.example.jxsms.data.sms.SmsMessageModel
import com.example.jxsms.domain.model.SmsCategory
import com.example.jxsms.notification.SmsNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isEmpty()) return@launch
                val address = parts.first().originatingAddress.orEmpty()
                val body = parts.joinToString("") { it.messageBody.orEmpty() }
                val date = parts.minOf { it.timestampMillis }
                val subId = intent.getIntExtra("subscription", -1).takeIf { it >= 0 }
                val app = context.applicationContext as JxSmsApplication
                if (isDuplicate(context, address, body, date, subId)) return@launch
                val raw = SmsMessageModel(0, address, body, date, date, false, false,
                    Telephony.Sms.MESSAGE_TYPE_INBOX, subId)
                val id = app.container.source.insertInbox(raw) ?: return@launch
                val contact = app.container.contacts.lookup(address)
                val category = app.container.classifier.classify(address, body, contact != null)
                app.container.notifications.notifySms(raw.copy(id = id, category = category,
                    contactName = contact?.displayName, contactPhotoUri = contact?.photoUri))
            } catch (e: Exception) {
                Log.e(TAG, "SMS receive failed: ${e.javaClass.simpleName}")
            } finally { pending.finish() }
        }
    }

    private fun isDuplicate(context: Context, address: String, body: String, date: Long, subId: Int?): Boolean {
        val start = date - 120_000
        val end = date + 120_000
        val selection = "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.BODY}=? AND " +
            "${Telephony.Sms.DATE} BETWEEN ? AND ?" +
            if (subId != null) " AND ${Telephony.Sms.SUBSCRIPTION_ID}=?" else ""
        val args = mutableListOf(address, body, start.toString(), end.toString())
        subId?.let { args += it.toString() }
        return context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID), selection, args.toTypedArray(), null)?.use { it.moveToFirst() } == true
    }
    companion object { private const val TAG = "IncomingSmsReceiver" }
}

class NotificationDeleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ACTION_DELETE_SMS, ACTION_MARK_READ) ||
            !intent.hasExtra(SmsNotificationManager.EXTRA_SMS_ID)) return
        val id = intent.getLongExtra(SmsNotificationManager.EXTRA_SMS_ID, -1)
        if (id <= 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as JxSmsApplication
                if (!com.example.jxsms.role.DefaultSmsRoleManager(context).isDefault()) {
                    Log.w(TAG, "Delete rejected because JX is not the default SMS app")
                    return@launch
                }
                if (intent.action == ACTION_MARK_READ) {
                    if (app.container.source.byId(id) == null || !app.container.source.setRead(id, true)) {
                        Log.e(TAG, "Mark read failed for SMS id=$id")
                    } else {
                        app.container.notifications.cancel(id)
                    }
                } else {
                    when (app.container.trash.moveSmsToTrash(id)) {
                        is com.example.jxsms.data.trash.TrashResult.Success -> app.container.notifications.cancel(id)
                        is com.example.jxsms.data.trash.TrashResult.Failure ->
                            Log.e(TAG, "Delete failed for SMS id=$id")
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Delete receiver failed: ${e.javaClass.simpleName}") }
            finally { pending.finish() }
        }
    }
    companion object {
        const val ACTION_DELETE_SMS = "com.example.jxsms.DELETE_SMS"
        const val ACTION_MARK_READ = "com.example.jxsms.MARK_SMS_READ"
        private const val TAG = "NotificationDelete"
    }
}

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        Log.w("MmsReceiver", "MMS received but not parsed or persisted")
        (context.applicationContext as JxSmsApplication).container.notifications.notifyMmsUnsupported()
    }
}
