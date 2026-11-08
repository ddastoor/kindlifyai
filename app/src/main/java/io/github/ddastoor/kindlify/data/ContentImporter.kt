package io.github.ddastoor.kindlify.data

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.IntentCompat
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Something found on the clipboard or shared to the app. */
sealed interface Pasted {
    data class Text(val text: String) : Pasted
    data class ImageUri(val uri: Uri) : Pasted

    /** A shared text file; read when it is turned into a block. */
    data class TextUri(val uri: Uri) : Pasted
}

/** What another app shared to Kindlify through the Android share sheet. */
data class SharedContent(val subject: String?, val items: List<Pasted>)

/** Reads the clipboard and shared content, and copies images into a project's storage. */
class ContentImporter(private val context: Context, private val store: ProjectStore) {

    /** Reads text and images shared from another app, or returns null if [intent] is not a share. */
    fun readShare(intent: Intent): SharedContent? {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val items = mutableListOf<Pasted>()
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { items += Pasted.Text(it) }
        val uris = if (action == Intent.ACTION_SEND) {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        }
        for (uri in uris) {
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: intent.type.orEmpty()
            items += if (type.startsWith("text/")) Pasted.TextUri(uri) else Pasted.ImageUri(uri)
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }
        return SharedContent(subject, items)
    }

    /**
     * Reads every item on the clipboard. Android only lets the app read the
     * clipboard while it is on screen, so call this straight from a tap.
     */
    fun readClipboard(): List<Pasted> {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: return emptyList()
        val items = mutableListOf<Pasted>()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val uri = item.uri
            if (uri != null && isImage(uri, clip.description)) {
                items += Pasted.ImageUri(uri)
                continue
            }
            val text = item.text?.toString() ?: runCatching { item.coerceToText(context)?.toString() }.getOrNull()
            if (!text.isNullOrBlank()) items += Pasted.Text(text)
        }
        return items
    }

    /** Turns a clipboard item into a canvas block. Does disk work: call off the main thread. */
    fun toBlock(projectId: String, pasted: Pasted): Block = when (pasted) {
        is Pasted.Text -> Block.Text(UUID.randomUUID().toString(), pasted.text)
        is Pasted.ImageUri -> importImage(projectId, pasted.uri)
        is Pasted.TextUri -> {
            val text = context.contentResolver.openInputStream(pasted.uri)!!.use { input ->
                String(input.readNBytesCompat(MAX_TEXT_BYTES), Charsets.UTF_8)
            }
            Block.Text(UUID.randomUUID().toString(), text)
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Copies an image into the project, downscaled so its longest side is at most
     * [MAX_IMAGE_SIDE] px and re-encoded as JPEG (or PNG if it has transparency).
     * That keeps the EPUB small and uses formats every Kindle understands,
     * whatever was copied (WebP, HEIC, huge camera photos...).
     */
    fun importImage(projectId: String, uri: Uri): Block.Image {
        // Copy the bytes right away: access to another app's clipboard/shared file can be short-lived.
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readNBytesCompat(MAX_IMAGE_BYTES + 1) }
        require(bytes.size <= MAX_IMAGE_BYTES) { "Image is too large" }
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > MAX_IMAGE_SIDE) {
                val scale = MAX_IMAGE_SIDE.toFloat() / longest
                decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            }
        }
        try {
            val png = bitmap.hasAlpha()
            val id = UUID.randomUUID().toString()
            val dir = store.imagesDir(projectId).apply { mkdirs() }
            val file = File(dir, id + if (png) ".png" else ".jpg")
            file.outputStream().buffered().use { out ->
                val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                check(bitmap.compress(format, if (png) 100 else 90, out)) { "Could not save image" }
            }
            return Block.Image(id, file.name, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun isImage(uri: Uri, description: ClipDescription): Boolean {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return type?.startsWith("image/") ?: description.hasMimeType("image/*")
    }

    companion object {
        const val MAX_IMAGE_SIDE = 2000
        const val MAX_TEXT_BYTES = 5_000_000
        const val MAX_IMAGE_BYTES = 80_000_000
    }
}
