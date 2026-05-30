package nu.staldal.mymail.ui.screen.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import nu.staldal.mymail.model.Contact

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(
    navController: NavController,
    replyTo: String? = null,
    replyAllTo: String? = null,
    forwardOf: String? = null,
    draftId: String? = null,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(replyTo, replyAllTo, forwardOf, draftId) {
        viewModel.initialize(replyTo, replyAllTo, forwardOf, draftId)
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { msg ->
            coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.cleanupRedownloadedFilesPublic()
        }
    }

    val initState by viewModel.initState.collectAsState()
    val identities by viewModel.identities.collectAsState()
    val selectedIdentityId by viewModel.selectedIdentityId.collectAsState()
    val toChips by viewModel.toChips.collectAsState()
    val ccChips by viewModel.ccChips.collectAsState()
    val bccChips by viewModel.bccChips.collectAsState()
    val replyToText by viewModel.replyToText.collectAsState()
    val subject by viewModel.subject.collectAsState()
    val bodyText by viewModel.bodyText.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    val attachmentError by viewModel.attachmentError.collectAsState()
    val isAttachmentLoading by viewModel.isAttachmentLoading.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val permanentError by viewModel.permanentError.collectAsState()
    val contactSuggestions by viewModel.contactSuggestions.collectAsState()
    val contactSuggestionsTotal by viewModel.contactSuggestionsTotal.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addAttachments(uris)
        }
    }

    var showCcBcc by remember { mutableStateOf(false) }
    var showReplyTo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compose") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val hasRecipients = toChips.isNotEmpty() || ccChips.isNotEmpty() || bccChips.isNotEmpty()
                    val identitiesEmpty = identities.isEmpty()
                    IconButton(
                        onClick = {
                            viewModel.send(onSuccess = { navController.popBackStack() })
                        },
                        enabled = hasRecipients && !isSending && !identitiesEmpty && permanentError == null,
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(modifier = Modifier.width(24.dp))
                        } else {
                            Icon(Icons.Filled.Send, contentDescription = "Send")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        when (val state = initState) {
            is ComposeInitState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is ComposeInitState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!state.message.contains("No sending identities")) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            viewModel.retryInit(replyTo, replyAllTo, forwardOf, draftId)
                        }) {
                            Text("Retry")
                        }
                    }
                }
            }
            is ComposeInitState.Ready -> {
                val fieldsEnabled = identities.isNotEmpty() && permanentError == null

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (permanentError != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = permanentError!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    FromDropdown(
                        identities = identities,
                        selectedIdentityId = selectedIdentityId,
                        onIdentitySelected = viewModel::onIdentitySelected,
                        enabled = fieldsEnabled,
                    )

                    AddressChipField(
                        label = "To",
                        chips = toChips,
                        onChipsChanged = viewModel::onToChipsChanged,
                        enabled = fieldsEnabled,
                        suggestions = contactSuggestions,
                        suggestionsTotal = contactSuggestionsTotal,
                        onQueryChanged = viewModel::queryContacts,
                        onSuggestionsCleared = viewModel::clearContactSuggestions,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { showCcBcc = !showCcBcc },
                            enabled = fieldsEnabled,
                        ) {
                            Icon(
                                if (showCcBcc) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (showCcBcc) "Hide Cc/Bcc" else "Show Cc/Bcc")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { showReplyTo = !showReplyTo },
                            enabled = fieldsEnabled,
                        ) {
                            Icon(
                                if (showReplyTo) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (showReplyTo) "Hide Reply-To" else "Show Reply-To")
                        }
                    }

                    if (showCcBcc) {
                        AddressChipField(
                            label = "Cc",
                            chips = ccChips,
                            onChipsChanged = viewModel::onCcChipsChanged,
                            enabled = fieldsEnabled,
                            suggestions = contactSuggestions,
                            suggestionsTotal = contactSuggestionsTotal,
                            onQueryChanged = viewModel::queryContacts,
                            onSuggestionsCleared = viewModel::clearContactSuggestions,
                        )
                        AddressChipField(
                            label = "Bcc",
                            chips = bccChips,
                            onChipsChanged = viewModel::onBccChipsChanged,
                            enabled = fieldsEnabled,
                            suggestions = contactSuggestions,
                            suggestionsTotal = contactSuggestionsTotal,
                            onQueryChanged = viewModel::queryContacts,
                            onSuggestionsCleared = viewModel::clearContactSuggestions,
                        )
                    }

                    if (showReplyTo) {
                        OutlinedTextField(
                            value = replyToText,
                            onValueChange = viewModel::onReplyToChanged,
                            label = { Text("Reply-To") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = fieldsEnabled,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        )
                    }

                    OutlinedTextField(
                        value = subject,
                        onValueChange = viewModel::onSubjectChanged,
                        label = { Text("Subject") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = fieldsEnabled,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )

                    HorizontalDivider()

                    if (sendError != null) {
                        Text(
                            text = sendError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    OutlinedTextField(
                        value = bodyText,
                        onValueChange = viewModel::onBodyChanged,
                        label = { Text("Body") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        enabled = fieldsEnabled,
                        maxLines = Int.MAX_VALUE,
                    )

                    HorizontalDivider()

                    Text("Attachments", style = MaterialTheme.typography.labelMedium)

                    if (isAttachmentLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }

                    if (attachmentError != null) {
                        Text(
                            text = attachmentError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    for (att in attachments) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = att.filename,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (att.meta?.size != null) {
                                Text(
                                    text = formatFileSize(att.meta.size.toLong()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            IconButton(
                                onClick = { viewModel.removeAttachment(att) },
                                enabled = fieldsEnabled && !isAttachmentLoading,
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove attachment")
                            }
                        }
                    }

                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        enabled = fieldsEnabled && !isAttachmentLoading,
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add Attachment")
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FromDropdown(
    identities: List<nu.staldal.mymail.model.Identity>,
    selectedIdentityId: Long?,
    onIdentitySelected: (Long) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedIdentity = identities.firstOrNull { it.id.toLong() == selectedIdentityId }
    val displayText = if (selectedIdentity != null) {
        if (selectedIdentity.name.isNotEmpty()) "${selectedIdentity.name} <${selectedIdentity.address}>"
        else selectedIdentity.address
    } else ""

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text("From") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            enabled = enabled,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (identity in identities) {
                val label = if (identity.name.isNotEmpty()) {
                    "${identity.name} <${identity.address}>"
                } else {
                    identity.address
                }
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onIdentitySelected(identity.id.toLong())
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AddressChipField(
    label: String,
    chips: List<AddressChip>,
    onChipsChanged: (List<AddressChip>) -> Unit,
    enabled: Boolean,
    suggestions: List<Contact>,
    suggestionsTotal: Int,
    onQueryChanged: (String) -> Unit,
    onSuggestionsCleared: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    var showDropdown by remember { mutableStateOf(false) }

    val showSuggestions = showDropdown && (suggestions.isNotEmpty() || suggestionsTotal > 10)

    fun commitChip(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            val chip = AddressChip(displayName = trimmed, rfcAddress = trimmed)
            onChipsChanged(chips + chip)
        }
        inputText = ""
        onSuggestionsCleared()
        showDropdown = false
    }

    fun addContactChip(contact: Contact) {
        val rfc = if (contact.name.isNotEmpty()) "\"${contact.name}\" <${contact.address}>"
        else contact.address
        val chip = AddressChip(
            displayName = if (contact.name.isNotEmpty()) contact.name else contact.address,
            rfcAddress = rfc,
        )
        onChipsChanged(chips + chip)
        inputText = ""
        onSuggestionsCleared()
        showDropdown = false
    }

    ExposedDropdownMenuBox(
        expanded = showSuggestions,
        onExpandedChange = { },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (chip in chips) {
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(chip.displayName) },
                        trailingIcon = {
                            if (enabled) {
                                IconButton(
                                    onClick = { onChipsChanged(chips - chip) },
                                    modifier = Modifier.widthIn(max = 24.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove ${chip.displayName}",
                                    )
                                }
                            }
                        },
                        enabled = enabled,
                    )
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { newText ->
                        val hasCommitChar = newText.contains(',') || newText.contains('\t')
                        if (hasCommitChar) {
                            val parts = newText.split(',', '\t')
                            for (part in parts.dropLast(1)) {
                                commitChip(part)
                            }
                            inputText = parts.last()
                        } else {
                            inputText = newText
                        }
                        if (inputText.isNotEmpty()) {
                            onQueryChanged(inputText)
                            showDropdown = true
                        } else {
                            onSuggestionsCleared()
                            showDropdown = false
                        }
                    },
                    modifier = Modifier
                        .widthIn(min = 120.dp)
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                commitChip(inputText)
                                true
                            } else {
                                false
                            }
                        },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitChip(inputText) }),
                    placeholder = { if (chips.isEmpty()) Text("Type an address…") },
                )
            }
        }
        ExposedDropdownMenu(
            expanded = showSuggestions,
            onDismissRequest = {
                showDropdown = false
                onSuggestionsCleared()
            },
        ) {
            for (contact in suggestions) {
                val displayLabel = if (contact.name.isNotEmpty()) {
                    "${contact.name} <${contact.address}>"
                } else {
                    contact.address
                }
                DropdownMenuItem(
                    text = { Text(displayLabel) },
                    onClick = { addContactChip(contact) },
                )
            }
            if (suggestionsTotal > 10) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Type more to narrow…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }
}
