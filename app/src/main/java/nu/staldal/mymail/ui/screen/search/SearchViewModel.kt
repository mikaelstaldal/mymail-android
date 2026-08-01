package nu.staldal.mymail.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nu.staldal.mymail.model.Folder
import nu.staldal.mymail.repository.FolderRepository
import nu.staldal.mymail.repository.MessageRepository
import nu.staldal.mymail.repository.MessageSummaryWithSnippet
import java.time.LocalDate
import javax.inject.Inject

/**
 * Typing in an address filter re-searches only after this quiet period, the same debounce the
 * contact list uses for its search field.
 */
private const val ADDRESS_DEBOUNCE_MS = 300L

/** REQ-ERR-01: a failed request is retried once, after 2 seconds. */
private const val RETRY_ATTEMPTS = 1

sealed class SearchUiState {
    data object Idle : SearchUiState()
    data object Loading : SearchUiState()
    data class Success(val results: List<MessageSummaryWithSnippet>) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
    data object Empty : SearchUiState()
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val folderRepository: FolderRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The refinements as the form currently holds them. */
    private val _refinements = MutableStateFlow(SearchRefinements())
    val refinements: StateFlow<SearchRefinements> = _refinements.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _isLoadingNextPage = MutableStateFlow(false)
    val isLoadingNextPage: StateFlow<Boolean> = _isLoadingNextPage.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    private var currentOffset = 0
    private var canLoadMore = true

    // The query and refinements the current result set was fetched with. Pagination and the
    // automatic retry re-run these, so an address filter still being typed — it only re-searches
    // after the debounce — cannot change what the next page fetches.
    private var activeQuery = ""
    private var activeRefinements = SearchRefinements()

    private var addressDebounceJob: Job? = null

    // A failed fetch is retried once (REQ-ERR-01). The budgets are renewed whenever a new search
    // is submitted — and, for pagination, after a page that did load — so a later failure gets
    // its own retry instead of inheriting an exhausted one.
    private var searchRetriesLeft = RETRY_ATTEMPTS
    private var nextPageRetriesLeft = RETRY_ATTEMPTS

    init {
        loadFolders()
    }

    private fun loadFolders() {
        viewModelScope.launch {
            folderRepository.listFolders().onSuccess { list ->
                _folders.value = list
            }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
        resetAndSearch()
    }

    fun setFolderId(id: Long?) {
        _refinements.update { it.copy(folderId = id) }
        resetAndSearch()
    }

    fun setDateFrom(date: LocalDate?) {
        _refinements.update { it.copy(dateFrom = date) }
        resetAndSearch()
    }

    fun setDateTo(date: LocalDate?) {
        _refinements.update { it.copy(dateTo = date) }
        resetAndSearch()
    }

    fun setFromAddr(addr: String) {
        _refinements.update { it.copy(fromAddr = addr) }
        scheduleAddressSearch()
    }

    fun setToAddr(addr: String) {
        _refinements.update { it.copy(toAddr = addr) }
        scheduleAddressSearch()
    }

    private fun scheduleAddressSearch() {
        addressDebounceJob?.cancel()
        addressDebounceJob = viewModelScope.launch {
            delay(ADDRESS_DEBOUNCE_MS)
            // Cleared before searching, so search() below never cancels the coroutine it runs on.
            addressDebounceJob = null
            resetAndSearch()
        }
    }

    private fun resetAndSearch() {
        currentOffset = 0
        canLoadMore = true
        search()
    }

    fun search() {
        // An explicit search supersedes a pending address-filter debounce.
        addressDebounceJob?.cancel()
        addressDebounceJob = null
        activeQuery = _query.value
        activeRefinements = _refinements.value
        searchRetriesLeft = RETRY_ATTEMPTS
        nextPageRetriesLeft = RETRY_ATTEMPTS
        if (activeQuery.isBlank()) {
            canLoadMore = false
            _uiState.value = SearchUiState.Idle
            return
        }
        viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            performSearch(offset = 0, replaceResults = true)
        }
    }

    fun loadNextPage() {
        if (!canLoadMore || _isLoadingNextPage.value) return
        if (activeQuery.isBlank()) return
        _isLoadingNextPage.value = true
        viewModelScope.launch {
            performSearch(offset = currentOffset, replaceResults = false)
            _isLoadingNextPage.value = false
        }
    }

    private suspend fun performSearch(offset: Int, replaceResults: Boolean) {
        val params = activeRefinements.toQueryParams()

        val result = messageRepository.searchMessages(
            q = activeQuery,
            folderId = params.folderId,
            dateFrom = params.dateFrom,
            dateTo = params.dateTo,
            fromAddr = params.fromAddr,
            toAddr = params.toAddr,
            limit = 50,
            offset = offset,
        )
        result.fold(
            onSuccess = { searchResult ->
                val newItems = searchResult.items
                if (newItems.size < 50) {
                    canLoadMore = false
                }
                if (replaceResults) {
                    currentOffset = newItems.size
                    _uiState.value = if (newItems.isEmpty()) {
                        SearchUiState.Empty
                    } else {
                        SearchUiState.Success(newItems)
                    }
                } else {
                    nextPageRetriesLeft = RETRY_ATTEMPTS
                    if (newItems.isNotEmpty()) {
                        currentOffset += newItems.size
                        val currentState = _uiState.value
                        if (currentState is SearchUiState.Success) {
                            _uiState.value = currentState.copy(
                                results = currentState.results + newItems,
                            )
                        }
                    }
                }
            },
            onFailure = { error ->
                val message = error.message ?: "Search failed"
                if (replaceResults) {
                    _uiState.value = SearchUiState.Error(message)
                    _snackbarMessage.emit(message)
                    // REQ-ERR-01: retry once. A failing retry leaves the error on screen for the
                    // user to act on rather than scheduling another.
                    if (searchRetriesLeft > 0) {
                        searchRetriesLeft--
                        scheduleRetrySearch()
                    }
                } else {
                    _snackbarMessage.emit(message)
                    if (nextPageRetriesLeft > 0) {
                        nextPageRetriesLeft--
                        scheduleRetryNextPage()
                    }
                }
            },
        )
    }

    private fun scheduleRetrySearch() {
        val queryCopy = activeQuery
        val refinementsCopy = activeRefinements
        viewModelScope.launch {
            delay(2000)
            if (activeQuery == queryCopy &&
                activeRefinements == refinementsCopy &&
                _uiState.value is SearchUiState.Error
            ) {
                performSearch(offset = 0, replaceResults = true)
            }
        }
    }

    private fun scheduleRetryNextPage() {
        viewModelScope.launch {
            delay(2000)
            if (canLoadMore && !_isLoadingNextPage.value) {
                loadNextPage()
            }
        }
    }

    fun removeMessageById(id: Long) {
        val currentState = _uiState.value
        if (currentState is SearchUiState.Success) {
            val updated = currentState.results.filter { it.summary.id.toLong() != id }
            _uiState.value = if (updated.isEmpty()) {
                SearchUiState.Empty
            } else {
                currentState.copy(results = updated)
            }
        }
    }
}
