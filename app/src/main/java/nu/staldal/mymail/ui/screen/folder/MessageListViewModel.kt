package nu.staldal.mymail.ui.screen.folder

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nu.staldal.mymail.model.Folder
import nu.staldal.mymail.model.MessageSummary
import nu.staldal.mymail.repository.FolderRepository
import nu.staldal.mymail.repository.HttpStatusException
import nu.staldal.mymail.repository.MessageRepository
import javax.inject.Inject
import javax.inject.Named

sealed class MessageListUiState {
    data object Loading : MessageListUiState()
    data class Success(val messages: List<MessageSummary>) : MessageListUiState()
    data class Error(val message: String, val is404: Boolean = false) : MessageListUiState()
}

@HiltViewModel
class MessageListViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val folderRepository: FolderRepository,
    @Named("plain") private val prefs: SharedPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MessageListUiState>(MessageListUiState.Loading)
    val uiState: StateFlow<MessageListUiState> = _uiState.asStateFlow()

    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _currentFolderId = MutableStateFlow(0L)
    val currentFolderId: StateFlow<Long> = _currentFolderId.asStateFlow()

    private val _folderName = MutableStateFlow("")
    val folderName: StateFlow<String> = _folderName.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    private val _isLoadingNextPage = MutableStateFlow(false)
    val isLoadingNextPage: StateFlow<Boolean> = _isLoadingNextPage.asStateFlow()

    private var currentOffset = 0
    private var canLoadMore = true

    val messageDensity: MessageDensity
        get() {
            val value = prefs.getString("message_list_density", "Normal") ?: "Normal"
            return MessageDensity.entries.firstOrNull { it.name == value } ?: MessageDensity.Normal
        }

    fun loadMessages(folderId: Long) {
        _currentFolderId.value = folderId
        currentOffset = 0
        canLoadMore = true
        viewModelScope.launch {
            _uiState.value = MessageListUiState.Loading
            loadFolderNameAndMessages(folderId, offset = 0)
        }
    }

    fun refresh() {
        val folderId = _currentFolderId.value
        currentOffset = 0
        canLoadMore = true
        viewModelScope.launch {
            _uiState.value = MessageListUiState.Loading
            loadFolderNameAndMessages(folderId, offset = 0)
        }
    }

    fun loadNextPage() {
        if (!canLoadMore || _isLoadingNextPage.value) return
        val folderId = _currentFolderId.value
        _isLoadingNextPage.value = true
        viewModelScope.launch {
            val result = messageRepository.listMessages(folderId, limit = 50, offset = currentOffset)
            result.fold(
                onSuccess = { newMessages ->
                    if (newMessages.size < 50) {
                        canLoadMore = false
                    }
                    if (newMessages.isNotEmpty()) {
                        currentOffset += newMessages.size
                        val currentState = _uiState.value
                        if (currentState is MessageListUiState.Success) {
                            _uiState.value = currentState.copy(
                                messages = currentState.messages + newMessages
                            )
                        }
                    }
                },
                onFailure = { error ->
                    val message = error.message ?: "Failed to load more messages"
                    _snackbarMessage.emit(message)
                    scheduleRetryNextPage(folderId)
                },
            )
            _isLoadingNextPage.value = false
        }
    }

    private fun scheduleRetryNextPage(folderId: Long) {
        viewModelScope.launch {
            delay(2000)
            if (_currentFolderId.value == folderId) {
                loadNextPage()
            }
        }
    }

    fun enterMultiSelect(messageId: Long) {
        _isMultiSelectMode.value = true
        _selectedIds.value = setOf(messageId)
    }

    fun exitMultiSelect() {
        _isMultiSelectMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelection(messageId: Long) {
        _selectedIds.update { current ->
            if (messageId in current) current - messageId else current + messageId
        }
    }

    fun markReadUnread() {
        val idsSet = _selectedIds.value
        if (idsSet.isEmpty()) return
        val idsList = idsSet.toList()

        val currentMessages = (_uiState.value as? MessageListUiState.Success)?.messages ?: return
        val hasUnread = currentMessages.any { it.id.toLong() in idsSet && !it.read }
        val markAsRead = hasUnread

        viewModelScope.launch {
            val result = messageRepository.bulkUpdateMessages(idsList, read = markAsRead)
            result.fold(
                onSuccess = {
                    val currentState = _uiState.value
                    if (currentState is MessageListUiState.Success) {
                        _uiState.value = currentState.copy(
                            messages = currentState.messages.map { msg ->
                                if (msg.id.toLong() in idsSet) {
                                    msg.copy(read = markAsRead)
                                } else {
                                    msg
                                }
                            }
                        )
                    }
                    exitMultiSelect()
                },
                onFailure = { error ->
                    _snackbarMessage.emit(error.message ?: "Failed to update messages")
                },
            )
        }
    }

    fun moveSelectedToFolder(targetFolderId: Long) {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            val result = messageRepository.moveMessages(ids, targetFolderId)
            result.fold(
                onSuccess = {
                    val currentState = _uiState.value
                    if (currentState is MessageListUiState.Success) {
                        _uiState.value = currentState.copy(
                            messages = currentState.messages.filter { msg ->
                                msg.id.toLong() !in ids
                            }
                        )
                    }
                    exitMultiSelect()
                },
                onFailure = { error ->
                    _snackbarMessage.emit(error.message ?: "Failed to move messages")
                },
            )
        }
    }

    fun deleteSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            val result = messageRepository.bulkDeleteMessages(ids)
            result.fold(
                onSuccess = {
                    val currentState = _uiState.value
                    if (currentState is MessageListUiState.Success) {
                        _uiState.value = currentState.copy(
                            messages = currentState.messages.filter { msg ->
                                msg.id.toLong() !in ids
                            }
                        )
                    }
                    exitMultiSelect()
                },
                onFailure = { error ->
                    _snackbarMessage.emit(error.message ?: "Failed to delete messages")
                },
            )
        }
    }

    fun markAllRead(folderId: Long) {
        viewModelScope.launch {
            val result = folderRepository.markAllRead(folderId)
            result.fold(
                onSuccess = {
                    val currentState = _uiState.value
                    if (currentState is MessageListUiState.Success) {
                        _uiState.value = currentState.copy(
                            messages = currentState.messages.map { it.copy(read = true) }
                        )
                    }
                },
                onFailure = { error ->
                    _snackbarMessage.emit(error.message ?: "Failed to mark all as read")
                },
            )
        }
    }

    fun deleteAllMessages(folderId: Long) {
        viewModelScope.launch {
            val result = folderRepository.deleteAllMessages(folderId)
            result.fold(
                onSuccess = {
                    currentOffset = 0
                    canLoadMore = true
                    loadFolderNameAndMessages(folderId, offset = 0)
                },
                onFailure = { error ->
                    _snackbarMessage.emit(error.message ?: "Failed to empty folder")
                },
            )
        }
    }

    fun removeMessage(messageId: Long) {
        val currentState = _uiState.value
        if (currentState is MessageListUiState.Success) {
            _uiState.value = currentState.copy(
                messages = currentState.messages.filter { it.id.toLong() != messageId }
            )
        }
    }

    fun loadFolders() {
        viewModelScope.launch {
            val result = folderRepository.listFolders()
            result.fold(
                onSuccess = { folderList ->
                    _folders.value = folderList
                },
                onFailure = { /* non-critical, ignore silently */ },
            )
        }
    }

    private suspend fun loadFolderNameAndMessages(folderId: Long, offset: Int) {
        val foldersResult = folderRepository.listFolders()
        foldersResult.fold(
            onSuccess = { folderList ->
                _folders.value = folderList
                val folder = folderList.firstOrNull { it.id.toLong() == folderId }
                if (folder != null) {
                    _folderName.value = folder.name
                }
            },
            onFailure = { /* non-critical */ },
        )

        val result = messageRepository.listMessages(folderId, limit = 50, offset = offset)
        result.fold(
            onSuccess = { messages ->
                if (messages.size < 50) {
                    canLoadMore = false
                }
                currentOffset = messages.size
                _uiState.value = MessageListUiState.Success(messages)
            },
            onFailure = { error ->
                val is404 = (error as? HttpStatusException)?.statusCode == 404
                val message = error.message ?: "Failed to load messages"
                _uiState.value = MessageListUiState.Error(message, is404 = is404)
                if (!is404) {
                    _snackbarMessage.emit(message)
                    scheduleRetryLoad(folderId)
                }
            },
        )
    }

    private fun scheduleRetryLoad(folderId: Long) {
        viewModelScope.launch {
            delay(2000)
            if (_currentFolderId.value == folderId && _uiState.value is MessageListUiState.Error) {
                loadFolderNameAndMessages(folderId, offset = 0)
            }
        }
    }
}

enum class MessageDensity {
    Compact,
    Normal,
    Relaxed,
}
