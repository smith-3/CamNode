package com.fragmind.camnode.feature.viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies the frame on screen at the video's own resolution. The texture holds full-size
 * frames, so asking for the video size returns them unscaled, whatever the view's size.
 */
internal class FrameGrabber {
    var view: TextureView? = null
    var videoWidth = 0
    var videoHeight = 0

    /** True once a frame is playing; the shutter waits for it. */
    var ready by mutableStateOf(false)

    /** Must run on the main thread. [rotationDegrees] matches the turn this screen shows. */
    fun grab(rotationDegrees: Int): Bitmap? {
        val texture = view?.takeIf { it.isAvailable } ?: return null
        val frame = (if (videoWidth > 0 && videoHeight > 0) texture.getBitmap(videoWidth, videoHeight) else texture.bitmap) ?: return null
        if (rotationDegrees % 360 == 0) return frame
        val turned = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
        if (turned !== frame) frame.recycle()
        return turned
    }
}

/**
 * Saves photos only in this phone's gallery (Pictures/CamNode). Nothing is sent to or kept
 * on the camera. Android 9 needs the storage permission; 10 and later do not.
 */
internal object PhotoStore {
    suspend fun save(context: Context, photo: Bitmap, cameraName: String, atMillis: Long): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(cameraName, atMillis))
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, atMillis)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CamNode")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        try {
            val written = resolver.openOutputStream(uri)?.use { photo.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) } ?: false
            if (!written) throw IOException("JPEG not written")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** "CamNode_Cocina_20261116_143205.jpg": sortable, and safe on any file system. */
    fun fileName(cameraName: String, atMillis: Long): String {
        val camera = cameraName.trim().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').ifEmpty { "camara" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(atMillis))
        return "CamNode_${camera}_$stamp.jpg"
    }

    private const val JPEG_QUALITY = 92
}
