package nu.staldal.mymail.ui.screen.folder

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
import nu.staldal.mymail.model.MessageSummary
import nu.staldal.mymail.repository.FolderRepository
import nu.staldal.mymail.repository.HttpStatusException
import nu.staldal.mymail.repository.MessageRepository
import nu.staldal.mymail.repository.messageSummaries
import nu.staldal.mymail.repository.mockPrefs
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Matches every message-list call, whatever its arguments. */
private suspend fun MockKMatcherScope.anyList(repo: MessageRepository): Result<List<MessageSummary>> =
    repo.listMessages(any(), any(), any())

/** Matches the message-list calls for exactly this folder and offset. */
private suspend fun MockKMatcherScope.list(
    repo: MessageRepository,
    folderId: Long,
    offset: Int,
): Result<List<MessageSummary>> = repo.listMessages(folderId, any(), offset)

@OptIn(ExperimentalCoroutinesApi::class)
class MessageListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var messages: MessageRepository
    private lateinit var folders: FolderRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        messages = mockk()
        folders = mockk()
        coEvery { folders.listFolders() } returns Result.success(emptyList())
        listReturns(Result.success(emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun listReturns(result: Result<List<MessageSummary>>) {
        coEvery { anyList(messages) } returns result
    }

    private fun viewModel() = MessageListViewModel(messages, folders, mockPrefs())

    @Test
    fun `a failed load is retried exactly once`() = runTest(dispatcher) {
        listReturns(Result.failure(IOException("network down")))
        val vm = viewModel()
        vm.loadMessages(1L)
        runCurrent()
        coVerify(exactly = 1) { anyList(messages) }

        advanceTimeBy(2000)
        runCurrent()
        coVerify(exactly = 2) { anyList(messages) } // the one retry, 2 s later

        // The retry failed too: the error stays on screen and nothing further is scheduled.
        advanceTimeBy(60_000)
        runCurrent()
        coVerify(exactly = 2) { anyList(messages) }
        assertTrue(vm.uiState.value is MessageListUiState.Error)
    }

    @Test
    fun `a failed next page is retried exactly once`() = runTest(dispatcher) {
        listReturns(Result.success(messageSummaries(50)))
        val vm = viewModel()
        vm.loadMessages(1L)
        runCurrent()
        coVerify(exactly = 1) { anyList(messages) }

        listReturns(Result.failure(IOException("network down")))
        vm.loadNextPage()
        runCurrent()
        coVerify(exactly = 1) { list(messages, folderId = 1L, offset = 50) }

        advanceTimeBy(2000)
        runCurrent()
        // The one retry, 2 s later.
        coVerify(exactly = 2) { list(messages, folderId = 1L, offset = 50) }

        advanceTimeBy(60_000)
        runCurrent()
        coVerify(exactly = 2) { list(messages, folderId = 1L, offset = 50) }
        // A failed page does not discard the page that did load.
        assertTrue(vm.uiState.value is MessageListUiState.Success)
    }

    @Test
    fun `a new load restores the retry budget`() = runTest(dispatcher) {
        listReturns(Result.failure(IOException("network down")))
        val vm = viewModel()
        vm.loadMessages(1L)
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        coVerify(exactly = 2) { list(messages, folderId = 1L, offset = 0) } // first load plus its retry

        vm.loadMessages(2L)
        runCurrent()
        coVerify(exactly = 1) { list(messages, folderId = 2L, offset = 0) }

        advanceTimeBy(2000)
        runCurrent()
        // The new load gets its own retry.
        coVerify(exactly = 2) { list(messages, folderId = 2L, offset = 0) }
        coVerify(exactly = 4) { anyList(messages) }
    }

    @Test
    fun `a 404 is not retried at all`() = runTest(dispatcher) {
        listReturns(Result.failure(HttpStatusException(404, "no such folder")))
        val vm = viewModel()
        vm.loadMessages(1L)
        runCurrent()
        coVerify(exactly = 1) { anyList(messages) }

        advanceTimeBy(60_000)
        runCurrent()
        coVerify(exactly = 1) { anyList(messages) } // a 404 navigates back instead of retrying
        assertTrue((vm.uiState.value as MessageListUiState.Error).is404)
    }
}
