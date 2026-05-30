package nu.staldal.mymail.ui.screen.setup

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nu.staldal.mymail.BuildConfig
import nu.staldal.mymail.auth.CredentialStore
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
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val navigateToFolders: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    val credentialStore: CredentialStore,
    val retrofitHolder: RetrofitHolder,
    val folderRepository: FolderRepository,
    private val okHttpClient: OkHttpClient,
    @Named("plain") val prefs: SharedPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SetupUiState(
            serverUrl = credentialStore.serverUrl ?: "",
            username = credentialStore.username ?: "",
        )
    )
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun onServerUrlChange(value: String) {
        _uiState.update { it.copy(serverUrl = value, errorMessage = null) }
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, errorMessage = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, passwordTouched = true, errorMessage = null) }
    }

    fun connect() {
        viewModelScope.launch {
            val state = _uiState.value

            val urlError = validateUrl(state.serverUrl)
            if (urlError != null) {
                _uiState.update { it.copy(errorMessage = urlError) }
                return@launch
            }

            if (state.passwordTouched && state.password.isEmpty()) {
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
            )
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
