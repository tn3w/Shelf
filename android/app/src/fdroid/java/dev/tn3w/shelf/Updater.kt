package dev.tn3w.shelf

import android.content.Context

object Updater {
    fun isEnabled(context: Context) = false

    suspend fun latest(): AppRelease? = null

    suspend fun install(
        context: Context,
        release: AppRelease,
        onProgress: (Float) -> Unit,
    ) {}
}
