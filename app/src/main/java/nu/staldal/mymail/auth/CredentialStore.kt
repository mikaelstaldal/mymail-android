package nu.staldal.mymail.auth

import androidx.security.crypto.EncryptedSharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialStore @Inject constructor(
    private val prefs: EncryptedSharedPreferences,
) {
    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        private set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        private set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String?
        get() = prefs.getString(KEY_PASSWORD, null)
        private set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    fun save(serverUrl: String, username: String, password: String) {
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .apply()
    }

    fun hasCredentials(): Boolean =
        serverUrl != null && username != null && password != null

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
    }
}
