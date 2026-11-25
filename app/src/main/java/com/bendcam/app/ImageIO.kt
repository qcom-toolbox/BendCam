package com.bendcam.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlin.math.max

object ImageIO {
    const val MAX_EDIT_SIZE = 1600

    /** Decode a gallery image, downsampled to [maxSize], EXIF-rotated, as a mutable-safe ARGB bitmap. */
    fun load(context: Context, uri: Uri, maxSize: Int = MAX_EDIT_SIZE): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Bounds-only decode always returns null; the size lands in [bounds].
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        return fit(raw, maxSize, rotation)
    }

    /** Rotate / mirror / scale so the longest edge is at most [maxSize]. */
    fun fit(src: Bitmap, maxSize: Int, rotation: Float = 0f, mirror: Boolean = false): Bitmap {
        val scale = minOf(1f, maxSize.toFloat() / max(src.width, src.height))
        if (scale == 1f && rotation == 0f && !mirror && src.config == Bitmap.Config.ARGB_8888) return src
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
            if (mirror) postScale(-1f, 1f) // mirror in display space, after rotation
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        return if (out.config == Bitmap.Config.ARGB_8888) out else out.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Saves to Pictures/BendCam. Returns the content Uri. */
    fun save(context: Context, bmp: Bitmap): Uri? {
        val name = "BendCam_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BendCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                cr.update(uri, values, null, null)
            }
            uri
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            null
        }
    }

    fun share(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share bent image"))
    }
}
