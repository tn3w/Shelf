package dev.tn3w.shelf.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color.BLACK
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.glance.appwidget.updateAll
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.tn3w.shelf.ShelfApp
import dev.tn3w.shelf.cover.renderCover
import dev.tn3w.shelf.data.*
import dev.tn3w.shelf.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge

private const val CORNER_SHARE = 0.04f
private const val SHADOW_SHARE = 0.1f
private const val REFRESH_DELAY = 2_000L

class WidgetBook(
    val work: Int,
    val title: String,
    val author: String,
    val progress: Float,
    val cover: Bitmap,
    val book: Bitmap,
    val dark: Tint,
)

class WidgetState(val books: List<WidgetBook>, val habit: Habit?)

suspend fun loadWidgetState(context: Context, limit: Int, width: Int): WidgetState {
    val app = context.applicationContext as ShelfApp
    val library = app.library
    val settings = library.settings.first()
    val progress = library.progress.first()
    val books = library.saved.first()
        .filter { it.shelf == Shelf.Reading }
        .take(limit)
        .map { widgetBook(app, it, progress[it.work]?.fraction ?: 0f, settings, width) }
    val habit = library.habit.first().takeIf { settings.dailyGoal > 0 }
    return WidgetState(books, habit)
}

suspend fun refreshWidgetsOnChange(app: ShelfApp) {
    val library = app.library
    merge(library.saved, library.progress, library.habit).collectLatest {
        delay(REFRESH_DELAY)
        WIDGETS.forEach { it.updateAll(app) }
    }
}

private suspend fun widgetBook(
    app: ShelfApp,
    saved: Saved,
    progress: Float,
    settings: Settings,
    width: Int,
): WidgetBook {
    val book = saved.toBook()
    val cover = frontOf(coverOf(app, book, settings, width), width)
    val tone = toneOf(cover) ?: CoverTone(cover.getPixel(0, 0), cover.getPixel(0, 0))
    return WidgetBook(
        work = book.work,
        title = mainTitle(book.title),
        author = book.author,
        progress = progress,
        cover = cover,
        book = bookOf(cover),
        dark = tintOf(tone, dark = true),
    )
}

suspend fun coverOf(app: ShelfApp, book: Book, settings: Settings, width: Int): Bitmap {
    val loader = SingletonImageLoader.get(app)
    val drawn = coverRequest(book, app.loaded.value?.catalogue)
    return coverSources(app, book, settings).firstNotNullOfOrNull { source ->
        val request = ImageRequest.Builder(app)
            .data(source)
            .size(width, width * 3 / 2)
            .allowHardware(false)
            .build()
        (loader.execute(request) as? SuccessResult)?.image?.toBitmap()
    } ?: renderCover(drawn, width)
}

private suspend fun coverSources(
    app: ShelfApp,
    book: Book,
    settings: Settings,
): List<Any> {
    val chosen = app.library.covers.first()[book.work]?.let(app.library::coverModel)
    if (book.isLocal && book.cover != 0) {
        return listOfNotNull(chosen, app.library.coverFile(book))
    }
    val source = book.coverUrl("L")
    val online = !settings.isOffline(app) && settings.onlineCovers
    if (!online || source == null) return listOfNotNull(chosen)
    val language = coverLanguage(book, app.loaded.value?.catalogue)
    val proxied = settings.coverProxyBase()
        ?.takeIf { language.isNotEmpty() }
        ?.let { proxyCoverUrl(it, language, book.work, 320) }
    return listOfNotNull(chosen, proxied, source)
}

private fun frontOf(cover: Bitmap, target: Int): Bitmap {
    val width = minOf(cover.width, cover.height * 2 / 3)
    val height = minOf(cover.height, cover.width * 3 / 2)
    val bias = frontCoverBias(cover.width.toFloat(), cover.height.toFloat())
    val left = ((cover.width - width) * (bias + 1) / 2).toInt()
    val top = (cover.height - height) / 2
    val front = Bitmap.createBitmap(cover, left, top, width, height)
    return Bitmap.createScaledBitmap(front, target, target * 3 / 2, true)
}

/** The cover as a bound book: rounded, a shaded spine and a soft shadow beneath. */
private fun bookOf(cover: Bitmap): Bitmap {
    val margin = cover.width * SHADOW_SHARE
    val result = Bitmap.createBitmap(
        (cover.width + margin * 2).toInt(),
        (cover.height + margin * 2).toInt(),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(result)
    val bounds = RectF(margin, margin, margin + cover.width, margin + cover.height)
    val radius = cover.width * CORNER_SHARE
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(margin * 0.7f, 0f, margin * 0.35f, 0x66000000)
    }
    canvas.drawRoundRect(bounds, radius, radius, shadow)
    val image = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(Matrix().apply { setTranslate(margin, margin) }) }
    }
    canvas.drawRoundRect(bounds, radius, radius, image)
    val spine = cover.width * 0.07f
    val shade = Paint().apply {
        shader = LinearGradient(
            margin, 0f, margin + spine, 0f,
            intArrayOf(0x33000000, 0, 0x24FFFFFF, 0),
            floatArrayOf(0f, 0.35f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(margin, margin, margin + spine, margin + cover.height, shade)
    return result
}

/** [cover] fading into a blur of itself, washed in the two colours of its [tint]. */
fun poster(cover: Bitmap, width: Int, height: Int, tint: Tint): Bitmap {
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val blur = listOf(6 to 9, 24 to 36, width to height)
        .fold(Bitmap.createScaledBitmap(cover, 2, 3, true)) { image, (across, down) ->
            Bitmap.createScaledBitmap(image, across, down, true)
        }
    canvas.drawBitmap(blur, 0f, 0f, null)
    val palette = LinearGradient(
        0f, 0f, width.toFloat(), height.toFloat(),
        tint.paper and 0xFFFFFF or (0xB0 shl 24),
        mix(tint.glow, tint.accent, 0.55f) and 0xFFFFFF or (0x90 shl 24),
        Shader.TileMode.CLAMP,
    )
    canvas.drawPaint(Paint().apply { shader = palette })
    val wide = width > height
    val scale = if (wide) {
        height.toFloat() / cover.height
    } else {
        width.toFloat() /
            cover.width
    }
    val drawn = RectF(0f, 0f, cover.width * scale, cover.height * scale)
    val end = (if (wide) drawn.right else minOf(drawn.bottom, height.toFloat())) - 3f
    val start = if (wide) end - drawn.width() * 0.3f else end * 0.35f
    val fade = if (wide) {
        LinearGradient(end, 0f, start, 0f, 0, BLACK, Shader.TileMode.CLAMP)
    } else {
        LinearGradient(0f, end, 0f, start, 0, BLACK, Shader.TileMode.CLAMP)
    }
    val mask = Paint().apply {
        shader = fade
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    canvas.saveLayer(drawn, null)
    canvas.drawBitmap(cover, null, drawn, Paint(Paint.FILTER_BITMAP_FLAG))
    canvas.drawRect(drawn, mask)
    canvas.restore()
    return result
}
