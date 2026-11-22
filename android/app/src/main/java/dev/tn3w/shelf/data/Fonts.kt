package dev.tn3w.shelf.data

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.tn3w.shelf.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream

private val FACES = listOf(
    FontWeight.Normal to FontStyle.Normal,
    FontWeight.Bold to FontStyle.Normal,
    FontWeight.Normal to FontStyle.Italic,
    FontWeight.Bold to FontStyle.Italic,
)

enum class ReaderFont(@StringRes val label: Int) {
    Serif(R.string.typeface_serif),
    Sans(R.string.typeface_sans),
    Dyslexic(R.string.typeface_dyslexic),
    Own(R.string.typeface_own),
}

fun fontFamilyOf(files: List<File>) =
    FontFamily(files.zip(FACES) { file, (weight, style) -> Font(file, weight, style) })

class Fonts(private val context: Context, private val models: Models) {
    private val directory = context.filesDir.resolve("fonts")
    private val _own = MutableStateFlow(ownFile())
    val own = _own.asStateFlow()

    private fun ownFile() = directory.listFiles().orEmpty().firstOrNull()

    fun dyslexic() = models.installed("font")?.let { model ->
        model.files.map { models.folderOf(model).resolve(it.name) }
    }

    fun familyOf(font: ReaderFont) = when (font) {
        ReaderFont.Serif -> FontFamily.Serif
        ReaderFont.Sans -> FontFamily.SansSerif
        ReaderFont.Dyslexic -> dyslexic()?.let(::fontFamilyOf)
        ReaderFont.Own -> own.value?.let { FontFamily(Font(it)) }
    } ?: FontFamily.Serif

    fun import(input: InputStream): Boolean {
        val temporary = File.createTempFile("font", null, context.cacheDir)
        try {
            temporary.outputStream().use(input::copyTo)
            if (Typeface.Builder(temporary).build() == null) return false
            directory.deleteRecursively()
            directory.mkdirs()
            val target = directory.resolve("own-${System.currentTimeMillis()}")
            check(temporary.renameTo(target)) { "cannot keep ${target.name}" }
            _own.value = target
            return true
        } finally {
            temporary.delete()
        }
    }
}
