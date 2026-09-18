package nu.staldal.mymail.auth

/**
 * The stored configuration that decides how — and whether — a request can authenticate.
 *
 * Kept as a plain value, separate from [CredentialStore]'s encrypted storage, so that the rules
 * around pw mode can be reasoned about and unit-tested on their own. The pw secret is never part
 * of it: it is passed in from [PwCredentialSession] at each call.
 */
data class AuthConfig(
    val serverUrl: String? = null,
    val storedUsername: String? = null,
    val storedPassword: String? = null,
    val usePw: Boolean = false,
    val pwEntryName: String? = null,
) {
    private val pwConfigured: Boolean get() = usePw && !pwEntryName.isNullOrBlank()

    /**
     * The credential to authenticate with, or `null` when none is available. In pw mode only a
     * credential fetched for the configured entry counts: one fetched for some other entry belongs
     * to another site and must not be sent to this server.
     */
    fun activeCredential(pwCredential: FetchedCredential?): Credential? = if (usePw) {
        if (pwConfigured && pwCredential?.isFor(pwEntryName) == true) pwCredential.credential else null
    } else {
        if (storedUsername != null && storedPassword != null) {
            Credential(storedUsername, storedPassword)
        } else {
            null
        }
    }

    /** Whether the app can talk to the server right now. */
    fun isConfigured(pwCredential: FetchedCredential?): Boolean =
        serverUrl != null && activeCredential(pwCredential) != null

    /**
     * True when the app is configured for pw but this process has no credential for the configured
     * entry, so the pw activity has to be launched before anything can be fetched from the server.
     */
    fun needsPwFetch(pwCredential: FetchedCredential?): Boolean =
        serverUrl != null && pwConfigured && pwCredential?.isFor(pwEntryName) != true

    // A data class would print the stored password.
    override fun toString(): String =
        "AuthConfig(serverUrl=$serverUrl, usePw=$usePw, pwEntryName=$pwEntryName, credentials=<redacted>)"
}
