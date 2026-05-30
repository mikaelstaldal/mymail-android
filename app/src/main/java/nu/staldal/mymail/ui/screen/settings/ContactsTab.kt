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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import nu.staldal.mymail.model.Contact

private sealed class ContactDialog {
    data object Create : ContactDialog()
    data class Edit(val contact: Contact) : ContactDialog()
    data class ConfirmDelete(val contact: Contact) : ContactDialog()
}

@Composable
fun ContactsTab(
    snackbarHostState: SnackbarHostState,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val listState by viewModel.listState.collectAsState()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var activeDialog by remember { mutableStateOf<ContactDialog?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadInitial()
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = lazyListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0)
            totalItems > 0 && lastVisibleIndex >= totalItems - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        snapshotFlow { shouldLoadMore }.collect { load ->
            if (load) viewModel.loadNextPage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = listState.query,
                onValueChange = { viewModel.onQueryChange(it) },
                label = { Text("Search contacts") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when {
                listState.isLoadingInitial -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                listState.initialError != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = listState.initialError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        Button(onClick = { viewModel.loadInitial() }) {
                            Text("Retry")
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 80.dp),
                    ) {
                        itemsIndexed(listState.contacts) { _, contact ->
                            ContactRow(
                                contact = contact,
                                onClick = { activeDialog = ContactDialog.Edit(contact) },
                            )
                            HorizontalDivider()
                        }

                        if (listState.isLoadingMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }

                        if (listState.pageError != null) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = listState.pageError,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(onClick = { viewModel.loadNextPage() }) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { activeDialog = ContactDialog.Create },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = "Add contact")
        }
    }

    when (val dialog = activeDialog) {
        is ContactDialog.Create -> {
            ContactEditDialog(
                title = "New contact",
                initialName = "",
                initialAddress = "",
                onConfirm = { name, address, setError, setLoading ->
                    scope.launch {
                        setLoading(true)
                        val result = viewModel.createContact(address = address, name = name)
                        setLoading(false)
                        when (result) {
                            is ContactDialogResult.Success -> activeDialog = null
                            is ContactDialogResult.CloseDialog -> activeDialog = null
                            is ContactDialogResult.InlineError -> setError(result.message)
                        }
                    }
                },
                onDismiss = { activeDialog = null },
            )
        }

        is ContactDialog.Edit -> {
            ContactEditDialog(
                title = "Edit contact",
                initialName = dialog.contact.name,
                initialAddress = dialog.contact.address,
                onConfirm = { name, address, setError, setLoading ->
                    scope.launch {
                        setLoading(true)
                        val result = viewModel.updateContact(
                            id = dialog.contact.id.toLong(),
                            address = address,
                            name = name,
                        )
                        setLoading(false)
                        when (result) {
                            is ContactDialogResult.Success -> activeDialog = null
                            is ContactDialogResult.CloseDialog -> activeDialog = null
                            is ContactDialogResult.InlineError -> setError(result.message)
                        }
                    }
                },
                onDismiss = { activeDialog = null },
                showDelete = true,
                onDelete = { activeDialog = ContactDialog.ConfirmDelete(dialog.contact) },
            )
        }

        is ContactDialog.ConfirmDelete -> {
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                title = { Text("Delete contact") },
                text = { Text("Delete ${dialog.contact.address}?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                viewModel.deleteContact(dialog.contact.id.toLong())
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
private fun ContactRow(
    contact: Contact,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (contact.name.isNotEmpty()) {
            Text(text = contact.name, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = contact.address,
            style = if (contact.name.isNotEmpty()) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = if (contact.name.isNotEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ContactEditDialog(
    title: String,
    initialName: String,
    initialAddress: String,
    onConfirm: (name: String, address: String, setError: (String) -> Unit, setLoading: (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    showDelete: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var address by remember { mutableStateOf(initialAddress) }
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
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        inlineError = null
                    },
                    label = { Text("Email address") },
                    singleLine = true,
                    enabled = !isLoading,
                    isError = inlineError != null,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showDelete && onDelete != null) {
                    TextButton(onClick = onDelete, enabled = !isLoading) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(
                    onClick = {
                        if (address.isBlank()) {
                            inlineError = "Email address is required"
                            return@TextButton
                        }
                        onConfirm(
                            name.trim(),
                            address.trim(),
                            { error -> inlineError = error },
                            { loading -> isLoading = loading },
                        )
                    },
                    enabled = !isLoading,
                ) {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
            }
        },
    )
}
