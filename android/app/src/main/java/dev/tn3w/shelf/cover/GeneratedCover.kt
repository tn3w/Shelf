package dev.tn3w.shelf.cover

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
fun GeneratedCover(
    request: CoverRequest,
    description: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPixels = with(LocalDensity.current) {
            (if (maxWidth > 0.dp) maxWidth else 160.dp).roundToPx()
        }
        val bitmap: ImageBitmap? by
            produceState(null, request, widthPixels) {
                value = withContext(Dispatchers.Default) {
                    Covers.cached(context, request, widthPixels).asImageBitmap()
                }
            }
        bitmap?.let {
            Image(
                it, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
        }
    }
}
