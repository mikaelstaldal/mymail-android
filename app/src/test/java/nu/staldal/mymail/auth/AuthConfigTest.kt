package nu.staldal.mymail.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The credential-source rules behind [CredentialStore], exercised without Android storage. */
class AuthConfigTest {

    private val stored = AuthConfig(
        serverUrl = "https://mail.example",
        storedUsername = "user",
        storedPassword = "s3cret",
    )

    private val pw = AuthConfig(
        serverUrl = "https://mail.example",
        usePw = true,
        pwEntryName = "MyMail",
    )

    private val fromPw = Credential("pw-user", "pw-secret")

    @Test
    fun `stored credentials authenticate without pw`() {
        assertEquals("user", stored.activeCredential(null)?.username)
        assertEquals("s3cret", stored.activeCredential(null)?.password)
        assertTrue(stored.isConfigured(null))
        assertFalse(stored.needsPwFetch(null))
    }

    @Test
    fun `an unconfigured app has nothing to authenticate with`() {
        assertNull(AuthConfig().activeCredential(null))
        assertFalse(AuthConfig().isConfigured(null))
        assertFalse(AuthConfig().needsPwFetch(null))
        assertFalse(AuthConfig(storedUsername = "user", storedPassword = "s3cret").isConfigured(null))
    }

    @Test
    fun `pw mode is unauthenticated until the process has fetched a credential`() {
        assertNull(pw.activeCredential(null))
        assertFalse(pw.isConfigured(null))
        assertTrue(pw.needsPwFetch(null))

        assertEquals("pw-user", pw.activeCredential(fromPw)?.username)
        assertEquals("pw-secret", pw.activeCredential(fromPw)?.password)
        assertTrue(pw.isConfigured(fromPw))
        assertFalse(pw.needsPwFetch(fromPw))
    }

    @Test
    fun `pw mode ignores any credential that is still stored`() {
        val leftOver = pw.copy(storedUsername = "user", storedPassword = "s3cret")

        assertNull(leftOver.activeCredential(null))
        assertEquals("pw-user", leftOver.activeCredential(fromPw)?.username)
    }

    @Test
    fun `a blank entry name is not a usable pw configuration`() {
        val blank = pw.copy(pwEntryName = " ")

        assertNull(blank.activeCredential(fromPw))
        assertFalse(blank.isConfigured(fromPw))
        assertFalse(blank.needsPwFetch(null))
    }

    @Test
    fun `without a server URL nothing is configured`() {
        assertFalse(stored.copy(serverUrl = null).isConfigured(null))
        assertFalse(pw.copy(serverUrl = null).isConfigured(fromPw))
        assertFalse(pw.copy(serverUrl = null).needsPwFetch(null))
    }
}

class PwCredentialSessionTest {

    @Test
    fun `a session starts empty and can be filled and emptied again`() {
        val session = PwCredentialSession()

        assertNull(session.current)
        assertNull(session.credential.value)

        session.set(Credential("user", "s3cret"))

        assertEquals("user", session.current?.username)
        assertEquals("s3cret", session.credential.value?.password)

        session.clear()

        assertNull(session.current)
        assertNull(session.credential.value)
    }
}
