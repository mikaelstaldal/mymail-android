package nu.staldal.mymail.repository

import nu.staldal.mymail.api.MessagesApi
import nu.staldal.mymail.di.RetrofitHolder
import nu.staldal.mymail.model.MessageDetail
import nu.staldal.mymail.model.MessageSummary
import nu.staldal.mymail.model.MessagesDeleteRequest
import nu.staldal.mymail.model.MessagesIdPatchRequest
import nu.staldal.mymail.model.MessagesMovePostRequest
import nu.staldal.mymail.model.MessagesPatchRequest
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
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

    suspend fun listMessages(
        folderId: Long,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<List<MessageSummary>> {
        val api = retrofitHolder.get().create(MessagesApi::class.java)
        return try {
            val response = api.listMessagesInFolder(folderId, limit, offset)
            Result.success(response.items)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun getMessage(id: Long): Result<MessageDetail> {
        val api = retrofitHolder.get().create(MessagesApi::class.java)
        return try {
            Result.success(api.getMessageDetails(id))
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun updateMessage(
        id: Long,
        read: Boolean? = null,
        folderId: Long? = null,
        flagged: Boolean? = null,
    ): Result<MessageSummary> {
        val api = retrofitHolder.get().create(MessagesApi::class.java)
        return try {
            Result.success(
                api.updateMessageMetadata(
                    id,
                    MessagesIdPatchRequest(
                        folderId = folderId?.toInt(),
                        read = read,
                        flagged = flagged,
                    ),
                )
            )
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun deleteMessage(id: Long): Result<Unit> {
        val api = retrofitHolder.get().create(MessagesApi::class.java)
        return try {
            api.deleteMessage(id)
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun bulkUpdateMessages(ids: List<Long>, read: Boolean? = null): Result<Unit> {
        val api = retrofitHolder.get().create(MessagesApi::class.java)
        return try {
            api.bulkUpdateMessages(MessagesPatchRequest(ids = ids.map { it.toInt() }, read = read))
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun bulkDeleteMessages(ids: List<Long>): Result<Unit> {
        val api = retrofitHolder.get().create(MessagesApi::class.java)
        return try {
            api.bulkDeleteMessages(MessagesDeleteRequest(ids = ids.map { it.toInt() }))
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun moveMessages(ids: List<Long>, folderId: Long): Result<Unit> {
        val api = retrofitHolder.get().create(MessagesApi::class.java)
        return try {
            api.bulkMoveMessagesToAFolder(
                MessagesMovePostRequest(ids = ids.map { it.toInt() }, folderId = folderId.toInt())
            )
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun getThread(id: Long): Result<ThreadResponse> {
        val api = retrofitHolder.get().create(MessagesApi::class.java)
        return try {
            val response = api.getThread(id)
            // response is an inline object with total, truncated, items
            Result.success(
                ThreadResponse(
                    total = response.total,
                    truncated = response.truncated,
                    items = response.items,
                )
            )
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun markJunk(id: Long): Result<Unit> {
        val api = retrofitHolder.get().create(MessagesApi::class.java)
        return try {
            api.markMessageAsJunk(id)
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun markNotJunk(id: Long): Result<Unit> {
        val api = retrofitHolder.get().create(MessagesApi::class.java)
        return try {
            api.markMessageAsNotJunk(id)
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun cancelSnooze(id: Long): Result<CancelSnoozeResponse> {
        val api = retrofitHolder.get().create(MessagesApi::class.java)
        return try {
            val response = api.cancelSnooze(id)
            Result.success(
                CancelSnoozeResponse(
                    id = response.id.toLong(),
                    folderId = response.folderId.toLong(),
                )
            )
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun searchMessages(
        q: String,
        folderId: Long? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<SearchResult> {
        val api = retrofitHolder.get().create(MessagesApi::class.java)
        return try {
            val response = api.searchMessages(q, folderId, dateFrom, dateTo, limit, offset)
            val items = response.items.map { item ->
                MessageSummaryWithSnippet(
                    summary = MessageSummary(
                        id = item.id,
                        folderId = item.folderId,
                        messageId = item.messageId,
                        fromAddr = item.fromAddr,
                        toAddr = item.toAddr,
                        subject = item.subject,
                        date = item.date,
                        read = item.read,
                        flagged = item.flagged,
                        hasAttachments = item.hasAttachments,
                        sendFailed = item.sendFailed,
                        createdAt = item.createdAt,
                    ),
                    snippet = item.snippet,
                )
            }
            Result.success(SearchResult(total = response.total, items = items))
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }
}
