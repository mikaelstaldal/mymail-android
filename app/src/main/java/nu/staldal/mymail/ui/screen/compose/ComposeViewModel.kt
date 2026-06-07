package nu.staldal.mymail.ui.screen.compose

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nu.staldal.mymail.intent.ComposeIntentData
import nu.staldal.mymail.intent.PendingComposeIntentHolder
import nu.staldal.mymail.model.AttachmentMeta
import nu.staldal.mymail.model.Contact
import nu.staldal.mymail.model.DraftRequest
import nu.staldal.mymail.model.Identity
import nu.staldal.mymail.model.MessageDetail
import nu.staldal.mymail.repository.ContactRepository
import nu.staldal.mymail.repository.DraftRepository
import nu.staldal.mymail.repository.HttpStatusException
import nu.staldal.mymail.repository.IdentityRepository
import nu.staldal.mymail.repository.MessageRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.apache.james.mime4j.dom.address.Group
import org.apache.james.mime4j.dom.address.Mailbox
import org.apache.james.mime4j.field.address.DefaultAddressParser
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class AddressChip(
    val displayName: String,
    val rfcAddress: String,
)

data class AttachmentInfo(
    val id: Long?,
    val meta: AttachmentMeta?,
    val localUri: android.net.Uri?,
    val filename: String,
    val contentType: String,
    val isUploading: Boolean = false,
)

sealed class ComposeInitState {
    data object Loading : ComposeInitState()
    data object Ready : ComposeInitState()
    data class Error(val message: String) : ComposeInitState()
}

private const val AUTO_SAVE_INTERVAL_MS = 30_000L
private const val AUTOCOMPLETE_DEBOUNCE_MS = 300L
private const val MAX_SUBJECT_CHARS = 998
private const val MAX_ADDR_CHARS = 8192
private const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
private const val TAG = "ComposeViewModel"

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val draftRepository: DraftRepository,
    private val messageRepository: MessageRepository,
    private val identityRepository: IdentityRepository,
    private val contactRepository: ContactRepository,
    private val pendingComposeIntentHolder: PendingComposeIntentHolder,
    application: Application,
) : AndroidViewModel(application) {

    private val _initState = MutableStateFlow<ComposeInitState>(ComposeInitState.Loading)
    val initState: StateFlow<ComposeInitState> = _initState.asStateFlow()

    private val _identities = MutableStateFlow<List<Identity>>(emptyList())
    val identities: StateFlow<List<Identity>> = _identities.asStateFlow()

    private val _selectedIdentityId = MutableStateFlow<Long?>(null)
    val selectedIdentityId: StateFlow<Long?> = _selectedIdentityId.asStateFlow()

    private val _toChips = MutableStateFlow<List<AddressChip>>(emptyList())
    val toChips: StateFlow<List<AddressChip>> = _toChips.asStateFlow()

    private val _ccChips = MutableStateFlow<List<AddressChip>>(emptyList())
    val ccChips: StateFlow<List<AddressChip>> = _ccChips.asStateFlow()

    private val _bccChips = MutableStateFlow<List<AddressChip>>(emptyList())
    val bccChips: StateFlow<List<AddressChip>> = _bccChips.asStateFlow()

    private val _replyToText = MutableStateFlow("")
    val replyToText: StateFlow<String> = _replyToText.asStateFlow()

    private val _subject = MutableStateFlow("")
    val subject: StateFlow<String> = _subject.asStateFlow()

    private val _bodyText = MutableStateFlow("")
    val bodyText: StateFlow<String> = _bodyText.asStateFlow()

    private val _attachments = MutableStateFlow<List<AttachmentInfo>>(emptyList())
    val attachments: StateFlow<List<AttachmentInfo>> = _attachments.asStateFlow()

    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()

    private val _isAttachmentLoading = MutableStateFlow(false)
    val isAttachmentLoading: StateFlow<Boolean> = _isAttachmentLoading.asStateFlow()

    private val _draftId = MutableStateFlow<Long?>(null)
    val draftId: StateFlow<Long?> = _draftId.asStateFlow()

    private val _autoSaveError = MutableStateFlow<String?>(null)
    val autoSaveError: StateFlow<String?> = _autoSaveError.asStateFlow()

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _contactSuggestions = MutableStateFlow<List<Contact>>(emptyList())
    val contactSuggestions: StateFlow<List<Contact>> = _contactSuggestions.asStateFlow()

    private val _contactSuggestionsTotal = MutableStateFlow(0)
    val contactSuggestionsTotal: StateFlow<Int> = _contactSuggestionsTotal.asStateFlow()

    private val _permanentError = MutableStateFlow<String?>(null)
    val permanentError: StateFlow<String?> = _permanentError.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    private var isDirty = false
    private val saveMutex = Mutex()
    private var autoSaveJob: Job? = null
    private var autocompleteJob: Job? = null

    private var inReplyTo: String? = null
    private var referencesForSave: List<String> = emptyList()
    private var sourceMessageIdForPost: Long? = null

    private var currentSignature: String = ""

    private val redownloadedFiles = mutableListOf<File>()

    fun initialize(
        replyTo: String?,
        replyAllTo: String?,
        forwardOf: String?,
        draftId: String?,
    ) {
        viewModelScope.launch {
            val existingDraftId = draftId?.toLongOrNull()
            when {
                existingDraftId != null -> initDraftEdit(existingDraftId)
                replyTo != null -> {
                    val msgId = replyTo.toLongOrNull() ?: return@launch
                    initReply(msgId, replyAll = false)
                }
                replyAllTo != null -> {
                    val msgId = replyAllTo.toLongOrNull() ?: return@launch
                    initReply(msgId, replyAll = true)
                }
                forwardOf != null -> {
                    val msgId = forwardOf.toLongOrNull() ?: return@launch
                    initForward(msgId)
                }
                else -> initNewCompose(pendingComposeIntentHolder.consume())
            }
        }
    }

    private suspend fun initNewCompose(prefill: ComposeIntentData?) {
        _initState.value = ComposeInitState.Loading
        identityRepository.listIdentities().fold(
            onSuccess = { list ->
                _identities.value = list
                if (list.isEmpty()) {
                    _initState.value = ComposeInitState.Error(
                        "No sending identities configured — go to Settings → Identities to add one."
                    )
                    return
                }
                val defaultIdentity = list.firstOrNull { it.isDefault } ?: list.first()
                selectIdentityInternal(defaultIdentity, initialBody = prefill?.body ?: "", isNew = true)
                applyPrefill(prefill)
                _initState.value = ComposeInitState.Ready
                startAutoSave()
                if (prefill != null && prefill.attachmentUris.isNotEmpty()) {
                    addAttachments(prefill.attachmentUris)
                }
            },
            onFailure = { error ->
                _initState.value = ComposeInitState.Error(error.message ?: "Failed to load identities")
            },
        )
    }

    private fun applyPrefill(prefill: ComposeIntentData?) {
        if (prefill == null) return
        if (prefill.to.isNotEmpty()) _toChips.value = parseAddressChips(prefill.to.joinToString(", "))
        if (prefill.cc.isNotEmpty()) _ccChips.value = parseAddressChips(prefill.cc.joinToString(", "))
        if (prefill.bcc.isNotEmpty()) _bccChips.value = parseAddressChips(prefill.bcc.joinToString(", "))
        prefill.subject?.let { raw ->
            val stripped = raw.replace(Regex("[\r\n]"), "")
            _subject.value = if (stripped.length > MAX_SUBJECT_CHARS) stripped.take(MAX_SUBJECT_CHARS) else stripped
        }
    }

    private suspend fun initReply(sourceMessageId: Long, replyAll: Boolean) {
        _initState.value = ComposeInitState.Loading
        val result = runCatching {
            coroutineScope {
                val msgDeferred = async { messageRepository.getMessage(sourceMessageId) }
                val identDeferred = async { identityRepository.listIdentities() }
                Pair(msgDeferred.await(), identDeferred.await())
            }
        }

        result.fold(
            onSuccess = { (msgResult, identResult) ->
                if (msgResult.isFailure || identResult.isFailure) {
                    val err = (msgResult.exceptionOrNull() ?: identResult.exceptionOrNull())
                    _initState.value = ComposeInitState.Error(err?.message ?: "Failed to load")
                    return
                }
                val source = msgResult.getOrThrow()
                val identList = identResult.getOrThrow()

                _identities.value = identList
                if (identList.isEmpty()) {
                    _initState.value = ComposeInitState.Error(
                        "No sending identities configured — go to Settings → Identities to add one."
                    )
                    return
                }

                val selectedIdentity = pickIdentityForReply(source, identList)
                val ownAddresses = identList.map { it.address.lowercase() }

                val toChips: List<AddressChip>
                val ccChips: List<AddressChip>

                if (replyAll) {
                    val replyToAddresses = parseAddressChips(source.replyToAddr)
                    val fromAddresses = parseAddressChips(source.fromAddr)
                    val rawTo = if (replyToAddresses.isNotEmpty()) replyToAddresses else fromAddresses
                    toChips = rawTo.filterNot { isOwnAddress(it.rfcAddress, ownAddresses) }
                    val allRecipients = parseAddressChips(source.toAddr) + parseAddressChips(source.ccAddr)
                    ccChips = allRecipients.filterNot { isOwnAddress(it.rfcAddress, ownAddresses) }
                } else {
                    val replyToAddresses = parseAddressChips(source.replyToAddr)
                    val fromAddresses = parseAddressChips(source.fromAddr)
                    val rawTo = if (replyToAddresses.isNotEmpty()) replyToAddresses else fromAddresses
                    toChips = rawTo.filterNot { isOwnAddress(it.rfcAddress, ownAddresses) }
                    ccChips = emptyList()
                }

                _toChips.value = toChips
                _ccChips.value = ccChips

                val reSubject = "Re: " + source.subject.replace(Regex("^(?i)(re:\\s*)+"), "")
                _subject.value = reSubject

                val dateStr = source.date.format(DateTimeFormatter.RFC_1123_DATE_TIME)
                val attribution = "On $dateStr, ${source.fromAddr} wrote:\n"
                val quotedBody = source.bodyText.lines().joinToString("\n") { "> $it" }
                val baseBody = "\n\n$attribution$quotedBody"

                selectIdentityInternal(selectedIdentity, initialBody = baseBody, isNew = true)

                if (source.messageId != null) {
                    inReplyTo = source.messageId
                }
                val refs = source.references.toMutableList()
                if (source.messageId != null) {
                    refs.add("<${source.messageId}>")
                }
                if (refs.isNotEmpty() || source.messageId != null) {
                    referencesForSave = refs
                }

                _initState.value = ComposeInitState.Ready
                startAutoSave()
            },
            onFailure = { error ->
                _initState.value = ComposeInitState.Error(error.message ?: "Failed to load")
            },
        )
    }

    private suspend fun initForward(sourceMessageId: Long) {
        _initState.value = ComposeInitState.Loading
        val result = runCatching {
            coroutineScope {
                val msgDeferred = async { messageRepository.getMessage(sourceMessageId) }
                val identDeferred = async { identityRepository.listIdentities() }
                Pair(msgDeferred.await(), identDeferred.await())
            }
        }

        result.fold(
            onSuccess = { (msgResult, identResult) ->
                if (msgResult.isFailure || identResult.isFailure) {
                    val err = msgResult.exceptionOrNull() ?: identResult.exceptionOrNull()
                    _initState.value = ComposeInitState.Error(err?.message ?: "Failed to load")
                    return
                }
                val source = msgResult.getOrThrow()
                val identList = identResult.getOrThrow()

                _identities.value = identList
                if (identList.isEmpty()) {
                    _initState.value = ComposeInitState.Error(
                        "No sending identities configured — go to Settings → Identities to add one."
                    )
                    return
                }
                val defaultIdentity = identList.firstOrNull { it.isDefault } ?: identList.first()

                val fwdSubject = "Fwd: " + source.subject.replace(Regex("^(?i)(fwd:\\s*)+"), "")
                _subject.value = fwdSubject

                val dateStr = source.date.format(DateTimeFormatter.RFC_1123_DATE_TIME)
                val forwardedBlock = buildString {
                    appendLine("---------- Forwarded message ----------")
                    appendLine("From: ${source.fromAddr}")
                    appendLine("Date: $dateStr")
                    appendLine("Subject: ${source.subject}")
                    appendLine("To: ${source.toAddr}")
                    appendLine()
                    append(source.bodyText)
                }
                val baseBody = "\n\n$forwardedBlock"

                selectIdentityInternal(defaultIdentity, initialBody = baseBody, isNew = true)
                sourceMessageIdForPost = sourceMessageId

                val existingAttachments = source.attachments.map { meta ->
                    AttachmentInfo(
                        id = meta.id.toLong(),
                        meta = meta,
                        localUri = null,
                        filename = meta.filename,
                        contentType = meta.contentType,
                    )
                }
                _attachments.value = existingAttachments

                _initState.value = ComposeInitState.Ready
                startAutoSave()
            },
            onFailure = { error ->
                _initState.value = ComposeInitState.Error(error.message ?: "Failed to load")
            },
        )
    }

    private suspend fun initDraftEdit(id: Long) {
        _initState.value = ComposeInitState.Loading
        val result = runCatching {
            coroutineScope {
                val msgDeferred = async { messageRepository.getMessage(id) }
                val identDeferred = async { identityRepository.listIdentities() }
                Pair(msgDeferred.await(), identDeferred.await())
            }
        }

        result.fold(
            onSuccess = { (msgResult, identResult) ->
                if (msgResult.isFailure) {
                    val err = msgResult.exceptionOrNull()
                    _initState.value = ComposeInitState.Error(err?.message ?: "Failed to load draft")
                    return
                }
                if (identResult.isFailure) {
                    val err = identResult.exceptionOrNull()
                    _initState.value = ComposeInitState.Error(err?.message ?: "Failed to load identities")
                    return
                }
                val draft = msgResult.getOrThrow()
                val identList = identResult.getOrThrow()

                _identities.value = identList
                if (identList.isEmpty()) {
                    _initState.value = ComposeInitState.Error(
                        "No sending identities configured — go to Settings → Identities to add one."
                    )
                    return
                }

                _draftId.value = id

                val fromAddr = extractAddrSpec(draft.fromAddr)
                val matchedIdentity = identList.firstOrNull {
                    it.address.lowercase() == fromAddr.lowercase()
                } ?: identList.firstOrNull { it.isDefault } ?: identList.first()
                _selectedIdentityId.value = matchedIdentity.id.toLong()
                currentSignature = matchedIdentity.signature

                _toChips.value = parseAddressChips(draft.toAddr)
                _ccChips.value = parseAddressChips(draft.ccAddr)
                _bccChips.value = parseAddressChips(draft.bccAddr)
                _replyToText.value = draft.replyToAddr
                _subject.value = draft.subject
                _bodyText.value = draft.bodyText

                if (draft.inReplyTo != null) {
                    inReplyTo = draft.inReplyTo
                }
                if (draft.references.isNotEmpty()) {
                    referencesForSave = draft.references
                }

                val existingAttachments = draft.attachments.map { meta ->
                    AttachmentInfo(
                        id = meta.id.toLong(),
                        meta = meta,
                        localUri = null,
                        filename = meta.filename,
                        contentType = meta.contentType,
                    )
                }
                _attachments.value = existingAttachments

                isDirty = false
                _initState.value = ComposeInitState.Ready
                startAutoSave()
            },
            onFailure = { error ->
                _initState.value = ComposeInitState.Error(error.message ?: "Failed to load draft")
            },
        )
    }

    private fun selectIdentityInternal(identity: Identity, initialBody: String, isNew: Boolean) {
        _selectedIdentityId.value = identity.id.toLong()
        if (isNew) {
            val sig = identity.signature
            currentSignature = sig
            _bodyText.value = if (sig.isNotEmpty()) {
                "$initialBody\n\n-- \n$sig"
            } else {
                if (initialBody.isEmpty()) "" else initialBody
            }
        }
    }

    fun onIdentitySelected(identityId: Long) {
        val newIdentity = _identities.value.firstOrNull { it.id.toLong() == identityId } ?: return
        val oldSignature = currentSignature
        val newSignature = newIdentity.signature
        if (oldSignature == newSignature) {
            _selectedIdentityId.value = identityId
            markDirty()
            return
        }

        val body = _bodyText.value
        val newBody = replaceSignatureInBody(body, oldSignature, newSignature)
        _selectedIdentityId.value = identityId
        currentSignature = newSignature
        _bodyText.value = newBody
        markDirty()
    }

    private fun replaceSignatureInBody(body: String, oldSig: String, newSig: String): String {
        val sigDelim = "\n\n-- \n"
        val exactMatch = "$sigDelim$oldSig"
        if (oldSig.isNotEmpty() && body.endsWith(exactMatch)) {
            val base = body.dropLast(exactMatch.length)
            return if (newSig.isNotEmpty()) "$base$sigDelim$newSig" else base
        }

        val lastDelimIdx = body.lastIndexOf(sigDelim)
        if (lastDelimIdx >= 0) {
            val base = body.substring(0, lastDelimIdx)
            return if (newSig.isNotEmpty()) "$base$sigDelim$newSig" else base
        }

        return if (newSig.isNotEmpty()) "$body$sigDelim$newSig" else body
    }

    fun onToChipsChanged(chips: List<AddressChip>) {
        _toChips.value = chips
        markDirty()
    }

    fun onCcChipsChanged(chips: List<AddressChip>) {
        _ccChips.value = chips
        markDirty()
    }

    fun onBccChipsChanged(chips: List<AddressChip>) {
        _bccChips.value = chips
        markDirty()
    }

    fun onReplyToChanged(text: String) {
        if (text.length <= MAX_ADDR_CHARS) {
            _replyToText.value = text
            markDirty()
        }
    }

    fun onSubjectChanged(text: String) {
        val stripped = text.replace(Regex("[\r\n]"), "")
        val capped = if (stripped.length > MAX_SUBJECT_CHARS) stripped.take(MAX_SUBJECT_CHARS) else stripped
        _subject.value = capped
        markDirty()
    }

    fun onBodyChanged(text: String) {
        _bodyText.value = text
        markDirty()
    }

    fun queryContacts(query: String) {
        autocompleteJob?.cancel()
        if (query.isEmpty()) {
            _contactSuggestions.value = emptyList()
            _contactSuggestionsTotal.value = 0
            return
        }
        autocompleteJob = viewModelScope.launch {
            delay(AUTOCOMPLETE_DEBOUNCE_MS)
            contactRepository.listContacts(q = query, limit = 10, offset = 0).fold(
                onSuccess = { result ->
                    _contactSuggestions.value = result.items
                    _contactSuggestionsTotal.value = result.total
                },
                onFailure = {
                    _contactSuggestions.value = emptyList()
                    _contactSuggestionsTotal.value = 0
                },
            )
        }
    }

    fun clearContactSuggestions() {
        autocompleteJob?.cancel()
        _contactSuggestions.value = emptyList()
        _contactSuggestionsTotal.value = 0
    }

    private fun markDirty() {
        isDirty = true
    }

    private fun buildDraftRequest(): DraftRequest {
        val toAddr = chipsToString(_toChips.value)
        val ccAddr = chipsToString(_ccChips.value)
        val bccAddr = chipsToString(_bccChips.value)
        return DraftRequest(
            identityId = _selectedIdentityId.value?.toInt(),
            toAddr = toAddr,
            ccAddr = ccAddr,
            bccAddr = bccAddr,
            replyToAddr = _replyToText.value,
            subject = _subject.value.replace(Regex("[\r\n]"), ""),
            bodyText = _bodyText.value,
            inReplyTo = inReplyTo,
            references = referencesForSave.ifEmpty { null },
        )
    }

    private fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            while (true) {
                delay(AUTO_SAVE_INTERVAL_MS)
                if (_permanentError.value != null) break
                performSave(isAutoSave = true)
            }
        }
    }

    private suspend fun performSave(isAutoSave: Boolean) {
        saveMutex.withLock {
            if (!isDirty) return
            val request = buildDraftRequest()
            val currentDraftId = _draftId.value
            if (currentDraftId == null) {
                val postRequest = if (sourceMessageIdForPost != null) {
                    request.copy(sourceMessageId = sourceMessageIdForPost?.toInt())
                } else {
                    request
                }
                draftRepository.createDraft(postRequest).fold(
                    onSuccess = { newId ->
                        _draftId.value = newId
                        isDirty = false
                        _autoSaveError.value = null
                    },
                    onFailure = { error ->
                        val status = (error as? HttpStatusException)?.statusCode
                        if (status == 400) {
                            _permanentError.value = error.message ?: "Draft could not be saved"
                        } else {
                            if (isAutoSave) {
                                _snackbarMessage.tryEmit("Draft could not be saved — will retry")
                            }
                        }
                    },
                )
            } else {
                draftRepository.updateDraft(currentDraftId, request).fold(
                    onSuccess = {
                        isDirty = false
                        _autoSaveError.value = null
                    },
                    onFailure = { error ->
                        if (isAutoSave) {
                            _snackbarMessage.tryEmit("Draft could not be saved — will retry")
                        }
                        Log.w(TAG, "Auto-save PUT failed: ${error.message}")
                    },
                )
            }
        }
    }

    fun send(onSuccess: () -> Unit) {
        if (_isSending.value) return
        autoSaveJob?.cancel()
        autoSaveJob = null
        _isSending.value = true
        _sendError.value = null

        viewModelScope.launch {
            var draftIdToSend = _draftId.value
            if (draftIdToSend == null) {
                saveMutex.withLock {
                    if (_draftId.value == null) {
                        val request = buildDraftRequest()
                        val postRequest = if (sourceMessageIdForPost != null) {
                            request.copy(sourceMessageId = sourceMessageIdForPost?.toInt())
                        } else {
                            request
                        }
                        draftRepository.createDraft(postRequest).fold(
                            onSuccess = { newId ->
                                _draftId.value = newId
                                isDirty = false
                            },
                            onFailure = { error ->
                                _sendError.value = error.message ?: "Failed to save draft before send"
                                _isSending.value = false
                                startAutoSave()
                            },
                        )
                    }
                }
                if (_draftId.value == null) return@launch
            }
            draftIdToSend = _draftId.value!!

            draftRepository.sendDraft(draftIdToSend).fold(
                onSuccess = { code ->
                    _isSending.value = false
                    onSuccess()
                },
                onFailure = { error ->
                    _isSending.value = false
                    val status = (error as? HttpStatusException)?.statusCode
                    val message = error.message ?: "Send failed"
                    _sendError.value = message
                    _snackbarMessage.tryEmit(message)
                    if (status != 404) {
                        startAutoSave()
                    }
                },
            )
        }
    }

    fun addAttachments(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _attachmentError.value = null
            _isAttachmentLoading.value = true

            var draftIdForUpload = _draftId.value
            if (draftIdForUpload == null) {
                saveMutex.withLock {
                    if (_draftId.value == null) {
                        val request = buildDraftRequest()
                        val postRequest = if (sourceMessageIdForPost != null) {
                            request.copy(sourceMessageId = sourceMessageIdForPost?.toInt())
                        } else {
                            request
                        }
                        draftRepository.createDraft(postRequest).fold(
                            onSuccess = { newId ->
                                _draftId.value = newId
                                isDirty = false
                            },
                            onFailure = { error ->
                                _attachmentError.value = error.message ?: "Failed to save draft"
                                _isAttachmentLoading.value = false
                            },
                        )
                    }
                }
                if (_draftId.value == null) return@launch
            }
            draftIdForUpload = _draftId.value!!

            val existingAttachments = _attachments.value.filter { it.id != null && it.meta != null }
            val redownloadedParts = mutableListOf<Pair<AttachmentInfo, MultipartBody.Part>>()

            for (att in existingAttachments) {
                val meta = att.meta!!
                val attId = att.id!!
                val attachDir = File(getApplication<Application>().cacheDir, "attachments")
                attachDir.mkdirs()
                val cachedFile = File(attachDir, "redownload_${attId}_${meta.filename}")

                draftRepository.downloadAttachment(attId).fold(
                    onSuccess = { body ->
                        val contentLength = body.contentLength()
                        if (contentLength < 0 || contentLength > MAX_ATTACHMENT_BYTES) {
                            body.close()
                            _attachmentError.value = "Attachment too large to re-download"
                            _isAttachmentLoading.value = false
                            return@launch
                        }
                        cachedFile.outputStream().use { out -> body.byteStream().copyTo(out) }
                        redownloadedFiles.add(cachedFile)

                        val requestBody = RequestBody.create(
                            meta.contentType.toMediaType(),
                            cachedFile,
                        )
                        val part = MultipartBody.Part.createFormData(
                            "attachments",
                            meta.filename,
                            requestBody,
                        )
                        redownloadedParts.add(Pair(att, part))
                    },
                    onFailure = { error ->
                        _attachmentError.value = error.message ?: "Failed to re-download attachment"
                        _isAttachmentLoading.value = false
                        return@launch
                    },
                )
            }

            val newParts = mutableListOf<MultipartBody.Part>()
            val newAttachmentInfos = mutableListOf<AttachmentInfo>()
            val context = getApplication<Application>()

            for (uri in uris) {
                val filename = resolveFilename(context, uri)
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: continue
                val requestBody = RequestBody.create(mimeType.toMediaType(), bytes)
                val part = MultipartBody.Part.createFormData("attachments", filename, requestBody)
                newParts.add(part)
                newAttachmentInfos.add(
                    AttachmentInfo(
                        id = null,
                        meta = null,
                        localUri = uri,
                        filename = filename,
                        contentType = mimeType,
                    )
                )
            }

            val allParts = redownloadedParts.map { it.second } + newParts
            val draftRequest = buildDraftRequest()

            draftRepository.replaceDraftWithAttachments(draftIdForUpload, draftRequest, allParts).fold(
                onSuccess = {
                    isDirty = false
                    val newList = redownloadedParts.map { it.first } + newAttachmentInfos
                    _attachments.value = newList
                    _attachmentError.value = null
                    _isAttachmentLoading.value = false

                    messageRepository.getMessage(draftIdForUpload).fold(
                        onSuccess = { detail ->
                            val updatedAtts = detail.attachments.map { meta ->
                                AttachmentInfo(
                                    id = meta.id.toLong(),
                                    meta = meta,
                                    localUri = null,
                                    filename = meta.filename,
                                    contentType = meta.contentType,
                                )
                            }
                            _attachments.value = updatedAtts
                        },
                        onFailure = {
                            Log.w(TAG, "Failed to refresh attachments after upload: ${it.message}")
                        },
                    )
                },
                onFailure = { error ->
                    _attachmentError.value = error.message ?: "Failed to upload attachments"
                    _isAttachmentLoading.value = false
                },
            )
        }
    }

    fun removeAttachment(attachment: AttachmentInfo) {
        val draftIdVal = _draftId.value
        if (attachment.id != null && draftIdVal != null) {
            viewModelScope.launch {
                draftRepository.deleteAttachment(draftIdVal, attachment.id).fold(
                    onSuccess = {
                        _attachments.value = _attachments.value.filter { it != attachment }
                    },
                    onFailure = { error ->
                        _snackbarMessage.tryEmit(error.message ?: "Failed to remove attachment")
                    },
                )
            }
        } else {
            _attachments.value = _attachments.value.filter { it != attachment }
        }
    }

    fun retryAttachmentUpload(uris: List<android.net.Uri>) {
        _attachmentError.value = null
        addAttachments(uris)
    }

    fun retryInit(
        replyTo: String?,
        replyAllTo: String?,
        forwardOf: String?,
        draftId: String?,
    ) {
        initialize(replyTo, replyAllTo, forwardOf, draftId)
    }

    override fun onCleared() {
        super.onCleared()
        autoSaveJob?.cancel()
        if (_permanentError.value != null) {
            cleanupRedownloadedFiles()
            return
        }
        viewModelScope.launch {
            withContext(NonCancellable) {
                performSave(isAutoSave = false)
            }
            cleanupRedownloadedFiles()
        }
    }

    fun cleanupRedownloadedFilesPublic() {
        cleanupRedownloadedFiles()
    }

    private fun cleanupRedownloadedFiles() {
        for (file in redownloadedFiles) {
            file.delete()
        }
        redownloadedFiles.clear()
    }

    private fun chipsToString(chips: List<AddressChip>): String =
        chips.joinToString(", ") { it.rfcAddress }

    private fun parseAddressChips(addrString: String): List<AddressChip> {
        if (addrString.isBlank()) return emptyList()
        return try {
            val addressList = DefaultAddressParser.DEFAULT.parseAddressList(addrString)
            val chips = addressList.flatMap { address ->
                when (address) {
                    is Mailbox -> listOf(address)
                    is Group -> address.mailboxes.toList()
                    else -> emptyList()
                }
            }.map { mailbox ->
                val name = mailbox.name
                val addr = buildString {
                    if (mailbox.localPart != null) append(mailbox.localPart)
                    if (mailbox.domain != null) {
                        append("@")
                        append(mailbox.domain)
                    }
                }
                val rfc = if (!name.isNullOrEmpty()) "\"$name\" <$addr>" else addr
                AddressChip(displayName = if (!name.isNullOrEmpty()) name else addr, rfcAddress = rfc)
            }
            chips.ifEmpty { parseAddressChipsFallback(addrString) }
        } catch (e: Exception) {
            // mime4j rejects non-ASCII display names decoded from MIME encoded-words; fall back to regex
            Log.w(TAG, "Failed to parse address list: $addrString", e)
            parseAddressChipsFallback(addrString)
        }
    }

    // Regex fallback for addresses that mime4j rejects (e.g. decoded non-ASCII display names)
    private fun parseAddressChipsFallback(addrString: String): List<AddressChip> {
        val result = mutableListOf<AddressChip>()
        // Match optional display name followed by <email@domain>, or bare email@domain
        val pattern = Regex("""([^<,]*)<([^>]*@[^>]*)>|([^\s,;<>"]+@[^\s,;<>"]+)""")
        for (match in pattern.findAll(addrString)) {
            val displayPart = match.groupValues[1].trim().trim('"')
            val angleEmail = match.groupValues[2].trim()
            val bareEmail = match.groupValues[3].trim()
            if (angleEmail.isNotEmpty()) {
                result.add(AddressChip(displayName = displayPart.ifEmpty { angleEmail }, rfcAddress = angleEmail))
            } else if (bareEmail.isNotEmpty()) {
                result.add(AddressChip(displayName = bareEmail, rfcAddress = bareEmail))
            }
        }
        return result
    }

    private fun extractAddrSpec(fromAddr: String): String {
        if (fromAddr.isBlank()) return ""
        return try {
            val addressList = DefaultAddressParser.DEFAULT.parseAddressList(fromAddr)
            val mailbox = addressList.flatMap { address ->
                when (address) {
                    is Mailbox -> listOf(address)
                    is Group -> address.mailboxes.toList()
                    else -> emptyList()
                }
            }.firstOrNull()
            if (mailbox != null) {
                buildString {
                    if (mailbox.localPart != null) append(mailbox.localPart)
                    if (mailbox.domain != null) {
                        append("@")
                        append(mailbox.domain)
                    }
                }
            } else {
                fromAddr.trim()
            }
        } catch (e: Exception) {
            fromAddr.trim()
        }
    }

    private fun pickIdentityForReply(source: MessageDetail, identities: List<Identity>): Identity {
        val allSourceAddresses = buildList {
            addAll(parseAddressChips(source.toAddr).map { extractAddrSpec(it.rfcAddress) })
            addAll(parseAddressChips(source.ccAddr).map { extractAddrSpec(it.rfcAddress) })
        }
        for (addr in allSourceAddresses) {
            val match = identities.firstOrNull {
                it.address.lowercase() == addr.lowercase()
            }
            if (match != null) return match
        }
        return identities.firstOrNull { it.isDefault } ?: identities.first()
    }

    private fun isOwnAddress(rfcAddress: String, ownAddresses: List<String>): Boolean {
        val addrSpec = extractAddrSpec(rfcAddress).lowercase()
        return ownAddresses.any { own ->
            addrSpec == own || isPlusAddressMatch(addrSpec, own)
        }
    }

    private fun isPlusAddressMatch(address: String, identity: String): Boolean {
        val atIdx = address.indexOf('@')
        if (atIdx < 0) return false
        val localPart = address.substring(0, atIdx)
        val domain = address.substring(atIdx + 1)
        val plusIdx = localPart.indexOf('+')
        if (plusIdx < 0) return false
        val baseLocal = localPart.substring(0, plusIdx)
        val identityAt = identity.indexOf('@')
        if (identityAt < 0) return false
        val identityLocal = identity.substring(0, identityAt)
        val identityDomain = identity.substring(identityAt + 1)
        return baseLocal.lowercase() == identityLocal.lowercase() &&
            domain.lowercase() == identityDomain.lowercase()
    }

    private fun resolveFilename(context: android.content.Context, uri: android.net.Uri): String {
        var name = "attachment"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) {
                    name = cursor.getString(idx) ?: name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve filename for URI: $uri", e)
        }
        return name
    }
}
