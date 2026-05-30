package nu.staldal.mymail.repository

import nu.staldal.mymail.api.FoldersApi
import nu.staldal.mymail.di.RetrofitHolder
import nu.staldal.mymail.model.Folder
import nu.staldal.mymail.model.FoldersIdPatchRequest
import nu.staldal.mymail.model.FoldersPostRequest
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderRepository @Inject constructor(
    private val retrofitHolder: RetrofitHolder,
) {

    private fun parseHttpError(e: HttpException): Throwable {
        return try {
            val body = e.response()?.errorBody()?.string()
            val message = if (body != null) {
                val match = Regex(""""error"\s*:\s*"([^"]+)"""").find(body)
                match?.groupValues?.get(1)
            } else {
                null
            }
            if (message != null) {
                HttpStatusException(e.code(), message)
            } else {
                e
            }
        } catch (_: Exception) {
            e
        }
    }

    suspend fun listFolders(): Result<List<Folder>> {
        val api = retrofitHolder.get().create(FoldersApi::class.java)
        return try {
            val response = api.listFolders()
            Result.success(response.items)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun createFolder(name: String): Result<Folder> {
        val api = retrofitHolder.get().create(FoldersApi::class.java)
        return try {
            Result.success(api.createFolder(FoldersPostRequest(name = name)))
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun renameFolder(id: Long, name: String): Result<Folder> {
        val api = retrofitHolder.get().create(FoldersApi::class.java)
        return try {
            Result.success(api.updateFolder(id, FoldersIdPatchRequest(name = name)))
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun deleteFolder(id: Long): Result<Unit> {
        val api = retrofitHolder.get().create(FoldersApi::class.java)
        return try {
            api.deleteFolder(id)
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun markAllRead(folderId: Long): Result<Unit> {
        val api = retrofitHolder.get().create(FoldersApi::class.java)
        return try {
            api.markAllMessagesInFolderAsRead(folderId)
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun deleteAllMessages(folderId: Long): Result<Unit> {
        val api = retrofitHolder.get().create(FoldersApi::class.java)
        return try {
            api.deleteAllMessagesInFolder(folderId)
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }
}
