package dev.tn3w.shelf.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tn3w.shelf.Narration
import dev.tn3w.shelf.Narrator
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.SLEEP_MINUTES
import dev.tn3w.shelf.data.SPEECH_RATES
import dev.tn3w.shelf.data.Sleep
import kotlin.math.roundToInt

private const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenSheet(work: Int, narrator: Narrator, onDismiss: () -> Unit) {
    val state by narrator.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                state.failed -> Text(stringResource(R.string.listen_failed))
                state.work != work || !state.active -> Loader()
                else -> Player(state, narrator)
            }
        }
    }
}

@Composable
private fun Loader() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Player(state: Narration, narrator: Narrator) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            state.chapter.ifBlank { state.title },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.chapter.isNotBlank()) {
            Text(
                state.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Text(
            state.text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(16.dp),
        )
    }
    Progress(state, narrator)
    Transport(state, narrator)
    Rates(state, narrator)
    SleepTimer(state, narrator)
    Voices(state, narrator)
}

@Composable
private fun Progress(state: Narration, narrator: Narrator) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column {
        Slider(
            value = dragging ?: state.index.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { narrator.seek(it.roundToInt()) }
                dragging = null
            },
            valueRange = 0f..maxOf(1, state.count - 1).toFloat(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val percent = 100 * state.index / maxOf(1, state.count - 1)
            val left = DateUtils.formatElapsedTime(state.secondsLeft)
            Text("$percent %", style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(R.string.time_left, left),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun Transport(state: Narration, narrator: Narrator) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Icons.Filled.SkipPrevious, stringResource(R.string.previous_chapter),
        ) { narrator.skipChapter(-1) }
        IconAction(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            stringResource(R.string.previous_sentence),
        ) { narrator.skip(-1) }
        val label = if (state.playing) R.string.pause else R.string.play
        FilledIconButton(narrator::toggle, Modifier.size(64.dp)) {
            val icon = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow
            Icon(icon, stringResource(label), Modifier.size(36.dp))
        }
        IconAction(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            stringResource(R.string.next_sentence),
        ) { narrator.skip(1) }
        IconAction(
            Icons.Filled.SkipNext, stringResource(R.string.next_chapter),
        ) { narrator.skipChapter(1) }
    }
}

@Composable
private fun Section(label: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun Rates(state: Narration, narrator: Narrator) {
    Section(R.string.speech_speed) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SPEECH_RATES.forEach { rate ->
                FilterChip(
                    selected = state.rate == rate,
                    onClick = { narrator.setRate(rate) },
                    label = { Text("${rate.toString().removeSuffix(".0")}×") },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimer(state: Narration, narrator: Narrator) {
    Section(R.string.sleep_timer) {
        val left = DateUtils.formatElapsedTime(state.sleepLeftSeconds)
        if (state.sleep is Sleep.After) {
            Text(
                stringResource(R.string.sleep_stops_in, left),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = listOf(Sleep.Off) + SLEEP_MINUTES.map { Sleep.After(it) } +
                Sleep.ChapterEnd
            options.forEach { option ->
                val selected = when (option) {
                    is Sleep.After -> (state.sleep as? Sleep.After)?.minutes ==
                        option.minutes

                    else -> state.sleep == option
                }
                FilterChip(
                    selected = selected,
                    onClick = { narrator.setSleep(option) },
                    label = { Text(sleepLabel(option)) },
                )
            }
        }
    }
}

@Composable
private fun sleepLabel(option: Sleep) = when (option) {
    Sleep.Off -> stringResource(R.string.sleep_off)
    Sleep.ChapterEnd -> stringResource(R.string.sleep_chapter_end)
    is Sleep.After -> stringResource(R.string.sleep_minutes, option.minutes)
}

@Composable
private fun Voices(state: Narration, narrator: Narrator) {
    val context = LocalContext.current
    if (state.missingVoice) {
        Text(
            stringResource(R.string.voice_missing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (state.voices.size > 1) {
        Section(R.string.voice) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.voices.forEach { voice ->
                    FilterChip(
                        selected = state.voice == voice.name,
                        onClick = { narrator.setVoice(voice.name) },
                        label = { Text(voice.label) },
                    )
                }
            }
        }
    }
    TextButton({ runCatching { context.startActivity(Intent(TTS_SETTINGS)) } }) {
        Text(stringResource(R.string.voice_settings))
    }
}
