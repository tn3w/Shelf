package dev.tn3w.shelf

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import androidx.core.net.toUri
import dev.tn3w.shelf.data.Level
import dev.tn3w.shelf.data.StreamCache
import dev.tn3w.shelf.data.loudness

private const val CODEC_TIMEOUT_MICROS = 10_000L

class AudioSource(
    val uri: String,
    val headers: Map<String, String>,
    val cache: StreamCache? = null,
) {
    val isStreamed get() = uri.startsWith("http://") || uri.startsWith("https://")
}

fun MediaPlayer.setDataSource(context: Context, source: AudioSource) {
    val cache = source.cache
    if (cache != null) return setDataSource(cache.asMediaSource())
    setDataSource(context, source.uri.toUri(), source.headers)
}

private fun MediaExtractor.setDataSource(context: Context, source: AudioSource) {
    val cache = source.cache
    if (cache != null) return setDataSource(cache.asMediaSource())
    setDataSource(context, source.uri.toUri(), source.headers)
}

private fun StreamCache.asMediaSource() = object : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) =
        this@asMediaSource.readAt(position, buffer, offset, size)

    override fun getSize() = this@asMediaSource.size

    override fun close() = Unit
}

fun Context.decodedLevels(source: AudioSource, fromMillis: Long, active: () -> Boolean) =
    sequence {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(this@decodedLevels, source)
            val format = extractor.selectAudio()
            extractor.seekTo(fromMillis * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            while (active()) {
                feed(extractor, decoder)
                val index = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_MICROS)
                if (index < 0) continue
                val pcm = decoder.getOutputBuffer(index)!!
                val level = Level(info.presentationTimeUs / 1000, loudness(pcm))
                decoder.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                yield(level)
            }
        } finally {
            codec?.release()
            extractor.release()
        }
    }

private fun MediaExtractor.selectAudio(): MediaFormat {
    val track = (0 until trackCount).first {
        getTrackFormat(it).mime.startsWith("audio/")
    }
    selectTrack(track)
    return getTrackFormat(track)
}

private val MediaFormat.mime get() = getString(MediaFormat.KEY_MIME).orEmpty()

private fun feed(extractor: MediaExtractor, codec: MediaCodec) {
    val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_MICROS)
    if (index < 0) return
    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
    if (size < 0) {
        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        return
    }
    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
    extractor.advance()
}
