package dev.tn3w.shelf

import android.app.Activity
import android.content.Context
import android.content.Intent

object Updater {
    fun isEnabled(context: Context) = false

    suspend fun latest(): AppRelease? = null

    suspend fun install(
        context: Context,
        release: AppRelease,
        onProgress: (Float) -> Unit,
    ) {}

    fun onIntent(activity: Activity, intent: Intent) {}
}
