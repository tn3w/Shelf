package dev.tn3w.shelf.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.tn3w.shelf.cover.art.adventureMap
import dev.tn3w.shelf.cover.art.biographyPortrait
import dev.tn3w.shelf.cover.art.businessAscent
import dev.tn3w.shelf.cover.art.childrenMeadow
import dev.tn3w.shelf.cover.art.dystopianBlocks
import dev.tn3w.shelf.cover.art.dystopianEye
import dev.tn3w.shelf.cover.art.dystopianMonolith
import dev.tn3w.shelf.cover.art.fantasyForest
import dev.tn3w.shelf.cover.art.fantasyPeaks
import dev.tn3w.shelf.cover.art.fantasySigil
import dev.tn3w.shelf.cover.art.historyEmblem
import dev.tn3w.shelf.cover.art.horrorCracks
import dev.tn3w.shelf.cover.art.horrorMoon
import dev.tn3w.shelf.cover.art.humorConfetti
import dev.tn3w.shelf.cover.art.literaryShape
import dev.tn3w.shelf.cover.art.mysteryKeyhole
import dev.tn3w.shelf.cover.art.mysteryPrint
import dev.tn3w.shelf.cover.art.mysteryStreet
import dev.tn3w.shelf.cover.art.natureContours
import dev.tn3w.shelf.cover.art.poetryWash
import dev.tn3w.shelf.cover.art.romanceBotanical
import dev.tn3w.shelf.cover.art.romanceDeco
import dev.tn3w.shelf.cover.art.scifiCircuit
import dev.tn3w.shelf.cover.art.scifiOrbit
import dev.tn3w.shelf.cover.art.scifiPlanet
import dev.tn3w.shelf.cover.art.spiritualRays
import dev.tn3w.shelf.cover.art.technicalGeometry
import dev.tn3w.shelf.cover.art.travelPoster
import dev.tn3w.shelf.cover.art.vintageBands
import java.io.File

internal typealias ArtDirection = (CoverCanvas) -> Typeset

internal fun directions(genre: CoverGenre): List<ArtDirection> =
    when (genre) {
        CoverGenre.Fantasy -> listOf(::fantasyPeaks, ::fantasySigil, ::fantasyForest)
        CoverGenre.Dystopian -> listOf(::dystopianMonolith, ::dystopianEye, ::dystopianBlocks)
        CoverGenre.ScienceFiction -> listOf(::scifiPlanet, ::scifiOrbit, ::scifiCircuit)
        CoverGenre.Mystery -> listOf(::mysteryStreet, ::mysteryKeyhole, ::mysteryPrint)
        CoverGenre.Horror -> listOf(::horrorMoon, ::horrorCracks)
        CoverGenre.Romance -> listOf(::romanceBotanical, ::romanceDeco)
        CoverGenre.Adventure -> listOf(::adventureMap)
        CoverGenre.Children -> listOf(::childrenMeadow)
        CoverGenre.Poetry -> listOf(::poetryWash)
        CoverGenre.Nature -> listOf(::natureContours)
        CoverGenre.Travel -> listOf(::travelPoster)
        CoverGenre.Spiritual -> listOf(::spiritualRays)
        CoverGenre.Business -> listOf(::businessAscent)
        CoverGenre.Humor -> listOf(::humorConfetti)
        CoverGenre.Vintage -> listOf(::vintageBands)
        CoverGenre.Biography -> listOf(::biographyPortrait)
        CoverGenre.History -> listOf(::historyEmblem)
        CoverGenre.Technical -> listOf(::technicalGeometry)
        CoverGenre.Literary -> listOf(::literaryShape)
    }

internal fun allDirections(): List<Pair<String, ArtDirection>> =
    CoverGenre.entries.flatMap { genre ->
        directions(genre).mapIndexed { index, direction -> "$genre-$index" to direction }
    }

data class CoverRequest(
    val work: Int,
    val title: String,
    val author: String,
    val slugs: List<String> = emptyList(),
)

fun renderCover(request: CoverRequest, widthPixels: Int): Bitmap {
    val random = CoverRandom(coverSeed(request.work, request.title, request.author))
    val choices = directions(classify(request.slugs))
    return paint(request, widthPixels, random, choices[random.index(choices.size)])
}

internal fun renderDirection(
    request: CoverRequest,
    widthPixels: Int,
    direction: ArtDirection,
): Bitmap =
    paint(request, widthPixels,
        CoverRandom(coverSeed(request.work, request.title, request.author)), direction)

private fun paint(
    request: CoverRequest,
    widthPixels: Int,
    random: CoverRandom,
    direction: ArtDirection,
): Bitmap {
    val width = widthPixels.coerceIn(48, 2048)
    val bitmap = Bitmap.createBitmap(width, width * 3 / 2, Bitmap.Config.ARGB_8888)
    val cover = CoverCanvas(bitmap, random)
    val typeset = direction(cover)
    cover.drawTypography(request.title, request.author, typeset)
    return bitmap
}

object Covers {
    private val memory =
        object : LruCache<String, Bitmap>(
            (Runtime.getRuntime().maxMemory() / 8192).toInt().coerceIn(2048, 32768)
        ) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

    fun cached(context: Context, request: CoverRequest, widthPixels: Int): Bitmap {
        val width = snap(widthPixels)
        val seed = coverSeed(request.work, request.title, request.author)
        val key = "$seed-$width"
        memory.get(key)?.let { return it }

        val file = File(directory(context), "$key.webp")
        decode(file)?.let {
            memory.put(key, it)
            return it
        }
        val bitmap = renderCover(request, width)
        memory.put(key, bitmap)
        store(file, bitmap)
        return bitmap
    }

    fun clear(context: Context) {
        memory.evictAll()
        directory(context).listFiles()?.forEach { it.delete() }
    }

    private fun snap(widthPixels: Int): Int {
        val steps = intArrayOf(120, 180, 240, 320, 420, 560, 720)
        return steps.firstOrNull { it >= widthPixels } ?: steps.last()
    }

    private fun directory(context: Context) =
        File(context.cacheDir, "covers").apply { mkdirs() }

    private fun decode(file: File): Bitmap? =
        if (!file.exists()) null else runCatching { BitmapFactory.decodeFile(file.path) }
            .getOrNull()

    private fun store(file: File, bitmap: Bitmap) {
        runCatching {
            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, it)
            }
        }
    }
}
