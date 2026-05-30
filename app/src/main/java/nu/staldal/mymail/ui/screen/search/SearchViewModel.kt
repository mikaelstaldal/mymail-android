package nu.staldal.mymail.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nu.staldal.mymail.model.Folder
import nu.staldal.mymail.repository.FolderRepository
import nu.staldal.mymail.repository.MessageRepository
import nu.staldal.mymail.repository.MessageSummaryWithSnippet
import java.time.LocalDate
import javax.inject.Inject

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

    private val _folderId = MutableStateFlow<Long?>(null)
    val folderId: StateFlow<Long?> = _folderId.asStateFlow()

    private val _dateFrom = MutableStateFlow<LocalDate?>(null)
    val dateFrom: StateFlow<LocalDate?> = _dateFrom.asStateFlow()

    private val _dateTo = MutableStateFlow<LocalDate?>(null)
    val dateTo: StateFlow<LocalDate?> = _dateTo.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _isLoadingNextPage = MutableStateFlow(false)
    val isLoadingNextPage: StateFlow<Boolean> = _isLoadingNextPage.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    private var currentOffset = 0
    private var canLoadMore = true

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
        _folderId.value = id
        resetAndSearch()
    }

    fun setDateFrom(date: LocalDate?) {
        _dateFrom.value = date
        resetAndSearch()
    }

    fun setDateTo(date: LocalDate?) {
        _dateTo.value = date
        resetAndSearch()
    }

    private fun resetAndSearch() {
        currentOffset = 0
        canLoadMore = true
        search()
    }

    fun search() {
        val q = _query.value
        if (q.isBlank()) {
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
        val q = _query.value
        if (q.isBlank()) return
        _isLoadingNextPage.value = true
        viewModelScope.launch {
            performSearch(offset = currentOffset, replaceResults = false)
            _isLoadingNextPage.value = false
        }
    }

    private suspend fun performSearch(offset: Int, replaceResults: Boolean) {
        val q = _query.value
        val folderId = _folderId.value
        val dateFromStr = _dateFrom.value?.let { toRfc3339StartOfDay(it) }
        val dateToStr = _dateTo.value?.let { toRfc3339StartOfNextDay(it) }

        val result = messageRepository.searchMessages(
            q = q,
            folderId = folderId,
            dateFrom = dateFromStr,
            dateTo = dateToStr,
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
                    scheduleRetrySearch()
                } else {
                    _snackbarMessage.emit(message)
                    scheduleRetryNextPage()
                }
            },
        )
    }

    private fun scheduleRetrySearch() {
        val queryCopy = _query.value
        viewModelScope.launch {
            delay(2000)
            if (_query.value == queryCopy && _uiState.value is SearchUiState.Error) {
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

private fun toRfc3339StartOfDay(date: LocalDate): String {
    val zdt = java.time.ZonedDateTime.of(date, java.time.LocalTime.MIDNIGHT, java.time.ZoneId.systemDefault())
    return zdt.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

private fun toRfc3339StartOfNextDay(date: LocalDate): String {
    val nextDay = date.plusDays(1)
    val zdt = java.time.ZonedDateTime.of(nextDay, java.time.LocalTime.MIDNIGHT, java.time.ZoneId.systemDefault())
    return zdt.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
