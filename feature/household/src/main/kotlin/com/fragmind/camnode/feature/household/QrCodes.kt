package com.fragmind.camnode.feature.household

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal object QrCodes {
    fun render(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) DARK else LIGHT
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    private const val DARK = 0xFF000000.toInt()
    private const val LIGHT = 0xFFFFFFFF.toInt()
}

/** Decodes QR codes from the luminance plane of each camera frame. */
internal class QrFrameAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes.first()
            val bytes = ByteArray(plane.buffer.remaining()).also(plane.buffer::get)
            val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
            try {
                onDecoded(reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text)
            } catch (_: NotFoundException) {
                // Most frames contain no code.
            } catch (_: ReaderException) {
                // Blurry or partial code; the next frame retries.
            } finally {
                reader.reset()
            }
        }
    }
}
