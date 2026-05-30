package nu.staldal.mymail.repository

import nu.staldal.mymail.api.ContactsApi
import nu.staldal.mymail.di.RetrofitHolder
import nu.staldal.mymail.model.Contact
import nu.staldal.mymail.model.ContactsPostRequest
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepository @Inject constructor(
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

    suspend fun listContacts(
        q: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<ContactListResult> {
        val api = retrofitHolder.get().create(ContactsApi::class.java)
        return try {
            val response = api.listContacts(q, limit, offset)
            Result.success(ContactListResult(total = response.total, items = response.items))
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun createContact(address: String, name: String?): Result<Contact> {
        val api = retrofitHolder.get().create(ContactsApi::class.java)
        return try {
            Result.success(api.createContact(ContactsPostRequest(address = address, name = name)))
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun updateContact(id: Long, address: String, name: String): Result<Contact> {
        val api = retrofitHolder.get().create(ContactsApi::class.java)
        return try {
            Result.success(api.replaceContact(id, ContactsPostRequest(address = address, name = name)))
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun deleteContact(id: Long): Result<Unit> {
        val api = retrofitHolder.get().create(ContactsApi::class.java)
        return try {
            api.deleteContact(id)
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }
}
