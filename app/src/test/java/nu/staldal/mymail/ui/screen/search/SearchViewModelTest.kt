package nu.staldal.mymail.ui.screen.search

import io.mockk.MockKMatcherScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nu.staldal.mymail.repository.FolderRepository
import nu.staldal.mymail.repository.MessageRepository
import nu.staldal.mymail.repository.SearchResult
import nu.staldal.mymail.repository.searchItems
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Matches every search call, whatever its arguments. */
private suspend fun MockKMatcherScope.anySearch(repo: MessageRepository): Result<SearchResult> =
    repo.searchMessages(any(), any(), any(), any(), any(), any(), any(), any())

/** Matches the search calls carrying exactly these refinements and offset. */
private suspend fun MockKMatcherScope.search(
    repo: MessageRepository,
    folderId: Long?,
    fromAddr: String?,
    toAddr: String?,
    offset: Int,
): Result<SearchResult> =
    repo.searchMessages(any(), folderId, any(), any(), fromAddr, toAddr, any(), offset)

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var messages: MessageRepository
    private lateinit var folders: FolderRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        messages = mockk()
        folders = mockk()
        coEvery { folders.listFolders() } returns Result.success(emptyList())
        searchReturns(Result.success(SearchResult(total = 0, items = emptyList())))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun searchReturns(result: Result<SearchResult>) {
        coEvery { anySearch(messages) } returns result
    }

    private fun viewModel() = SearchViewModel(messages, folders)

    @Test
    fun `rapid address edits issue a single search once the field settles`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setQuery("hello")
        runCurrent()
        coVerify(exactly = 1) { anySearch(messages) }

        vm.setFromAddr("a")
        vm.setFromAddr("al")
        vm.setFromAddr("alice@example.com")
        advanceTimeBy(299)
        runCurrent()
        coVerify(exactly = 1) { anySearch(messages) } // typing must not search per keystroke

        advanceTimeBy(1)
        runCurrent()
        coVerify(exactly = 2) { anySearch(messages) }
        coVerify(exactly = 1) {
            search(messages, folderId = null, fromAddr = "alice@example.com", toAddr = null, offset = 0)
        }
        // Only the settled value was ever searched for, not the keystrokes leading to it.
        coVerify(exactly = 0) { search(messages, folderId = null, fromAddr = "al", toAddr = null, offset = 0) }
    }

    @Test
    fun `the keyboard search action commits without waiting for the debounce`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setQuery("hello")
        runCurrent()

        vm.setToAddr("  bob@example.com  ")
        vm.search()
        runCurrent()

        coVerify(exactly = 2) { anySearch(messages) }
        coVerify(exactly = 1) {
            search(messages, folderId = null, fromAddr = null, toAddr = "bob@example.com", offset = 0)
        }

        // The pending debounce was superseded, so it must not fire a second identical search.
        advanceTimeBy(1000)
        runCurrent()
        coVerify(exactly = 2) { anySearch(messages) }
    }

    @Test
    fun `blank address filters are not sent`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setFromAddr("   ")
        vm.setQuery("hello")
        runCurrent()

        coVerify(exactly = 1) { anySearch(messages) }
        coVerify(exactly = 1) {
            search(messages, folderId = null, fromAddr = null, toAddr = null, offset = 0)
        }
    }

    @Test
    fun `the next page uses the last submitted refinements, not the live form`() = runTest(dispatcher) {
        searchReturns(Result.success(SearchResult(total = 120, items = searchItems(50))))
        val vm = viewModel()
        vm.setQuery("hello")
        runCurrent()
        coVerify(exactly = 1) { anySearch(messages) }

        // Typed but not yet searched for: the debounce is still pending.
        vm.setFromAddr("carol@example.com")
        vm.loadNextPage()
        runCurrent()

        coVerify(exactly = 2) { anySearch(messages) }
        // The page fetch carries the submitted refinements — a live edit must not change it.
        coVerify(exactly = 1) {
            search(messages, folderId = null, fromAddr = null, toAddr = null, offset = 50)
        }
        coVerify(exactly = 0) {
            search(messages, folderId = null, fromAddr = "carol@example.com", toAddr = null, offset = 50)
        }

        // Once the debounce fires it starts over from the first page.
        advanceTimeBy(300)
        runCurrent()
        coVerify(exactly = 3) { anySearch(messages) }
        coVerify(exactly = 1) {
            search(messages, folderId = null, fromAddr = "carol@example.com", toAddr = null, offset = 0)
        }
    }

    @Test
    fun `the automatic retry re-runs the last submitted refinements`() = runTest(dispatcher) {
        searchReturns(Result.failure(IOException("network down")))
        val vm = viewModel()
        vm.setQuery("hello")
        runCurrent()
        coVerify(exactly = 1) { anySearch(messages) }

        // Fails too, so the screen stays in the error state. Retry is now scheduled for t+2300.
        vm.setFromAddr("dave@example.com")
        advanceTimeBy(300)
        runCurrent()
        coVerify(exactly = 2) { anySearch(messages) }
        coVerify(exactly = 1) {
            search(messages, folderId = null, fromAddr = "dave@example.com", toAddr = null, offset = 0)
        }

        // t=2000, when the first search's retry was due — superseded, so it must not fire.
        advanceTimeBy(1700)
        runCurrent()
        coVerify(exactly = 2) { anySearch(messages) } // a superseded retry is abandoned

        // Edit the form again, then let the surviving retry fire before its debounce does.
        advanceTimeBy(200)
        vm.setFromAddr("erin@example.com")
        advanceTimeBy(100)
        runCurrent()

        coVerify(exactly = 3) { anySearch(messages) }
        // The retry re-ran what was submitted; the live form value was never searched for.
        coVerify(exactly = 2) {
            search(messages, folderId = null, fromAddr = "dave@example.com", toAddr = null, offset = 0)
        }
        coVerify(exactly = 0) {
            search(messages, folderId = null, fromAddr = "erin@example.com", toAddr = null, offset = 0)
        }
    }

    @Test
    fun `a failed search is retried exactly once`() = runTest(dispatcher) {
        searchReturns(Result.failure(IOException("network down")))
        val vm = viewModel()
        vm.setQuery("hello")
        runCurrent()
        coVerify(exactly = 1) { anySearch(messages) }

        advanceTimeBy(2000)
        runCurrent()
        coVerify(exactly = 2) { anySearch(messages) } // the one retry, 2 s later

        // The retry failed too: the error stays on screen and nothing further is scheduled.
        advanceTimeBy(60_000)
        runCurrent()
        coVerify(exactly = 2) { anySearch(messages) }
        assertTrue(vm.uiState.value is SearchUiState.Error)
    }

    @Test
    fun `a failed next page is retried exactly once`() = runTest(dispatcher) {
        searchReturns(Result.success(SearchResult(total = 120, items = searchItems(50))))
        val vm = viewModel()
        vm.setQuery("hello")
        runCurrent()
        coVerify(exactly = 1) { anySearch(messages) }

        searchReturns(Result.failure(IOException("network down")))
        vm.loadNextPage()
        runCurrent()
        coVerify(exactly = 1) {
            search(messages, folderId = null, fromAddr = null, toAddr = null, offset = 50)
        }

        advanceTimeBy(2000)
        runCurrent()
        coVerify(exactly = 2) { // the one retry, 2 s later
            search(messages, folderId = null, fromAddr = null, toAddr = null, offset = 50)
        }

        advanceTimeBy(60_000)
        runCurrent()
        coVerify(exactly = 2) {
            search(messages, folderId = null, fromAddr = null, toAddr = null, offset = 50)
        }
        // A failed page does not discard the page that did load.
        assertTrue(vm.uiState.value is SearchUiState.Success)
    }

    @Test
    fun `a new search restores the retry budget`() = runTest(dispatcher) {
        searchReturns(Result.failure(IOException("network down")))
        val vm = viewModel()
        vm.setQuery("hello")
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        coVerify(exactly = 2) { anySearch(messages) } // first search plus its retry

        vm.setFolderId(7L)
        runCurrent()
        coVerify(exactly = 1) {
            search(messages, folderId = 7L, fromAddr = null, toAddr = null, offset = 0)
        }

        advanceTimeBy(2000)
        runCurrent()
        // The new submission gets its own retry.
        coVerify(exactly = 2) {
            search(messages, folderId = 7L, fromAddr = null, toAddr = null, offset = 0)
        }
        coVerify(exactly = 4) { anySearch(messages) }
    }
}
