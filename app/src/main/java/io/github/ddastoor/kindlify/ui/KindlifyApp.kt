package io.github.ddastoor.kindlify.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.ddastoor.kindlify.AppViewModel
import io.github.ddastoor.kindlify.KindleShare
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Two screens: the list of canvases, and the open canvas. */
@Composable
fun KindlifyApp(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(vm) {
        vm.messages.collectLatest { snackbar.showSnackbar(it) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addImage(uri)
    }

    val project = vm.current
    // Don't carry a message from one screen over to the next.
    LaunchedEffect(project?.id) { snackbar.currentSnackbarData?.dismiss() }

    if (project == null) {
        HomeScreen(
            projects = vm.projects,
            snackbar = snackbar,
            onNew = { vm.newCanvas() },
            onOpen = vm::open,
        )
    } else {
        BackHandler(onBack = vm::close)
        CanvasScreen(
            project = project,
            imageFile = { vm.store.imageFile(project.id, it) },
            busy = vm.busy,
            snackbar = snackbar,
            onBack = vm::close,
            onDiscard = vm::discard,
            onTitleChange = vm::setTitle,
            onTextChange = vm::setText,
            onMove = vm::move,
            onRemove = vm::remove,
            onPaste = vm::paste,
            onAddImage = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onCreateEpub = vm::createEpub,
            onExport = {
                val file = vm.epubFile(project)
                if (file == null) {
                    scope.launch { snackbar.showSnackbar("Create the EPUB first.") }
                } else {
                    try {
                        val direct = KindleShare.share(context, file, project.title.ifBlank { "Untitled" })
                        if (!direct) {
                            scope.launch { snackbar.showSnackbar("Kindle app not found, so pick an app to send the EPUB to.") }
                        }
                    } catch (e: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar("No app on this phone can receive an EPUB.") }
                    }
                }
            },
        )
    }
}
