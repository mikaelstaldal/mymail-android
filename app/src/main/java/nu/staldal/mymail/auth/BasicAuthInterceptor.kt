package nu.staldal.mymail.auth

import android.util.Base64
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BasicAuthInterceptor @Inject constructor(
    private val credentialStore: CredentialStore,
    private val authEventBus: AuthEventBus,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // Either the stored credential or, in pw mode, the one fetched from pw in this process.
        val credential = credentialStore.activeCredential

        val requestBuilder = chain.request().newBuilder()

        if (credential != null) {
            val credentials = "${credential.username}:${credential.password}"
            val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            // The Authorization header value is intentionally not logged anywhere.
            requestBuilder.header("Authorization", "Basic $encoded")
        }

        val response = chain.proceed(requestBuilder.build())

        if (response.code == 401) {
            authEventBus.emit401()
        }

        return response
    }
}
