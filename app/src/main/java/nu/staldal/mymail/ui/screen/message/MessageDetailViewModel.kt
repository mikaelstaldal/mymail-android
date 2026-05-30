package nu.staldal.mymail.ui.screen.message

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nu.staldal.mymail.model.AttachmentMeta
import nu.staldal.mymail.model.Folder
import nu.staldal.mymail.model.MessageDetail
import nu.staldal.mymail.repository.DraftRepository
import nu.staldal.mymail.repository.FolderRepository
import nu.staldal.mymail.repository.HttpStatusException
import nu.staldal.mymail.repository.MessageRepository
import nu.staldal.mymail.repository.ScheduledRepository
import nu.staldal.mymail.repository.ThreadResponse
import java.io.File
import javax.inject.Inject

sealed class MessageDetailUiState {
    data object Loading : MessageDetailUiState()
    data class Success(val message: MessageDetail) : MessageDetailUiState()
    data class Error(val message: String, val is404: Boolean = false) : MessageDetailUiState()
}

sealed class ThreadUiState {
    data object Loading : ThreadUiState()
    data class Success(val thread: ThreadResponse) : ThreadUiState()
    data class Error(val message: String) : ThreadUiState()
}

sealed class AttachmentState {
    data object Idle : AttachmentState()
    data object Downloading : AttachmentState()
    data class Ready(val uri: Uri) : AttachmentState()
    data class BlockedMimeType(val error: String) : AttachmentState()
    data class Error(val message: String) : AttachmentState()
}

private val BLOCKED_MIME_TYPES = setOf(
    "text/html",
    "application/xhtml+xml",
    "application/x-sh",
    "application/x-shellscript",
    "application/x-executable",
    "application/vnd.android.package-archive",
)

private const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024

@HiltViewModel
class MessageDetailViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val draftRepository: DraftRepository,
    private val scheduledRepository: ScheduledRepository,
    private val folderRepository: FolderRepository,
    application: Application,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<MessageDetailUiState>(MessageDetailUiState.Loading)
    val uiState: StateFlow<MessageDetailUiState> = _uiState.asStateFlow()

    private val _threadState = MutableStateFlow<ThreadUiState>(ThreadUiState.Loading)
    val threadState: StateFlow<ThreadUiState> = _threadState.asStateFlow()

    private val _attachmentStates = MutableStateFlow<Map<Long, AttachmentState>>(emptyMap())
    val attachmentStates: StateFlow<Map<Long, AttachmentState>> = _attachmentStates.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    private val cachedAttachmentFiles = mutableListOf<File>()

    fun loadMessage(messageId: Long) {
        viewModelScope.launch {
            _uiState.value = MessageDetailUiState.Loading
            _threadState.value = ThreadUiState.Loading

            coroutineScope {
                val messageDeferred = async { messageRepository.getMessage(messageId) }
                val threadDeferred = async { messageRepository.getThread(messageId) }

                val messageResult = messageDeferred.await()
                val threadResult = threadDeferred.await()

                messageResult.fold(
                    onSuccess = { detail ->
                        _uiState.value = MessageDetailUiState.Success(detail)
                        if (detail.sendError != null) {
                            Log.w("MessageDetail", "send_error for message $messageId: ${detail.sendError}")
                        }
                        if (!detail.read) {
                            markReadSilently(messageId)
                        }
                    },
                    onFailure = { error ->
                        val is404 = (error as? HttpStatusException)?.statusCode == 404
                        _uiState.value = MessageDetailUiState.Error(
                            message = error.message ?: "Failed to load message",
                            is404 = is404,
                        )
                    },
                )

                threadResult.fold(
                    onSuccess = { thread ->
                        _threadState.value = ThreadUiState.Success(thread)
                    },
                    onFailure = { error ->
                        _threadState.value = ThreadUiState.Error(
                            error.message ?: "Failed to load thread",
                        )
                    },
                )
            }
        }
    }

    private fun markReadSilently(messageId: Long) {
        viewModelScope.launch {
            messageRepository.updateMessage(messageId, read = true).fold(
                onSuccess = { summary ->
                    val current = _uiState.value
                    if (current is MessageDetailUiState.Success) {
                        _uiState.value = current.copy(
                            message = current.message.copy(read = summary.read),
                        )
                    }
                },
                onFailure = { /* silently ignore */ },
            )
        }
    }

    fun reloadThread(messageId: Long) {
        viewModelScope.launch {
            _threadState.value = ThreadUiState.Loading
            messageRepository.getThread(messageId).fold(
                onSuccess = { _threadState.value = ThreadUiState.Success(it) },
                onFailure = { _threadState.value = ThreadUiState.Error(it.message ?: "Failed to load thread") },
            )
        }
    }

    fun loadFolders() {
        viewModelScope.launch {
            folderRepository.listFolders().fold(
                onSuccess = { _folders.value = it },
                onFailure = { /* non-critical */ },
            )
        }
    }

    fun markJunk(messageId: Long, onPopBack: () -> Unit) {
        viewModelScope.launch {
            messageRepository.markJunk(messageId).fold(
                onSuccess = { onPopBack() },
                onFailure = { error ->
                    _snackbarMessage.emit(error.message ?: "Failed to mark as junk")
                },
            )
        }
    }

    fun markNotJunk(messageId: Long, onPopBack: () -> Unit) {
        viewModelScope.launch {
            messageRepository.markNotJunk(messageId).fold(
                onSuccess = { onPopBack() },
                onFailure = { error ->
                    _snackbarMessage.emit(error.message ?: "Failed to mark as not junk")
                },
            )
        }
    }

    fun deleteMessage(messageId: Long, onPopBack: () -> Unit) {
        viewModelScope.launch {
            messageRepository.deleteMessage(messageId).fold(
                onSuccess = { onPopBack() },
                onFailure = { error ->
                    _snackbarMessage.emit(error.message ?: "Failed to delete message")
                },
            )
        }
    }

    fun moveMessage(messageId: Long, targetFolderId: Long, onPopBack: () -> Unit) {
        viewModelScope.launch {
            messageRepository.moveMessages(listOf(messageId), targetFolderId).fold(
                onSuccess = { onPopBack() },
                onFailure = { error ->
                    _snackbarMessage.emit(error.message ?: "Failed to move message")
                },
            )
        }
    }

    fun discardDraft(messageId: Long, onPopBack: () -> Unit) {
        viewModelScope.launch {
            draftRepository.deleteDraft(messageId).fold(
                onSuccess = { onPopBack() },
                onFailure = { error ->
                    val statusCode = (error as? HttpStatusException)?.statusCode
                    if (statusCode == 404) {
                        _snackbarMessage.emit("Draft already discarded")
                        onPopBack()
                    } else {
                        _snackbarMessage.emit(error.message ?: "Failed to discard draft")
                    }
                },
            )
        }
    }

    fun cancelScheduled(messageId: Long, onPopBack: () -> Unit) {
        viewModelScope.launch {
            scheduledRepository.cancelScheduled(messageId).fold(
                onSuccess = { onPopBack() },
                onFailure = { error ->
                    val statusCode = (error as? HttpStatusException)?.statusCode
                    if (statusCode == 404) {
                        _snackbarMessage.emit("Message was already processed")
                        onPopBack()
                    } else {
                        _snackbarMessage.emit(error.message ?: "Failed to cancel scheduled send")
                    }
                },
            )
        }
    }

    fun cancelSnooze(messageId: Long, onPopBack: () -> Unit) {
        viewModelScope.launch {
            messageRepository.cancelSnooze(messageId).fold(
                onSuccess = { onPopBack() },
                onFailure = { error ->
                    _snackbarMessage.emit(error.message ?: error.toString())
                    onPopBack()
                },
            )
        }
    }

    fun downloadAttachment(attachmentId: Long, meta: AttachmentMeta) {
        viewModelScope.launch {
            _attachmentStates.value = _attachmentStates.value + (attachmentId to AttachmentState.Downloading)

            draftRepository.downloadAttachment(attachmentId).fold(
                onSuccess = { body ->
                    val contentLength = body.contentLength()
                    if (contentLength < 0 || contentLength > MAX_ATTACHMENT_BYTES) {
                        body.close()
                        _attachmentStates.value = _attachmentStates.value + (attachmentId to
                            AttachmentState.Error("Attachment too large to download"))
                        return@fold
                    }

                    val rawMimeType = meta.contentType.substringBefore(";").trim().lowercase()
                    if (rawMimeType in BLOCKED_MIME_TYPES) {
                        body.close()
                        _attachmentStates.value = _attachmentStates.value + (attachmentId to
                            AttachmentState.BlockedMimeType("This file type cannot be opened for security reasons"))
                        return@fold
                    }

                    try {
                        val attachDir = File(getApplication<Application>().cacheDir, "attachments")
                        attachDir.mkdirs()
                        val file = File(attachDir, meta.filename)
                        file.outputStream().use { out ->
                            body.byteStream().copyTo(out)
                        }
                        cachedAttachmentFiles.add(file)

                        val uri = FileProvider.getUriForFile(
                            getApplication(),
                            "nu.staldal.mymail.fileprovider",
                            file,
                        )
                        _attachmentStates.value = _attachmentStates.value + (attachmentId to AttachmentState.Ready(uri))
                    } catch (e: Exception) {
                        _attachmentStates.value = _attachmentStates.value + (attachmentId to
                            AttachmentState.Error(e.message ?: "Failed to save attachment"))
                    }
                },
                onFailure = { error ->
                    _attachmentStates.value = _attachmentStates.value + (attachmentId to
                        AttachmentState.Error(error.message ?: "Failed to download attachment"))
                },
            )
        }
    }

    fun deleteCachedAttachments() {
        for (file in cachedAttachmentFiles) {
            file.delete()
        }
        cachedAttachmentFiles.clear()
    }

    override fun onCleared() {
        super.onCleared()
        deleteCachedAttachments()
    }
}
