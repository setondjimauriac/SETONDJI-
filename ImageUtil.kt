package com.example.voicemsg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.InputStream

object ImageUtil {

    private const val MAX_SIDE_SEND = 1280   // photo réduite avant envoi (~200-400 Ko)

    /** Réduit et compresse la photo choisie (en tenant compte de son orientation). */
    fun prepare(context: Context, uri: Uri): File? {
        return try {
            val resolver = context.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE_SEND * 2) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null

            val longest = maxOf(bmp.width, bmp.height)
            if (longest > MAX_SIDE_SEND) {
                val scale = MAX_SIDE_SEND.toFloat() / longest
                bmp = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
                )
            }

            val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }

            val out = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun exifRotation(stream: InputStream): Int {
        return try {
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /** Charge une version allégée pour l'affichage dans la conversation. */
    fun decodeForDisplay(file: File, maxSide: Int = 1000): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (e: Exception) {
            null
        }
    }
}
