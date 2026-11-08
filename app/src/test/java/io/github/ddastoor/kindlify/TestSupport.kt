package io.github.ddastoor.kindlify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.content.FileProvider
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * Saves what's on screen to app/build/screenshots/<name>.png, drawing the window
 * (and any open dialog over a dimmed background) straight into a bitmap.
 */
fun AndroidComposeTestRule<*, MainActivity>.screenshot(name: String) {
    waitForIdle()
    val root = activity.window.decorView
    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    root.draw(canvas)
    val dialog = ShadowDialog.getLatestDialog()
    if (dialog != null && dialog.isShowing) {
        val view = dialog.window!!.decorView
        canvas.drawColor(Color.argb(0x80, 0, 0, 0))
        canvas.save()
        canvas.translate((root.width - view.width) / 2f, (root.height - view.height) / 2f)
        view.draw(canvas)
        canvas.restore()
    }
    val dir = File("build/screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/**
 * Test-only reset. AndroidX FileProvider caches its storage roots in a static map;
 * Robolectric keeps classes loaded between tests but gives each test a fresh data
 * folder, so a stale root would break the next test. (Not an issue on a phone.)
 */
fun resetFileProviderCache() {
    val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
    (field.get(null) as MutableMap<*, *>).clear()
    ShadowLog.stream = System.out
}

/** A simple landscape "photo" larger than the app's 2000 px limit. */
fun writeTestPhoto(file: File, width: Int = 3000, height: Int = 2000) {
    file.parentFile!!.mkdirs()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.rgb(46, 74, 107))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 180, 60) }
    canvas.drawCircle(width * 0.7f, height * 0.35f, height * 0.18f, paint)
    paint.color = Color.rgb(30, 50, 40)
    canvas.drawRect(0f, height * 0.7f, width.toFloat(), height.toFloat(), paint)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
}
