package dev.tn3w.shelf.ui

import android.Manifest.permission.CAMERA
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import kotlin.math.abs
import kotlin.math.max

private const val ANALYSIS_PIXELS = 1280 * 960
private const val PREVIEW_LIMIT = 1920
private const val TAP_MILLIS = 1000

private class Marks(val dots: List<Dot>, val width: Float, val height: Float)

private class Found(val book: Book, val frame: Bitmap?, val marks: Marks)

@Composable
fun ScanScreen(navigator: Navigator) {
    val app = shelfApp()
    val loaded by app.loaded.collectAsStateWithLifecycle()
    val recognizer by produceState<Recognizer?>(null) {
        value = withContext(Dispatchers.IO) {
            app.assets.open("scan.bin").use { Recognizer(it.readBytes()) }
        }
    }
    var found by remember { mutableStateOf<Found?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val current = loaded
    val model = recognizer

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (found == null && current != null && model != null) {
            val scanner = remember(attempt, current, model) {
                Scanner(current.catalogue, current.searcher, model)
            }
            key(scanner) {
                CameraPreview(scanner::next) { work, frame, marks ->
                    val book = current.catalogue.book(work) ?: return@CameraPreview
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    found = Found(book, frame, marks)
                }
            }
        }
        found?.let { Frozen(it) }
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            BackBar(navigator::back)
        }
        if (found == null) {
            Text(
                stringResource(R.string.scan_hint),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
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
    onFound: (Int, Bitmap?, Marks) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onFound)
    val view = remember { TextureView(context) }
    val camera = remember {
        Camera(context, view, analyze) { work, frame, marks ->
            latest(work, frame, marks)
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) camera.open()
            if (event == Lifecycle.Event.ON_PAUSE) camera.close()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            camera.close()
        }
    }
    val tap = Modifier.pointerInput(camera) {
        detectTapGestures {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            camera.capture()
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
                Icon(Icons.Outlined.DocumentScanner, null, Modifier.size(18.dp))
                Text(stringResource(R.string.scan_again), Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun fourByThree(size: Size) = size.width * 3 == size.height * 4

private fun grayOf(image: Image): Gray {
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

private val cameraHandler by lazy {
    Handler(HandlerThread("camera").apply { start() }.looper)
}

private class Camera(
    private val context: Context,
    private val view: TextureView,
    private val analyze: (Gray, Boolean) -> Step,
    private val onFound: (Int, Bitmap?, Marks) -> Unit,
) : TextureView.SurfaceTextureListener {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val busy = AtomicBoolean(false)
    private var device: CameraDevice? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    private var worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var running = false
    private var degrees = 0
    private var generation = 0

    @Volatile
    private var tappedAt = 0L

    fun capture() {
        tappedAt = SystemClock.uptimeMillis()
    }

    fun open() {
        running = true
        if (!view.isAvailable) {
            view.surfaceTextureListener = this
            return
        }
        if (ContextCompat.checkSelfPermission(context, CAMERA) !=
            PERMISSION_GRANTED
        ) {
            return
        }
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return
        val characteristics = manager.getCameraCharacteristics(id)
        val map =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        degrees = (sensor - displayRotation() * 90 + 360) % 360
        val previews = map.getOutputSizes(SurfaceTexture::class.java)
        val preview = previews.filter { fourByThree(it) && it.width <= PREVIEW_LIMIT }
            .maxByOrNull { it.width } ?: previews.first()
        val analysis = map.getOutputSizes(ImageFormat.YUV_420_888).minBy {
            abs(it.width * it.height - ANALYSIS_PIXELS) +
                if (fourByThree(it)) 0 else 1_000_000
        }
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        view.surfaceTexture?.setDefaultBufferSize(preview.width, preview.height)
        transform(preview)
        cameraHandler.post { start(id, analysis) }
    }

    fun close() {
        running = false
        worker.shutdown()
        cameraHandler.post(::stop)
    }

    private fun start(id: String, analysis: Size) {
        reader =
            ImageReader.newInstance(
                analysis.width, analysis.height, ImageFormat.YUV_420_888, 2,
            )
                .apply { setOnImageAvailableListener(::onImage, cameraHandler) }
        val callback = DeviceCallback(++generation)
        try {
            manager.openCamera(id, callback, cameraHandler)
        } catch (_: SecurityException) {
        } catch (_: CameraAccessException) {
        }
    }

    private fun stop() {
        generation++
        device?.close()
        reader?.close()
        surface?.release()
        device = null
        reader = null
        surface = null
    }

    private inner class DeviceCallback(private val attempt: Int) :
        CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (attempt != generation) return camera.close()
            device = camera
            val preview = Surface(view.surfaceTexture).also { surface = it }
            val surfaces = listOfNotNull(preview, reader?.surface)

            @Suppress("DEPRECATION")
            val callback = SessionCallback(camera, surfaces)
            camera.createCaptureSession(surfaces, callback, cameraHandler)
        }

        override fun onDisconnected(camera: CameraDevice) = camera.close()

        override fun onError(camera: CameraDevice, error: Int) = camera.close()
    }

    private class SessionCallback(
        private val camera: CameraDevice,
        private val surfaces: List<Surface>,
    ) : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            val template = CameraDevice.TEMPLATE_PREVIEW
            val request = camera.createCaptureRequest(template).apply {
                surfaces.forEach(::addTarget)
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                )
            }
            runCatching { session.setRepeatingRequest(request.build(), null, null) }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) = session.close()
    }

    private fun onImage(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        if (!busy.compareAndSet(false, true)) return image.close()
        val frame = runCatching { image.use(::grayOf) }.getOrNull()
        if (frame == null) return busy.set(false)
        runCatching {
            worker.execute {
                if (running) {
                    val tapped = SystemClock.uptimeMillis() - tappedAt < TAP_MILLIS
                    val step = analyze(frame, tapped)
                    val work = step.work
                    if (work != null) {
                        val marks = upright(step.dots, frame, degrees)
                        view.post { finish(work, marks) }
                    }
                }
                busy.set(false)
            }
        }.onFailure { busy.set(false) }
    }

    private fun finish(work: Int, marks: Marks) {
        if (!running) return
        val frame = snapshot()
        close()
        onFound(work, frame, marks)
    }

    private fun snapshot(): Bitmap? {
        val raw = view.bitmap ?: return null
        val frame = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(frame).drawBitmap(raw, view.getTransform(null), null)
        raw.recycle()
        return frame
    }

    private fun displayRotation() = view.display?.rotation ?: Surface.ROTATION_0

    private fun transform(preview: Size) {
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        if (width == 0f || height == 0f) return
        val rotation = displayRotation()
        val centerX = width / 2
        val centerY = height / 2
        val matrix = Matrix()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val buffer = RectF(0f, 0f, preview.height.toFloat(), preview.width.toFloat())
            buffer.offset(centerX - buffer.centerX(), centerY - buffer.centerY())
            val screen = RectF(0f, 0f, width, height)
            matrix.setRectToRect(screen, buffer, Matrix.ScaleToFit.FILL)
            val scale = max(height / preview.height, width / preview.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else {
            val content = preview.height.toFloat() / preview.width
            val screen = width / height
            val stretch = content / screen
            matrix.setScale(max(stretch, 1f), max(1 / stretch, 1f), centerX, centerY)
            val turn = if (rotation == Surface.ROTATION_180) 180f else 0f
            matrix.postRotate(turn, centerX, centerY)
        }
        view.setTransform(matrix)
    }

    override fun onSurfaceTextureAvailable(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        if (running) open()
    }

    override fun onSurfaceTextureSizeChanged(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}
