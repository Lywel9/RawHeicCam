package dev.rawheic

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Publishes finished HEIC/JPEG files into the shared gallery via MediaStore. */
object PhotoSaver {

    private const val RELATIVE_DIR = "Pictures/RawHeicCam"

    /** Publishes the original DNG alongside the HEIC when keep-DNG is on. */
    fun saveDng(context: Context, file: File): Uri? {
        val name = "RAWHEIC_${System.currentTimeMillis()}.dng"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$RELATIVE_DIR/raw")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null
                )
            }
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun saveToGallery(context: Context, file: File): Uri? {
        val isHeic = file.extension.equals("heic", ignoreCase = true)
        val mime = if (isHeic) "image/vnd.android.heic" else "image/jpeg"
        val name = "RAWHEIC_${System.currentTimeMillis()}.${file.extension}"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null
                )
            }
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
