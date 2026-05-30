package nu.staldal.mymail

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nu.staldal.mymail.auth.AuthEventBus
import nu.staldal.mymail.auth.CredentialStore
import nu.staldal.mymail.repository.FolderRepository
import nu.staldal.mymail.ui.navigation.NavGraph
import nu.staldal.mymail.ui.theme.MyMailTheme
import nu.staldal.mymail.worker.MailPollingWorker
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Named

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var credentialStore: CredentialStore

    @Inject
    lateinit var folderRepository: FolderRepository

    @Inject
    lateinit var authEventBus: AuthEventBus

    @Inject
    @Named("plain")
    lateinit var prefs: SharedPreferences

    // In-memory foreground-poller baseline; -1 means not yet initialised.
    // Reset to -1 on each activity recreation (screen rotation, etc.).
    var foregroundPollerBaseline by mutableIntStateOf(-1)
        private set

    private var pollingJob: Job? = null

    fun resetForegroundPollerBaseline() {
        foregroundPollerBaseline = -1
    }

    fun startForegroundPollingIfNeeded() {
        if (pollingJob?.isActive != true) {
            startForegroundPolling()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hasCredentials = credentialStore.hasCredentials()
        val startDestination = if (hasCredentials) "folders" else "setup"

        setContent {
            MyMailTheme {
                val navController = rememberNavController()

                // Collect 401 auth events and navigate to setup
                LaunchedEffect(Unit) {
                    authEventBus.events.collect {
                        val app = application as MyMailApplication
                        if (app.isAppInForeground.get()) {
                            if (navController.currentDestination?.route != "setup") {
                                navController.navigate("setup") {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        }
                    }
                }

                NavGraph(
                    navController = navController,
                    startDestination = startDestination,
                )
            }
        }

        if (hasCredentials) {
            MailPollingWorker.enqueuePolling(this)
            startForegroundPolling()
        }
    }

    private fun startForegroundPolling() {
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                delay(FOREGROUND_POLL_INTERVAL_MS)
                pollInboxForNewMail()
            }
        }
    }

    private suspend fun pollInboxForNewMail() {
        val result = folderRepository.listFolders()
        result.onFailure { error ->
            if (error !is HttpException || error.code() != 401) {
                Log.w(TAG, "Foreground poll failed", error)
            }
            return
        }

        val folders = result.getOrNull() ?: return
        val inbox = folders.find { it.id.toLong() == INBOX_ID } ?: return
        val currentCount = inbox.unreadCount

        if (foregroundPollerBaseline < 0) {
            foregroundPollerBaseline = currentCount
            return
        }

        if (currentCount > foregroundPollerBaseline) {
            postNewMailNotification()
        }

        foregroundPollerBaseline = currentCount
    }

    fun postNewMailNotification() {
        val notificationsEnabled = prefs.getBoolean("notifications_enabled", false)
        if (!notificationsEnabled) return

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(
            this,
            MyMailApplication.NOTIFICATION_CHANNEL_NEW_MAIL,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New mail")
            .setContentText("You have new messages in your inbox")
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this)
            .notify(MailPollingWorker.NOTIFICATION_ID_NEW_MAIL, notification)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val FOREGROUND_POLL_INTERVAL_MS = 30_000L
    }
}
