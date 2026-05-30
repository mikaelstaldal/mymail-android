package nu.staldal.mymail.repository

import nu.staldal.mymail.api.AttachmentsApi
import nu.staldal.mymail.api.DraftsApi
import nu.staldal.mymail.api.DraftsWithAttachmentsApi
import nu.staldal.mymail.di.RetrofitHolder
import nu.staldal.mymail.model.DraftRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DraftRepository @Inject constructor(
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

    suspend fun createDraft(request: DraftRequest): Result<Long> {
        val api = retrofitHolder.get().create(DraftsApi::class.java)
        return try {
            val response = api.saveNewDraft(request)
            Result.success(response.id.toLong())
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun updateDraft(id: Long, request: DraftRequest): Result<Unit> {
        val api = retrofitHolder.get().create(DraftsApi::class.java)
        return try {
            api.replaceDraftContent(id, request)
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun deleteDraft(id: Long): Result<Unit> {
        val api = retrofitHolder.get().create(DraftsApi::class.java)
        return try {
            api.deleteDraft(id)
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun sendDraft(id: Long): Result<Int> {
        val api = retrofitHolder.get().create(DraftsApi::class.java)
        return try {
            val response = api.sendOrScheduleADraft(id)
            // The raw response code distinguishes 201 (sent) from 202 (scheduled)
            Result.success(response.raw().code)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun deleteAttachment(draftId: Long, attachmentId: Long): Result<Unit> {
        val api = retrofitHolder.get().create(DraftsApi::class.java)
        return try {
            api.removeASingleAttachmentFromADraft(draftId, attachmentId)
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun createDraftWithAttachments(
        request: DraftRequest,
        attachments: List<MultipartBody.Part>,
    ): Result<Long> {
        val api = retrofitHolder.get().create(DraftsWithAttachmentsApi::class.java)
        return try {
            val messagePart = MultipartBody.Part.createFormData(
                "message",
                null,
                okhttp3.RequestBody.create(
                    "application/json".toMediaType(),
                    kotlinx.serialization.json.Json.encodeToString(DraftRequest.serializer(), request),
                ),
            )
            val response = api.saveNewDraftWithAttachments(messagePart, attachments)
            Result.success(response.id.toLong())
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun replaceDraftWithAttachments(
        id: Long,
        request: DraftRequest,
        attachments: List<MultipartBody.Part>,
    ): Result<Unit> {
        val api = retrofitHolder.get().create(DraftsWithAttachmentsApi::class.java)
        return try {
            val messagePart = MultipartBody.Part.createFormData(
                "message",
                null,
                okhttp3.RequestBody.create(
                    "application/json".toMediaType(),
                    kotlinx.serialization.json.Json.encodeToString(DraftRequest.serializer(), request),
                ),
            )
            api.replaceDraftContentWithAttachments(id, messagePart, attachments)
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    suspend fun downloadAttachment(id: Long): Result<ResponseBody> {
        val api = retrofitHolder.get().create(AttachmentsApi::class.java)
        return try {
            Result.success(api.downloadAttachment(id))
        } catch (e: HttpException) {
            Result.failure(parseHttpError(e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }
}
