package dev.tn3w.shelf.ui

import android.Manifest.permission.CAMERA
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.annotation.StringRes
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Navigator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.data.*
import kotlinx.coroutines.flow.*
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

class Marks(val dots: List<Dot>, val width: Float, val height: Float)

class Found(val book: Book, val frame: Bitmap?, val marks: Marks)

class ScanViewModel(app: ShelfApp) : ShelfViewModel(app) {
    private val attempts = MutableStateFlow(0)
    val scanner = combine(
        loaded.filterNotNull(),
        flow {
            emit(recognizer.apply { warmUp() })
        },
        attempts, ::Triple,
    )
        .map { (current, model) ->
            Scanner(current.catalogue, current.searcher, model) { current.others }
        }
        .asState()
    var found by mutableStateOf<Found?>(null)
        private set
    var frozen by mutableStateOf<Found?>(null)
        private set
    var failure by mutableStateOf<Int?>(null)
        private set
    var paused by mutableStateOf(false)
        private set

    fun show(work: Int, frame: Bitmap?, marks: Marks): Boolean {
        val book = loaded.value?.book(work) ?: return false
        found = Found(book, frame, marks).also { frozen = it }
        return true
    }

    fun unfreeze() {
        frozen = null
    }

    fun fail(@StringRes message: Int) {
        failure = message
    }

    fun scanAgain() {
        found = null
        attempts.update { it + 1 }
    }

    fun leave() {
        found = null
        paused = true
    }

    fun resume() {
        if (!paused) return
        paused = false
        scanAgain()
    }
}

@Composable
fun ScanScreen(navigator: Navigator) {
    val viewModel = shelfViewModel(create = ::ScanViewModel)
    val scanner by viewModel.scanner.collectAsStateWithLifecycle()
    val found = viewModel.found
    val haptics = LocalHapticFeedback.current
    val current = scanner
    LaunchedEffect(Unit) { viewModel.resume() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (found == null && current != null && !viewModel.paused) {
            key(current) {
                CameraPreview(
                    analyze = current::next,
                    onFailed = viewModel::fail,
                    onShown = viewModel::unfreeze,
                ) { work, frame, marks ->
                    if (!viewModel.show(work, frame, marks)) return@CameraPreview
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }
        viewModel.frozen?.let { Frozen(it) }
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            BackBar(navigator::back)
        }
        if (found == null && !viewModel.paused) {
            Text(
                stringResource(viewModel.failure ?: R.string.scan_hint),
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
        ScanSheet(it.book, viewModel::scanAgain) { book, origin ->
            viewModel.leave()
            navigator.bookPage(book, origin)
        }
    }
}

@Composable
private fun Frozen(found: Found) {
    found.frame?.let {
        Image(
            it.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
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
    val shown by rememberUpdatedState(onShown)
    var surface by remember { mutableStateOf<SurfaceRequest?>(null) }
    val tappedAt = remember { AtomicLong() }
    DisposableEffect(owner) {
        if (ContextCompat.checkSelfPermission(context, CAMERA) != PERMISSION_GRANTED) {
            onFailed(R.string.camera_denied)
            return@DisposableEffect onDispose {}
        }
        val closed = AtomicBoolean(false)
        val streaming = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val analysis =
            ImageAnalysis.Builder().setResolutionSelector(ANALYSIS_SIZE).build()
        analysis.setAnalyzer(worker) { image ->
            if (streaming.compareAndSet(false, true)) main.execute { shown() }
            val tapped = SystemClock.uptimeMillis() - tappedAt.get() < TAP_MILLIS
            val result = image.use { runCatching { recognize(it, analyze, tapped) } }
            val (work, frame, marks) = result.getOrNull() ?: return@setAnalyzer
            main.execute {
                if (closed.compareAndSet(false, true)) found(work, frame, marks)
            }
        }
        val preview = Preview.Builder().setResolutionSelector(PREVIEW_SIZE).build()
        preview.setSurfaceProvider(main) { surface = it }
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
        surface?.let { CameraXViewfinder(it, Modifier.fillMaxSize()) }
    }
}

private fun recognize(
    image: ImageProxy,
    analyze: (Gray, Boolean) -> Step,
    tapped: Boolean,
): Triple<Int, Bitmap?, Marks>? {
    val degrees = image.imageInfo.rotationDegrees
    val frame = grayOf(image)
    val step = analyze(frame, tapped)
    val work = step.work ?: return null
    val picture = runCatching { upright(image.toBitmap(), degrees) }.getOrNull()
    return Triple(work, picture, upright(step.dots, frame, degrees))
}

private fun upright(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val turn = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, turn, true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanSheet(book: Book, onAgain: () -> Unit, onOpen: (Book, String) -> Unit) {
    val viewModel = shelfViewModel()
    val saved by viewModel.library.saved.collectAsStateWithLifecycle(emptyList())
    val shelf = saved.firstOrNull { it.work == book.work }?.shelf
    ModalBottomSheet(onAgain, sheetState = rememberModalBottomSheetState(true)) {
        Column(Modifier.padding(bottom = 24.dp), Arrangement.spacedBy(12.dp)) {
            BookListItem(book, "scan", shared = false, onOpen = onOpen)
            Box(Modifier.padding(horizontal = ScreenPadding)) {
                ShelfPicker(shelf) { viewModel.place(book, it) }
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
    plane.buffer.rewind()
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
