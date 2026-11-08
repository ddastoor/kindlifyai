package io.github.ddastoor.kindlify

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ddastoor.kindlify.data.Block
import io.github.ddastoor.kindlify.data.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** A share that launches Kindlify from scratch (app not running) also starts a new canvas. */
@RunWith(AndroidJUnit4::class)
class ShareColdStartTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = resetFileProviderCache()

    @Test
    fun coldStartShare_createsCanvasWithSharedText() {
        val intent = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Shared while the app was closed.")

        Robolectric.buildActivity(MainActivity::class.java, intent).setup().use { controller ->
            val store = ProjectStore(app.filesDir)
            val deadline = System.currentTimeMillis() + 10_000
            while (store.list().singleOrNull()?.blocks.isNullOrEmpty() && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
            }
            val project = store.list().single()
            assertEquals(listOf("Shared while the app was closed."), project.blocks.map { (it as Block.Text).text })
            // The share is consumed, so recreating the activity (e.g. rotation) doesn't add it again.
            assertTrue(controller.get().intent.getBooleanExtra(MainActivity.EXTRA_SHARE_HANDLED, false))
        }
    }
}
