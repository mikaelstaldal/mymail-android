package nu.staldal.mymail.ui.screen.folder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nu.staldal.mymail.model.Folder
import nu.staldal.mymail.repository.FolderRepository
import javax.inject.Inject

sealed class FolderListUiState {
    data object Loading : FolderListUiState()
    data class Success(val folders: List<Folder>) : FolderListUiState()
    data class Error(val message: String) : FolderListUiState()
}

@HiltViewModel
class FolderListViewModel @Inject constructor(
    val folderRepository: FolderRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<FolderListUiState>(FolderListUiState.Loading)
    val uiState: StateFlow<FolderListUiState> = _uiState.asStateFlow()

    fun loadFolders() {
        viewModelScope.launch {
            _uiState.value = FolderListUiState.Loading
            val result = folderRepository.listFolders()
            result.fold(
                onSuccess = { folders ->
                    _uiState.value = FolderListUiState.Success(folders)
                },
                onFailure = { error ->
                    _uiState.value = FolderListUiState.Error(
                        error.message ?: "Failed to load folders"
                    )
                },
            )
        }
    }
}
