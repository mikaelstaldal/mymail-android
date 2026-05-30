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
        val username = credentialStore.username
        val password = credentialStore.password

        val requestBuilder = chain.request().newBuilder()

        if (username != null && password != null) {
            val credentials = "$username:$password"
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
