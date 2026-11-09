package io.github.ddastoor.kindlify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ddastoor.kindlify.PendingShare
import io.github.ddastoor.kindlify.data.Pasted
import io.github.ddastoor.kindlify.data.Project

/**
 * Something was shared to Kindlify and there are several canvases: pick the one it goes
 * into (it's added at the end, after a separator), or Cancel to drop the share.
 */
@Composable
fun ShareTargetDialog(
    pending: PendingShare,
    openId: String?,
    onPick: (Project) -> Unit,
    onCancel: () -> Unit,
) {
    // Plain full-width Dialog (like the EPUB title box) rather than AlertDialog, which sizes
    // itself around its content and doesn't suit a scrolling list.
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 16.dp)) {
                Text(
                    "Add to which canvas?",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${describe(pending)} will be added at the end, after a separator.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    pending.choices.forEach { project ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button, onClickLabel = "Add here") { onPick(project) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text(
                                project.title.ifBlank { "Untitled" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                canvasSummary(project) + if (project.id == openId) " · open now" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

/** What's being shared, for the dialog: its subject, or what it contains. */
private fun describe(pending: PendingShare): String {
    pending.content.subject?.let { return "“$it”" }
    val texts = pending.content.items.count { it is Pasted.Text || it is Pasted.TextUri }
    val images = pending.content.items.count { it is Pasted.ImageUri }
    val parts = buildList {
        if (texts > 0) add(if (texts == 1) "1 text" else "$texts texts")
        if (images > 0) add(if (images == 1) "1 image" else "$images images")
    }
    return "The shared " + parts.joinToString(" and ").ifEmpty { "item" }
}
