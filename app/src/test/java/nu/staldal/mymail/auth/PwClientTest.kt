package nu.staldal.mymail.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PwClientTest {

    @Test
    fun `a complete result yields the credential`() {
        val credential = PwClient.credentialFromExtras("user@example.com", "s3cret")

        assertEquals("user@example.com", credential?.username)
        assertEquals("s3cret", credential?.password)
    }

    @Test
    fun `an incomplete result yields nothing`() {
        assertNull(PwClient.credentialFromExtras(null, "s3cret"))
        assertNull(PwClient.credentialFromExtras("  ", "s3cret"))
        assertNull(PwClient.credentialFromExtras("user", null))
        assertNull(PwClient.credentialFromExtras(null, null))
    }

    @Test
    fun `an empty password is a legitimate credential`() {
        assertEquals("", PwClient.credentialFromExtras("user", "")?.password)
    }

    @Test
    fun `the password is not part of the string representation`() {
        val text = Credential("user", "s3cret").toString()

        assertTrue(text.contains("user"))
        assertFalse(text.contains("s3cret"))
    }
}
