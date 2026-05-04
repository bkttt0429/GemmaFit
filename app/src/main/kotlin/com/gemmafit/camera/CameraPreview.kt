package com.gemmafit.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class PoseLandmarkerHelper(
    private val context: Context,
    private val listener: PoseLandmarkerListener,
) {
    private var poseLandmarker: PoseLandmarker? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var started = false

    interface PoseLandmarkerListener {
        fun onError(error: String)
        fun onResults(result: PoseLandmarkerResult, imageHeight: Int, imageWidth: Int, timestampMs: Long)
    }

    fun start() {
        if (started) return
        started = true
        setupPoseLandmarker()
    }

    private fun setupPoseLandmarker() {
        try {
            val modelPath = "pose_landmarker_lite.task"
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setDelegate(Delegate.GPU).setModelAssetPath(modelPath).build())
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, image ->
                    listener.onResults(result, image.height, image.width, System.currentTimeMillis())
                }
                .setErrorListener { error ->
                    listener.onError(error.message ?: "PoseLandmarker error")
                }
                .build()

            try {
                poseLandmarker = PoseLandmarker.createFromOptions(context, options)
                Log.i("PoseLandmarker", "ready: GPU, LIVE_STREAM")
            } catch (e: Exception) {
                Log.w("PoseLandmarker", "GPU failed, trying CPU: ${e.message}")
                val cpuOptions = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setDelegate(Delegate.CPU).setModelAssetPath(modelPath).build())
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { result, image ->
                        listener.onResults(result, image.height, image.width, System.currentTimeMillis())
                    }
                    .setErrorListener { error ->
                        listener.onError(error.message ?: "PoseLandmarker error")
                    }
                    .build()
                poseLandmarker = PoseLandmarker.createFromOptions(context, cpuOptions)
                Log.i("PoseLandmarker", "ready: CPU fallback")
            }
        } catch (e: Exception) {
            Log.e("PoseLandmarker", "setup failed: ${e.message}", e)
            listener.onError("PoseLandmarker setup failed: ${e.message}")
        }
    }

    fun detectLiveStream(imageProxy: ImageProxy) {
        val frameTime = System.currentTimeMillis()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val raw = imageProxy.toBitmap()
        val bitmap = if (rotation != 0) {
            val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        } else raw

        val mpImage = BitmapImageBuilder(bitmap).build()
        try {
            poseLandmarker?.detectAsync(mpImage, frameTime)
        } catch (e: Exception) {
            Log.e("PoseLandmarker", "detectAsync failed: ${e.message}")
        }
        imageProxy.close()
    }

    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
        executor.shutdown()
    }

    private fun ImageProxy.toBitmap(): android.graphics.Bitmap {
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val yIdx = y * yRowStride + x
                val uvIdx = (y shr 1) * uvRowStride + (x shr 1) * uvPixelStride
                val yVal = (yBuf[yIdx].toInt() and 0xFF) - 16
                val uVal = (uBuf[uvIdx.coerceIn(0, uBuf.capacity() - 1)].toInt() and 0xFF) - 128
                val vVal = (vBuf[uvIdx.coerceIn(0, vBuf.capacity() - 1)].toInt() and 0xFF) - 128
                val r = (1.164f * yVal + 1.596f * vVal).toInt().coerceIn(0, 255)
                val g = (1.164f * yVal - 0.392f * uVal - 0.813f * vVal).toInt().coerceIn(0, 255)
                val b = (1.164f * yVal + 2.017f * uVal).toInt().coerceIn(0, 255)
                pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
    }
}

@Composable
fun CameraPreviewWithOverlay(
    onPoseDetected: (PoseLandmarkerResult, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    val poseHelper = remember {
        PoseLandmarkerHelper(context, object : PoseLandmarkerHelper.PoseLandmarkerListener {
            override fun onError(error: String) {
                Log.e("PoseLandmarker", error)
            }

            override fun onResults(
                result: PoseLandmarkerResult,
                imageHeight: Int,
                imageWidth: Int,
                timestampMs: Long,
            ) {
                onPoseDetected(result, imageHeight, imageWidth)
            }
        })
    }

    LaunchedEffect(lensFacing) {
        val cameraProvider = getCameraProvider(context)
        val lifecycleOwner = context as androidx.lifecycle.LifecycleOwner

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(Dispatchers.Default.asExecutor()) { imageProxy ->
                    poseHelper.start()
                    poseHelper.detectLiveStream(imageProxy)
                }
            }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis,
            )
        } catch (e: Exception) {
            Log.e("CameraPreview", "Use case binding failed", e)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}

private suspend fun getCameraProvider(context: Context): ProcessCameraProvider {
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val future = ProcessCameraProvider.getInstance(context)
        future.get()
    }
}

@Composable
fun CameraPreviewScreen(
    onPoseDetected: (PoseLandmarkerResult, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    CameraPreviewWithOverlay(
        onPoseDetected = onPoseDetected,
        modifier = modifier,
    )
}