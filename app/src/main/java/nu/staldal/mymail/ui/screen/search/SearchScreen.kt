package nu.staldal.mymail.ui.screen.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import nu.staldal.mymail.repository.MessageSummaryWithSnippet
import nu.staldal.mymail.ui.component.ScheduleTimeLabel
import nu.staldal.mymail.utils.formatMessageListDate
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateDisplayFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val query by viewModel.query.collectAsState()
    val refinements by viewModel.refinements.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val isLoadingNextPage by viewModel.isLoadingNextPage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Observe removedMessageId from SavedStateHandle
    val removedMessageId by navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow<Long?>("removedMessageId", null)
        ?.collectAsState(initial = null)
        ?: remember { mutableStateOf(null) }

    LaunchedEffect(removedMessageId) {
        removedMessageId?.let { id ->
            viewModel.removeMessageById(id)
            navController.currentBackStackEntry?.savedStateHandle?.set("removedMessageId", null)
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

    var showFolderDropdown by remember { mutableStateOf(false) }
    var showDateFromPicker by remember { mutableStateOf(false) }
    var showDateToPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Search bar
            TextField(
                value = query,
                onValueChange = { newVal ->
                    if (newVal.length <= 500) {
                        viewModel.setQuery(newVal)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search mail…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Clear search",
                            )
                        }
                    }
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )

            // Address filter row: From / To. Both match as a case-insensitive substring, To
            // against either the To or the Cc header.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AddressFilterField(
                    value = refinements.fromAddr,
                    onValueChange = viewModel::setFromAddr,
                    label = "From",
                    clearContentDescription = "Clear from address",
                    onSearch = viewModel::search,
                    modifier = Modifier.weight(1f),
                )
                AddressFilterField(
                    value = refinements.toAddr,
                    onValueChange = viewModel::setToAddr,
                    label = "To / Cc",
                    clearContentDescription = "Clear to address",
                    onSearch = viewModel::search,
                    modifier = Modifier.weight(1f),
                )
            }

            // Filter row: folder + date range
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Folder filter
                Box {
                    OutlinedButton(onClick = { showFolderDropdown = true }) {
                        val selectedFolderName = if (refinements.folderId == null) {
                            "All mail"
                        } else {
                            folders.firstOrNull { it.id.toLong() == refinements.folderId }?.name ?: "All mail"
                        }
                        Text(selectedFolderName, maxLines = 1)
                    }
                    DropdownMenu(
                        expanded = showFolderDropdown,
                        onDismissRequest = { showFolderDropdown = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("All mail")
                                    Text(
                                        text = "Excludes Junk, Drafts & Scheduled",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                viewModel.setFolderId(null)
                                showFolderDropdown = false
                            },
                        )
                        folders.forEach { folder ->
                            DropdownMenuItem(
                                text = { Text(folder.name) },
                                onClick = {
                                    viewModel.setFolderId(folder.id.toLong())
                                    showFolderDropdown = false
                                },
                            )
                        }
                    }
                }

                // Date from picker
                OutlinedButton(onClick = { showDateFromPicker = true }) {
                    Text(
                        text = refinements.dateFrom?.format(dateDisplayFormatter) ?: "From date",
                        maxLines = 1,
                    )
                }
                if (refinements.dateFrom != null) {
                    IconButton(
                        onClick = { viewModel.setDateFrom(null) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Clear from date",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                // Date to picker
                OutlinedButton(onClick = { showDateToPicker = true }) {
                    Text(
                        text = refinements.dateTo?.format(dateDisplayFormatter) ?: "To date",
                        maxLines = 1,
                    )
                }
                if (refinements.dateTo != null) {
                    IconButton(
                        onClick = { viewModel.setDateTo(null) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Clear to date",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            HorizontalDivider()

            // Content area
            Box(modifier = Modifier.fillMaxSize()) {
                when (val state = uiState) {
                    is SearchUiState.Idle -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Enter a search query",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is SearchUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    is SearchUiState.Success -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(state.results, key = { it.summary.id }) { item ->
                                SearchResultRow(
                                    item = item,
                                    onClick = {
                                        navController.navigate("message/${item.summary.id}")
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

                    is SearchUiState.Error -> {
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
                            Button(onClick = { viewModel.search() }) {
                                Text("Retry")
                            }
                        }
                    }

                    is SearchUiState.Empty -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No results",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // Date from picker dialog
    if (showDateFromPicker) {
        val initialMillis = refinements.dateFrom
            ?.atStartOfDay(ZoneId.systemDefault())
            ?.toInstant()
            ?.toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDateFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        val selected = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                        viewModel.setDateFrom(selected)
                    }
                    showDateFromPicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDateFromPicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Date to picker dialog
    if (showDateToPicker) {
        val initialMillis = refinements.dateTo
            ?.atStartOfDay(ZoneId.systemDefault())
            ?.toInstant()
            ?.toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDateToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        val selected = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                        viewModel.setDateTo(selected)
                    }
                    showDateToPicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDateToPicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/**
 * One of the two address filters. Typing does not issue a request directly — the ViewModel
 * debounces — but the keyboard's search action commits immediately.
 */
@Composable
private fun AddressFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    clearContentDescription: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newVal ->
            if (newVal.length <= ADDRESS_FILTER_MAX_LENGTH) {
                onValueChange(newVal)
            }
        },
        modifier = modifier,
        label = { Text(label) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = clearContentDescription,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun SearchResultRow(
    item: MessageSummaryWithSnippet,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.summary.fromAddr.ifBlank { "(no sender)" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (!item.summary.read) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(8.dp))
            val dateText = formatMessageListDate(item.summary.date)
            Text(
                text = dateText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = item.summary.subject.ifBlank { "(no subject)" },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (!item.summary.read) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
        // A scheduled or snoozed message found here carries its time too.
        ScheduleTimeLabel(message = item.summary)
        Text(
            text = parseSnippet(item.snippet),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

private fun parseSnippet(snippet: String): AnnotatedString {
    return buildAnnotatedString {
        val parts = snippet.split("**")
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(part)
                }
            } else {
                append(part)
            }
        }
    }
}
