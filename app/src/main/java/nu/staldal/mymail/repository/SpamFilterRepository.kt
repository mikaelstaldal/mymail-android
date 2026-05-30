package nu.staldal.mymail.repository

import nu.staldal.mymail.api.SpamFilterApi
import nu.staldal.mymail.di.RetrofitHolder
import nu.staldal.mymail.model.SpamFilterSettings
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpamFilterRepository @Inject constructor(
    private val retrofitHolder: RetrofitHolder,
) {

    private fun parseHttpError(e: HttpException): Throwable {
        return try {
            val body = e.response()?.errorBody()?.string()
            if (body != null) {
                val match = Regex(""""error"\s*:\s*"([^"]+)"""").find(body)
                if (match != null) {
                    RuntimeException(match.groupValues[1])
                } else {
                    e
                }
            } else {
                e
            }
        } catch (_: Exception) {
            e
        }
    }

    suspend fun getSettings(): Result<SpamFilterSettings> {
        val api = retrofitHolder.get().create(SpamFilterApi::class.java)
        return try {
            Result.success(api.getSpamFilterSettings())
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun updateSettings(settings: SpamFilterSettings): Result<SpamFilterSettings> {
        val api = retrofitHolder.get().create(SpamFilterApi::class.java)
        return try {
            Result.success(api.replaceSpamFilterSettings(settings))
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }
}
