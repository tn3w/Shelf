package dev.tn3w.shelf.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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

private enum class Choosing { Speed, Sleep, Voice }

/** The player: the book's cover, in the book's colours, over whatever is open. */
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
    val saved by shelfViewModel().saved.collectAsStateWithLifecycle()
    val book = remember(saved, work) { saved?.firstOrNull { it.work == work }?.toBook() }
    CoverTheme(coverTone(book)) {
        Sheet(onDismiss) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                book?.let { BookCover(it, 148.dp, lift = 18.dp) }
                Header(work, title, state)
                val failure = if (isAudio) R.string.no_audio else R.string.listen_failed
                when {
                    state.failed -> Hint(
                        stringResource(failure),
                        textAlign = TextAlign.Center,
                    )

                    state.work != work || !state.active -> Ring(
                        null,
                        Modifier.padding(28.dp),
                    )

                    else -> Player(state, playback, isAudio)
                }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    SheetAction(Icons.Rounded.Tune, R.string.voice_settings) {
                        onDismiss()
                        onSettings()
                    }
                    if (isAudio) {
                        SheetAction(Icons.Rounded.LinkOff, R.string.remove_audiobook) {
                            audiobooks.detach(work)
                            onDismiss()
                        }
                    } else {
                        SheetAction(Icons.Rounded.Headphones, R.string.add_audiobook) {
                            picker.launch(arrayOf("audio/*"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(work: Int, title: String, state: Narration) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val chapter = state.chapter.takeIf { state.work == work && it.isNotBlank() }
        Text(
            chapter ?: title,
            style = Look.type.heading,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (chapter != null) Hint(title, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SheetAction(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) =
    Pill(stringResource(label), icon = icon, kind = PillKind.Bare, onClick = onClick)

@Composable
private fun Player(state: Narration, playback: Playback, isAudio: Boolean) {
    if (state.text.isNotBlank()) {
        Text(
            state.text,
            Modifier.fillMaxWidth()
                .animateContentSize()
                .surface(Corners.well, Look.colors.sunken)
                .padding(16.dp),
            style = Look.type.body,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
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
            color = Look.colors.danger,
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
        Icon(Icons.Rounded.Groups, null, Modifier.size(18.dp), Look.colors.soft)
        Hint("${stringResource(R.string.character_voices)} · $status")
    }
}

@Composable
private fun Progress(state: Narration, playback: Playback) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Scrubber(
        value = dragging ?: state.index.toFloat(),
        onChange = { dragging = it },
        range = 0f..maxOf(1, state.count - 1).toFloat(),
        onFinished = {
            dragging?.let { playback.seek(it.roundToInt()) }
            dragging = null
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val percent = 100 * state.index / maxOf(1, state.count - 1)
        val left = DateUtils.formatElapsedTime(state.secondsLeft)
        Hint("$percent %")
        Hint(stringResource(R.string.time_left, left))
    }
}

private val SPEECH_STEPS = listOf(
    Icons.AutoMirrored.Rounded.KeyboardArrowLeft to R.string.previous_sentence,
    Icons.AutoMirrored.Rounded.KeyboardArrowRight to R.string.next_sentence,
)
private val AUDIO_STEPS = listOf(
    Icons.Rounded.Replay10 to R.string.rewind,
    Icons.Rounded.Forward10 to R.string.fast_forward,
)

@Composable
private fun Transport(state: Narration, playback: Playback, isAudio: Boolean) {
    val (back, forward) = if (isAudio) AUDIO_STEPS else SPEECH_STEPS
    val colors = Look.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            Icons.Rounded.SkipPrevious, stringResource(R.string.previous_chapter),
            tint = colors.soft,
        ) { playback.skipChapter(-1) }
        IconAction(back.first, stringResource(back.second)) { playback.skip(-1) }
        val label = if (state.playing) R.string.pause else R.string.play
        val icon = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
        Box(
            Modifier.size(72.dp)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(role = Role.Button, onClick = playback::toggle),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, stringResource(label), Modifier.size(38.dp), colors.onAccent) }
        IconAction(forward.first, stringResource(forward.second)) { playback.skip(1) }
        IconAction(
            Icons.Rounded.SkipNext, stringResource(R.string.next_chapter),
            tint = colors.soft,
        ) { playback.skipChapter(1) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Options(state: Narration, playback: Playback) {
    var choosing by remember { mutableStateOf<Choosing?>(null) }
    fun toggle(option: Choosing) {
        choosing = option.takeIf { it != choosing }
    }
    val voices = state.voices.takeIf { playback is Narrator && it.size > 1 }.orEmpty()
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val speed = choosing == Choosing.Speed
        Tag(rateLabel(state.rate), selected = speed, icon = Icons.Rounded.Speed) {
            toggle(Choosing.Speed)
        }
        val sleep = when (state.sleep) {
            is Sleep.After -> DateUtils.formatElapsedTime(state.sleepLeftSeconds)
            Sleep.Off -> stringResource(R.string.sleep_timer)
            Sleep.ChapterEnd -> sleepLabel(state.sleep)
        }
        Tag(sleep, selected = choosing == Choosing.Sleep, icon = Icons.Rounded.Bedtime) {
            toggle(Choosing.Sleep)
        }
        if (voices.isNotEmpty()) {
            val current = voices.firstOrNull { it.name == state.voice }
            val label = current?.label ?: stringResource(R.string.voice)
            val icon = Icons.Rounded.RecordVoiceOver
            Tag(label, selected = choosing == Choosing.Voice, icon = icon) {
                toggle(Choosing.Voice)
            }
        }
    }
    when (choosing) {
        Choosing.Speed -> Choices(SPEECH_RATES, { it == state.rate }, { rateLabel(it) }) {
            playback.setRate(it)
        }

        Choosing.Sleep -> Choices(SLEEP_OPTIONS, {
            it == state.sleep
        }, { sleepLabel(it) }) {
            playback.setSleep(it)
        }

        Choosing.Voice -> Choices(voices, { it.name == state.voice }, { it.label }) {
            (playback as? Narrator)?.setVoice(it.name)
        }

        null -> Unit
    }
}

/** The values of one option, opened in place under the option itself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(
    options: List<T>,
    isSelected: (T) -> Boolean,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) = FlowRow(
    Modifier.fillMaxWidth().surface(Corners.well, Look.colors.sunken).padding(10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    options.forEach { Tag(label(it), selected = isSelected(it)) { onSelect(it) } }
}

private fun rateLabel(rate: Float) = "${rate.toString().removeSuffix(".0")}×"

@Composable
private fun sleepLabel(option: Sleep) = when (option) {
    Sleep.Off -> stringResource(R.string.sleep_off)
    Sleep.ChapterEnd -> stringResource(R.string.sleep_chapter_end)
    is Sleep.After -> stringResource(R.string.sleep_minutes, option.minutes)
}
