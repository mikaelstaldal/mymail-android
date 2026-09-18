package nu.staldal.mymail.ui.screen.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupUiStateTest {

    @Test
    fun `connecting with stored credentials only waits for the request in flight`() {
        assertTrue(SetupUiState(serverUrl = "https://mail.example").canConnect)
        assertFalse(SetupUiState(serverUrl = "https://mail.example", isLoading = true).canConnect)
    }

    @Test
    fun `pw mode cannot connect before an entry name and a credential are in place`() {
        val state = SetupUiState(serverUrl = "https://mail.example", usePw = true)

        assertFalse(state.canConnect)
        assertFalse(state.copy(pwEntryName = "MyMail").canConnect)
        assertFalse(state.copy(pwCredentialLoaded = true).canConnect)
        assertTrue(state.copy(pwEntryName = "MyMail", pwCredentialLoaded = true).canConnect)
    }
}
