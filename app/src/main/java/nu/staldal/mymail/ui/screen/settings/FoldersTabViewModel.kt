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
import nu.staldal.mymail.model.Folder
import nu.staldal.mymail.repository.FolderRepository
import nu.staldal.mymail.repository.HttpStatusException
import javax.inject.Inject

sealed class FoldersTabUiState {
    data object Loading : FoldersTabUiState()
    data class Success(val folders: List<Folder>) : FoldersTabUiState()
    data class Error(val message: String) : FoldersTabUiState()
}

@HiltViewModel
class FoldersTabViewModel @Inject constructor(
    private val folderRepository: FolderRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<FoldersTabUiState>(FoldersTabUiState.Loading)
    val uiState: StateFlow<FoldersTabUiState> = _uiState.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.value = FoldersTabUiState.Loading
            folderRepository.listFolders().fold(
                onSuccess = { folders ->
                    _uiState.value = FoldersTabUiState.Success(folders)
                },
                onFailure = { error ->
                    _uiState.value = FoldersTabUiState.Error(error.message ?: "Failed to load folders")
                },
            )
        }
    }

    suspend fun createFolder(name: String): FolderDialogResult {
        return folderRepository.createFolder(name).fold(
            onSuccess = {
                load()
                FolderDialogResult.Success
            },
            onFailure = { error ->
                when {
                    error is HttpStatusException && error.statusCode == 409 ->
                        FolderDialogResult.InlineError(error.message ?: "Duplicate folder name")
                    else ->
                        FolderDialogResult.InlineError(error.message ?: "Failed to create folder")
                }
            },
        )
    }

    suspend fun renameFolder(id: Long, name: String): FolderDialogResult {
        return folderRepository.renameFolder(id, name).fold(
            onSuccess = {
                load()
                FolderDialogResult.Success
            },
            onFailure = { error ->
                when {
                    error is HttpStatusException && error.statusCode == 404 -> {
                        load()
                        _snackbarMessage.tryEmit(error.message ?: "Folder not found")
                        FolderDialogResult.CloseDialog
                    }
                    error is HttpStatusException && (error.statusCode == 409 || error.statusCode == 400) ->
                        FolderDialogResult.InlineError(error.message ?: "Error renaming folder")
                    else ->
                        FolderDialogResult.InlineError(error.message ?: "Failed to rename folder")
                }
            },
        )
    }

    suspend fun deleteFolder(id: Long): Boolean {
        return folderRepository.deleteFolder(id).fold(
            onSuccess = {
                load()
                true
            },
            onFailure = { error ->
                _snackbarMessage.tryEmit(error.message ?: "Failed to delete folder")
                false
            },
        )
    }
}

sealed class FolderDialogResult {
    data object Success : FolderDialogResult()
    data object CloseDialog : FolderDialogResult()
    data class InlineError(val message: String) : FolderDialogResult()
}
