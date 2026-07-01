package nu.staldal.mymail.di

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import nu.staldal.mymail.BuildConfig
import nu.staldal.mymail.auth.BasicAuthInterceptor
import nu.staldal.mymail.auth.CredentialStore
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.openapitools.client.infrastructure.Serializer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Singleton

private const val TAG = "ErrorLoggingInterceptor"

/** Logs the full response body of unexpected (non-2xx) API responses to LogCat for debugging. */
private class ErrorLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) {
            val body = response.peekBody(8192).string()
            Log.w(TAG, "Unexpected ${response.code} response from ${response.request.method} ${response.request.url}: $body")
        }
        return response
    }
}

@Singleton
class RetrofitHolder(initialRetrofit: Retrofit) {

    private val reference = AtomicReference(initialRetrofit)

    fun get(): Retrofit = reference.get()

    fun rebuild(serverUrl: String, okHttpClient: OkHttpClient) {
        val baseUrl = serverUrl.trimEnd('/') + "/api/v1/"
        val json = Json {
            serializersModule = Serializer.kotlinxSerializationAdapters
            ignoreUnknownKeys = true
        }
        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
        reference.set(retrofit)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(basicAuthInterceptor: BasicAuthInterceptor): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(basicAuthInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                redactHeader("Authorization")
            }
            builder.addInterceptor(loggingInterceptor)
            builder.addInterceptor(ErrorLoggingInterceptor())
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofitHolder(
        credentialStore: CredentialStore,
        okHttpClient: OkHttpClient,
    ): RetrofitHolder {
        val serverUrl = credentialStore.serverUrl
        val baseUrl = if (serverUrl != null) {
            serverUrl.trimEnd('/') + "/api/v1/"
        } else {
            "http://localhost/api/v1/"
        }

        val json = Json {
            serializersModule = Serializer.kotlinxSerializationAdapters
            ignoreUnknownKeys = true
        }
        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        return RetrofitHolder(retrofit)
    }
}
