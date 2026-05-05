package com.gemmafit.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class PoseLandmarkerHelper(
    private val context: Context,
    private val listener: PoseLandmarkerListener,
) {
    private companion object {
        const val MIN_LIVE_ANALYSIS_INTERVAL_MS = 66L
    }

    private var poseLandmarker: PoseLandmarker? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var started = false
    private var lastAcceptedFrameElapsedMs = 0L

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
                .setNumPoses(4)
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
                    .setNumPoses(4)
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
        val elapsedMs = SystemClock.elapsedRealtime()
        if (elapsedMs - lastAcceptedFrameElapsedMs < MIN_LIVE_ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastAcceptedFrameElapsedMs = elapsedMs

        val frameTime = System.currentTimeMillis()
        val rotation = imageProxy.imageInfo.rotationDegrees
        var raw: android.graphics.Bitmap? = null
        var bitmap: android.graphics.Bitmap? = null
        try {
            raw = imageProxy.toBitmap()
            bitmap = if (rotation != 0) {
                val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } else {
                raw
            }
            val frameBitmap = bitmap ?: throw IllegalStateException("Bitmap conversion returned null")
            val mpImage = BitmapImageBuilder(frameBitmap).build()
            poseLandmarker?.detectAsync(mpImage, frameTime)
        } catch (e: Exception) {
            Log.e("PoseLandmarker", "detectAsync failed: ${e.message}", e)
        } finally {
            if (raw != null && bitmap !== raw) {
                raw.recycle()
            }
            imageProxy.close()
        }
    }

    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
        executor.shutdown()
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
    val activeImageAnalysis = remember { AtomicReference<ImageAnalysis?>(null) }

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

    LaunchedEffect(lensFacing, previewView) {
        val cameraProvider = getCameraProvider(context)
        val lifecycleOwner = context as? LifecycleOwner
        if (lifecycleOwner == null) {
            Log.e("CameraPreview", "Context is not a LifecycleOwner: ${context::class.java.name}")
            return@LaunchedEffect
        }

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(analysisResolutionSelector)
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
            activeImageAnalysis.getAndSet(null)?.clearAnalyzer()
            cameraProvider.unbindAll()
            activeImageAnalysis.set(imageAnalysis)
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis,
            )
        } catch (e: Exception) {
            imageAnalysis.clearAnalyzer()
            activeImageAnalysis.compareAndSet(imageAnalysis, null)
            Log.e("CameraPreview", "Use case binding failed", e)
        }
    }

    DisposableEffect(context, previewView, poseHelper) {
        onDispose {
            activeImageAnalysis.getAndSet(null)?.clearAnalyzer()
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener(
                {
                    try {
                        providerFuture.get().unbindAll()
                    } catch (e: Exception) {
                        Log.w("CameraPreview", "Camera unbind failed during dispose", e)
                    }
                },
                Dispatchers.Main.asExecutor(),
            )
            poseHelper.close()
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
