package dev.tn3w.shelf.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
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
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
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
import androidx.compose.material3.Switch
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
import dev.tn3w.shelf.AudiobookPlayer
import dev.tn3w.shelf.Narration
import dev.tn3w.shelf.Narrator
import dev.tn3w.shelf.Playback
import dev.tn3w.shelf.R
import dev.tn3w.shelf.data.SLEEP_MINUTES
import dev.tn3w.shelf.data.SPEECH_RATES
import dev.tn3w.shelf.data.Sleep
import kotlin.math.roundToInt

private const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenSheet(
    work: Int,
    title: String,
    narrator: Narrator,
    audiobooks: AudiobookPlayer,
    onDismiss: () -> Unit,
) {
    val speech by narrator.state.collectAsStateWithLifecycle()
    val audio by audiobooks.state.collectAsStateWithLifecycle()
    val isAudio = audio.work == work && (audio.active || audio.loading || audio.failed)
    val state = if (isAudio) audio else speech
    val playback = if (isAudio) audiobooks else narrator
    val picker = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) audiobooks.attach(work, title, uris)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val failure = if (isAudio) R.string.no_audio else R.string.listen_failed
            when {
                state.failed -> Text(stringResource(failure))
                state.work != work || !state.active -> Loader()
                else -> Player(state, playback, isAudio)
            }
            if (isAudio) {
                TextButton({
                    audiobooks.detach(work)
                    onDismiss()
                }) { Text(stringResource(R.string.remove_audiobook)) }
            } else {
                TextButton({ picker.launch(arrayOf("audio/*")) }) {
                    Text(stringResource(R.string.add_audiobook))
                }
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
private fun Player(state: Narration, playback: Playback, isAudio: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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
    if (state.text.isNotBlank()) {
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
    }
    Progress(state, playback)
    Transport(state, playback, isAudio)
    Rates(state, playback)
    SleepTimer(state, playback)
    if (playback is Narrator) Voices(state, playback)
}

@Composable
private fun Progress(state: Narration, playback: Playback) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column {
        Slider(
            value = dragging ?: state.index.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { playback.seek(it.roundToInt()) }
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

private val SPEECH_STEPS = listOf(
    Icons.AutoMirrored.Filled.KeyboardArrowLeft to R.string.previous_sentence,
    Icons.AutoMirrored.Filled.KeyboardArrowRight to R.string.next_sentence,
)
private val AUDIO_STEPS = listOf(
    Icons.Filled.Replay10 to R.string.rewind,
    Icons.Filled.Forward10 to R.string.fast_forward,
)

@Composable
private fun Transport(state: Narration, playback: Playback, isAudio: Boolean) {
    val (back, forward) = if (isAudio) AUDIO_STEPS else SPEECH_STEPS
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Icons.Filled.SkipPrevious, stringResource(R.string.previous_chapter),
        ) { playback.skipChapter(-1) }
        IconAction(back.first, stringResource(back.second)) { playback.skip(-1) }
        val label = if (state.playing) R.string.pause else R.string.play
        FilledIconButton(playback::toggle, Modifier.size(64.dp)) {
            val icon = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow
            Icon(icon, stringResource(label), Modifier.size(36.dp))
        }
        IconAction(forward.first, stringResource(forward.second)) { playback.skip(1) }
        IconAction(
            Icons.Filled.SkipNext, stringResource(R.string.next_chapter),
        ) { playback.skipChapter(1) }
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
private fun Rates(state: Narration, playback: Playback) {
    Section(R.string.speech_speed) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SPEECH_RATES.forEach { rate ->
                FilterChip(
                    selected = state.rate == rate,
                    onClick = { playback.setRate(rate) },
                    label = { Text("${rate.toString().removeSuffix(".0")}×") },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimer(state: Narration, playback: Playback) {
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
                    onClick = { playback.setSleep(option) },
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
    if (state.canVoiceCharacters) CharacterVoices(state, narrator)
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
    if (state.natural) return
    TextButton({ runCatching { context.startActivity(Intent(TTS_SETTINGS)) } }) {
        Text(stringResource(R.string.voice_settings))
    }
}

@Composable
private fun CharacterVoices(state: Narration, narrator: Narrator) {
    val status = when {
        !state.characterVoices -> stringResource(R.string.character_voices_hint)
        state.preparing -> stringResource(R.string.finding_speakers)
        else -> stringResource(R.string.speakers_found, state.speakers)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.character_voices),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(state.characterVoices, narrator::setCharacterVoices)
    }
}
