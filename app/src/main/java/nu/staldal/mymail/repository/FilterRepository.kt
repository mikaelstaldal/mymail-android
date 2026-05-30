package nu.staldal.mymail.repository

import nu.staldal.mymail.api.FiltersApi
import nu.staldal.mymail.di.RetrofitHolder
import nu.staldal.mymail.model.Filter
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FilterRepository @Inject constructor(
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

    suspend fun listFilters(): Result<List<Filter>> {
        val api = retrofitHolder.get().create(FiltersApi::class.java)
        return try {
            val response = api.listFilters()
            Result.success(response.items)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }
}
