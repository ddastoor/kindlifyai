package io.github.ddastoor.kindlify

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Hands a finished EPUB to the Kindle app (which uploads it via Send to Kindle). */
object KindleShare {

    const val KINDLE_PACKAGE = "com.amazon.kindle"
    const val EPUB_MIME = "application/epub+zip"

    /**
     * Opens the Kindle app's share screen for [epub]. If the Kindle app is not
     * installed (or won't take EPUBs), falls back to the normal share sheet.
     *
     * @return true if the file went straight to the Kindle app.
     */
    fun share(context: Context, epub: File, title: String): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", epub)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = EPUB_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val toKindle = Intent(send).setPackage(KINDLE_PACKAGE)
        return if (toKindle.resolveActivity(context.packageManager) != null) {
            context.startActivity(toKindle)
            true
        } else {
            context.startActivity(Intent.createChooser(send, "Send EPUB to…"))
            false
        }
    }
}
