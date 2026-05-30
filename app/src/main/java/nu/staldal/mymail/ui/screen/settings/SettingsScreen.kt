package nu.staldal.mymail.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }
    val credentialStore = viewModel.credentialStore

    val tabs = listOf(
        "Identities",
        "Folders",
        "Filters",
        "Spam",
        "Contacts",
        "Preferences",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 0.dp,
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> IdentitiesTab()
                    1 -> FoldersTab(snackbarHostState = snackbarHostState)
                    2 -> FiltersTab()
                    3 -> SpamTab(snackbarHostState = snackbarHostState)
                    4 -> ContactsTab(snackbarHostState = snackbarHostState)
                    5 -> PreferencesTab(snackbarHostState = snackbarHostState)
                }
            }

            HorizontalDivider()
            ServerSection(
                serverUrl = credentialStore.serverUrl ?: "",
                onChangeServer = { navController.navigate("setup") },
            )
        }
    }
}

@Composable
private fun ServerSection(
    serverUrl: String,
    onChangeServer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Server",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = serverUrl.ifEmpty { "(not set)" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onChangeServer) {
            Text("Change server")
        }
    }
}
