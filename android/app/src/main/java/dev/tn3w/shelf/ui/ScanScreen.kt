package dev.tn3w.shelf.ui

import android.Manifest.permission.CAMERA
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.PreviewView.StreamState.STREAMING
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

private const val TAP_MILLIS = 1000
private val FOUR_BY_THREE = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
private val PREVIEW_SIZE = ResolutionSelector.Builder()
    .setAspectRatioStrategy(FOUR_BY_THREE)
    .build()
private val ANALYSIS_SIZE = ResolutionSelector.Builder()
    .setAspectRatioStrategy(FOUR_BY_THREE)
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(1280, 960),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
        ),
    )
    .build()

private class Marks(val dots: List<Dot>, val width: Float, val height: Float)

private class Found(val book: Book, val frame: Bitmap?, val marks: Marks)

@Composable
fun ScanScreen(navigator: Navigator) {
    val app = shelfApp()
    val loaded by app.loaded.collectAsStateWithLifecycle()
    val recognizer by produceState<Recognizer?>(null) {
        value = withContext(Dispatchers.IO) { app.recognizer }
    }
    var found by remember { mutableStateOf<Found?>(null) }
    var frozen by remember { mutableStateOf<Found?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<Int?>(null) }
    val haptics = LocalHapticFeedback.current
    val current = loaded
    val model = recognizer

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (found == null && current != null && model != null) {
            val scanner = remember(attempt, current, model) {
                Scanner(current.catalogue, current.searcher, model) { current.others }
            }
            key(scanner) {
                CameraPreview(
                    analyze = scanner::next,
                    onFailed = { failure = it },
                    onShown = { frozen = null },
                ) { work, frame, marks ->
                    val book = current.book(work) ?: return@CameraPreview
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    found = Found(book, frame, marks).also { frozen = it }
                }
            }
        }
        frozen?.let { Frozen(it) }
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            BackBar(navigator::back)
        }
        if (found == null) {
            Text(
                stringResource(failure ?: R.string.scan_hint),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(32.dp),
            )
        }
    }
    found?.let {
        ScanSheet(it.book, navigator) {
            found = null
            attempt++
        }
    }
}

@Composable
private fun Frozen(found: Found) {
    found.frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
    val color = MaterialTheme.colorScheme.primary
    val marks = found.marks
    Canvas(Modifier.fillMaxSize()) {
        val scale = max(size.width / marks.width, size.height / marks.height)
        val left = (size.width - marks.width * scale) / 2
        val top = (size.height - marks.height * scale) / 2
        for (dot in marks.dots) {
            val center = Offset(left + dot.x * scale, top + dot.y * scale)
            drawCircle(Color.White, 4.dp.toPx(), center)
            drawCircle(color, 2.5.dp.toPx(), center)
        }
    }
}

@Composable
private fun CameraPreview(
    analyze: (Gray, Boolean) -> Step,
    onFailed: (Int) -> Unit,
    onShown: () -> Unit,
    onFound: (Int, Bitmap?, Marks) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val found by rememberUpdatedState(onFound)
    val view = remember { PreviewView(context) }
    val tappedAt = remember { AtomicLong() }
    DisposableEffect(owner) {
        if (ContextCompat.checkSelfPermission(context, CAMERA) != PERMISSION_GRANTED) {
            onFailed(R.string.camera_denied)
            return@DisposableEffect onDispose {}
        }
        val closed = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor()
        val analysis =
            ImageAnalysis.Builder().setResolutionSelector(ANALYSIS_SIZE).build()
        analysis.setAnalyzer(worker) { image ->
            val degrees = image.imageInfo.rotationDegrees
            val frame = runCatching { image.use(::grayOf) }.getOrNull()
                ?: return@setAnalyzer
            val tapped = SystemClock.uptimeMillis() - tappedAt.get() < TAP_MILLIS
            val step = analyze(frame, tapped)
            val work = step.work ?: return@setAnalyzer
            val marks = upright(step.dots, frame, degrees)
            view.post {
                if (closed.compareAndSet(false, true)) found(work, view.bitmap, marks)
            }
        }
        val preview = Preview.Builder().setResolutionSelector(PREVIEW_SIZE).build()
        preview.surfaceProvider = view.surfaceProvider
        view.previewStreamState.observe(owner) { if (it == STREAMING) onShown() }
        val main = ContextCompat.getMainExecutor(context)
        val provider = ProcessCameraProvider.getInstance(context)
        provider.addListener({
            if (closed.get()) return@addListener
            runCatching {
                val camera = provider.get()
                camera.bindToLifecycle(owner, DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { onFailed(R.string.camera_failed) }
        }, main)
        onDispose {
            closed.set(true)
            view.previewStreamState.removeObservers(owner)
            analysis.clearAnalyzer()
            worker.shutdown()
            if (provider.isDone) runCatching { provider.get().unbind(preview, analysis) }
        }
    }
    val tap = Modifier.pointerInput(Unit) {
        detectTapGestures {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            tappedAt.set(SystemClock.uptimeMillis())
        }
    }
    Box(Modifier.fillMaxSize().then(tap)) {
        AndroidView({ view }, Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanSheet(book: Book, navigator: Navigator, onAgain: () -> Unit) {
    val app = shelfApp()
    val scope = rememberCoroutineScope()
    val saved by app.library.saved.collectAsStateWithLifecycle(emptyList())
    val shelf = saved.firstOrNull { it.work == book.work }?.shelf
    ModalBottomSheet(onAgain, sheetState = rememberModalBottomSheetState(true)) {
        Column(Modifier.padding(bottom = 24.dp), Arrangement.spacedBy(12.dp)) {
            BookListItem(book, "scan", shared = false, onOpen = navigator::book)
            Box(Modifier.padding(horizontal = ScreenPadding)) {
                ShelfPicker(shelf) { scope.launch { app.library.place(book, it) } }
            }
            OutlinedButton(
                onAgain, Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
            ) {
                IconLabel(Icons.Outlined.DocumentScanner, R.string.scan_again)
            }
        }
    }
}

private fun grayOf(image: ImageProxy): Gray {
    val plane = image.planes[0]
    val bytes = ByteArray(plane.buffer.remaining()).also(plane.buffer::get)
    return Gray.of(bytes, image.width, image.height, plane.rowStride)
}

private fun upright(dots: List<Dot>, frame: Gray, degrees: Int): Marks {
    val width = frame.width.toFloat()
    val height = frame.height.toFloat()
    val turned = dots.map {
        when (degrees) {
            90 -> Dot(height - it.y, it.x)
            180 -> Dot(width - it.x, height - it.y)
            270 -> Dot(it.y, width - it.x)
            else -> it
        }
    }
    if (degrees % 180 == 0) return Marks(turned, width, height)
    return Marks(turned, height, width)
}
