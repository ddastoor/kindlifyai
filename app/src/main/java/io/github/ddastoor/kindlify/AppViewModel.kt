package io.github.ddastoor.kindlify

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.ddastoor.kindlify.data.Block
import io.github.ddastoor.kindlify.data.ContentImporter
import io.github.ddastoor.kindlify.data.Pasted
import io.github.ddastoor.kindlify.data.Project
import io.github.ddastoor.kindlify.data.ProjectStore
import io.github.ddastoor.kindlify.data.SharedContent
import io.github.ddastoor.kindlify.data.WebImporter
import io.github.ddastoor.kindlify.data.WebViewRenderer
import io.github.ddastoor.kindlify.data.soleLink
import io.github.ddastoor.kindlify.epub.EpubBlock
import io.github.ddastoor.kindlify.epub.EpubBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class AppViewModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {

    val store = ProjectStore(app.filesDir)
    private val importer = ContentImporter(app, store)
    private val web = WebImporter(importer, WebViewRenderer(app))

    /** All disk writes go through this one-at-a-time dispatcher, so saves never overlap. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val disk = Dispatchers.IO.limitedParallelism(1)
    private var saveJob: Job? = null

    var projects by mutableStateOf<List<Project>>(emptyList())
        private set

    /** The open canvas, or null when the home screen is showing. */
    var current by mutableStateOf<Project?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    /** Loads the list and reopens the canvas you were on if Android closed the app in the background. */
    private val restored: Job = viewModelScope.launch {
        val openId = savedState.get<String>(KEY_OPEN)
        val (all, open) = withContext(disk) { store.list() to openId?.let { store.load(it) } }
        projects = all
        if (open != null && current == null) current = open
    }

    // ---- Home ----

    fun newCanvas(title: String? = null): Project {
        flush()
        val now = System.currentTimeMillis()
        val name = title ?: (DEFAULT_TITLE_PREFIX + DateFormat.format("d MMM yyyy, HH:mm", now))
        val project = Project(UUID.randomUUID().toString(), name, now, now, emptyList())
        show(project)
        save(project)
        // Runs after the save above (the disk dispatcher is one-at-a-time), so the new canvas counts.
        viewModelScope.launch {
            projects = withContext(disk) {
                pruneHistory(keepId = project.id)
                store.list()
            }
        }
        return project
    }

    /**
     * Keeps the [MAX_CANVASES] most recently edited canvases (the order of the home screen
     * list) and deletes the rest, with their images and EPUBs. Never deletes [keepId].
     */
    private fun pruneHistory(keepId: String) {
        store.list()
            .filter { it.id != keepId }
            .drop(MAX_CANVASES - 1)
            .forEach { store.delete(it.id) }
    }

    /** A share waiting for you to pick which canvas it goes into (when there are several). */
    var pendingShare by mutableStateOf<PendingShare?>(null)
        private set

    /**
     * Text or images shared to Kindlify from another app:
     * - no canvases yet: starts a new canvas with them (titled from the share's subject, if any);
     * - one or more canvases: asks which one ([pendingShare]), with a "New canvas" option too;
     *   see [shareInto], [shareIntoNewCanvas] and [cancelShare].
     * Returns false if the intent wasn't a share.
     */
    fun receiveShare(intent: Intent): Boolean {
        val shared = runCatching { importer.readShare(intent) }.getOrNull() ?: return false
        viewModelScope.launch {
            // After a restart, wait until the canvas you had open is back before deciding.
            restored.join()
            if (shared.items.isEmpty()) {
                message("Nothing usable was shared. Kindlify takes text and images.")
                return@launch
            }
            val open = current
            val saved = withContext(disk) { store.list() }
            // The open canvas may have edits not saved yet: offer that copy rather than the one on disk.
            val canvases = saved.map { if (open != null && it.id == open.id) open else it } +
                listOfNotNull(open?.takeIf { o -> saved.none { it.id == o.id } })
            if (canvases.isEmpty()) {
                addAll(newCanvas(shared.subject), shared.items)
            } else {
                pendingShare = PendingShare(shared, canvases)
            }
        }
        return true
    }

    /** The canvas picked from the list for a pending share. */
    fun shareInto(project: Project) {
        val pending = pendingShare ?: return
        pendingShare = null
        appendShare(project, pending.content)
    }

    /** "New canvas" in the list: the share starts a new canvas (named after its subject, if any). */
    fun shareIntoNewCanvas() {
        val pending = pendingShare ?: return
        pendingShare = null
        addAll(newCanvas(pending.content.subject), pending.content.items)
    }

    /** Cancel in the canvas list: the share is dropped and nothing changes. */
    fun cancelShare() {
        if (pendingShare == null) return
        pendingShare = null
        message("Share cancelled. Nothing was added.")
    }

    /** Opens [target] (if it isn't already) and adds the share at the end, after a separator. */
    private fun appendShare(target: Project, shared: SharedContent) {
        val project = current?.takeIf { it.id == target.id } ?: target.also {
            flush() // keep any edits to the canvas being left
            show(it)
        }
        addAll(
            project,
            shared.items,
            separated = true,
            chapterTitle = shared.subject,
            doneMessage = "Added to the end of this canvas.",
        )
    }

    fun open(project: Project) = show(project)

    /** Leaves the canvas, keeping it as a draft. */
    fun close() {
        flush()
        show(null)
        refreshList()
    }

    /** The Cancel button: abandons the canvas and deletes everything in it. */
    fun discard() {
        val project = current ?: return
        saveJob?.cancel()
        show(null)
        viewModelScope.launch {
            withContext(disk) { store.delete(project.id) }
            refreshList()
        }
    }

    /** Long-press on the home screen: deletes one canvas, with its pictures and EPUB. */
    fun delete(project: Project) {
        if (current?.id == project.id) return discard()
        projects = projects.filterNot { it.id == project.id }
        viewModelScope.launch {
            withContext(disk) { store.delete(project.id) }
            refreshList()
        }
        message("Deleted “${project.title.ifBlank { "Untitled" }}”.")
    }

    /** The Delete all button on the home screen: deletes every canvas. */
    fun deleteAll() {
        if (current != null) return // only offered on the home screen
        projects = emptyList()
        viewModelScope.launch {
            // Queued behind any save still in flight, so nothing is written back afterwards.
            withContext(disk) { store.deleteAll() }
            refreshList()
        }
        message("All canvases deleted.")
    }

    // ---- Editing ----

    fun setText(blockId: String, text: String) = edit(debounce = true) { p ->
        p.copy(blocks = p.blocks.map { if (it.id == blockId && it is Block.Text) it.copy(text = text) else it })
    }

    fun move(blockId: String, delta: Int) = edit { p ->
        val from = p.blocks.indexOfFirst { it.id == blockId }
        val to = from + delta
        if (from < 0 || to !in p.blocks.indices) return@edit p
        p.copy(blocks = p.blocks.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun remove(blockId: String) {
        val project = current ?: return
        val block = project.blocks.firstOrNull { it.id == blockId } ?: return
        edit { p -> p.copy(blocks = p.blocks.filterNot { it.id == blockId }) }
        if (block is Block.Image) viewModelScope.launch(disk) { store.imageFile(project.id, block).delete() }
    }

    /** The Paste button: adds whatever text and images are on the clipboard. */
    fun paste() {
        val project = current ?: return
        val items = runCatching { importer.readClipboard() }.getOrDefault(emptyList())
        if (items.isEmpty()) {
            message("The clipboard is empty. Copy some text or an image first.")
            return
        }
        addAll(project, items)
    }

    /**
     * The launcher shortcuts: "Paste into latest canvas" (the open one, else the most recently
     * edited, else a new one) and "New canvas from clipboard". Adds the clipboard the same way
     * as the Paste button. Call only once the window has focus: Android lets an app read the
     * clipboard only then.
     */
    fun pasteFromShortcut(intoNewCanvas: Boolean) {
        val items = runCatching { importer.readClipboard() }.getOrDefault(emptyList()) // read now, while focused
        viewModelScope.launch {
            restored.join()
            if (items.isEmpty()) {
                message("The clipboard is empty. Copy some text or an image first.")
                return@launch
            }
            val target = when {
                intoNewCanvas -> newCanvas()
                else -> current
                    ?: withContext(disk) { store.list().firstOrNull() }?.also { show(it) }
                    ?: newCanvas()
            }
            addAll(target, items)
        }
    }

    /** Adds an image chosen from the photo picker. */
    fun addImage(uri: Uri) {
        val project = current ?: return
        addAll(project, listOf(Pasted.ImageUri(uri)))
    }

    /**
     * Imports [items] and appends them to [project]. With [separated], a separator line
     * (a new chapter, named [chapterTitle] if given) goes first, unless the canvas is still empty.
     */
    private fun addAll(
        project: Project,
        items: List<Pasted>,
        separated: Boolean = false,
        chapterTitle: String? = null,
        doneMessage: String? = null,
    ) {
        busy = true
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                items.map { runCatching { importer.toBlock(project.id, it) } }
            }
            busy = false
            val blocks = results.mapNotNull { it.getOrNull() }
            if (current?.id == project.id && blocks.isNotEmpty()) {
                edit { p ->
                    val separator = if (separated && p.blocks.isNotEmpty()) {
                        listOf(Block.Separator(UUID.randomUUID().toString(), chapterTitle))
                    } else {
                        emptyList()
                    }
                    p.copy(blocks = p.blocks + separator + blocks)
                }
                doneMessage?.let(::message)
            }
            results.forEach { r -> r.exceptionOrNull()?.let { Log.w(TAG, "Couldn't import item", it) } }
            val failed = results.count { it.isFailure }
            if (failed > 0) message("Couldn't read $failed item(s).")
        }
    }

    // ---- Fetch contents ----

    /**
     * The Fetch contents button: when the canvas holds only a link, downloads that page
     * in reader mode and appends its text and pictures after a separator, keeping the link.
     */
    fun fetchContents() {
        val project = current ?: return
        val url = project.soleLink() ?: run {
            message("Fetch contents works when the canvas has only a link in it.")
            return
        }
        busy = true
        viewModelScope.launch {
            val result = runCatching { web.fetch(project.id, url) }
            busy = false
            val page = result.getOrElse {
                Log.w(TAG, "Fetching $url failed", it)
                message("Couldn't fetch the page: ${it.message ?: it.javaClass.simpleName}")
                return@launch
            }
            if (current?.id != project.id) return@launch
            if (page.blocks.isEmpty()) {
                message("Couldn't find any readable content on that page.")
                return@launch
            }
            edit { p ->
                // A canvas still on its automatic "Untitled <date>" name takes the article's title.
                val title = if (p.title.startsWith(DEFAULT_TITLE_PREFIX) && !page.title.isNullOrBlank()) page.title else p.title
                // The separator starts the article's chapter, named after it in the table of contents.
                val separator = Block.Separator(UUID.randomUUID().toString(), page.title?.takeIf { it.isNotBlank() })
                p.copy(title = title, blocks = p.blocks + separator + page.blocks)
            }
            message("Page contents added below the link.")
        }
    }

    // ---- EPUB ----

    fun epubFile(project: Project): File? = if (project.hasCurrentEpub) store.epubFile(project) else null

    /** The Create EPUB button, once the book title has been confirmed in its dialog. */
    fun createEpub(title: String) {
        val name = title.trim()
        if (name.isNotEmpty()) edit { it.copy(title = name) }
        val project = current ?: return
        if (project.blocks.isEmpty()) {
            message("Paste something in first.")
            return
        }
        busy = true
        viewModelScope.launch {
            val result = withContext(disk) {
                runCatching {
                    val dir = store.epubDir(project.id)
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val file = File(dir, fileNameFor(project.title))
                    val tmp = File(dir, "building.tmp")
                    tmp.outputStream().buffered().use { out ->
                        EpubBuilder.write(
                            title = project.title,
                            blocks = project.blocks.map { b ->
                                when (b) {
                                    is Block.Text -> EpubBlock.Text(b.text, b.formatted)
                                    is Block.Image -> EpubBlock.Image(store.imageFile(project.id, b))
                                    is Block.Separator -> EpubBlock.ChapterBreak(b.title)
                                }
                            },
                            out = out,
                            language = Locale.getDefault().toLanguageTag(),
                            bookId = project.id,
                        )
                    }
                    check(tmp.renameTo(file)) { "Could not save the EPUB" }
                    file
                }
            }
            busy = false
            result
                .onSuccess { file ->
                    val now = current
                    // Only mark it current if nothing was edited while it was being built.
                    if (now != null && now.id == project.id && now.updatedAt == project.updatedAt) {
                        val done = now.copy(epubFileName = file.name, epubBuiltFrom = now.updatedAt)
                        current = done
                        save(done)
                    }
                    message("EPUB created (${formatSize(file.length())}). Tap Export to send it to Kindle.")
                }
                .onFailure {
                    Log.w(TAG, "EPUB creation failed", it)
                    message("Couldn't create the EPUB: ${it.message}")
                }
        }
    }

    // ---- Internals ----

    /** Writes the open canvas now. Not tracked by [saveJob], so a later save of another canvas can't cancel it. */
    fun flush() {
        val project = current ?: return
        saveJob?.cancel()
        saveJob = null
        viewModelScope.launch(disk) { store.save(project) }
    }

    private fun show(project: Project?) {
        current = project
        if (project == null) savedState.remove<String>(KEY_OPEN) else savedState[KEY_OPEN] = project.id
    }

    /** Applies a change to the open canvas and saves it. Any change makes the last EPUB stale. */
    private inline fun edit(debounce: Boolean = false, change: (Project) -> Project) {
        val project = current ?: return
        val changed = change(project)
        if (changed == project) return
        val updated = changed.copy(updatedAt = maxOf(System.currentTimeMillis(), project.updatedAt + 1))
        current = updated
        save(updated, debounce)
    }

    private fun save(project: Project, debounce: Boolean = false) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (debounce) delay(400)
            withContext(disk) { store.save(project) }
        }
    }

    private fun refreshList() {
        viewModelScope.launch { projects = withContext(disk) { store.list() } }
    }

    private fun message(text: String) {
        messageChannel.trySend(text)
    }

    companion object {
        private const val KEY_OPEN = "open_project"
        private const val TAG = "Kindlify"
        private const val DEFAULT_TITLE_PREFIX = "Untitled "

        /** How many canvases are kept; creating one more deletes the least recently edited. */
        const val MAX_CANVASES = 20

        fun fileNameFor(title: String): String {
            val safe = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(80)
                .trim()
            return safe.ifEmpty { "Book" } + ".epub"
        }

        fun formatSize(bytes: Long): String = when {
            bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
            else -> "${(bytes + 999) / 1000} KB"
        }
    }
}

/** A share waiting for a canvas to be picked: what was shared, and the canvases to choose from (newest first). */
data class PendingShare(val content: SharedContent, val choices: List<Project>)
