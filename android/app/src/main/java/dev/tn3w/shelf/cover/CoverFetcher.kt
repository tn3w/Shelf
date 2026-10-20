package dev.tn3w.shelf.cover

import android.graphics.Bitmap
import android.os.Build
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer

private const val VERSION = "v6"
private const val QUALITY = 82
private val WIDTHS = intArrayOf(120, 180, 240, 320, 420, 560, 720)
private val FORMAT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Bitmap.CompressFormat.WEBP_LOSSY
} else {
    Bitmap.CompressFormat.JPEG
}

private fun coverWidth(size: Size): Int {
    val width = (size.width as? Dimension.Pixels)?.px ?: WIDTHS[3]
    return WIDTHS.firstOrNull { it >= width } ?: WIDTHS.last()
}

private fun coverKey(request: CoverRequest, width: Int): String {
    val seed = coverSeed(request.work, request.title, request.author)
    return "cover-$VERSION-$seed-${classify(request.slugs).name}-$width"
}

class CoverKeyer : Keyer<CoverRequest> {
    override fun key(data: CoverRequest, options: Options) =
        coverKey(data, coverWidth(options.size))
}

class CoverFetcher(
    private val request: CoverRequest,
    private val options: Options,
    private val cache: DiskCache?,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val width = coverWidth(options.size)
        val key = coverKey(request, width)
        cache?.openSnapshot(key)?.let { snapshot ->
            val source = ImageSource(snapshot.data, options.fileSystem, key, snapshot)
            return SourceFetchResult(source, null, DataSource.DISK)
        }
        val bitmap = withContext(Dispatchers.Default) { renderCover(request, width) }
        cache?.let { store(it, key, bitmap) }
        return ImageFetchResult(bitmap.asImage(), isSampled = false, DataSource.MEMORY)
    }

    private fun store(cache: DiskCache, key: String, bitmap: Bitmap) {
        val editor = cache.openEditor(key) ?: return
        runCatching {
            cache.fileSystem.sink(editor.data).buffer().outputStream().use {
                bitmap.compress(FORMAT, QUALITY, it)
            }
            editor.commit()
        }.onFailure { editor.abort() }
    }

    class Factory : Fetcher.Factory<CoverRequest> {
        override fun create(
            data: CoverRequest,
            options: Options,
            imageLoader: ImageLoader,
        ) = CoverFetcher(data, options, imageLoader.diskCache)
    }
}
