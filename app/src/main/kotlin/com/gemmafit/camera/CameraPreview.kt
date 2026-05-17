package com.gemmafit.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
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
import com.gemmafit.debug.GemmaFitDebugApi
import com.gemmafit.debug.RgbaPipelineFrameSample
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

enum class LiveCameraImagePipeline(
    val configName: String,
    val cameraXFormat: Int,
    val cameraXOutputRotationEnabled: Boolean,
    val usesCameraXRgba: Boolean,
) {
    CURRENT_YUV_BITMAP_ROTATE(
        configName = "current_yuv_bitmap_rotate",
        cameraXFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888,
        cameraXOutputRotationEnabled = false,
        usesCameraXRgba = false,
    ),
    CAMERAX_ROTATED_YUV_BITMAP(
        configName = "camerax_rotated_yuv_bitmap",
        cameraXFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888,
        cameraXOutputRotationEnabled = true,
        usesCameraXRgba = false,
    ),
    CAMERAX_ROTATED_RGBA_BITMAP(
        configName = "camerax_rotated_rgba_bitmap",
        cameraXFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888,
        cameraXOutputRotationEnabled = true,
        usesCameraXRgba = true,
    );

    companion object {
        private const val DEBUG_CONFIG_PATH = "debug/live_camera_image_pipeline.txt"
        val DEFAULT = CAMERAX_ROTATED_RGBA_BITMAP

        fun fromDebugConfig(context: Context): LiveCameraImagePipeline {
            val configured = runCatching {
                context.filesDir
                    .resolve(DEBUG_CONFIG_PATH)
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.trim()
                    ?.lowercase()
            }.getOrNull()
            return values().firstOrNull { it.configName == configured || it.name.lowercase() == configured }
                ?: DEFAULT
        }
    }
}

class PoseLandmarkerHelper(
    private val context: Context,
    private val listener: PoseLandmarkerListener,
    private val imagePipeline: LiveCameraImagePipeline,
) {
    private companion object {
        const val MIN_LIVE_ANALYSIS_INTERVAL_MS = 66L
        const val APPEARANCE_SNAPSHOT_INTERVAL_MS = 250L
        const val MAX_PENDING_APPEARANCE_SNAPSHOTS = 2

        fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000L

        fun elapsedUs(startUs: Long): Long = nowUs() - startUs

        fun bitmapConfigName(bitmap: Bitmap?): String {
            return bitmap?.config?.name ?: "null"
        }

        fun imageFormatName(format: Int): String {
            return when (format) {
                ImageFormat.YUV_420_888 -> "YUV_420_888"
                ImageFormat.JPEG -> "JPEG"
                ImageFormat.NV21 -> "NV21"
                ImageFormat.YUY2 -> "YUY2"
                ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
                ImageFormat.PRIVATE -> "PRIVATE"
                PixelFormat.RGBA_8888 -> "RGBA_8888"
                else -> "UNKNOWN_$format"
            }
        }
    }

    private var poseLandmarker: PoseLandmarker? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val pendingAppearanceSnapshots = ConcurrentLinkedQueue<Bitmap>()
    private var started = false
    private var lastAcceptedFrameElapsedMs = 0L
    private var lastAppearanceSnapshotElapsedMs = 0L

    interface PoseLandmarkerListener {
        fun onError(error: String)
        fun onResults(
            result: PoseLandmarkerResult,
            imageHeight: Int,
            imageWidth: Int,
            timestampMs: Long,
            frameBitmap: Bitmap?,
        )
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
                    listener.onResults(
                        result,
                        image.height,
                        image.width,
                        System.currentTimeMillis(),
                        pendingAppearanceSnapshots.poll(),
                    )
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
                        listener.onResults(
                            result,
                            image.height,
                            image.width,
                            System.currentTimeMillis(),
                            pendingAppearanceSnapshots.poll(),
                        )
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
        val totalStartUs = nowUs()
        val imageFormat = imageProxy.format
        val imageFormatName = imageFormatName(imageFormat)
        val planeCount = imageProxy.planes.size
        val proxyWidth = imageProxy.width
        val proxyHeight = imageProxy.height
        var yuvToBitmapUs = -1L
        var rotateUs = 0L
        var bitmapImageBuildUs = -1L
        var detectAsyncUs = -1L
        var appearanceSnapshotCopyUs: Long? = null
        var errorDetail: String? = null
        var raw: android.graphics.Bitmap? = null
        var bitmap: android.graphics.Bitmap? = null
        try {
            val yuvStartUs = nowUs()
            raw = imageProxy.toArgbBitmap(imagePipeline)
            yuvToBitmapUs = elapsedUs(yuvStartUs)
            bitmap = if (rotation != 0) {
                val rotateStartUs = nowUs()
                android.graphics.Bitmap.createBitmap(
                    raw,
                    0,
                    0,
                    raw.width,
                    raw.height,
                    android.graphics.Matrix().apply { postRotate(rotation.toFloat()) },
                    true,
                ).also {
                    rotateUs = elapsedUs(rotateStartUs)
                }
            } else {
                raw
            }
            val frameBitmap = bitmap ?: throw IllegalStateException("Bitmap conversion returned null")
            val buildStartUs = nowUs()
            val mpImage = BitmapImageBuilder(frameBitmap).build()
            bitmapImageBuildUs = elapsedUs(buildStartUs)
            val detectStartUs = nowUs()
            poseLandmarker?.detectAsync(mpImage, frameTime)
            detectAsyncUs = elapsedUs(detectStartUs)
            appearanceSnapshotCopyUs = enqueueAppearanceSnapshot(frameBitmap, elapsedMs)
        } catch (e: Exception) {
            errorDetail = e.message ?: e::class.java.name
            Log.e("PoseLandmarker", "detectAsync failed: ${e.message}", e)
        } finally {
            GemmaFitDebugApi.recordRgbaPipelineFrame(
                context = context,
                sample = RgbaPipelineFrameSample(
                    timestampMs = frameTime,
                    source = "live_camera",
                    pipelineVariant = imagePipeline.name,
                    cameraXOutputRotationEnabled = imagePipeline.cameraXOutputRotationEnabled,
                    imageFormat = imageFormat,
                    imageFormatName = imageFormatName,
                    planeCount = planeCount,
                    proxyWidth = proxyWidth,
                    proxyHeight = proxyHeight,
                    rotationDegrees = rotation,
                    rawBitmapConfig = bitmapConfigName(raw),
                    rawBitmapWidth = raw?.width ?: 0,
                    rawBitmapHeight = raw?.height ?: 0,
                    frameBitmapConfig = bitmapConfigName(bitmap),
                    frameBitmapWidth = bitmap?.width ?: 0,
                    frameBitmapHeight = bitmap?.height ?: 0,
                    yuvToBitmapUs = yuvToBitmapUs,
                    rotateUs = rotateUs,
                    bitmapImageBuildUs = bitmapImageBuildUs,
                    detectAsyncUs = detectAsyncUs,
                    appearanceSnapshotCopyUs = appearanceSnapshotCopyUs,
                    totalAcceptedFrameUs = elapsedUs(totalStartUs),
                    error = errorDetail,
                ),
            )
            if (raw != null && bitmap !== raw) {
                raw.recycle()
            }
            imageProxy.close()
        }
    }

    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
        while (true) {
            val snapshot = pendingAppearanceSnapshots.poll() ?: break
            snapshot.recycle()
        }
        executor.shutdown()
    }

    private fun enqueueAppearanceSnapshot(frameBitmap: Bitmap, elapsedMs: Long): Long? {
        if (elapsedMs - lastAppearanceSnapshotElapsedMs < APPEARANCE_SNAPSHOT_INTERVAL_MS) return null
        lastAppearanceSnapshotElapsedMs = elapsedMs
        val copyStartUs = nowUs()
        val snapshot = try {
            frameBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Log.w("PoseLandmarker", "Appearance snapshot copy failed: ${e.message}")
            null
        }
        val copyUs = elapsedUs(copyStartUs)
        snapshot ?: return copyUs
        pendingAppearanceSnapshots.add(snapshot)
        while (pendingAppearanceSnapshots.size > MAX_PENDING_APPEARANCE_SNAPSHOTS) {
            pendingAppearanceSnapshots.poll()?.recycle()
        }
        return copyUs
    }

    private fun ImageProxy.toArgbBitmap(pipeline: LiveCameraImagePipeline): Bitmap {
        return if (pipeline.usesCameraXRgba) {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                planes.firstOrNull()?.buffer?.let { buffer ->
                    buffer.rewind()
                    bitmap.copyPixelsFromBuffer(buffer)
                } ?: error("RGBA ImageProxy did not expose a plane buffer")
            }
        } else {
            toBitmap()
        }
    }

}

@Composable
fun CameraPreviewWithOverlay(
    onPoseDetected: (PoseLandmarkerResult, Int, Int, Int, Bitmap?) -> Unit,
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val activeImageAnalysis = remember { AtomicReference<ImageAnalysis?>(null) }
    val imagePipeline = remember { LiveCameraImagePipeline.fromDebugConfig(context) }

    val poseHelper = remember(lensFacing, imagePipeline) {
        PoseLandmarkerHelper(context, object : PoseLandmarkerHelper.PoseLandmarkerListener {
            override fun onError(error: String) {
                Log.e("PoseLandmarker", error)
            }

            override fun onResults(
                result: PoseLandmarkerResult,
                imageHeight: Int,
                imageWidth: Int,
                timestampMs: Long,
                frameBitmap: Bitmap?,
            ) {
                onPoseDetected(result, imageHeight, imageWidth, lensFacing, frameBitmap)
            }
        }, imagePipeline)
    }

    LaunchedEffect(lensFacing, previewView, imagePipeline) {
        val cameraProvider = getCameraProvider(context)
        val lifecycleOwner = context as? LifecycleOwner
        if (lifecycleOwner == null) {
            Log.e("CameraPreview", "Context is not a LifecycleOwner: ${context::class.java.name}")
            return@LaunchedEffect
        }

        val targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0

        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
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
            .setTargetRotation(targetRotation)
            .setOutputImageFormat(imagePipeline.cameraXFormat)
            .setOutputImageRotationEnabled(imagePipeline.cameraXOutputRotationEnabled)
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
        onPoseDetected = { result, imageHeight, imageWidth, _, frameBitmap ->
            frameBitmap?.recycle()
            onPoseDetected(result, imageHeight, imageWidth)
        },
        modifier = modifier,
    )
}
