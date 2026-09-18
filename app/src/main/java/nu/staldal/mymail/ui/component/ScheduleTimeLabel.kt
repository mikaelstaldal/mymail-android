package nu.staldal.mymail.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import nu.staldal.mymail.SCHEDULED_ID
import nu.staldal.mymail.SNOOZED_ID
import nu.staldal.mymail.model.MessageSummary
import nu.staldal.mymail.utils.formatScheduleDate
import java.time.OffsetDateTime

/**
 * When a message will be sent, or until when it is snoozed — the one thing the Scheduled and
 * Snoozed folders exist to tell you, shown in the listing so it costs no tap per message.
 *
 * Renders nothing anywhere else: the API sets each value only inside its own folder, and the
 * folder is checked as well so a stray value cannot mislabel a row.
 */
@Composable
fun ScheduleTimeLabel(message: MessageSummary, modifier: Modifier = Modifier) {
    val folderId = message.folderId.toLong()
    val icon: ImageVector
    val label: String
    val time: OffsetDateTime
    when {
        folderId == SCHEDULED_ID && message.sendAt != null -> {
            icon = Icons.Filled.Schedule
            label = "Send at"
            time = message.sendAt
        }

        folderId == SNOOZED_ID && message.snoozedUntil != null -> {
            icon = Icons.Filled.Snooze
            label = "Snoozed until"
            time = message.snoozedUntil
        }

        else -> return
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = formatScheduleDate(time),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
