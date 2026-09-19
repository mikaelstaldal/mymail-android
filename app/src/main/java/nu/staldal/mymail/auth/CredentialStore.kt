package nu.staldal.mymail.auth

import androidx.security.crypto.EncryptedSharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialStore @Inject constructor(
    private val prefs: EncryptedSharedPreferences,
    private val myPassCredentialSession: MyPassCredentialSession,
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

    /** Whether the username and password come from pw instead of being stored here. */
    val usePw: Boolean
        get() = prefs.getBoolean(KEY_USE_PW, false)

    /** Exact, case-sensitive pw entry name. Only the name is persisted, never the secret. */
    val pwEntryName: String?
        get() = prefs.getString(KEY_PW_ENTRY_NAME, null)

    private val config: AuthConfig
        get() = AuthConfig(
            serverUrl = serverUrl,
            storedUsername = username,
            storedPassword = password,
            usePw = usePw,
            pwEntryName = pwEntryName,
        )

    /**
     * The credential to authenticate with right now, or `null` when none is available — which in
     * pw mode is the normal state until the user has completed the pw activity in this process.
     */
    val activeCredential: Credential?
        get() = config.activeCredential(myPassCredentialSession.current)

    fun save(
        serverUrl: String,
        username: String,
        password: String,
        usePw: Boolean = false,
        pwEntryName: String = "",
    ) {
        prefs.edit().apply {
            putString(KEY_SERVER_URL, serverUrl)
            putBoolean(KEY_USE_PW, usePw)
            if (usePw) {
                putString(KEY_PW_ENTRY_NAME, pwEntryName)
                remove(KEY_USERNAME)
                remove(KEY_PASSWORD)
            } else {
                remove(KEY_PW_ENTRY_NAME)
                putString(KEY_USERNAME, username)
                putString(KEY_PASSWORD, password)
            }
        }.apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_USE_PW)
            .remove(KEY_PW_ENTRY_NAME)
            .apply()
        myPassCredentialSession.clear()
    }

    fun hasCredentials(): Boolean = config.isConfigured(myPassCredentialSession.current)

    /**
     * True when the app is configured for pw but this process has no credential yet, so the pw
     * activity has to be launched before anything can be fetched from the server.
     */
    fun needsPwFetch(): Boolean = config.needsPwFetch(myPassCredentialSession.current)

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_USE_PW = "use_pw"
        const val KEY_PW_ENTRY_NAME = "pw_entry_name"
    }
}
