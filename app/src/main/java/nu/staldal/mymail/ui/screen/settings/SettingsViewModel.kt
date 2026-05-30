package nu.staldal.mymail.ui.screen.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import nu.staldal.mymail.auth.CredentialStore
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val credentialStore: CredentialStore,
) : ViewModel()
