package dev.tn3w.shelf.cover

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GeneratedCover(request: CoverRequest, description: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPixels =
            with(LocalDensity.current) {
                (if (maxWidth > 0.dp) maxWidth else 160.dp).roundToPx()
            }
        val bitmap: ImageBitmap? by
            produceState(null, request.work, request.title, widthPixels) {
                value =
                    withContext(Dispatchers.Default) {
                        Covers.cached(context, request, widthPixels).asImageBitmap()
                    }
            }
        bitmap?.let {
            Image(it, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
