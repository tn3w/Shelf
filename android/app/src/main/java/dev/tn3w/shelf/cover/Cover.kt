package dev.tn3w.shelf.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import dev.tn3w.shelf.cover.art.*
import java.io.File

internal typealias ArtDirection = (CoverCanvas) -> Typeset

internal fun directions(genre: CoverGenre): List<ArtDirection> = when (genre) {
    CoverGenre.Fantasy ->
        listOf(
            ::fantasyPeaks,
            ::fantasySigil,
            ::fantasyForest,
            ::fantasyDawn,
            ::fantasyIlluminated,
            ::fantasyCrystal,
            ::fantasySword,
        )

    CoverGenre.Dystopian ->
        listOf(
            ::dystopianMonolith,
            ::dystopianEye,
            ::dystopianBlocks,
            ::dystopianPropaganda,
            ::dystopianBarcode,
        )

    CoverGenre.ScienceFiction ->
        listOf(
            ::scifiPlanet,
            ::scifiOrbit,
            ::scifiCircuit,
            ::scifiTunnel,
            ::scifiRetro,
            ::dystopianGlitch,
        )

    CoverGenre.Mystery ->
        listOf(
            ::mysteryStreet,
            ::mysteryKeyhole,
            ::mysteryPrint,
            ::mysteryBlinds,
            ::mysteryRedThread,
        )

    CoverGenre.Horror ->
        listOf(::horrorMoon, ::horrorCracks, ::horrorDrip, ::horrorFog)

    CoverGenre.Romance ->
        listOf(::romanceBotanical, ::romanceDeco, ::romanceBloom, ::romanceLetter)

    CoverGenre.Adventure ->
        listOf(::adventureMap, ::adventureCompass, ::travelRoute, ::seigaihaWaves)

    CoverGenre.Children ->
        listOf(::childrenMeadow, ::childrenBalloons, ::childrenRainbow, ::natureTree)

    CoverGenre.Poetry ->
        listOf(::poetryWash, ::poetryMoons, ::poetryRain, ::literaryFields)

    CoverGenre.Nature ->
        listOf(::natureContours, ::natureLeaves, ::natureLake, ::natureTree)

    CoverGenre.Travel ->
        listOf(::travelPoster, ::travelStamp, ::travelRoute, ::natureLake)

    CoverGenre.Spiritual ->
        listOf(::spiritualRays, ::spiritualMandala, ::spiritualEnso, ::spiritualLotus)

    CoverGenre.Business ->
        listOf(::businessAscent, ::businessGraph, ::swissGrid, ::technicalBlueprint)

    CoverGenre.Humor ->
        listOf(::humorConfetti, ::humorPop, ::risoHalftone, ::childrenBalloons)

    CoverGenre.Vintage ->
        listOf(::vintageBands, ::penguinBands, ::vintageDeco, ::bauhausTiles)

    CoverGenre.Biography ->
        listOf(::biographyPortrait, ::biographyCameo, ::historyLaurel, ::penguinBands)

    CoverGenre.History ->
        listOf(::historyEmblem, ::historyLaurel, ::historyTemple, ::fantasyIlluminated)

    CoverGenre.Technical ->
        listOf(::technicalGeometry, ::technicalBlueprint, ::technicalAtom, ::bauhausTiles)

    CoverGenre.Literary ->
        listOf(::literaryType, ::literaryShape, ::penguinBands, ::literaryFields)
}

data class CoverRequest(
    val work: Int,
    val title: String,
    val author: String,
    val slugs: List<String> = emptyList(),
)

fun renderCover(request: CoverRequest, widthPixels: Int): Bitmap {
    val random = CoverRandom(coverSeed(request.work, request.title, request.author))
    val direction = random.pick(directions(classify(request.slugs)))
    val width = widthPixels.coerceIn(48, 2048)
    val bitmap = Bitmap.createBitmap(width, width * 3 / 2, Bitmap.Config.ARGB_8888)
    val cover = CoverCanvas(bitmap, random)
    cover.drawTypography(request.title, request.author, direction(cover))
    return bitmap
}

object Covers {
    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8192).toInt().coerceIn(2048, 32768),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun cached(context: Context, request: CoverRequest, widthPixels: Int): Bitmap {
        val width = snap(widthPixels)
        val seed = coverSeed(request.work, request.title, request.author)
        val key = "v3-$seed-$width"
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

    private fun decode(file: File): Bitmap? = if (!file.exists()) {
        null
    } else {
        runCatching { BitmapFactory.decodeFile(file.path) }
            .getOrNull()
    }

    private val compressFormat = if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.R
    ) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        Bitmap.CompressFormat.JPEG
    }

    private fun store(file: File, bitmap: Bitmap) {
        runCatching {
            file.outputStream().use { bitmap.compress(compressFormat, 82, it) }
        }
    }
}
