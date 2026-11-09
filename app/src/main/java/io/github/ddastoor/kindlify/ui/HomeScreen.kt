package io.github.ddastoor.kindlify.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ddastoor.kindlify.AppViewModel
import io.github.ddastoor.kindlify.BuildConfig
import io.github.ddastoor.kindlify.data.Block
import io.github.ddastoor.kindlify.data.Project

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    projects: List<Project>,
    snackbar: SnackbarHostState,
    onNew: () -> Unit,
    onOpen: (Project) -> Unit,
    onDelete: (Project) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kindlify") },
                actions = {
                    if (projects.isNotEmpty()) {
                        TextButton(
                            onClick = { confirmDeleteAll = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Delete all") }
                    }
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
        floatingActionButton = {
            // The content-lambda variant keeps the label visible to TalkBack (and tests).
            ExtendedFloatingActionButton(onClick = onNew) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("New canvas")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (projects.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    AppIcons.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Text("No canvases yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Start a canvas, paste in text and images, then turn it into an EPUB for your Kindle.\n\n" +
                        "You can also share text or images to Kindlify from any app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "history-note") {
                    Text(
                        "Your ${AppViewModel.MAX_CANVASES} most recently edited canvases are kept. " +
                            "Starting another one removes the oldest. Long-press a canvas to delete it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onClick = { onOpen(project) },
                        onLongClick = { deletingId = project.id },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    projects.firstOrNull { it.id == deletingId }?.let { project ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("Delete this canvas?") },
            text = { Text("“${project.title.ifBlank { "Untitled" }}”, and any EPUB made from it, will be deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingId = null
                        onDelete(project)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Keep") } },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all canvases?") },
            text = {
                val count = if (projects.size == 1) "Your 1 canvas" else "All ${projects.size} canvases"
                Text("$count, with their pictures and EPUBs, will be deleted. This can't be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteAll = false
                        onDeleteAll()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

/** Tap opens the canvas; long-press asks to delete it. */
@Composable
private fun ProjectCard(project: Project, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardDefaults.shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onLongClickLabel = "Delete",
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                project.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                canvasSummary(project),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "2 texts · 1 image · 5 min. ago · EPUB ready" */
internal fun canvasSummary(project: Project): String {
    val texts = project.blocks.count { it is Block.Text }
    val images = project.blocks.count { it is Block.Image }
    val parts = buildList {
        if (texts > 0) add(if (texts == 1) "1 text" else "$texts texts")
        if (images > 0) add(if (images == 1) "1 image" else "$images images")
        if (isEmpty()) add("Empty")
        val now = System.currentTimeMillis()
        add(
            if (now - project.updatedAt < DateUtils.MINUTE_IN_MILLIS) {
                "Just now"
            } else {
                DateUtils.getRelativeTimeSpanString(
                    project.updatedAt,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE,
                ).toString()
            },
        )
        if (project.hasCurrentEpub) add("EPUB ready")
    }
    return parts.joinToString(" · ")
}
