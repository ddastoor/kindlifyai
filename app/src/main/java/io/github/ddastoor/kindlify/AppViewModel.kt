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

    init {
        viewModelScope.launch {
            // Reopen the canvas you were on if Android closed the app in the background.
            val openId = savedState.get<String>(KEY_OPEN)
            val (all, open) = withContext(disk) { store.list() to openId?.let { store.load(it) } }
            projects = all
            if (open != null && current == null) current = open
        }
    }

    // ---- Home ----

    fun newCanvas(title: String? = null): Project {
        flush()
        val now = System.currentTimeMillis()
        val name = title ?: ("Untitled " + DateFormat.format("d MMM yyyy, HH:mm", now))
        val project = Project(UUID.randomUUID().toString(), name, now, now, emptyList())
        show(project)
        save(project)
        return project
    }

    /**
     * Text or images shared to Kindlify from another app: always starts a new
     * canvas (the one you were on is kept as a draft). Returns false if the
     * intent wasn't a share.
     */
    fun newCanvasFromShare(intent: Intent): Boolean {
        val shared = runCatching { importer.readShare(intent) }.getOrNull() ?: return false
        if (shared.items.isEmpty()) {
            message("Nothing usable was shared. Kindlify takes text and images.")
            return true
        }
        val project = newCanvas(shared.subject)
        addAll(project, shared.items)
        return true
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

    // ---- Editing ----

    fun setTitle(title: String) = edit(debounce = true) { it.copy(title = title) }

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

    /** Adds an image chosen from the photo picker. */
    fun addImage(uri: Uri) {
        val project = current ?: return
        addAll(project, listOf(Pasted.ImageUri(uri)))
    }

    private fun addAll(project: Project, items: List<Pasted>) {
        busy = true
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                items.map { runCatching { importer.toBlock(project.id, it) } }
            }
            busy = false
            val blocks = results.mapNotNull { it.getOrNull() }
            if (current?.id == project.id && blocks.isNotEmpty()) {
                edit { it.copy(blocks = it.blocks + blocks) }
            }
            results.forEach { r -> r.exceptionOrNull()?.let { Log.w(TAG, "Couldn't import item", it) } }
            val failed = results.count { it.isFailure }
            if (failed > 0) message("Couldn't read $failed item(s).")
        }
    }

    // ---- EPUB ----

    fun epubFile(project: Project): File? = if (project.hasCurrentEpub) store.epubFile(project) else null

    /** The Create EPUB button. */
    fun createEpub() {
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
                                    is Block.Text -> EpubBlock.Text(b.text)
                                    is Block.Image -> EpubBlock.Image(store.imageFile(project.id, b))
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
