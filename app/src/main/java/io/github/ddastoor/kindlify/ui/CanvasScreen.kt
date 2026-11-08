package io.github.ddastoor.kindlify.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ddastoor.kindlify.data.Block
import io.github.ddastoor.kindlify.data.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CanvasScreen(
    project: Project,
    imageFile: (Block.Image) -> File,
    busy: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onDiscard: () -> Unit,
    onTextChange: (blockId: String, text: String) -> Unit,
    onMove: (blockId: String, delta: Int) -> Unit,
    onRemove: (blockId: String) -> Unit,
    onPaste: () -> Unit,
    onAddImage: () -> Unit,
    onCreateEpub: (title: String) -> Unit,
    onExport: () -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var askTitle by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val keyboardOpen = WindowInsets.isImeVisible

    // When something new is pasted, scroll down to it.
    val blockCount = project.blocks.size
    var previousCount by remember { mutableIntStateOf(blockCount) }
    LaunchedEffect(blockCount) {
        if (blockCount > previousCount) listState.animateScrollToItem(blockCount)
        previousCount = blockCount
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(project.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to all canvases")
                    }
                },
                actions = {
                    IconButton(onClick = onAddImage, enabled = !busy) {
                        Icon(AppIcons.Image, contentDescription = "Add image from gallery")
                    }
                    TextButton(
                        onClick = { confirmDiscard = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Cancel") }
                },
            )
        },
        floatingActionButton = {
            if (!keyboardOpen) {
                ExtendedFloatingActionButton(onClick = { if (!busy) onPaste() }) {
                    Icon(AppIcons.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Paste")
                }
            }
        },
        bottomBar = {
            if (!keyboardOpen) {
                CanvasActions(
                    hasContent = project.blocks.isNotEmpty(),
                    epubReady = project.hasCurrentEpub,
                    busy = busy,
                    onCreateEpub = { askTitle = true },
                    onExport = onExport,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (project.blocks.isEmpty()) {
                item(key = "empty") { EmptyCanvasHint() }
            }
            itemsIndexed(project.blocks, key = { _, block -> block.id }) { index, block ->
                val isFirst = index == 0
                val isLast = index == project.blocks.lastIndex
                if (block is Block.Separator) {
                    SeparatorRow(
                        onMoveUp = if (isFirst) null else ({ onMove(block.id, -1) }),
                        onMoveDown = if (isLast) null else ({ onMove(block.id, 1) }),
                        onRemove = { onRemove(block.id) },
                        modifier = Modifier.animateItem(),
                    )
                } else {
                    BlockCard(
                        block = block,
                        imageFile = imageFile,
                        isFirst = isFirst,
                        isLast = isLast,
                        onTextChange = onTextChange,
                        onMove = onMove,
                        onRemove = onRemove,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (askTitle) {
        EpubTitleDialog(
            initialTitle = project.title,
            onDismiss = { askTitle = false },
            onCreate = { title ->
                askTitle = false
                onCreateEpub(title)
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this canvas?") },
            text = { Text("Everything on it, and any EPUB made from it, will be deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onDiscard()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}

@Composable
private fun CanvasActions(
    hasContent: Boolean,
    epubReady: Boolean,
    busy: Boolean,
    onCreateEpub: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 10.dp))
            }
            Text(
                text = when {
                    busy -> "Working…"
                    !hasContent -> "Copy text or images in any app, then tap Paste."
                    epubReady -> "EPUB ready. Export sends it to your Kindle app."
                    else -> "When you're done, create the EPUB."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = onCreateEpub,
                    enabled = hasContent && !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(AppIcons.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create EPUB")
                }
                Button(
                    onClick = onExport,
                    enabled = epubReady && !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export")
                }
            }
        }
    }
}

@Composable
private fun EmptyCanvasHint() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            AppIcons.ContentPaste,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("Your canvas is empty", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Copy text or an image in any app, come back and tap Paste. Everything you add shows up here, in order.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BlockCard(
    block: Block,
    imageFile: (Block.Image) -> File,
    isFirst: Boolean,
    isLast: Boolean,
    onTextChange: (String, String) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box {
            when (block) {
                is Block.Text -> BasicTextField(
                    value = block.text,
                    onValueChange = { onTextChange(block.id, it) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 48.dp),
                )
                is Block.Image -> FileImage(
                    file = imageFile(block),
                    width = block.width,
                    height = block.height,
                    modifier = Modifier.padding(8.dp),
                )
                is Block.Separator -> Unit // drawn by SeparatorRow
            }
            BlockMenu(
                onMoveUp = if (isFirst) null else ({ onMove(block.id, -1) }),
                onMoveDown = if (isLast) null else ({ onMove(block.id, 1) }),
                onRemove = { onRemove(block.id) },
                overImage = block is Block.Image,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/** A dividing line between items; becomes a horizontal rule in the EPUB. */
@Composable
private fun SeparatorRow(
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(48.dp)) {
        HorizontalDivider(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = 24.dp, end = 64.dp)
                .semantics { contentDescription = "Separator line" },
            thickness = 2.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        BlockMenu(
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onRemove = onRemove,
            overImage = false,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/** Asks for the book title before creating the EPUB, pre-filled with the canvas title. */
@Composable
private fun EpubTitleDialog(initialTitle: String, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    // Cursor at the end; the ✕ button clears the default "Untitled <date>" in one tap.
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialTitle, selection = TextRange(initialTitle.length)))
    }
    val canCreate = value.text.isNotBlank()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // A plain full-width Dialog styled like an AlertDialog: Material's AlertDialog sizes
    // itself around its content, which doesn't settle with a text field inside it.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text("Create EPUB", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Book title") },
                    singleLine = true,
                    trailingIcon = {
                        if (value.text.isNotEmpty()) {
                            IconButton(onClick = { value = TextFieldValue("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear title")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (canCreate) onCreate(value.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onCreate(value.text) }, enabled = canCreate) { Text("Create") }
                }
            }
        }
    }
}

@Composable
private fun BlockMenu(
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: () -> Unit,
    overImage: Boolean,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.padding(if (overImage) 20.dp else 0.dp)) {
        IconButton(
            onClick = { open = true },
            modifier = if (overImage) {
                Modifier.size(36.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
            } else {
                Modifier
            },
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "Item options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Move up") },
                leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null) },
                enabled = onMoveUp != null,
                onClick = { open = false; onMoveUp?.invoke() },
            )
            DropdownMenuItem(
                text = { Text("Move down") },
                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                enabled = onMoveDown != null,
                onClick = { open = false; onMoveDown?.invoke() },
            )
            DropdownMenuItem(
                text = { Text("Remove") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = { open = false; onRemove() },
            )
        }
    }
}

@Composable
private fun FileImage(file: File, width: Int, height: Int, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { decodePreview(file, 1440)?.asImageBitmap() }
    }
    val ratio = (width.toFloat() / height.coerceAtLeast(1)).coerceIn(0.5f, 4f)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        bitmap?.let {
            Image(it, contentDescription = "Pasted image", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

/** Decodes a downsampled copy for display, so big images don't use lots of memory. */
private fun decodePreview(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}
