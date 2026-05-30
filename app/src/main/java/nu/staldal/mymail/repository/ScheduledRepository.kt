package nu.staldal.mymail.repository

import nu.staldal.mymail.api.ScheduledApi
import nu.staldal.mymail.di.RetrofitHolder
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledRepository @Inject constructor(
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

    suspend fun cancelScheduled(id: Long): Result<Unit> {
        val api = retrofitHolder.get().create(ScheduledApi::class.java)
        return try {
            api.cancelScheduledMessage(id)
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }
}
