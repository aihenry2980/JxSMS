package com.example.jxsms

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.jxsms.data.contacts.ContactRepository
import com.example.jxsms.data.backup.EmailBackupPreferencesRepository
import com.example.jxsms.data.backup.EmailBackupRepository
import com.example.jxsms.data.preferences.SwipePreferencesRepository
import com.example.jxsms.data.sms.AndroidSmsDataSource
import com.example.jxsms.data.sms.SmsRepository
import com.example.jxsms.data.trash.SmsReaderDatabase
import com.example.jxsms.data.trash.TrashRepository
import com.example.jxsms.domain.classifier.RuleBasedSmsClassifier
import com.example.jxsms.notification.SmsNotificationManager
import com.example.jxsms.worker.TrashCleanupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class JxSmsApplication : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { container.trash.cleanupExpired() }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.emailBackup.cleanupOldExports()
        }
        val work = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "trash-cleanup", ExistingPeriodicWorkPolicy.UPDATE, work
        )
        container.notifications.createChannels()
    }
}

class AppContainer(app: Application) {
    private val database = SmsReaderDatabase.get(app)
    val classifier = RuleBasedSmsClassifier()
    val source = AndroidSmsDataSource(app.contentResolver)
    val contacts = ContactRepository(app, app.contentResolver)
    val sms = SmsRepository(app.contentResolver, source, classifier, contacts)
    val trash = TrashRepository(database.trashDao(), source, classifier)
    val preferences = SwipePreferencesRepository(app)
    val emailBackupPreferences = EmailBackupPreferencesRepository(app)
    val emailBackup = EmailBackupRepository(
        app,
        database.emailBackupDao(),
        sms,
        preferences
    )
    val notifications = SmsNotificationManager(app, preferences)
}
