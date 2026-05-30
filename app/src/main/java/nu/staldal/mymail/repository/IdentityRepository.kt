package nu.staldal.mymail.repository

import nu.staldal.mymail.api.IdentitiesApi
import nu.staldal.mymail.di.RetrofitHolder
import nu.staldal.mymail.model.Identity
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityRepository @Inject constructor(
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

    suspend fun listIdentities(): Result<List<Identity>> {
        val api = retrofitHolder.get().create(IdentitiesApi::class.java)
        return try {
            val response = api.listIdentities()
            Result.success(response.items)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }
}
