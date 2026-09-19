package nu.staldal.mymail.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Credentials fetched from pw live only for the lifetime of this app process: they are never
 * persisted, logged or placed in saved instance state. A process restart therefore requires
 * another trip through the pw activity.
 *
 * What is held is a [FetchedCredential], not a bare [Credential]: a credential fetched for one pw
 * entry must never authenticate a server configured for another, so readers name the entry they
 * want.
 */
@Singleton
class MyPassCredentialSession @Inject constructor() {
    private val _credential = MutableStateFlow<FetchedCredential?>(null)
    val credential: StateFlow<FetchedCredential?> = _credential.asStateFlow()

    val current: FetchedCredential? get() = _credential.value

    /**
     * Whether pw has already been asked in this process, however that ended. Process-scoped on
     * purpose: it must **not** be derived from saved instance state, which Android also restores
     * after a process kill — precisely the case in which the memory-only credential is gone and pw
     * has to be asked again.
     */
    var fetchAttempted: Boolean = false
        private set

    /** Call before launching pw, so that a failed launch still counts as having asked. */
    fun markFetchAttempted() {
        fetchAttempted = true
    }

    fun set(entryName: String, credential: Credential) {
        _credential.value = FetchedCredential(entryName, credential)
    }

    /** The credential for [entryName], or `null` when this process holds one for another entry. */
    fun credentialFor(entryName: String?): Credential? =
        current?.takeIf { it.isFor(entryName) }?.credential

    fun holdsCredentialFor(entryName: String?): Boolean = credentialFor(entryName) != null

    fun clear() {
        _credential.value = null
        fetchAttempted = false
    }
}
