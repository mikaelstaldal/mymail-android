package nu.staldal.mymail.ui.screen.message

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import nu.staldal.mymail.DRAFTS_ID
import nu.staldal.mymail.INBOX_ID
import nu.staldal.mymail.JUNK_ID
import nu.staldal.mymail.SCHEDULED_ID
import nu.staldal.mymail.SENT_ID
import nu.staldal.mymail.SNOOZED_ID
import nu.staldal.mymail.TRASH_ID
import nu.staldal.mymail.model.AttachmentMeta
import nu.staldal.mymail.model.MessageDetail
import nu.staldal.mymail.model.MessageSummary
import nu.staldal.mymail.utils.formatMessageDetailDate
import nu.staldal.mymail.utils.formatMessageListDate
import java.time.OffsetDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MessageDetailScreen(
    navController: NavController,
    messageId: Long,
    viewModel: MessageDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val threadState by viewModel.threadState.collectAsState()
    val attachmentStates by viewModel.attachmentStates.collectAsState()
    val folders by viewModel.folders.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(messageId) {
        viewModel.loadMessage(messageId)
        viewModel.loadFolders()
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.deleteCachedAttachments()
        }
    }

    fun setNavResultAndPop() {
        navController.previousBackStackEntry?.savedStateHandle?.set("needsRefresh", true)
        navController.previousBackStackEntry?.savedStateHandle?.set("removedMessageId", messageId)
        navController.popBackStack()
    }

    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showCancelScheduledConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val state = uiState) {
            is MessageDetailUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is MessageDetailUiState.Error -> {
                if (state.is404) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Not found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        Button(onClick = { viewModel.loadMessage(messageId) }) {
                            Text("Retry")
                        }
                    }
                }
            }

            is MessageDetailUiState.Success -> {
                val message = state.message
                val folderId = message.folderId.toLong()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    item {
                        HeaderSection(
                            message = message,
                            onCopyDate = { iso ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("date", iso))
                                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Date copied to clipboard")
                                    }
                                }
                            },
                        )
                    }

                    if (message.sendFailed && (folderId == SCHEDULED_ID || folderId == DRAFTS_ID)) {
                        item {
                            val bannerColor = if (folderId == SCHEDULED_ID) Color(0xFFFFF9C4) else Color(0xFFFFCDD2)
                            val textColor = if (folderId == SCHEDULED_ID) Color(0xFF795548) else Color(0xFFB71C1C)
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = bannerColor,
                            ) {
                                Text(
                                    text = "Send failed",
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }

                    item {
                        SelectionContainer {
                            Text(
                                text = message.bodyText,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            )
                        }
                    }

                    if (message.attachments.isNotEmpty()) {
                        item {
                            HorizontalDivider()
                            Text(
                                text = "Attachments",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(message.attachments) { attachment ->
                            val attachId = attachment.id.toLong()
                            AttachmentRow(
                                attachment = attachment,
                                state = attachmentStates[attachId] ?: AttachmentState.Idle,
                                onTap = {
                                    val attachState = attachmentStates[attachId]
                                    if (attachState is AttachmentState.Ready) {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(attachState.uri, context.contentResolver.getType(attachState.uri))
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            scope.launch { snackbarHostState.showSnackbar("No app found to open this file") }
                                        }
                                    } else if (
                                        attachState !is AttachmentState.Downloading &&
                                        attachState !is AttachmentState.BlockedMimeType
                                    ) {
                                        viewModel.downloadAttachment(attachId, attachment)
                                    }
                                },
                            )
                        }
                    }

                    item {
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                        ThreadSection(
                            messageId = messageId,
                            threadState = threadState,
                            onRetry = { viewModel.reloadThread(messageId) },
                            onNavigateToMessage = { threadMsgId ->
                                navController.navigate("message/$threadMsgId") {
                                    popUpTo("message/$messageId") { inclusive = true }
                                }
                            },
                        )
                    }

                    item {
                        HorizontalDivider()
                        ActionBar(
                            folderId = folderId,
                            onReply = { navController.navigate("compose?replyTo=$messageId") },
                            onReplyAll = { navController.navigate("compose?replyAllTo=$messageId") },
                            onForward = { navController.navigate("compose?forwardOf=$messageId") },
                            onMove = { showMovePicker = true },
                            onMarkJunk = {
                                viewModel.markJunk(messageId) { setNavResultAndPop() }
                            },
                            onDelete = {
                                if (folderId == JUNK_ID || folderId == TRASH_ID) {
                                    showDeleteConfirm = true
                                } else {
                                    viewModel.deleteMessage(messageId) { setNavResultAndPop() }
                                }
                            },
                            onEdit = { navController.navigate("compose?draftId=$messageId") },
                            onDiscard = { showDiscardConfirm = true },
                            onCancelScheduled = { showCancelScheduledConfirm = true },
                            onCancelSnooze = {
                                viewModel.cancelSnooze(messageId) { setNavResultAndPop() }
                            },
                            onNotJunk = {
                                viewModel.markNotJunk(messageId) { setNavResultAndPop() }
                            },
                        )
                    }
                }

                if (showDiscardConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDiscardConfirm = false },
                        title = { Text("Discard draft") },
                        text = { Text("Permanently discard this draft?") },
                        confirmButton = {
                            TextButton(onClick = {
                                showDiscardConfirm = false
                                viewModel.discardDraft(messageId) { setNavResultAndPop() }
                            }) { Text("Discard") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDiscardConfirm = false }) { Text("Cancel") }
                        },
                    )
                }

                if (showCancelScheduledConfirm) {
                    AlertDialog(
                        onDismissRequest = { showCancelScheduledConfirm = false },
                        title = { Text("Cancel scheduled send") },
                        text = { Text("Move this message back to Drafts?") },
                        confirmButton = {
                            TextButton(onClick = {
                                showCancelScheduledConfirm = false
                                viewModel.cancelScheduled(messageId) { setNavResultAndPop() }
                            }) { Text("Cancel send") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCancelScheduledConfirm = false }) { Text("Keep") }
                        },
                    )
                }

                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text("Delete message") },
                        text = { Text("Permanently delete this message?") },
                        confirmButton = {
                            TextButton(onClick = {
                                showDeleteConfirm = false
                                viewModel.deleteMessage(messageId) { setNavResultAndPop() }
                            }) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                        },
                    )
                }

                if (showMovePicker) {
                    val excludedFolderIds = setOf(SCHEDULED_ID, SNOOZED_ID, DRAFTS_ID, folderId)
                    val movableFolders = folders.filter { it.id.toLong() !in excludedFolderIds }
                    val sheetState = rememberModalBottomSheetState()
                    ModalBottomSheet(
                        onDismissRequest = { showMovePicker = false },
                        sheetState = sheetState,
                    ) {
                        Text(
                            text = "Move to folder",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        if (movableFolders.isEmpty()) {
                            Text(
                                text = "No folders available",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        } else {
                            movableFolders.forEach { folder ->
                                TextButton(
                                    onClick = {
                                        showMovePicker = false
                                        viewModel.moveMessage(messageId, folder.id.toLong()) {
                                            setNavResultAndPop()
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                ) {
                                    Text(
                                        text = folder.name,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderSection(
    message: MessageDetail,
    onCopyDate: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val folderId = message.folderId.toLong()
    val dateFormatted = formatMessageDetailDate(message.date)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.fromAddr.ifBlank { "(no sender)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (!expanded) {
                    Text(
                        text = message.subject.ifBlank { "(no subject)" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse header" else "Expand header",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HeaderRow("From", message.fromAddr)
                HeaderRow("To", message.toAddr)
                if (message.ccAddr.isNotBlank()) HeaderRow("Cc", message.ccAddr)
                if (message.bccAddr.isNotBlank()) HeaderRow("Bcc", message.bccAddr)
                if (message.replyToAddr.isNotBlank()) HeaderRow("Reply-To", message.replyToAddr)

                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Date",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.25f),
                    )
                    Text(
                        text = dateFormatted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(0.75f)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { onCopyDate(message.date.toString()) },
                            ),
                    )
                }

                HeaderRow("Subject", message.subject.ifBlank { "(no subject)" })

                if (folderId == SNOOZED_ID && message.snoozedUntil != null) {
                    HeaderRow("Snoozed until", formatMessageDetailDate(message.snoozedUntil!!))
                }

                if (folderId == SCHEDULED_ID && message.sendAt != null) {
                    HeaderRow("Scheduled for", formatMessageDetailDate(message.sendAt!!))
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun HeaderRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.25f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.75f),
        )
    }
}

@Composable
private fun AttachmentRow(
    attachment: AttachmentMeta,
    state: AttachmentState,
    onTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = state !is AttachmentState.Downloading && state !is AttachmentState.BlockedMimeType) { onTap() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = attachment.filename,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = formatFileSize(attachment.propertySize.toLong()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is AttachmentState.Downloading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        }
        when (state) {
            is AttachmentState.BlockedMimeType -> {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            is AttachmentState.Error -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            else -> {}
        }
    }
}

@Composable
private fun ThreadSection(
    messageId: Long,
    threadState: ThreadUiState,
    onRetry: () -> Unit,
    onNavigateToMessage: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Thread",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse thread" else "Expand thread",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            when (val ts = threadState) {
                is ThreadUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }

                is ThreadUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = ts.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }

                is ThreadUiState.Success -> {
                    if (ts.thread.truncated) {
                        Text(
                            text = "Thread too long — showing recent messages only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    ts.thread.items.forEach { summary ->
                        ThreadMessageRow(
                            summary = summary,
                            isCurrent = summary.id.toLong() == messageId,
                            onClick = {
                                if (summary.id.toLong() != messageId) {
                                    onNavigateToMessage(summary.id.toLong())
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadMessageRow(
    summary: MessageSummary,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isCurrent) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.fromAddr.ifBlank { "(no sender)" },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (!summary.read) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
            Text(
                text = summary.subject.ifBlank { "(no subject)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        val dateText = formatMessageListDate(summary.date)
        Text(
            text = dateText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionBar(
    folderId: Long,
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
    onMove: () -> Unit,
    onMarkJunk: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onDiscard: () -> Unit,
    onCancelScheduled: () -> Unit,
    onCancelSnooze: () -> Unit,
    onNotJunk: () -> Unit,
) {
    var showMoreMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            folderId == INBOX_ID || folderId >= 100L -> {
                TextButton(onClick = onReply) { Text("Reply") }
                TextButton(onClick = onReplyAll) { Text("Reply All") }
                TextButton(onClick = onForward) { Text("Forward") }
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Move") },
                            onClick = { showMoreMenu = false; onMove() },
                        )
                        DropdownMenuItem(
                            text = { Text("Junk") },
                            onClick = { showMoreMenu = false; onMarkJunk() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { showMoreMenu = false; onDelete() },
                        )
                    }
                }
            }
            folderId == SENT_ID -> {
                TextButton(onClick = onForward) { Text("Forward") }
                TextButton(onClick = onMove) { Text("Move") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
            folderId == DRAFTS_ID -> {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDiscard) { Text("Discard") }
            }
            folderId == SCHEDULED_ID -> {
                TextButton(onClick = onCancelScheduled) { Text("Cancel scheduled send") }
            }
            folderId == SNOOZED_ID -> {
                TextButton(onClick = onReply) { Text("Reply") }
                TextButton(onClick = onReplyAll) { Text("Reply All") }
                TextButton(onClick = onForward) { Text("Forward") }
                TextButton(onClick = onCancelSnooze) { Text("Cancel snooze") }
            }
            folderId == JUNK_ID -> {
                TextButton(onClick = onNotJunk) { Text("Not junk") }
                TextButton(onClick = onMove) { Text("Move") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
            folderId == TRASH_ID -> {
                TextButton(onClick = onMove) { Text("Move") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024.0 * 1024.0))} MB"
    }
}
