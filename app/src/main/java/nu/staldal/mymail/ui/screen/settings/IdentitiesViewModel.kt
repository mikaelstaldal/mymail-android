package nu.staldal.mymail.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nu.staldal.mymail.model.Identity
import nu.staldal.mymail.repository.IdentityRepository
import javax.inject.Inject

sealed class IdentitiesUiState {
    data object Loading : IdentitiesUiState()
    data class Success(val identities: List<Identity>) : IdentitiesUiState()
    data class Error(val message: String) : IdentitiesUiState()
}

@HiltViewModel
class IdentitiesViewModel @Inject constructor(
    private val identityRepository: IdentityRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<IdentitiesUiState>(IdentitiesUiState.Loading)
    val uiState: StateFlow<IdentitiesUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.value = IdentitiesUiState.Loading
            identityRepository.listIdentities().fold(
                onSuccess = { identities ->
                    _uiState.value = IdentitiesUiState.Success(identities)
                },
                onFailure = { error ->
                    _uiState.value = IdentitiesUiState.Error(error.message ?: "Failed to load identities")
                },
            )
        }
    }
}
