package nu.staldal.mymail.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import nu.staldal.mymail.model.Folder

private sealed class FolderDialog {
    data object Create : FolderDialog()
    data class Rename(val folder: Folder) : FolderDialog()
    data class ConfirmDelete(val folder: Folder) : FolderDialog()
}

@Composable
fun FoldersTab(
    snackbarHostState: SnackbarHostState,
    viewModel: FoldersTabViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var activeDialog by remember { mutableStateOf<FolderDialog?>(null) }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is FoldersTabUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            is FoldersTabUiState.Success -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp),
                ) {
                    items(state.folders) { folder ->
                        FolderTabRow(
                            folder = folder,
                            onRename = { activeDialog = FolderDialog.Rename(folder) },
                            onDelete = { activeDialog = FolderDialog.ConfirmDelete(folder) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            is FoldersTabUiState.Error -> {
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
                    Button(onClick = { viewModel.load() }) {
                        Text("Retry")
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { activeDialog = FolderDialog.Create },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = "Create folder")
        }
    }

    when (val dialog = activeDialog) {
        is FolderDialog.Create -> {
            FolderNameDialog(
                title = "Create folder",
                initialName = "",
                confirmLabel = "Create",
                onConfirm = { name, setError, setLoading ->
                    scope.launch {
                        setLoading(true)
                        val result = viewModel.createFolder(name)
                        setLoading(false)
                        when (result) {
                            is FolderDialogResult.Success -> activeDialog = null
                            is FolderDialogResult.CloseDialog -> activeDialog = null
                            is FolderDialogResult.InlineError -> setError(result.message)
                        }
                    }
                },
                onDismiss = { activeDialog = null },
            )
        }

        is FolderDialog.Rename -> {
            FolderNameDialog(
                title = "Rename folder",
                initialName = dialog.folder.name,
                confirmLabel = "Rename",
                onConfirm = { name, setError, setLoading ->
                    scope.launch {
                        setLoading(true)
                        val result = viewModel.renameFolder(dialog.folder.id.toLong(), name)
                        setLoading(false)
                        when (result) {
                            is FolderDialogResult.Success -> activeDialog = null
                            is FolderDialogResult.CloseDialog -> activeDialog = null
                            is FolderDialogResult.InlineError -> setError(result.message)
                        }
                    }
                },
                onDismiss = { activeDialog = null },
            )
        }

        is FolderDialog.ConfirmDelete -> {
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                title = { Text("Delete folder") },
                text = { Text("Messages will be moved to Trash") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                viewModel.deleteFolder(dialog.folder.id.toLong())
                                activeDialog = null
                            }
                        },
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = null }) {
                        Text("Cancel")
                    }
                },
            )
        }

        null -> {}
    }
}

@Composable
private fun FolderTabRow(
    folder: Folder,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val isUserFolder = folder.id >= 100
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isUserFolder) Modifier.clickable(onClick = onRename) else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (isUserFolder) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete folder",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun FolderNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (name: String, setError: (String) -> Unit, setLoading: (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var inlineError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        inlineError = null
                    },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = inlineError != null,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (inlineError != null) {
                    Text(
                        text = inlineError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) {
                        inlineError = "Name is required"
                        return@TextButton
                    }
                    onConfirm(
                        name.trim(),
                        { error -> inlineError = error },
                        { loading -> isLoading = loading },
                    )
                },
                enabled = !isLoading,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
            }
        },
    )
}
