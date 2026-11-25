package com.bendcam.app.effects

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private val bayer4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

/** Ordered (Bayer) dither down to very few levels per channel. */
internal fun dither(f: Frame, a: Float) {
    val levels = (2 + ((1f - a) * 6).toInt()).coerceIn(2, 8)
    val step = 255f / (levels - 1)
    for (y in 0 until f.h) {
        val row = y * f.w
        for (x in 0 until f.w) {
            val t = (bayer4[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f - 0.5f
            val c = f.px[row + x]
            fun q(v: Int) = (((v / step) + t).let { Math.round(it) }.coerceIn(0, levels - 1) * step).toInt()
            f.px[row + x] = rgb(q(r(c)), q(g(c)), q(b(c)))
        }
    }
}

/** Sobel edges glowing in the pixel's own (saturated) color over a darkened image. */
internal fun edgeNeon(f: Frame, a: Float) {
    val (w, h) = f.w to f.h
    val lum = IntArray(w * h) { luma(f.px[it]) }
    val out = IntArray(w * h)
    val keep = 1f - a
    for (y in 0 until h) {
        val ym = max(0, y - 1) * w; val y0 = y * w; val yp = min(h - 1, y + 1) * w
        for (x in 0 until w) {
            val xm = max(0, x - 1); val xp = min(w - 1, x + 1)
            val gx = -lum[ym + xm] - 2 * lum[y0 + xm] - lum[yp + xm] + lum[ym + xp] + 2 * lum[y0 + xp] + lum[yp + xp]
            val gy = -lum[ym + xm] - 2 * lum[ym + x] - lum[ym + xp] + lum[yp + xm] + 2 * lum[yp + x] + lum[yp + xp]
            val mag = min(255, (abs(gx) + abs(gy)) shr 1)
            val c = f.px[y0 + x]
            val mx = max(1, max(r(c), max(g(c), b(c))))
            // Saturated hue of the source pixel, with a cyan fallback for grey areas.
            val sat = mx - min(r(c), min(g(c), b(c)))
            val (nr, ng, nb) = if (sat < 24) Triple(0, 229, 255)
            else Triple(r(c) * 255 / mx, g(c) * 255 / mx, b(c) * 255 / mx)
            val e = mag / 255f * 1.6f
            out[y0 + x] = rgb(
                (r(c) * keep * 0.5f + nr * e).toInt(),
                (g(c) * keep * 0.5f + ng * e).toInt(),
                (b(c) * keep * 0.5f + nb * e).toInt(),
            )
        }
    }
    f.px = out
}

private inline fun remap(f: Frame, map: (x: Int, y: Int, out: IntArray) -> Unit) {
    val (w, h) = f.w to f.h
    val src = f.px
    val dst = IntArray(w * h)
    val xy = IntArray(2)
    for (y in 0 until h) for (x in 0 until w) {
        map(x, y, xy)
        dst[y * w + x] = src[xy[1].coerceIn(0, h - 1) * w + xy[0].coerceIn(0, w - 1)]
    }
    f.px = dst
}

internal fun swirl(f: Frame, a: Float) {
    val cx = f.w / 2f; val cy = f.h / 2f
    val radius = min(f.w, f.h) * 0.55f
    val strength = a * 6f
    remap(f) { x, y, o ->
        val dx = x - cx; val dy = y - cy
        val d = sqrt(dx * dx + dy * dy)
        if (d < radius) {
            val t = 1f - d / radius
            val ang = strength * t * t
            val cs = cos(ang); val sn = sin(ang)
            o[0] = (cx + dx * cs - dy * sn).toInt(); o[1] = (cy + dx * sn + dy * cs).toInt()
        } else { o[0] = x; o[1] = y }
    }
}

internal fun fisheye(f: Frame, a: Float) {
    val cx = f.w / 2f; val cy = f.h / 2f
    val radius = sqrt(cx * cx + cy * cy)
    val k = 1f + a * 1.5f
    remap(f) { x, y, o ->
        val dx = x - cx; val dy = y - cy
        val d = sqrt(dx * dx + dy * dy) / radius
        val scale = if (d > 0f) d.pow(k) / d else 1f
        o[0] = (cx + dx * scale).toInt(); o[1] = (cy + dy * scale).toInt()
    }
}

/** Radial chromatic aberration: red pushed out, blue pulled in. */
internal fun prism(f: Frame, a: Float) {
    val (w, h) = f.w to f.h
    val cx = w / 2f; val cy = h / 2f
    val k = a * 0.06f
    val src = f.px
    val out = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val dx = x - cx; val dy = y - cy
        val rx = (cx + dx * (1 + k)).toInt().coerceIn(0, w - 1); val ry = (cy + dy * (1 + k)).toInt().coerceIn(0, h - 1)
        val bx = (cx + dx * (1 - k)).toInt().coerceIn(0, w - 1); val by = (cy + dy * (1 - k)).toInt().coerceIn(0, h - 1)
        out[y * w + x] = rgb(r(src[ry * w + rx]), g(src[y * w + x]), b(src[by * w + bx]))
    }
    f.px = out
}

/** Hue rotation; animates over time when a context is present. */
internal fun hueCycle(f: Frame, a: Float, ctx: FxContext?) {
    val deg = a * 360f + (ctx?.frame ?: 0L) * 6f * a
    val th = deg / 180f * PI.toFloat()
    val cs = cos(th); val sn = sin(th)
    // Rotation around the grey axis (standard hue-rotate matrix).
    val m00 = 0.213f + cs * 0.787f - sn * 0.213f; val m01 = 0.715f - cs * 0.715f - sn * 0.715f; val m02 = 0.072f - cs * 0.072f + sn * 0.928f
    val m10 = 0.213f - cs * 0.213f + sn * 0.143f; val m11 = 0.715f + cs * 0.285f + sn * 0.140f; val m12 = 0.072f - cs * 0.072f - sn * 0.283f
    val m20 = 0.213f - cs * 0.213f - sn * 0.787f; val m21 = 0.715f - cs * 0.715f + sn * 0.715f; val m22 = 0.072f + cs * 0.928f + sn * 0.072f
    for (i in f.px.indices) {
        val c = f.px[i]
        val rr = r(c); val gg = g(c); val bb = b(c)
        f.px[i] = rgb(
            (rr * m00 + gg * m01 + bb * m02).toInt(),
            (rr * m10 + gg * m11 + bb * m12).toInt(),
            (rr * m20 + gg * m21 + bb * m22).toInt(),
        )
    }
}

internal fun kaleido(f: Frame, a: Float) {
    val cx = f.w / 2f; val cy = f.h / 2f
    val segments = 3 + (a * 9).toInt()
    val seg = (2 * PI / segments).toFloat()
    remap(f) { x, y, o ->
        val dx = x - cx; val dy = y - cy
        val d = sqrt(dx * dx + dy * dy)
        var ang = atan2(dy, dx)
        if (ang < 0) ang += (2 * PI).toFloat()
        ang %= seg
        if (ang > seg / 2) ang = seg - ang
        o[0] = (cx + d * cos(ang)).toInt(); o[1] = (cy + d * sin(ang)).toInt()
    }
}

/** Macroblocks with inverted or dropped channels, like a decoder losing its bitstream. */
internal fun blockInvert(f: Frame, a: Float, rnd: Random) {
    val (w, h) = f.w to f.h
    val bs = max(8, min(w, h) / 20)
    repeat(4 + (a * 60).toInt()) {
        val bx = rnd.nextInt(max(1, w / bs)) * bs
        val by = rnd.nextInt(max(1, h / bs)) * bs
        val bw = bs * (1 + rnd.nextInt(4)); val bh = bs * (1 + rnd.nextInt(2))
        val mode = rnd.nextInt(4)
        for (y in by until min(h, by + bh)) for (x in bx until min(w, bx + bw)) {
            val c = f.px[y * w + x]
            f.px[y * w + x] = when (mode) {
                0 -> rgb(255 - r(c), 255 - g(c), 255 - b(c))
                1 -> rgb(0, g(c), b(c))
                2 -> rgb(r(c), 0, 255 - b(c))
                else -> rgb(b(c), r(c), 255 - g(c))
            }
        }
    }
}

/** Video feedback: each frame keeps a decaying, slightly zoomed echo of the last output. */
internal fun ghost(f: Frame, a: Float, ctx: FxContext?) {
    val (w, h) = f.w to f.h
    val prev = ctx?.store?.get(Effect.GHOST) as? IntArray
    val out = IntArray(w * h)
    if (ctx != null && prev != null && prev.size == w * h) {
        val keep = 0.55f + a * 0.4f
        val zoom = 0.985f
        val cx = w / 2f; val cy = h / 2f
        for (y in 0 until h) for (x in 0 until w) {
            val px = (cx + (x - cx) * zoom).toInt(); val py = (cy + (y - cy) * zoom).toInt()
            val p = prev[py * w + px]
            val c = f.px[y * w + x]
            // Lighten-blend so bright trails persist like phosphor.
            out[y * w + x] = rgb(
                max(r(c), (r(p) * keep).toInt()),
                max(g(c), (g(p) * keep).toInt()),
                max(b(c), (b(p) * keep).toInt()),
            )
        }
        ctx.store[Effect.GHOST] = out
    } else {
        // Still image: fake the trail with shifted, fading copies.
        System.arraycopy(f.px, 0, out, 0, out.size)
        val copies = 2 + (a * 4).toInt()
        val step = max(2, (w * 0.012f * (0.5f + a)).toInt())
        for (k in 1..copies) {
            val fade = 0.7f / k
            for (y in 0 until h) for (x in 0 until w) {
                val sx = x - k * step
                if (sx < 0) continue
                val p = f.px[y * w + sx]
                val c = out[y * w + x]
                out[y * w + x] = rgb(
                    max(r(c), (r(p) * fade).toInt()),
                    max(g(c), (g(p) * fade).toInt()),
                    max(b(c), (b(p) * fade).toInt()),
                )
            }
        }
        ctx?.store?.set(Effect.GHOST, out)
    }
    f.px = out
}

/** Each row is taken from a progressively older frame: rolling-shutter taken to the extreme. */
internal fun slitScan(f: Frame, a: Float, ctx: FxContext?) {
    val (w, h) = f.w to f.h
    if (ctx == null) {
        // Still image fallback: rows drift sideways by a growing amount (fake time skew).
        val src = f.px.copyOf()
        val maxShift = a * w * 0.25f
        for (y in 0 until h) {
            val s = (maxShift * y / h).toInt()
            for (x in 0 until w) f.px[y * w + x] = src[y * w + min(w - 1, x + s)]
        }
        return
    }
    val depth = 4 + (a * 12).toInt()
    @Suppress("UNCHECKED_CAST")
    val history = ctx.store.getOrPut(Effect.SLIT_SCAN) { ArrayDeque<IntArray>() } as ArrayDeque<IntArray>
    if (history.isNotEmpty() && history.first().size != w * h) history.clear()
    history.addFirst(f.px.copyOf())
    while (history.size > depth) history.removeLast()
    val out = IntArray(w * h)
    for (y in 0 until h) {
        val frame = history[min(history.size - 1, y * depth / h)]
        System.arraycopy(frame, y * w, out, y * w, w)
    }
    f.px = out
}
