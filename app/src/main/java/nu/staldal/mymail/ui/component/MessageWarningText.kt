package nu.staldal.mymail.ui.component

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nu.staldal.mymail.model.MessageWarning
import nu.staldal.mymail.utils.warningText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageWarningText(warning: MessageWarning) {
    if (warning.kind != MessageWarning.Kind.deceptive_link) {
        Text(warningText(warning))
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(warningText(warning), modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
            }
        },
        state = rememberTooltipState(isPersistent = true),
    ) {
        Text(warningText(warning, abbreviateLinks = true))
    }
}
