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
 */
@Singleton
class PwCredentialSession @Inject constructor() {
    private val _credential = MutableStateFlow<Credential?>(null)
    val credential: StateFlow<Credential?> = _credential.asStateFlow()

    val current: Credential? get() = _credential.value

    fun set(credential: Credential) {
        _credential.value = credential
    }

    fun clear() {
        _credential.value = null
    }
}
