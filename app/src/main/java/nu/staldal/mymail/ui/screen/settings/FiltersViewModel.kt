package nu.staldal.mymail.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nu.staldal.mymail.model.Filter
import nu.staldal.mymail.repository.FilterRepository
import javax.inject.Inject

sealed class FiltersUiState {
    data object Loading : FiltersUiState()
    data class Success(val filters: List<Filter>) : FiltersUiState()
    data class Error(val message: String) : FiltersUiState()
}

@HiltViewModel
class FiltersViewModel @Inject constructor(
    private val filterRepository: FilterRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<FiltersUiState>(FiltersUiState.Loading)
    val uiState: StateFlow<FiltersUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.value = FiltersUiState.Loading
            filterRepository.listFilters().fold(
                onSuccess = { filters ->
                    _uiState.value = FiltersUiState.Success(filters)
                },
                onFailure = { error ->
                    _uiState.value = FiltersUiState.Error(error.message ?: "Failed to load filters")
                },
            )
        }
    }
}
