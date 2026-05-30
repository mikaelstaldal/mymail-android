package nu.staldal.mymail.ui.screen.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@Composable
fun PreferencesTab(
    snackbarHostState: SnackbarHostState,
    viewModel: PreferencesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setNewMailNotifications(true)
        } else {
            viewModel.setNewMailNotifications(false)
            scope.launch {
                snackbarHostState.showSnackbar("Notification permission was denied")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = "Dark mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        DarkModeSegmentedControl(
            selected = state.darkMode,
            onSelect = { viewModel.setDarkMode(it) },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Text(
            text = "Message list density",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        DensitySegmentedControl(
            selected = state.messageListDensity,
            onSelect = { viewModel.setMessageListDensity(it) },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "New mail notifications",
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = state.newMailNotifications,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val permissionStatus = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                            if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
                                viewModel.setNewMailNotifications(true)
                            } else {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else {
                            viewModel.setNewMailNotifications(true)
                        }
                    } else {
                        viewModel.setNewMailNotifications(false)
                    }
                },
            )
        }
    }
}

@Composable
private fun DarkModeSegmentedControl(
    selected: DarkModePreference,
    onSelect: (DarkModePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DarkModePreference.entries.forEach { mode ->
            val label = when (mode) {
                DarkModePreference.SYSTEM -> "System"
                DarkModePreference.LIGHT -> "Light"
                DarkModePreference.DARK -> "Dark"
            }
            SegmentedButton(
                label = label,
                selected = selected == mode,
                onClick = { onSelect(mode) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DensitySegmentedControl(
    selected: MessageListDensity,
    onSelect: (MessageListDensity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MessageListDensity.entries.forEach { density ->
            val label = when (density) {
                MessageListDensity.COMPACT -> "Compact"
                MessageListDensity.NORMAL -> "Normal"
                MessageListDensity.RELAXED -> "Relaxed"
            }
            SegmentedButton(
                label = label,
                selected = selected == density,
                onClick = { onSelect(density) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SegmentedButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
        },
        modifier = modifier,
    )
}
