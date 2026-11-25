package com.bendcam.app.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.bendcam.app.ImageIO
import com.bendcam.app.effects.Bender
import com.bendcam.app.effects.EffectLayer
import com.bendcam.app.effects.FxContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max

/** Records already-bent live frames (plus optional mic) to an MP4 in the cache dir. */
class LiveRecorder(private val context: Context, width: Int, height: Int, withAudio: Boolean) {
    private val file = File(context.cacheDir, "rec_${System.currentTimeMillis()}.mp4")
    private val origin = System.nanoTime()
    private val writer = MuxWriter(file, if (withAudio) 2 else 1)
    private val video = VideoTrackEncoder(width and 1.inv(), height and 1.inv(), 30, writer)
    private val mic: MicEncoder? = if (withAudio) MicEncoder(writer, origin).takeIf { it.start() } else null
    private var stopped = false
    val startedAt = System.currentTimeMillis()

    init {
        if (withAudio && mic == null) writer.dropExpectedTrack()
    }

    /** Frames of a different size (camera switched) are ignored. */
    @Synchronized
    fun onFrame(bmp: Bitmap) {
        if (stopped) return
        if (bmp.width < video.width || bmp.height < video.height) return
        try {
            video.submit(bmp, (System.nanoTime() - origin) / 1000)
        } catch (e: Exception) {
            Log.e("BendCam", "record frame", e)
        }
    }

    /** Finishes the file and copies it to Movies/BendCam. Blocking; call off the main thread. */
    fun stop(): Uri? {
        synchronized(this) {
            if (stopped) return null
            stopped = true
            video.finish()
        }
        mic?.stop()
        val ok = writer.close()
        val uri = if (ok) ImageIO.saveVideo(context, file) else null
        file.delete()
        return uri
    }
}

data class VideoInfo(val durationMs: Long, val width: Int, val height: Int, val frameCount: Int, val fps: Float)

object VideoBender {
    fun info(context: Context, uri: Uri): VideoInfo? = withRetriever(context, uri) { r ->
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@withRetriever null
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val frames = if (Build.VERSION.SDK_INT >= 28)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0 else 0
        val fps = if (frames > 0 && dur > 0) frames * 1000f / dur else 30f
        val (ow, oh) = if (rot == 90 || rot == 270) h to w else w to h
        VideoInfo(dur, ow, oh, frames, fps)
    }

    /** Frame at [fraction] of the clip, upright, for previews. */
    fun frameAt(context: Context, uri: Uri, fraction: Float, maxSize: Int): Bitmap? = withRetriever(context, uri) { r ->
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val t = (dur * 1000L * fraction.coerceIn(0f, 0.999f)).toLong()
        r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { ImageIO.fit(it, maxSize) }
    }

    /**
     * Re-encodes the clip with [chain] applied to every frame (temporal effects included)
     * and the original audio copied through. Returns the saved Uri, or null on failure/cancel.
     */
    fun export(
        context: Context,
        uri: Uri,
        chain: List<EffectLayer>,
        seed: Long,
        maxSize: Int,
        onProgress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ): Uri? {
        val info = info(context, uri) ?: return null
        val file = File(context.cacheDir, "bend_${System.currentTimeMillis()}.mp4")
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        var writer: MuxWriter? = null
        var encoder: VideoTrackEncoder? = null
        var ok = false
        try {
            retriever.setDataSource(context, uri)

            // Audio passthrough track, if the container supports it.
            extractor.setDataSource(context, uri, null)
            var audioIdx = -1
            var audioFmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                val supported = mime == MediaFormat.MIMETYPE_AUDIO_AAC || mime == MediaFormat.MIMETYPE_AUDIO_AMR_NB ||
                    mime == MediaFormat.MIMETYPE_AUDIO_AMR_WB ||
                    (Build.VERSION.SDK_INT >= 29 && mime == MediaFormat.MIMETYPE_AUDIO_OPUS)
                if (mime.startsWith("audio/") && supported) { audioIdx = i; audioFmt = f; break }
            }
            val w = MuxWriter(file, if (audioFmt != null) 2 else 1).also { writer = it }
            var audioTrack = -1
            if (audioFmt != null) {
                audioTrack = try { w.addTrack(audioFmt) } catch (e: Exception) { w.dropExpectedTrack(); -1 }
            }

            // Sample at most 30 fps.
            val srcFps = if (info.fps > 1f) info.fps else 30f
            val step = max(1, ceil(srcFps / 30f).toInt())
            val outFps = srcFps / step
            val useIndex = Build.VERSION.SDK_INT >= 28 && info.frameCount > 0
            val total = if (useIndex) (info.frameCount + step - 1) / step
            else max(1, (info.durationMs / 1000f * 24f).toInt())
            val frameUs = if (useIndex) (1_000_000f / outFps) else 1_000_000f / 24f
            val ctx = FxContext()

            var n = 0
            fun emit(frame: Bitmap) {
                val upright = ImageIO.fit(frame, maxSize)
                val enc = encoder ?: VideoTrackEncoder(
                    upright.width and 1.inv(), upright.height and 1.inv(), outFps.toInt().coerceAtLeast(1), w
                ).also { encoder = it }
                val bent = Bender.render(upright, chain, seed xor (n.toLong() * 7919L), ctx)
                if (bent.width >= enc.width && bent.height >= enc.height) enc.submit(bent, (n * frameUs).toLong())
                bent.recycle()
                if (upright !== frame) upright.recycle()
                n++
                onProgress(n.toFloat() / total)
            }

            if (useIndex && Build.VERSION.SDK_INT >= 28) {
                val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
                var i = 0
                val batch = 12
                while (i < info.frameCount && !cancelled()) {
                    val count = minOf(batch, info.frameCount - i)
                    val frames = try { retriever.getFramesAtIndex(i, count, params) } catch (e: Exception) {
                        Log.w("BendCam", "frame batch $i failed", e); emptyList()
                    }
                    for ((k, fr) in frames.withIndex()) {
                        if ((i + k) % step == 0 && !cancelled()) emit(fr)
                        fr.recycle()
                    }
                    i += count
                }
            } else {
                for (k in 0 until total) {
                    if (cancelled()) break
                    val fr = retriever.getFrameAtTime((k * frameUs).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                    emit(fr)
                    fr.recycle()
                }
            }
            encoder?.finish(); encoder = null

            if (audioTrack >= 0 && !cancelled() && n > 0) {
                extractor.selectTrack(audioIdx)
                val buf = ByteBuffer.allocate(1 shl 20)
                val bi = MediaCodec.BufferInfo()
                val endUs = (n * frameUs).toLong()
                while (true) {
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0 || extractor.sampleTime > endUs) break
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    bi.set(0, size, extractor.sampleTime, flags)
                    w.write(audioTrack, buf, bi)
                    extractor.advance()
                }
            }
            ok = n > 0 && !cancelled()
        } catch (e: Exception) {
            Log.e("BendCam", "video export", e)
        } finally {
            encoder?.finish()
            val closed = writer?.close() ?: false
            ok = ok && closed
            runCatching { retriever.release() }
            extractor.release()
        }
        val saved = if (ok && !cancelled()) ImageIO.saveVideo(context, file) else null
        file.delete()
        return saved
    }

    private fun <T> withRetriever(context: Context, uri: Uri, block: (MediaMetadataRetriever) -> T?): T? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri); block(r)
        } catch (e: Exception) {
            Log.e("BendCam", "retriever", e); null
        } finally {
            runCatching { r.release() }
        }
    }
}
