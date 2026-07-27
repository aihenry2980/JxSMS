package com.example.jxsms.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.jxsms.MainActivity
import com.example.jxsms.R
import com.example.jxsms.data.sms.SmsMessageModel
import com.example.jxsms.data.preferences.SwipePreferencesRepository
import com.example.jxsms.domain.model.SmsCategory
import com.example.jxsms.receiver.NotificationDeleteReceiver
import kotlinx.coroutines.flow.first

class SmsNotificationManager(
    private val context: Context,
    private val preferences: SwipePreferencesRepository
) {
    fun createChannels() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SMS, "新短信", NotificationManager.IMPORTANCE_HIGH)
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_WARNING, "兼容性提醒", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    @SuppressLint("MissingPermission") // canNotify() guards POST_NOTIFICATIONS immediately below.
    suspend fun notifySms(sms: SmsMessageModel) {
        if (!canNotify()) return
        val requestCode = notificationId(sms.id)
        val open = PendingIntent.getActivity(context, requestCode,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_SMS
                putExtra(EXTRA_SMS_ID, sms.id)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val delete = PendingIntent.getBroadcast(context, requestCode,
            Intent(context, NotificationDeleteReceiver::class.java).apply {
                action = NotificationDeleteReceiver.ACTION_DELETE_SMS
                putExtra(EXTRA_SMS_ID, sms.id)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val markRead = PendingIntent.getBroadcast(context, requestCode xor 0x40000000,
            Intent(context, NotificationDeleteReceiver::class.java).apply {
                action = NotificationDeleteReceiver.ACTION_MARK_READ
                putExtra(EXTRA_SMS_ID, sms.id)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val redDeleteTitle = SpannableString("🔴 删除").apply {
            setSpan(ForegroundColorSpan(0xffc62828.toInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val title = "【${sms.category.label}】${sms.contactName ?: sms.address}"
        val builder = NotificationCompat.Builder(context, CHANNEL_SMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(sms.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sms.body))
            .setContentIntent(open).setAutoCancel(true)
            .setAllowSystemGeneratedContextualActions(false)
        if (preferences.preferences.first().notificationDeleteOnLeft) {
            builder.addAction(0, redDeleteTitle, delete)
                .addAction(0, "✓ 标为已读", markRead)
        } else {
            builder.addAction(0, "✓ 标为已读", markRead)
                .addAction(0, redDeleteTitle, delete)
        }
        val notification = builder.build()
        try {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
        } catch (_: SecurityException) { /* Permission may be revoked between check and notify. */ }
    }

    @SuppressLint("MissingPermission") // canNotify() guards POST_NOTIFICATIONS immediately below.
    fun notifyMmsUnsupported() {
        if (!canNotify()) return
        val text = "JX SMS Reader 当前只支持普通 SMS。请切换到支持 MMS 的短信应用查看此类消息。"
        val n = NotificationCompat.Builder(context, CHANNEL_WARNING)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("暂不支持 MMS")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).build()
        try {
            NotificationManagerCompat.from(context).notify(900_001, n)
        } catch (_: SecurityException) { /* Permission may be revoked between check and notify. */ }
    }

    fun cancel(smsId: Long) = NotificationManagerCompat.from(context).cancel(notificationId(smsId))
    private fun canNotify() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_SMS = "new_sms"
        const val CHANNEL_WARNING = "compatibility"
        const val EXTRA_SMS_ID = "sms_id"
        const val ACTION_OPEN_SMS = "com.example.jxsms.OPEN_SMS"
        fun notificationId(id: Long): Int = (id xor (id ushr 32)).toInt() and 0x7fffffff
    }
}
