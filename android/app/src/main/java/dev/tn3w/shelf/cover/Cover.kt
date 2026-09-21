package dev.tn3w.shelf.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import dev.tn3w.shelf.cover.art.adventureCompass
import dev.tn3w.shelf.cover.art.adventureMap
import dev.tn3w.shelf.cover.art.bauhausTiles
import dev.tn3w.shelf.cover.art.biographyCameo
import dev.tn3w.shelf.cover.art.biographyPortrait
import dev.tn3w.shelf.cover.art.businessAscent
import dev.tn3w.shelf.cover.art.businessGraph
import dev.tn3w.shelf.cover.art.childrenBalloons
import dev.tn3w.shelf.cover.art.childrenMeadow
import dev.tn3w.shelf.cover.art.childrenRainbow
import dev.tn3w.shelf.cover.art.dystopianBarcode
import dev.tn3w.shelf.cover.art.dystopianBlocks
import dev.tn3w.shelf.cover.art.dystopianEye
import dev.tn3w.shelf.cover.art.dystopianGlitch
import dev.tn3w.shelf.cover.art.dystopianMonolith
import dev.tn3w.shelf.cover.art.dystopianPropaganda
import dev.tn3w.shelf.cover.art.fantasyCrystal
import dev.tn3w.shelf.cover.art.fantasyDawn
import dev.tn3w.shelf.cover.art.fantasyForest
import dev.tn3w.shelf.cover.art.fantasyIlluminated
import dev.tn3w.shelf.cover.art.fantasyPeaks
import dev.tn3w.shelf.cover.art.fantasySigil
import dev.tn3w.shelf.cover.art.fantasySword
import dev.tn3w.shelf.cover.art.historyEmblem
import dev.tn3w.shelf.cover.art.historyLaurel
import dev.tn3w.shelf.cover.art.historyTemple
import dev.tn3w.shelf.cover.art.horrorCracks
import dev.tn3w.shelf.cover.art.horrorDrip
import dev.tn3w.shelf.cover.art.horrorFog
import dev.tn3w.shelf.cover.art.horrorMoon
import dev.tn3w.shelf.cover.art.humorConfetti
import dev.tn3w.shelf.cover.art.humorPop
import dev.tn3w.shelf.cover.art.literaryFields
import dev.tn3w.shelf.cover.art.literaryShape
import dev.tn3w.shelf.cover.art.literaryType
import dev.tn3w.shelf.cover.art.mysteryBlinds
import dev.tn3w.shelf.cover.art.mysteryKeyhole
import dev.tn3w.shelf.cover.art.mysteryPrint
import dev.tn3w.shelf.cover.art.mysteryRedThread
import dev.tn3w.shelf.cover.art.mysteryStreet
import dev.tn3w.shelf.cover.art.natureContours
import dev.tn3w.shelf.cover.art.natureLake
import dev.tn3w.shelf.cover.art.natureLeaves
import dev.tn3w.shelf.cover.art.natureTree
import dev.tn3w.shelf.cover.art.penguinBands
import dev.tn3w.shelf.cover.art.poetryMoons
import dev.tn3w.shelf.cover.art.poetryRain
import dev.tn3w.shelf.cover.art.poetryWash
import dev.tn3w.shelf.cover.art.risoHalftone
import dev.tn3w.shelf.cover.art.romanceBloom
import dev.tn3w.shelf.cover.art.romanceBotanical
import dev.tn3w.shelf.cover.art.romanceDeco
import dev.tn3w.shelf.cover.art.romanceLetter
import dev.tn3w.shelf.cover.art.scifiCircuit
import dev.tn3w.shelf.cover.art.scifiOrbit
import dev.tn3w.shelf.cover.art.scifiPlanet
import dev.tn3w.shelf.cover.art.scifiRetro
import dev.tn3w.shelf.cover.art.scifiTunnel
import dev.tn3w.shelf.cover.art.seigaihaWaves
import dev.tn3w.shelf.cover.art.spiritualEnso
import dev.tn3w.shelf.cover.art.spiritualLotus
import dev.tn3w.shelf.cover.art.spiritualMandala
import dev.tn3w.shelf.cover.art.spiritualRays
import dev.tn3w.shelf.cover.art.swissGrid
import dev.tn3w.shelf.cover.art.technicalAtom
import dev.tn3w.shelf.cover.art.technicalBlueprint
import dev.tn3w.shelf.cover.art.technicalGeometry
import dev.tn3w.shelf.cover.art.travelPoster
import dev.tn3w.shelf.cover.art.travelRoute
import dev.tn3w.shelf.cover.art.travelStamp
import dev.tn3w.shelf.cover.art.vintageBands
import dev.tn3w.shelf.cover.art.vintageDeco
import java.io.File

internal typealias ArtDirection = (CoverCanvas) -> Typeset

internal fun directions(genre: CoverGenre): List<ArtDirection> =
    when (genre) {
        CoverGenre.Fantasy ->
            listOf(::fantasyPeaks, ::fantasySigil, ::fantasyForest, ::fantasyDawn,
                ::fantasyIlluminated, ::fantasyCrystal, ::fantasySword)
        CoverGenre.Dystopian ->
            listOf(::dystopianMonolith, ::dystopianEye, ::dystopianBlocks,
                ::dystopianPropaganda, ::dystopianBarcode)
        CoverGenre.ScienceFiction ->
            listOf(::scifiPlanet, ::scifiOrbit, ::scifiCircuit, ::scifiTunnel,
                ::scifiRetro, ::dystopianGlitch)
        CoverGenre.Mystery ->
            listOf(::mysteryStreet, ::mysteryKeyhole, ::mysteryPrint, ::mysteryBlinds,
                ::mysteryRedThread)
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
            listOf(::historyEmblem, ::historyLaurel, ::historyTemple,
                ::fantasyIlluminated)
        CoverGenre.Technical ->
            listOf(::technicalGeometry, ::technicalBlueprint, ::technicalAtom,
                ::bauhausTiles)
        CoverGenre.Literary ->
            listOf(::literaryType, ::literaryShape, ::penguinBands, ::literaryFields)
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

    private fun decode(file: File): Bitmap? =
        if (!file.exists()) null else runCatching { BitmapFactory.decodeFile(file.path) }
            .getOrNull()

    private val compressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.JPEG

    private fun store(file: File, bitmap: Bitmap) {
        runCatching {
            file.outputStream().use { bitmap.compress(compressFormat, 82, it) }
        }
    }
}
