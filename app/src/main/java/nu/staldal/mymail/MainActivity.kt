package nu.staldal.mymail

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nu.staldal.mymail.auth.AuthEventBus
import nu.staldal.mymail.auth.CredentialStore
import nu.staldal.mymail.auth.MyPassClient
import nu.staldal.mymail.auth.MyPassCredentialSession
import nu.staldal.mymail.intent.PendingComposeIntentHolder
import nu.staldal.mymail.intent.parseComposeIntent
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
    lateinit var pendingComposeIntentHolder: PendingComposeIntentHolder

    @Inject
    lateinit var myPassCredentialSession: MyPassCredentialSession

    @Inject
    @Named("plain")
    lateinit var prefs: SharedPreferences

    // In-memory foreground-poller baseline; -1 means not yet initialised.
    // Reset to -1 on each activity recreation (screen rotation, etc.).
    var foregroundPollerBaseline by mutableIntStateOf(-1)
        private set

    private var pollingJob: Job? = null

    // Answers "where should the pending pw result navigate": set while the pw activity launched
    // from onCreate is running, so that only that fetch — and not one the user starts on the setup
    // screen — moves on to the folder list. That is activity-scoped UI state, which is why it, and
    // only it, goes into saved instance state.
    //
    // It deliberately does NOT answer "has this process asked pw yet" — that is process-scoped and
    // lives in PwCredentialSession.fetchAttempted. Saved instance state is restored after a process
    // kill as well, so deciding the fetch from it leaves a restored process never asking pw again.
    private var awaitingStartupPwFetch = false
    private val startupPwFetchFinished = MutableStateFlow(false)

    // The entry the running fetch asked pw for, so that the result is bound to the right entry
    // even if the configuration changed while pw was in front.
    private var pwFetchEntryName: String? = null

    private val pwLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val entryName = pwFetchEntryName
        MyPassClient.credentialFromResult(result.resultCode, result.data)?.let { credential ->
            if (entryName != null) myPassCredentialSession.set(entryName, credential)
        }
        pwFetchEntryName = null
        if (awaitingStartupPwFetch) {
            awaitingStartupPwFetch = false
            startupPwFetchFinished.value = true
        }
    }

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

        // In pw mode every cold start needs a trip through pw before anything is authenticated, so
        // an incoming send intent is kept and acted on once that fetch succeeds.
        val pwFetchPending = !hasCredentials && credentialStore.needsPwFetch()

        // Only act on incoming "send mail" intents (e.g. share-to, mailto: links) once logged in;
        // otherwise the prefill data would be lost on the way to the setup screen.
        val composeIntentData = if (hasCredentials || pwFetchPending) parseComposeIntent(intent) else null
        composeIntentData?.let { pendingComposeIntentHolder.set(it) }

        setContent {
            MyMailTheme {
                val navController = rememberNavController()

                LaunchedEffect(composeIntentData) {
                    if (composeIntentData != null && hasCredentials) {
                        navController.navigate("compose")
                    }
                }

                // The pw fetch started below finished: continue into the app when it produced a
                // credential, otherwise leave the user on the setup screen.
                LaunchedEffect(Unit) {
                    startupPwFetchFinished.collect { finished ->
                        if (!finished) return@collect
                        startupPwFetchFinished.value = false
                        if (!credentialStore.hasCredentials()) {
                            // The share was not delivered; drop it rather than have it turn up in
                            // some later, unrelated new message.
                            pendingComposeIntentHolder.consume()
                            return@collect
                        }
                        MailPollingWorker.enqueuePolling(this@MainActivity)
                        startForegroundPollingIfNeeded()
                        navController.navigate("folders") {
                            popUpTo(0) { inclusive = true }
                        }
                        if (composeIntentData != null) {
                            navController.navigate("compose")
                        }
                    }
                }

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
        } else {
            // Recreated while pw was in front: keep waiting for that result. Note that this cannot
            // be told apart from a restore after a process kill by the bundle alone — the guard on
            // asking pw again is the process-scoped flag inside fetchPwCredentialIfConfigured().
            if (savedInstanceState?.getBoolean(STATE_AWAITING_PW_FETCH, false) == true) {
                awaitingStartupPwFetch = true
                pwFetchEntryName = credentialStore.pwEntryName
            }
            fetchPwCredentialIfConfigured()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Only this flag — never the credential itself — is allowed into saved instance state.
        outState.putBoolean(STATE_AWAITING_PW_FETCH, awaitingStartupPwFetch)
    }

    /**
     * pw credentials are process-memory-only, so a configured app starts each process without one.
     * Ask pw for it right away instead of making the user visit the setup screen every time.
     *
     * Asked at most once per process — including when the launch throws — so that a user who
     * cancels pw is not asked again on every activity recreation. A new process starts over,
     * which is exactly when the credential is gone.
     */
    private fun fetchPwCredentialIfConfigured() {
        if (myPassCredentialSession.fetchAttempted) return
        val entryName = credentialStore.pwEntryName ?: return
        if (!credentialStore.needsPwFetch() || !MyPassClient.isAvailable(this)) return
        myPassCredentialSession.markFetchAttempted()
        awaitingStartupPwFetch = true
        pwFetchEntryName = entryName
        try {
            pwLauncher.launch(MyPassClient.createFetchIntent(entryName))
        } catch (_: ActivityNotFoundException) {
            // pw may have been uninstalled since it was resolved.
            abandonStartupPwFetch()
        } catch (_: SecurityException) {
            // The setup screen explains that both apps must share a signing key.
            abandonStartupPwFetch()
        }
    }

    private fun abandonStartupPwFetch() {
        awaitingStartupPwFetch = false
        pwFetchEntryName = null
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
        private const val STATE_AWAITING_PW_FETCH = "awaiting_pw_fetch"
    }
}
