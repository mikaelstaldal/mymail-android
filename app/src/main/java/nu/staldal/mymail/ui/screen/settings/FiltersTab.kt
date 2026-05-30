package nu.staldal.mymail.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import nu.staldal.mymail.model.Filter
import nu.staldal.mymail.model.Folder

@Composable
fun FiltersTab(
    filtersViewModel: FiltersViewModel = hiltViewModel(),
    foldersViewModel: FoldersTabViewModel = hiltViewModel(),
) {
    val filtersState by filtersViewModel.uiState.collectAsState()
    val foldersState by foldersViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        filtersViewModel.load()
        if (foldersState is FoldersTabUiState.Loading) {
            foldersViewModel.load()
        }
    }

    val folders: List<Folder> = when (val fs = foldersState) {
        is FoldersTabUiState.Success -> fs.folders
        else -> emptyList()
    }

    when (val state = filtersState) {
        is FiltersUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is FiltersUiState.Success -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.filters) { filter ->
                    FilterRow(filter = filter, folders = folders)
                    HorizontalDivider()
                }
            }
        }

        is FiltersUiState.Error -> {
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
                Button(onClick = { filtersViewModel.load() }) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun FilterRow(filter: Filter, folders: List<Folder>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = filter.name,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = buildFilterSummary(filter, folders),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun buildFilterSummary(filter: Filter, folders: List<Folder>): String {
    val conditionParts = buildList {
        if (!filter.matchFrom.isNullOrEmpty()) add("from contains '${filter.matchFrom}'")
        if (!filter.matchTo.isNullOrEmpty()) add("to contains '${filter.matchTo}'")
        if (!filter.matchSubject.isNullOrEmpty()) add("subject contains '${filter.matchSubject}'")
    }
    val condition = if (conditionParts.isEmpty()) "(matches all)" else conditionParts.joinToString(" AND ")

    val action = when (filter.action) {
        Filter.Action.move -> {
            val folderId = filter.folderId
            val folderName = folders.find { it.id == folderId }?.name
            if (folderName != null) "Move to $folderName" else "Move to folder #$folderId"
        }
        Filter.Action.trash -> "Move to Trash"
        Filter.Action.mark_read -> "Mark as read"
        Filter.Action.drop -> "Drop"
    }

    return "If $condition → $action"
}
