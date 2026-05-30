package nu.staldal.mymail

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import androidx.hilt.work.HiltWorkerFactory

@HiltAndroidApp
class MyMailApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    val isAppInForeground = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_NEW_MAIL,
            getString(R.string.notification_channel_new_mail_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isAppInForeground.set(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                isAppInForeground.set(false)
            }
        })
    }

    companion object {
        const val NOTIFICATION_CHANNEL_NEW_MAIL = "mymail_new_mail"
    }
}
