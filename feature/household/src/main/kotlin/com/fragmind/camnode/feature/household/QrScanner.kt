package com.fragmind.camnode.feature.household

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera preview that reports the first QR code it reads, exactly once.
 * Teardown order matters: the analyzer is detached before its thread stops, otherwise
 * CameraX can hand a late frame to a dead executor and crash the app.
 */
@Composable
internal fun QrScanner(onScanned: (String) -> Unit, onCameraUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val currentOnUnavailable by rememberUpdatedState(onCameraUnavailable)
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val delivered = AtomicBoolean(false)
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor, QrFrameAnalyzer { text ->
            if (delivered.compareAndSet(false, true)) mainExecutor.execute { currentOnScanned(text) }
        })
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                provider = future.get().also { it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
            } catch (e: Exception) {
                // Camera busy (this phone may be streaming) or missing: tell the user instead of crashing.
                Log.w(TAG, "QR camera unavailable", e)
                currentOnUnavailable()
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            analysisExecutor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private const val TAG = "QrScanner"
