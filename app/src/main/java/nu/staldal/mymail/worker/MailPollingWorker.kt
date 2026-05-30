package nu.staldal.mymail.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Result
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import nu.staldal.mymail.INBOX_ID
import nu.staldal.mymail.MainActivity
import nu.staldal.mymail.MyMailApplication
import nu.staldal.mymail.R
import nu.staldal.mymail.repository.FolderRepository
import retrofit2.HttpException
import java.util.concurrent.TimeUnit
import javax.inject.Named

@HiltWorker
class MailPollingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val folderRepository: FolderRepository,
    @Named("plain") private val prefs: SharedPreferences,
    private val application: MyMailApplication,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (application.isAppInForeground.get()) {
            return Result.success()
        }

        val result = folderRepository.listFolders()

        result.onFailure { error ->
            if (error is HttpException && error.code() == 401) {
                WorkManager.getInstance(applicationContext)
                    .cancelUniqueWork(WORK_NAME)
                if (!application.isAppInForeground.get()) {
                    postSessionExpiredNotification()
                }
                return Result.success()
            }
            Log.w(TAG, "Failed to fetch folders for polling", error)
            return Result.retry()
        }

        val folders = result.getOrNull() ?: return Result.success()
        val inbox = folders.find { it.id.toLong() == INBOX_ID } ?: return Result.success()
        val currentCount = inbox.unreadCount

        val storedCount = if (prefs.contains(KEY_INBOX_UNREAD_COUNT)) {
            prefs.getInt(KEY_INBOX_UNREAD_COUNT, 0)
        } else {
            null
        }

        if (storedCount == null) {
            prefs.edit().putInt(KEY_INBOX_UNREAD_COUNT, currentCount).apply()
            return Result.success()
        }

        if (currentCount > storedCount) {
            postNewMailNotification()
        }

        prefs.edit().putInt(KEY_INBOX_UNREAD_COUNT, currentCount).apply()
        return Result.success()
    }

    private fun buildDeepLinkPendingIntent(deepLinkUri: String): PendingIntent {
        val deepLinkIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(deepLinkUri),
            applicationContext,
            MainActivity::class.java,
        )
        return TaskStackBuilder.create(applicationContext).run {
            addNextIntentWithParentStack(deepLinkIntent)
            getPendingIntent(
                0,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )!!
        }
    }

    private fun postNewMailNotification() {
        val notificationsEnabled = prefs.getBoolean("notifications_enabled", false)
        if (!notificationsEnabled) return

        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val pendingIntent = buildDeepLinkPendingIntent("mymail://messages/$INBOX_ID")

        val notification = NotificationCompat.Builder(
            applicationContext,
            MyMailApplication.NOTIFICATION_CHANNEL_NEW_MAIL,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New mail")
            .setContentText("You have new messages in your inbox")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(NOTIFICATION_ID_NEW_MAIL, notification)
    }

    private fun postSessionExpiredNotification() {
        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val pendingIntent = buildDeepLinkPendingIntent("mymail://setup")

        val notification = NotificationCompat.Builder(
            applicationContext,
            MyMailApplication.NOTIFICATION_CHANNEL_NEW_MAIL,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Session expired")
            .setContentText("Tap to sign in again")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(NOTIFICATION_ID_SESSION_EXPIRED, notification)
    }

    companion object {
        private const val TAG = "MailPollingWorker"
        private const val WORK_NAME = "mail_polling"
        const val KEY_INBOX_UNREAD_COUNT = "inbox_unread_count"
        const val NOTIFICATION_ID_NEW_MAIL = 1
        const val NOTIFICATION_ID_SESSION_EXPIRED = 2

        fun enqueuePolling(context: Context) {
            val request = PeriodicWorkRequestBuilder<MailPollingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
