package nu.staldal.mymail.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nu.staldal.mymail.model.SpamFilterSettings
import nu.staldal.mymail.repository.HttpStatusException
import nu.staldal.mymail.repository.SpamFilterRepository
import javax.inject.Inject

sealed class SpamUiState {
    data object Loading : SpamUiState()
    data class Loaded(
        val enabled: Boolean,
        val scoreHeader: String,
        val scoreThreshold: String,
        val isSaving: Boolean = false,
        val inlineError: String? = null,
    ) : SpamUiState()
    data class Error(val message: String) : SpamUiState()
}

@HiltViewModel
class SpamViewModel @Inject constructor(
    private val spamFilterRepository: SpamFilterRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SpamUiState>(SpamUiState.Loading)
    val uiState: StateFlow<SpamUiState> = _uiState.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.value = SpamUiState.Loading
            spamFilterRepository.getSettings().fold(
                onSuccess = { settings ->
                    _uiState.value = SpamUiState.Loaded(
                        enabled = settings.enabled,
                        scoreHeader = settings.scoreHeader,
                        scoreThreshold = settings.scoreThreshold.stripTrailingZeros().toPlainString(),
                    )
                },
                onFailure = { error ->
                    _uiState.value = SpamUiState.Error(error.message ?: "Failed to load spam filter settings")
                },
            )
        }
    }

    fun onEnabledChange(enabled: Boolean) {
        val current = _uiState.value as? SpamUiState.Loaded ?: return
        _uiState.value = current.copy(enabled = enabled, inlineError = null)
    }

    fun onScoreHeaderChange(value: String) {
        val current = _uiState.value as? SpamUiState.Loaded ?: return
        _uiState.value = current.copy(scoreHeader = value, inlineError = null)
    }

    fun onScoreThresholdChange(value: String) {
        val current = _uiState.value as? SpamUiState.Loaded ?: return
        _uiState.value = current.copy(scoreThreshold = value, inlineError = null)
    }

    fun save() {
        val current = _uiState.value as? SpamUiState.Loaded ?: return
        val threshold = current.scoreThreshold.toDoubleOrNull()
        if (threshold == null || threshold < 0) {
            _uiState.value = current.copy(inlineError = "Score threshold must be a non-negative number")
            return
        }
        viewModelScope.launch {
            _uiState.value = current.copy(isSaving = true, inlineError = null)
            val settings = SpamFilterSettings(
                enabled = current.enabled,
                scoreHeader = current.scoreHeader,
                scoreThreshold = threshold.toBigDecimal(),
            )
            spamFilterRepository.updateSettings(settings).fold(
                onSuccess = {
                    _uiState.value = current.copy(isSaving = false, inlineError = null)
                    _snackbarMessage.tryEmit("Settings saved")
                },
                onFailure = { error ->
                    val isBadRequest = error is HttpStatusException && error.statusCode == 400
                    if (isBadRequest) {
                        _uiState.value = current.copy(
                            isSaving = false,
                            inlineError = error.message ?: "Invalid settings",
                        )
                    } else {
                        _uiState.value = current.copy(isSaving = false)
                        _snackbarMessage.tryEmit(error.message ?: "Failed to save settings")
                    }
                },
            )
        }
    }
}
