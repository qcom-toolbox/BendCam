package com.bendcam.app.video

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "BendCam"

/**
 * MediaMuxer wrapper that can be fed from several threads and buffers samples
 * until every expected track has announced its format.
 */
class MuxWriter(file: File, private var expectedTracks: Int) {
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var added = 0
    private var started = false
    private var wroteAny = false
    private val pending = ArrayList<Triple<Int, ByteArray, MediaCodec.BufferInfo>>()

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val idx = muxer.addTrack(format)
        added++
        if (added >= expectedTracks) start()
        return idx
    }

    /** A track that will never deliver (e.g. mic failed): stop waiting for it. */
    @Synchronized
    fun dropExpectedTrack() {
        expectedTracks--
        if (added in 1..Int.MAX_VALUE && added >= expectedTracks) start()
    }

    private fun start() {
        if (started) return
        muxer.start()
        started = true
        for ((t, bytes, info) in pending) {
            muxer.writeSampleData(t, ByteBuffer.wrap(bytes), info)
        }
        pending.clear()
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return
        wroteAny = true
        if (started) {
            muxer.writeSampleData(track, buf, info)
        } else {
            val copy = ByteArray(info.size)
            buf.position(info.offset); buf.get(copy, 0, info.size)
            val ci = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
            pending.add(Triple(track, copy, ci))
        }
    }

    /** Returns true when a playable file was written. */
    @Synchronized
    fun close(): Boolean {
        return try {
            if (!started && added > 0) start()
            if (started) muxer.stop()
            started && wroteAny
        } catch (e: Exception) {
            Log.e(TAG, "muxer close", e); false
        } finally {
            runCatching { muxer.release() }
        }
    }
}

/** H.264 encoder fed with ARGB bitmaps at explicit timestamps. Not thread-safe; use from one thread. */
class VideoTrackEncoder(val width: Int, val height: Int, fps: Int, private val writer: MuxWriter) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var lastPts = -1L
    private var argb = IntArray(width * height)
    private val yRow = ByteArray(width)

    init {
        require(width % 2 == 0 && height % 2 == 0)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * 0.25f).toInt().coerceIn(1_500_000, 16_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun submit(bmp: Bitmap, ptsUs: Long) {
        val pts = if (ptsUs <= lastPts) lastPts + 1 else ptsUs
        lastPts = pts
        // Crop to the encoder size (bitmaps may be one pixel larger on odd dimensions).
        bmp.getPixels(argb, 0, width, 0, 0, minOf(width, bmp.width), minOf(height, bmp.height))
        var idx: Int
        while (true) {
            idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) break
            drain(false)
        }
        val image = codec.getInputImage(idx)
        if (image != null) {
            val planes = image.planes
            // Y
            val yp = planes[0]
            val yBuf = yp.buffer
            for (y in 0 until height) {
                val row = y * width
                if (yp.pixelStride == 1) {
                    for (x in 0 until width) yRow[x] = yOf(argb[row + x]).toByte()
                    yBuf.position(y * yp.rowStride)
                    yBuf.put(yRow, 0, width)
                } else {
                    for (x in 0 until width) yBuf.put(y * yp.rowStride + x * yp.pixelStride, yOf(argb[row + x]).toByte())
                }
            }
            // U, V (subsampled 2x2)
            val up = planes[1]; val vp = planes[2]
            val uBuf = up.buffer; val vBuf = vp.buffer
            for (y in 0 until height / 2) for (x in 0 until width / 2) {
                val c = argb[(y * 2) * width + x * 2]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                uBuf.put(y * up.rowStride + x * up.pixelStride, u.coerceIn(0, 255).toByte())
                vBuf.put(y * vp.rowStride + x * vp.pixelStride, v.coerceIn(0, 255).toByte())
            }
        } else {
            // Rare: codec without Image support. Write planar I420 into the raw buffer.
            val buf = codec.getInputBuffer(idx)!!
            buf.clear()
            for (i in 0 until width * height) buf.put(yOf(argb[i]).toByte())
            val us = ByteArray(width * height / 4); val vs = ByteArray(width * height / 4)
            var k = 0
            for (y in 0 until height / 2) for (x in 0 until width / 2) {
                val c = argb[(y * 2) * width + x * 2]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                us[k] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                vs[k] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                k++
            }
            buf.put(us); buf.put(vs)
        }
        codec.queueInputBuffer(idx, 0, width * height * 3 / 2, pts, 0)
        drain(false)
    }

    private fun yOf(c: Int): Int {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = writer.addTrack(codec.outputFormat)
                out >= 0 -> {
                    val buf = codec.getOutputBuffer(out)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && track >= 0) writer.write(track, buf, info)
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun finish() {
        try {
            var idx: Int
            var tries = 0
            while (true) {
                idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0 || ++tries > 100) break
                drain(false)
            }
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, lastPts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "video finish", e)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }
}

/** Microphone → AAC, on its own thread. Timestamps share the caller's clock origin. */
class MicEncoder(private val writer: MuxWriter, private val originNanos: Long) {
    @Volatile private var running = true
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start(): Boolean {
        val rate = 44_100
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        } catch (e: Exception) { return false }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return false }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        record.startRecording()
        thread = Thread({
            val info = MediaCodec.BufferInfo()
            var track = -1
            var lastPts = -1L
            var eosSent = false
            try {
                loop@ while (true) {
                    if (!eosSent) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx)!!
                            buf.clear()
                            val n = record.read(buf, minOf(buf.capacity(), minBuf))
                            var pts = (System.nanoTime() - originNanos) / 1000 - (n / 2) * 1_000_000L / rate
                            if (pts <= lastPts) pts = lastPts + 1
                            lastPts = pts
                            if (!running) {
                                codec.queueInputBuffer(idx, 0, maxOf(0, n), pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosSent = true
                            } else {
                                codec.queueInputBuffer(idx, 0, maxOf(0, n), pts, 0)
                            }
                        }
                    }
                    while (true) {
                        val out = codec.dequeueOutputBuffer(info, 0)
                        if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = writer.addTrack(codec.outputFormat); continue }
                        if (out >= 0) {
                            val buf = codec.getOutputBuffer(out)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && track >= 0) writer.write(track, buf, info)
                            codec.releaseOutputBuffer(out, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "mic encoder", e)
            } finally {
                if (track < 0) writer.dropExpectedTrack()
                runCatching { record.stop() }; record.release()
                runCatching { codec.stop() }; codec.release()
            }
        }, "bendcam-mic").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(3000)
    }
}
