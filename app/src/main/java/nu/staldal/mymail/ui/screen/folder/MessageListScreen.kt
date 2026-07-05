package nu.staldal.mymail.ui.screen.folder

import android.app.NotificationManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import nu.staldal.mymail.DRAFTS_ID
import nu.staldal.mymail.INBOX_ID
import nu.staldal.mymail.JUNK_ID
import nu.staldal.mymail.SCHEDULED_ID
import nu.staldal.mymail.SNOOZED_ID
import nu.staldal.mymail.TRASH_ID
import nu.staldal.mymail.model.Folder
import nu.staldal.mymail.model.MessageSummary
import nu.staldal.mymail.utils.formatMessageListDate
import java.time.OffsetDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageListScreen(
    navController: NavController,
    folderId: Long,
    viewModel: MessageListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isMultiSelectMode by viewModel.isMultiSelectMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val folderName by viewModel.folderName.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val isLoadingNextPage by viewModel.isLoadingNextPage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(folderId) {
        viewModel.loadMessages(folderId)
        viewModel.loadFolders()
    }

    // Clear notifications, reset persisted baseline, and reset in-memory poller baseline when entering Inbox
    LaunchedEffect(Unit) {
        if (folderId == INBOX_ID) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancelAll()
            context.getSharedPreferences("mymail_prefs", Context.MODE_PRIVATE)
                .edit()
                .remove("inbox_unread_count")
                .apply()
            (context as? nu.staldal.mymail.MainActivity)?.resetForegroundPollerBaseline()
        }
    }

    // Observe needsRefresh from SavedStateHandle
    val needsRefresh by navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow("needsRefresh", false)
        ?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) }

    LaunchedEffect(needsRefresh) {
        if (needsRefresh == true) {
            viewModel.refresh()
            navController.currentBackStackEntry?.savedStateHandle?.set("needsRefresh", false)
        }
    }

    // Collect snackbar messages from ViewModel
    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Infinite scroll: trigger next page when near end of list
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 3
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadNextPage()
        }
    }

    // System Back exits multi-select mode
    BackHandler(enabled = isMultiSelectMode) {
        viewModel.exitMultiSelect()
    }

    // 404 error: navigate back to folder list
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is MessageListUiState.Error && state.is404) {
            snackbarHostState.showSnackbar("Folder not found")
            navController.popBackStack()
        }
    }

    val multiSelectForbidden = folderId == DRAFTS_ID || folderId == SCHEDULED_ID || folderId == SNOOZED_ID
    val showMarkAllRead = folderId != DRAFTS_ID && folderId != SCHEDULED_ID
    val showEmptyFolder = folderId == TRASH_ID || folderId == JUNK_ID

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showEmptyFolderDialog by remember { mutableStateOf(false) }

    val isRefreshing = uiState is MessageListUiState.Loading

    Scaffold(
        topBar = {
            if (isMultiSelectMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitMultiSelect() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Exit selection",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.markReadUnread() }) {
                            Icon(
                                imageVector = Icons.Filled.MarkEmailRead,
                                contentDescription = "Mark read/unread",
                            )
                        }
                        IconButton(onClick = { showMovePicker = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                                contentDescription = "Move to folder",
                            )
                        }
                        IconButton(onClick = {
                            val needsConfirm = folderId == TRASH_ID || folderId == JUNK_ID
                            if (needsConfirm) {
                                showDeleteConfirmDialog = true
                            } else {
                                viewModel.deleteSelected()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text(folderName.ifEmpty { "Messages" }) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    actions = {
                        if (showMarkAllRead || showEmptyFolder) {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = "More options",
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                if (showMarkAllRead) {
                                    DropdownMenuItem(
                                        text = { Text("Mark all as read") },
                                        onClick = {
                                            showOverflowMenu = false
                                            viewModel.markAllRead(folderId)
                                        },
                                    )
                                }
                                if (showEmptyFolder) {
                                    DropdownMenuItem(
                                        text = { Text("Empty folder") },
                                        onClick = {
                                            showOverflowMenu = false
                                            showEmptyFolderDialog = true
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!isMultiSelectMode) {
                FloatingActionButton(onClick = { navController.navigate("compose") }) {
                    Icon(
                        imageVector = Icons.Filled.Create,
                        contentDescription = "Compose",
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is MessageListUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is MessageListUiState.Success -> {
                    if (state.messages.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No messages",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        val density = viewModel.messageDensity
                        val rowHeight: Dp = when (density) {
                            MessageDensity.Compact -> 56.dp
                            MessageDensity.Normal -> 72.dp
                            MessageDensity.Relaxed -> 88.dp
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(state.messages) { _, message ->
                                MessageRow(
                                    message = message,
                                    folderId = folderId,
                                    rowHeight = rowHeight,
                                    isSelected = message.id.toLong() in selectedIds,
                                    isMultiSelectMode = isMultiSelectMode,
                                    onClick = {
                                        if (isMultiSelectMode) {
                                            viewModel.toggleSelection(message.id.toLong())
                                        } else {
                                            navController.navigate("message/${message.id}")
                                        }
                                    },
                                    onLongClick = {
                                        if (!multiSelectForbidden) {
                                            viewModel.enterMultiSelect(message.id.toLong())
                                        }
                                    },
                                )
                                HorizontalDivider()
                            }

                            if (isLoadingNextPage) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                is MessageListUiState.Error -> {
                    if (!state.is404) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
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
                            Button(onClick = { viewModel.loadMessages(folderId) }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog for permanent deletion in Trash/Junk
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete messages") },
            text = { Text("Permanently delete the selected messages?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmDialog = false
                    viewModel.deleteSelected()
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Empty folder confirmation dialog
    if (showEmptyFolderDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyFolderDialog = false },
            title = { Text("Empty folder") },
            text = { Text("Permanently delete all messages in this folder?") },
            confirmButton = {
                TextButton(onClick = {
                    showEmptyFolderDialog = false
                    viewModel.deleteAllMessages(folderId)
                }) {
                    Text("Delete all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyFolderDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Folder picker dialog for multi-select move
    if (showMovePicker) {
        val excludedFolderIds = setOf(SCHEDULED_ID, SNOOZED_ID, DRAFTS_ID, folderId)
        val movableFolders = folders.filter { it.id.toLong() !in excludedFolderIds }
        FolderPickerDialog(
            folders = movableFolders,
            onFolderSelected = { targetFolderId ->
                showMovePicker = false
                viewModel.moveSelectedToFolder(targetFolderId)
            },
            onDismiss = { showMovePicker = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: MessageSummary,
    folderId: Long,
    rowHeight: Dp,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isMultiSelectMode) {
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                contentDescription = if (isSelected) "Selected" else "Not selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.fromAddr.ifBlank { "(no sender)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (!message.read) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(8.dp))
                val dateText = formatMessageListDate(message.date)
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.subject.ifBlank { "(no subject)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (!message.read) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (message.hasAttachments) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.Attachment,
                        contentDescription = "Has attachments",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (message.sendFailed && (folderId == SCHEDULED_ID || folderId == DRAFTS_ID)) {
                    Spacer(modifier = Modifier.width(4.dp))
                    val badgeColor =
                        if (folderId == SCHEDULED_ID) Color(0xFFFFC107) else Color(0xFFD32F2F)
                    Text(
                        text = "Send failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeColor)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderPickerDialog(
    folders: List<Folder>,
    onFolderSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            LazyColumn {
                items(folders) { folder ->
                    TextButton(
                        onClick = { onFolderSelected(folder.id.toLong()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = folder.name,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
