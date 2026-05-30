package nu.staldal.mymail.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nu.staldal.mymail.model.Contact
import nu.staldal.mymail.repository.ContactRepository
import nu.staldal.mymail.repository.HttpStatusException
import javax.inject.Inject

data class ContactsListState(
    val contacts: List<Contact> = emptyList(),
    val isLoadingInitial: Boolean = false,
    val isLoadingMore: Boolean = false,
    val initialError: String? = null,
    val pageError: String? = null,
    val canLoadMore: Boolean = true,
    val query: String = "",
    val currentOffset: Int = 0,
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
) : ViewModel() {

    private val _listState = MutableStateFlow(ContactsListState(isLoadingInitial = true))
    val listState: StateFlow<ContactsListState> = _listState.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    private var searchDebounceJob: Job? = null

    fun loadInitial() {
        val query = _listState.value.query
        viewModelScope.launch {
            _listState.value = ContactsListState(isLoadingInitial = true, query = query)
            val q = query.ifBlank { null }
            contactRepository.listContacts(q = q, limit = 50, offset = 0).fold(
                onSuccess = { result ->
                    _listState.value = ContactsListState(
                        contacts = result.items,
                        canLoadMore = result.items.size >= 50,
                        currentOffset = result.items.size,
                        query = query,
                    )
                },
                onFailure = { error ->
                    _listState.value = ContactsListState(
                        initialError = error.message ?: "Failed to load contacts",
                        query = query,
                    )
                },
            )
        }
    }

    fun loadNextPage() {
        val state = _listState.value
        if (!state.canLoadMore || state.isLoadingMore || state.isLoadingInitial) return
        viewModelScope.launch {
            _listState.value = state.copy(isLoadingMore = true, pageError = null)
            val q = state.query.ifBlank { null }
            contactRepository.listContacts(q = q, limit = 50, offset = state.currentOffset).fold(
                onSuccess = { result ->
                    _listState.value = state.copy(
                        contacts = state.contacts + result.items,
                        canLoadMore = result.items.size >= 50,
                        currentOffset = state.currentOffset + result.items.size,
                        isLoadingMore = false,
                        pageError = null,
                    )
                },
                onFailure = { error ->
                    _listState.value = state.copy(
                        isLoadingMore = false,
                        pageError = error.message ?: "Failed to load more contacts",
                    )
                },
            )
        }
    }

    fun onQueryChange(query: String) {
        _listState.value = _listState.value.copy(query = query)
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            delay(300)
            loadInitial()
        }
    }

    suspend fun createContact(address: String, name: String): ContactDialogResult {
        return contactRepository.createContact(address = address, name = name.ifBlank { null }).fold(
            onSuccess = {
                loadInitial()
                ContactDialogResult.Success
            },
            onFailure = { error ->
                when {
                    error is HttpStatusException && error.statusCode == 409 ->
                        ContactDialogResult.InlineError(error.message ?: "Duplicate address")
                    error is HttpStatusException && error.statusCode == 400 ->
                        ContactDialogResult.InlineError(error.message ?: "Invalid contact")
                    else ->
                        ContactDialogResult.InlineError(error.message ?: "Failed to create contact")
                }
            },
        )
    }

    suspend fun updateContact(id: Long, address: String, name: String): ContactDialogResult {
        return contactRepository.updateContact(id = id, address = address, name = name).fold(
            onSuccess = {
                loadInitial()
                ContactDialogResult.Success
            },
            onFailure = { error ->
                when {
                    error is HttpStatusException && error.statusCode == 404 -> {
                        loadInitial()
                        _snackbarMessage.tryEmit(error.message ?: "Contact not found")
                        ContactDialogResult.CloseDialog
                    }
                    error is HttpStatusException && error.statusCode == 409 ->
                        ContactDialogResult.InlineError(error.message ?: "Duplicate address")
                    error is HttpStatusException && error.statusCode == 400 ->
                        ContactDialogResult.InlineError(error.message ?: "Invalid contact")
                    else ->
                        ContactDialogResult.InlineError(error.message ?: "Failed to update contact")
                }
            },
        )
    }

    suspend fun deleteContact(id: Long) {
        contactRepository.deleteContact(id).fold(
            onSuccess = {
                loadInitial()
            },
            onFailure = { error ->
                _snackbarMessage.tryEmit(error.message ?: "Failed to delete contact")
                loadInitial()
            },
        )
    }
}

sealed class ContactDialogResult {
    data object Success : ContactDialogResult()
    data object CloseDialog : ContactDialogResult()
    data class InlineError(val message: String) : ContactDialogResult()
}
