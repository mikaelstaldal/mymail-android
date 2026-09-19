package nu.staldal.mymail.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MyPassClientTest {

    @Test
    fun `a complete result yields the credential`() {
        val credential = MyPassClient.credentialFromExtras("user@example.com", "s3cret")

        assertEquals("user@example.com", credential?.username)
        assertEquals("s3cret", credential?.password)
    }

    @Test
    fun `an incomplete result yields nothing`() {
        assertNull(MyPassClient.credentialFromExtras(null, "s3cret"))
        assertNull(MyPassClient.credentialFromExtras("  ", "s3cret"))
        assertNull(MyPassClient.credentialFromExtras("user", null))
        assertNull(MyPassClient.credentialFromExtras(null, null))
    }

    @Test
    fun `an empty password is a legitimate credential`() {
        assertEquals("", MyPassClient.credentialFromExtras("user", "")?.password)
    }

    @Test
    fun `neither half of a credential is part of a string representation`() {
        val credential = Credential("user@example.com", "s3cret")

        for (text in listOf(credential.toString(), FetchedCredential("MyMail", credential).toString())) {
            assertFalse(text.contains("user@example.com"))
            assertFalse(text.contains("s3cret"))
        }
    }
}
