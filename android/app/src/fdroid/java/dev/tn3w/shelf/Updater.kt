package dev.tn3w.shelf

import android.content.Context
import dev.tn3w.shelf.data.Release

object Updater {
    fun isEnabled(context: Context) = false

    suspend fun latest(): Release? = null

    suspend fun install(
        context: Context,
        release: Release,
        onProgress: (Float) -> Unit,
    ) {}
}
