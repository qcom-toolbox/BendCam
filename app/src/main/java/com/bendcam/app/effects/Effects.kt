package com.bendcam.app.effects

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** ARGB pixel buffer. Effects mutate [px] in place or swap it for a new array. */
class Frame(var px: IntArray, val w: Int, val h: Int)

/**
 * A "circuit bent" camera effect. [amount] is 0..1. [rnd] is seeded so a gallery
 * render is reproducible; live camera re-seeds each frame for flicker.
 */
enum class Effect(val label: String, val default: Float) {
    RGB_SPLIT("RGB Split", 0.4f),
    SCAN_TEAR("Scan Tear", 0.5f),
    PIXEL_SORT("Pixel Sort", 0.5f),
    DATAMOSH("Datamosh", 0.4f),
    JPEG_ROT("JPEG Rot", 0.3f),
    ROW_SMEAR("Row Smear", 0.4f),
    WAVE("Wave Bend", 0.4f),
    INTERLACE("Interlace", 0.5f),
    VHS("VHS", 0.5f),
    SENSOR_NOISE("Dead Sensor", 0.4f),
    BIT_CRUSH("Bit Crush", 0.5f),
    CHANNEL_SWAP("Channel Swap", 0.5f),
    SOLARIZE("Solarize", 0.5f),
    THERMAL("Thermal", 0.7f),
    MIRROR("Mirror", 1f),
    PIXELATE("Pixelate", 0.3f);

    fun apply(f: Frame, amount: Float, rnd: Random) {
        val a = amount.coerceIn(0f, 1f)
        if (a <= 0.001f) return
        when (this) {
            RGB_SPLIT -> rgbSplit(f, a)
            SCAN_TEAR -> scanTear(f, a, rnd)
            PIXEL_SORT -> pixelSort(f, a)
            DATAMOSH -> datamosh(f, a, rnd)
            JPEG_ROT -> jpegRot(f, a, rnd)
            ROW_SMEAR -> rowSmear(f, a, rnd)
            WAVE -> wave(f, a, rnd)
            INTERLACE -> interlace(f, a)
            VHS -> vhs(f, a, rnd)
            SENSOR_NOISE -> sensorNoise(f, a, rnd)
            BIT_CRUSH -> bitCrush(f, a)
            CHANNEL_SWAP -> channelSwap(f, a)
            SOLARIZE -> solarize(f, a)
            THERMAL -> thermal(f, a)
            MIRROR -> mirror(f, a)
            PIXELATE -> pixelate(f, a)
        }
    }
}

data class EffectLayer(val effect: Effect, val amount: Float = effect.default)

object Bender {
    fun render(src: Bitmap, chain: List<EffectLayer>, seed: Long): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val f = Frame(px, w, h)
        val rnd = Random(seed)
        for (layer in chain) layer.effect.apply(f, layer.amount, rnd)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(f.px, 0, w, 0, 0, w, h)
        return out
    }
}

// ---------- helpers ----------

private fun r(c: Int) = (c shr 16) and 0xFF
private fun g(c: Int) = (c shr 8) and 0xFF
private fun b(c: Int) = c and 0xFF
private fun rgb(r: Int, g: Int, b: Int) =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
private fun luma(c: Int) = (r(c) * 77 + g(c) * 150 + b(c) * 29) shr 8

// ---------- effects ----------

private fun rgbSplit(f: Frame, a: Float) {
    val (w, h) = f.w to f.h
    val d = max(1, (a * w * 0.04f).toInt())
    val dy = (d * 0.35f).toInt()
    val src = f.px
    val out = IntArray(src.size)
    for (y in 0 until h) {
        val yr = (y - dy).coerceIn(0, h - 1) * w
        val yb = (y + dy).coerceIn(0, h - 1) * w
        val row = y * w
        for (x in 0 until w) {
            val cr = src[yr + (x - d).coerceIn(0, w - 1)]
            val cb = src[yb + (x + d).coerceIn(0, w - 1)]
            out[row + x] = rgb(r(cr), g(src[row + x]), b(cb))
        }
    }
    f.px = out
}

private fun scanTear(f: Frame, a: Float, rnd: Random) {
    val (w, h) = f.w to f.h
    val bands = 2 + (a * 18).toInt()
    val tmp = IntArray(w)
    repeat(bands) {
        val y0 = rnd.nextInt(h)
        val bh = 1 + rnd.nextInt(max(2, (h * 0.06f * a).toInt() + 2))
        val shift = ((rnd.nextFloat() * 2 - 1) * w * 0.35f * a).toInt()
        for (y in y0 until min(h, y0 + bh)) {
            val row = y * w
            System.arraycopy(f.px, row, tmp, 0, w)
            for (x in 0 until w) {
                val sx = ((x - shift) % w + w) % w
                f.px[row + x] = tmp[sx]
            }
        }
    }
}

private fun pixelSort(f: Frame, a: Float) {
    val (w, h) = f.w to f.h
    // Sort runs of pixels brighter than a threshold; more amount = lower threshold.
    val threshold = (220 - a * 180).toInt()
    val keys = LongArray(w)
    for (y in 0 until h) {
        val row = y * w
        var x = 0
        while (x < w) {
            if (luma(f.px[row + x]) < threshold) { x++; continue }
            var end = x
            while (end < w && luma(f.px[row + end]) >= threshold) end++
            val n = end - x
            if (n > 1) {
                for (i in 0 until n) {
                    val c = f.px[row + x + i]
                    keys[i] = (luma(c).toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)
                }
                keys.sort(0, n)
                for (i in 0 until n) f.px[row + x + i] = keys[i].toInt()
            }
            x = end
        }
    }
}

private fun datamosh(f: Frame, a: Float, rnd: Random) {
    val (w, h) = f.w to f.h
    val bs = max(8, min(w, h) / 24)
    val count = (a * 120).toInt() + 4
    val src = f.px.copyOf()
    repeat(count) {
        val bx = rnd.nextInt(max(1, w / bs)) * bs
        val by = rnd.nextInt(max(1, h / bs)) * bs
        // Motion vector: blocks drift from a nearby location, like a P-frame gone wrong.
        val mvx = ((rnd.nextFloat() * 2 - 1) * bs * 4 * a).toInt()
        val mvy = ((rnd.nextFloat() * 2 - 1) * bs * 2 * a).toInt()
        val bw = bs * (1 + rnd.nextInt(3))
        val bh = bs * (1 + rnd.nextInt(2))
        for (y in by until min(h, by + bh)) {
            val sy = (y + mvy).coerceIn(0, h - 1)
            for (x in bx until min(w, bx + bw)) {
                val sx = (x + mvx).coerceIn(0, w - 1)
                f.px[y * w + x] = src[sy * w + sx]
            }
        }
    }
}

private fun jpegRot(f: Frame, a: Float, rnd: Random) {
    val bmp = Bitmap.createBitmap(f.px, f.w, f.h, Bitmap.Config.ARGB_8888)
    val bos = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, (60 - a * 50).toInt().coerceAtLeast(5), bos)
    bmp.recycle()
    val bytes = bos.toByteArray()
    // Find start-of-scan marker so we only corrupt entropy-coded data, keeping headers valid.
    var sos = -1
    for (i in 0 until bytes.size - 1) {
        if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xDA.toByte()) { sos = i + 12; break }
    }
    if (sos < 0 || sos >= bytes.size - 4) return
    val hits = 1 + (a * a * 40).toInt()
    repeat(hits) {
        val pos = sos + rnd.nextInt(bytes.size - 2 - sos)
        var v = rnd.nextInt(256)
        if (v == 0xFF) v = 0xFE // avoid creating markers
        if (bytes[pos] != 0xFF.toByte() && bytes[pos - 1] != 0xFF.toByte()) bytes[pos] = v.toByte()
    }
    val decoded = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Throwable) { null } ?: return
    if (decoded.width == f.w && decoded.height == f.h) {
        decoded.getPixels(f.px, 0, f.w, 0, 0, f.w, f.h)
    }
    decoded.recycle()
}

private fun rowSmear(f: Frame, a: Float, rnd: Random) {
    val (w, h) = f.w to f.h
    val runs = 1 + (a * 12).toInt()
    repeat(runs) {
        val y0 = rnd.nextInt(h)
        val len = 1 + rnd.nextInt(max(2, (h * 0.25f * a).toInt() + 2))
        val vertical = rnd.nextFloat() < 0.3f
        if (!vertical) {
            for (y in y0 + 1 until min(h, y0 + len)) System.arraycopy(f.px, y0 * w, f.px, y * w, w)
        } else {
            val x0 = rnd.nextInt(w)
            val l = min(w - x0, len)
            for (y in 0 until h) {
                val c = f.px[y * w + x0]
                for (x in x0 until x0 + l) f.px[y * w + x] = c
            }
        }
    }
}

private fun wave(f: Frame, a: Float, rnd: Random) {
    val (w, h) = f.w to f.h
    val amp = a * w * 0.06f
    val freq = 2f + rnd.nextFloat() * 6f
    val phase = rnd.nextFloat() * 2f * PI.toFloat()
    val src = f.px.copyOf()
    for (y in 0 until h) {
        val t = y.toFloat() / h
        val off = (sin(t * freq * 2 * PI.toFloat() + phase) * amp +
            sin(t * freq * 7.3f + phase * 2) * amp * 0.3f).toInt()
        val row = y * w
        for (x in 0 until w) f.px[row + x] = src[row + (x + off).coerceIn(0, w - 1)]
    }
}

private fun interlace(f: Frame, a: Float) {
    val (w, h) = f.w to f.h
    val shift = max(1, (a * w * 0.02f).toInt())
    val dark = 1f - a * 0.6f
    val tmp = IntArray(w)
    for (y in 1 until h step 2) {
        val row = y * w
        System.arraycopy(f.px, row, tmp, 0, w)
        for (x in 0 until w) {
            val c = tmp[(x - shift).coerceIn(0, w - 1)]
            f.px[row + x] = rgb((r(c) * dark).toInt(), (g(c) * dark).toInt(), (b(c) * dark).toInt())
        }
    }
}

private fun vhs(f: Frame, a: Float, rnd: Random) {
    val (w, h) = f.w to f.h
    val src = f.px
    val out = IntArray(src.size)
    val bleed = max(1, (a * w * 0.015f).toInt())
    val trackY = rnd.nextInt(h)
    val trackH = (h * 0.05f * a).toInt() + 2
    for (y in 0 until h) {
        val row = y * w
        val inTrack = y in trackY until trackY + trackH
        val jitter = if (inTrack) (rnd.nextInt(-bleed * 6, bleed * 6 + 1)) else 0
        for (x in 0 until w) {
            val c = src[row + (x + jitter).coerceIn(0, w - 1)]
            val cc = src[row + (x - bleed + jitter).coerceIn(0, w - 1)]
            // Luma stays sharp, chroma smears to the right.
            val yv = luma(c)
            val cr = r(cc) - luma(cc)
            val cb = b(cc) - luma(cc)
            val noise = ((rnd.nextFloat() - 0.5f) * 40 * a).toInt()
            var rr = yv + cr + noise
            var gg = yv - ((cr + cb) shr 1) + noise
            var bb = yv + cb + noise
            if (inTrack) { rr += 60; gg += 60; bb += 60 }
            out[row + x] = rgb(rr, gg, bb)
        }
    }
    f.px = out
}

private fun sensorNoise(f: Frame, a: Float, rnd: Random) {
    val (w, h) = f.w to f.h
    // Color noise
    val n = (a * 50).toInt()
    for (i in f.px.indices) {
        if (n == 0) break
        val c = f.px[i]
        f.px[i] = rgb(r(c) + rnd.nextInt(-n, n + 1), g(c) + rnd.nextInt(-n, n + 1), b(c) + rnd.nextInt(-n, n + 1))
    }
    // Hot pixels
    repeat((a * w * h * 0.002f).toInt()) {
        f.px[rnd.nextInt(f.px.size)] = rgb(255, rnd.nextInt(256), rnd.nextInt(80))
    }
    // Dead columns / rows
    repeat((a * 8).toInt()) {
        if (rnd.nextBoolean()) {
            val x = rnd.nextInt(w)
            val c = if (rnd.nextBoolean()) 0xFF000000.toInt() else rgb(rnd.nextInt(256), 255, rnd.nextInt(256))
            for (y in 0 until h) f.px[y * w + x] = c
        } else {
            val y = rnd.nextInt(h)
            val c = rgb(rnd.nextInt(256), rnd.nextInt(256), 255)
            java.util.Arrays.fill(f.px, y * w, y * w + w, c)
        }
    }
}

private fun bitCrush(f: Frame, a: Float) {
    val bits = (8 - (a * 6.5f).toInt()).coerceIn(1, 8)
    val mask = (0xFF shl (8 - bits)) and 0xFF
    val xor = if (a > 0.6f) 0x20 else 0
    for (i in f.px.indices) {
        val c = f.px[i]
        f.px[i] = rgb((r(c) and mask) xor xor, g(c) and mask, (b(c) and mask) xor (xor shr 1))
    }
}

private fun channelSwap(f: Frame, a: Float) {
    val mode = (a * 4.99f).toInt()
    for (i in f.px.indices) {
        val c = f.px[i]
        val rr = r(c); val gg = g(c); val bb = b(c)
        f.px[i] = when (mode) {
            0 -> rgb(gg, rr, bb)
            1 -> rgb(bb, gg, rr)
            2 -> rgb(gg, bb, rr)
            3 -> rgb(bb, rr, gg)
            else -> rgb(255 - gg, rr, 255 - bb)
        }
    }
}

private fun solarize(f: Frame, a: Float) {
    val t = (255 - a * 200).toInt()
    for (i in f.px.indices) {
        val c = f.px[i]
        fun s(v: Int) = if (v > t) 255 - v else v
        f.px[i] = rgb(s(r(c)), s(g(c)), s(b(c)))
    }
}

private val thermalLut: IntArray by lazy {
    val stops = intArrayOf(0x000010, 0x2A0060, 0x8A0090, 0xE0301E, 0xFF9A00, 0xFFF060, 0xFFFFFF)
    IntArray(256) { i ->
        val p = i / 255f * (stops.size - 1)
        val k = min(stops.size - 2, p.toInt())
        val t = p - k
        val c0 = stops[k]; val c1 = stops[k + 1]
        rgb(
            (r(c0) + (r(c1) - r(c0)) * t).toInt(),
            (g(c0) + (g(c1) - g(c0)) * t).toInt(),
            (b(c0) + (b(c1) - b(c0)) * t).toInt(),
        )
    }
}

private fun thermal(f: Frame, a: Float) {
    val lut = thermalLut
    for (i in f.px.indices) {
        val c = f.px[i]
        val t = lut[luma(c)]
        f.px[i] = rgb(
            (r(c) + (r(t) - r(c)) * a).toInt(),
            (g(c) + (g(t) - g(c)) * a).toInt(),
            (b(c) + (b(t) - b(c)) * a).toInt(),
        )
    }
}

private fun mirror(f: Frame, a: Float) {
    val (w, h) = f.w to f.h
    val quad = a > 0.66f
    val vertical = a in 0.33f..0.66f
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var sx = x; var sy = y
            if (!vertical || quad) if (x >= w / 2) sx = w - 1 - x
            if (vertical || quad) if (y >= h / 2) sy = h - 1 - y
            if (sx != x || sy != y) f.px[row + x] = f.px[sy * w + sx]
        }
    }
}

private fun pixelate(f: Frame, a: Float) {
    val (w, h) = f.w to f.h
    val bs = max(2, (a * min(w, h) / 12f).toInt())
    for (by in 0 until h step bs) for (bx in 0 until w step bs) {
        val c = f.px[min(h - 1, by + bs / 2) * w + min(w - 1, bx + bs / 2)]
        for (y in by until min(h, by + bs)) java.util.Arrays.fill(f.px, y * w + bx, y * w + min(w, bx + bs), c)
    }
}

/** Random chain for the dice button. */
fun randomChain(rnd: Random): List<EffectLayer> {
    val n = 2 + rnd.nextInt(3)
    return Effect.entries.shuffled(rnd).take(n).map {
        EffectLayer(it, 0.2f + rnd.nextFloat() * 0.7f)
    }.sortedBy { it.effect.ordinal }
}
