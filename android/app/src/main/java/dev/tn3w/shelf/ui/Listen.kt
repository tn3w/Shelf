package dev.tn3w.shelf.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import dev.tn3w.shelf.data.toBook
import kotlin.math.roundToInt

private val SLEEP_OPTIONS =
    listOf(Sleep.Off) + SLEEP_MINUTES.map { Sleep.After(it) } + Sleep.ChapterEnd

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenSheet(
    work: Int,
    title: String,
    narrator: Narrator,
    audiobooks: AudiobookPlayer,
    onSettings: () -> Unit,
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Header(work, title, state)
            val failure = if (isAudio) R.string.no_audio else R.string.listen_failed
            when {
                state.failed -> Failure(failure)
                state.work != work || !state.active -> Loader()
                else -> Player(state, playback, isAudio)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SheetAction(Icons.Outlined.Tune, R.string.voice_settings) {
                    onDismiss()
                    onSettings()
                }
                if (isAudio) {
                    SheetAction(Icons.Outlined.LinkOff, R.string.remove_audiobook) {
                        audiobooks.detach(work)
                        onDismiss()
                    }
                } else {
                    SheetAction(Icons.Outlined.Headphones, R.string.add_audiobook) {
                        picker.launch(arrayOf("audio/*"))
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(work: Int, title: String, state: Narration) {
    val saved by shelfViewModel().saved.collectAsStateWithLifecycle()
    val book = remember(saved, work) { saved?.firstOrNull { it.work == work }?.toBook() }
    book?.let { BookCover(it, 132.dp) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val chapter = state.chapter.takeIf { state.work == work && it.isNotBlank() }
        Text(
            chapter ?: title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (chapter != null) Hint(title, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SheetAction(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    TextButton(onClick) {
        Icon(icon, null, Modifier.size(18.dp))
        Text(stringResource(label), Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Failure(@StringRes message: Int) =
    Hint(stringResource(message), textAlign = TextAlign.Center)

@Composable
private fun Loader() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Player(state: Narration, playback: Playback, isAudio: Boolean) {
    if (state.text.isNotBlank()) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().animateContentSize(),
        ) {
            Text(
                state.text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
    if (playback is Narrator) VoiceStatus(state)
    Column {
        Progress(state, playback)
        Transport(state, playback, isAudio)
    }
    Options(state, playback)
}

@Composable
private fun VoiceStatus(state: Narration) {
    if (state.missingVoice) {
        return Hint(
            stringResource(R.string.voice_missing),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
    if (!state.characterVoices || !state.canVoiceCharacters) return
    val status = if (state.preparing) {
        stringResource(R.string.finding_speakers)
    } else {
        stringResource(R.string.speakers_found, state.speakers)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Groups,
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Hint("${stringResource(R.string.character_voices)} · $status")
    }
}

@Composable
private fun Progress(state: Narration, playback: Playback) {
    var dragging by remember { mutableStateOf<Float?>(null) }
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
        Hint("$percent %")
        Hint(stringResource(R.string.time_left, left))
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
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Icons.Filled.SkipPrevious, stringResource(R.string.previous_chapter),
        ) { playback.skipChapter(-1) }
        IconAction(back.first, stringResource(back.second)) { playback.skip(-1) }
        val label = if (state.playing) R.string.pause else R.string.play
        FilledIconButton(playback::toggle, Modifier.size(72.dp)) {
            val icon = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow
            Icon(icon, stringResource(label), Modifier.size(40.dp))
        }
        IconAction(forward.first, stringResource(forward.second)) { playback.skip(1) }
        IconAction(
            Icons.Filled.SkipNext, stringResource(R.string.next_chapter),
        ) { playback.skipChapter(1) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Options(state: Narration, playback: Playback) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OptionMenu(
            Icons.Outlined.Speed,
            rateLabel(state.rate),
            SPEECH_RATES,
            { it == state.rate },
            { rateLabel(it) },
            playback::setRate,
        )
        val sleep = when (state.sleep) {
            is Sleep.After -> DateUtils.formatElapsedTime(state.sleepLeftSeconds)
            Sleep.Off -> stringResource(R.string.sleep_timer)
            Sleep.ChapterEnd -> sleepLabel(state.sleep)
        }
        OptionMenu(
            Icons.Outlined.Bedtime,
            sleep,
            SLEEP_OPTIONS,
            { it == state.sleep },
            { sleepLabel(it) },
            playback::setSleep,
        )
        if (playback is Narrator && state.voices.size > 1) {
            val current = state.voices.firstOrNull { it.name == state.voice }
            OptionMenu(
                Icons.Outlined.RecordVoiceOver,
                current?.label ?: stringResource(R.string.voice),
                state.voices,
                { it.name == state.voice },
                { it.label },
                { playback.setVoice(it.name) },
            )
        }
    }
}

@Composable
private fun <T> OptionMenu(
    icon: ImageVector,
    label: String,
    options: List<T>,
    isSelected: (T) -> Boolean,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton({ open = true }) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(
                label,
                Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    trailingIcon = if (isSelected(option)) {
                        { Icon(Icons.Outlined.Check, null) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

private fun rateLabel(rate: Float) = "${rate.toString().removeSuffix(".0")}×"

@Composable
private fun sleepLabel(option: Sleep) = when (option) {
    Sleep.Off -> stringResource(R.string.sleep_off)
    Sleep.ChapterEnd -> stringResource(R.string.sleep_chapter_end)
    is Sleep.After -> stringResource(R.string.sleep_minutes, option.minutes)
}
