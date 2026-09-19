package nu.staldal.mymail.ui.screen.setup

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nu.staldal.mymail.BuildConfig
import nu.staldal.mymail.auth.Credential
import nu.staldal.mymail.auth.CredentialStore
import nu.staldal.mymail.auth.MyPassClient
import nu.staldal.mymail.auth.MyPassCredentialSession
import nu.staldal.mymail.di.RetrofitHolder
import nu.staldal.mymail.repository.FolderRepository
import okhttp3.OkHttpClient
import retrofit2.HttpException
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import javax.inject.Inject
import javax.inject.Named

data class SetupUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val passwordTouched: Boolean = false,
    val usePw: Boolean = false,
    val pwEntryName: String = "",
    val pwAvailable: Boolean = false,
    val pwCredentialLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val navigateToFolders: Boolean = false,
) {
    /** In MyPass mode there is nothing to connect with until MyPass has handed over a credential. */
    val canConnect: Boolean
        get() = !isLoading && (!usePw || (pwEntryName.isNotBlank() && pwCredentialLoaded))
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    val credentialStore: CredentialStore,
    val retrofitHolder: RetrofitHolder,
    val folderRepository: FolderRepository,
    private val okHttpClient: OkHttpClient,
    private val myPassCredentialSession: MyPassCredentialSession,
    @ApplicationContext private val context: Context,
    @Named("plain") val prefs: SharedPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SetupUiState(
            serverUrl = credentialStore.serverUrl ?: "",
            username = credentialStore.username ?: "",
            usePw = credentialStore.usePw,
            pwEntryName = credentialStore.pwEntryName ?: "",
            pwAvailable = MyPassClient.isAvailable(context),
            pwCredentialLoaded = myPassCredentialSession.holdsCredentialFor(credentialStore.pwEntryName),
        )
    )
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            myPassCredentialSession.credential.collect { refreshPwCredentialLoaded() }
        }
    }

    /**
     * The session binds each credential to the entry it was fetched for, so editing the name shows
     * the credential as no longer loaded without discarding one the rest of the app is still using.
     */
    private fun refreshPwCredentialLoaded() {
        _uiState.update {
            it.copy(pwCredentialLoaded = myPassCredentialSession.holdsCredentialFor(it.pwEntryName))
        }
    }

    fun onServerUrlChange(value: String) {
        _uiState.update { it.copy(serverUrl = value, errorMessage = null) }
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, errorMessage = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, passwordTouched = true, errorMessage = null) }
    }

    fun onUsePwChange(value: Boolean) {
        _uiState.update { it.copy(usePw = value, errorMessage = null) }
    }

    fun onPwEntryNameChange(value: String) {
        _uiState.update { it.copy(pwEntryName = value, errorMessage = null) }
        refreshPwCredentialLoaded()
    }

    /** Result of the MyPass activity launched from the setup screen. */
    fun onPwCredential(credential: Credential?) {
        if (credential == null) {
            _uiState.update { it.copy(errorMessage = "pw returned no credential") }
            return
        }
        myPassCredentialSession.set(_uiState.value.pwEntryName.trim(), credential)
        _uiState.update { it.copy(errorMessage = null) }
        refreshPwCredentialLoaded()
    }

    fun onPwLaunchFailed() {
        _uiState.update {
            it.copy(errorMessage = "Could not open MyPass — check that both apps use the same signing key")
        }
    }

    fun connect() {
        viewModelScope.launch {
            val state = _uiState.value

            val urlError = validateUrl(state.serverUrl)
            if (urlError != null) {
                _uiState.update { it.copy(errorMessage = urlError) }
                return@launch
            }

            if (state.usePw) {
                if (state.pwEntryName.isBlank()) {
                    _uiState.update { it.copy(errorMessage = "pw entry name is required") }
                    return@launch
                }
                if (!state.pwCredentialLoaded) {
                    _uiState.update { it.copy(errorMessage = "Fetch the credential from MyPass first") }
                    return@launch
                }
            } else if (state.password.isEmpty() &&
                // An untouched, empty field keeps the stored password — unless there is none,
                // which is the case when switching away from pw mode.
                (state.passwordTouched || credentialStore.password == null)
            ) {
                _uiState.update { it.copy(errorMessage = "Password is required") }
                return@launch
            }

            val effectivePassword = if (!state.passwordTouched && state.password.isEmpty()) {
                credentialStore.password ?: ""
            } else {
                state.password
            }

            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            credentialStore.save(
                serverUrl = state.serverUrl,
                username = state.username,
                password = effectivePassword,
                usePw = state.usePw,
                pwEntryName = state.pwEntryName.trim(),
            )
            // Leaving pw mode: the fetched secret must not keep authenticating requests.
            if (!state.usePw) myPassCredentialSession.clear()
            retrofitHolder.rebuild(state.serverUrl, okHttpClient)

            val result = folderRepository.listFolders()
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, navigateToFolders = true) }
                },
                onFailure = { error ->
                    val message = mapError(error)
                    _uiState.update { it.copy(isLoading = false, errorMessage = message) }
                },
            )
        }
    }

    fun onNavigatedToFolders() {
        _uiState.update { it.copy(navigateToFolders = false) }
    }

    private fun validateUrl(url: String): String? {
        if (url.isBlank()) return "Server URL is required"
        val parsed = try {
            URL(url)
        } catch (_: MalformedURLException) {
            return "Invalid server URL"
        }
        val scheme = parsed.protocol
        if (scheme != "https" && scheme != "http") return "Invalid server URL"
        if (!BuildConfig.DEBUG && scheme != "https") return "HTTPS is required"
        return null
    }

    private fun mapError(error: Throwable): String = when (error) {
        is IOException -> "Could not connect — check the server URL and your network connection"
        is HttpException -> when (error.code()) {
            401 -> "Invalid username or password"
            404, 503 -> "Server not reachable — check the server URL"
            else -> "Connection failed (HTTP ${error.code()})"
        }
        else -> error.message ?: "Unknown error"
    }
}
