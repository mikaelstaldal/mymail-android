package nu.staldal.mymail.ui.screen.settings

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Named

enum class DarkModePreference { SYSTEM, LIGHT, DARK }
enum class MessageListDensity { COMPACT, NORMAL, RELAXED }

data class PreferencesState(
    val darkMode: DarkModePreference = DarkModePreference.SYSTEM,
    val messageListDensity: MessageListDensity = MessageListDensity.NORMAL,
    val newMailNotifications: Boolean = false,
)

@HiltViewModel
class PreferencesViewModel @Inject constructor(
    @Named("plain") private val prefs: SharedPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(loadFromPrefs())
    val state: StateFlow<PreferencesState> = _state.asStateFlow()

    private fun loadFromPrefs(): PreferencesState {
        val darkMode = when (prefs.getString(KEY_DARK_MODE, null)) {
            "LIGHT" -> DarkModePreference.LIGHT
            "DARK" -> DarkModePreference.DARK
            else -> DarkModePreference.SYSTEM
        }
        val density = when (prefs.getString(KEY_DENSITY, null)) {
            "COMPACT" -> MessageListDensity.COMPACT
            "RELAXED" -> MessageListDensity.RELAXED
            else -> MessageListDensity.NORMAL
        }
        val notifications = prefs.getBoolean(KEY_NOTIFICATIONS, false)
        return PreferencesState(darkMode, density, notifications)
    }

    fun setDarkMode(value: DarkModePreference) {
        prefs.edit().putString(KEY_DARK_MODE, value.name).apply()
        _state.value = _state.value.copy(darkMode = value)
    }

    fun setMessageListDensity(value: MessageListDensity) {
        prefs.edit().putString(KEY_DENSITY, value.name).apply()
        _state.value = _state.value.copy(messageListDensity = value)
    }

    fun setNewMailNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply()
        _state.value = _state.value.copy(newMailNotifications = enabled)
    }

    companion object {
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_DENSITY = "message_list_density"
        const val KEY_NOTIFICATIONS = "new_mail_notifications"
    }
}
