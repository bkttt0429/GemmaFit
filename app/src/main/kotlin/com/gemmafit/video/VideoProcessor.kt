package com.gemmafit.video

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class VideoFrameResult(
    val frameIndex: Int,
    val timestampMs: Long,
    val landmarks: VideoPoseResult?,
    val bitmap: Bitmap?,
    val bitmapWidth: Int = 0,
    val bitmapHeight: Int = 0,
)

data class VideoPoseResult(
    val landmarks: List<List<NormalizedLandmark>>,
    val worldLandmarks: List<List<NormalizedLandmark>>?,
)

data class NormalizedLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
)

class VideoProcessor(
    private val context: Context,
    private val poseLandmarker: PoseLandmarker?,
    private val sampleEveryNFrames: Int = 3,
    private val maxDimension: Int = 640,
) {
    companion object {
        private const val TAG = "GemmaFit.VideoProc"
    }

    fun processVideo(uri: Uri): Flow<VideoFrameResult> = flow {
        val results = withContext(Dispatchers.IO) {
            try {
                extractWithCodec(uri)
            } catch (e: Exception) {
                Log.w(TAG, "MediaCodec fallback: ${e.message}")
                extractWithRetriever(uri)
            }
        }
        results.forEach { emit(it) }
    }.flowOn(Dispatchers.Default)

    private fun extractWithCodec(uri: Uri): List<VideoFrameResult> {
        val frames = mutableListOf<VideoFrameResult>()
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open URI: ${e.message}", e)
            return extractWithRetriever(uri)
        }

        val videoTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: run {
            extractor.release()
            return extractWithRetriever(uri)
        }

        extractor.selectTrack(videoTrackIndex)
        val format = extractor.getTrackFormat(videoTrackIndex)
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val durationUs = format.getLong(MediaFormat.KEY_DURATION)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"

        Log.d(TAG, "Codec: ${width}x${height}, duration=${durationUs / 1000}ms, mime=$mime, sampleEvery=$sampleEveryNFrames")

        val scale = maxDimension.toFloat() / maxOf(width, height)
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)

        val decodeFormat = MediaFormat.createVideoFormat(mime, width, height)

        val handlerThread = HandlerThread("FrameDecoder").also { it.start() }
        val handler = Handler(handlerThread.looper)
        val eosReached = AtomicBoolean(false)
        val decodedFrames = ConcurrentLinkedQueue<Bitmap>()
        val decodeLatch = CountDownLatch(1)
        var decodeError: Exception? = null

        var decoder: MediaCodec? = null
        try {
            decoder = MediaCodec.createDecoderByType(mime)

            decoder!!.setCallback(object : MediaCodec.Callback() {
                private var outputCount = 0

                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    if (eosReached.get()) return
                    val buf = mc.getInputBuffer(index) ?: return
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        mc.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosReached.set(true)
                    } else {
                        mc.queueInputBuffer(index, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        mc.releaseOutputBuffer(index, false)
                        decodeLatch.countDown()
                        return
                    }

                    if (info.size > 0 && outputCount % sampleEveryNFrames.coerceAtLeast(1) == 0) {
                        val image = try { mc.getOutputImage(index) } catch (_: Exception) { null }
                        if (image != null) {
                            try {
                                val bmp = yuvImageToBitmap(image, width, height, targetW, targetH)
                                decodedFrames.add(bmp)
                            } catch (e: Exception) {
                                Log.w(TAG, "YUV conversion failed: ${e.message}")
                            } finally {
                                image.close()
                            }
                        }
                    }
                    outputCount++
                    mc.releaseOutputBuffer(index, false)
                }

                override fun onOutputFormatChanged(mc: MediaCodec, fmt: MediaFormat) {
                    Log.d(TAG, "Output format changed: ${fmt.toString().take(100)}")
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Codec error: ${e.message}")
                    decodeError = e
                    decodeLatch.countDown()
                }
            }, handler)

            decoder!!.configure(decodeFormat, null, null, 0)
            decoder!!.start()

            val timeoutSeconds = (durationUs / 1_000_000L).coerceAtLeast(10L) + 15L
            decodeLatch.await(timeoutSeconds, TimeUnit.SECONDS)

            Log.d(TAG, "Codec decoded ${decodedFrames.size} frames, outputIndex=done")

        } catch (e: Exception) {
            Log.e(TAG, "Codec extraction error: ${e.message}", e)
            decodeError = e
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            handlerThread.quitSafely()
            extractor.release()
        }

        if (decodeError != null && decodedFrames.isEmpty()) {
            throw decodeError!!
        }

        var frameIdx = 0
        var detectedFrames = 0
        val timeStep = (durationUs / maxOf(decodedFrames.size, 1)) / 1000L
        for (bmp in decodedFrames) {
            val tsMs = frameIdx * timeStep
            val result = detectPose(bmp, frameIdx, tsMs)
            frames.add(result)
            if (result.landmarks != null && result.landmarks.landmarks.isNotEmpty()) {
                detectedFrames++
            }
            frameIdx++
        }

        Log.d(TAG, "Codec result: ${frames.size} frames, $detectedFrames with pose")
        return frames
    }

    private fun yuvImageToBitmap(image: Image, srcW: Int, srcH: Int, targetW: Int, targetH: Int): Bitmap {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val pixels = IntArray(srcW * srcH)

        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val yIdx = y * yRowStride + x
                val uvIdx = (y shr 1) * uvRowStride + (x shr 1) * uvPixelStride

                val yVal = (yBuf[yIdx].toInt() and 0xFF) - 16
                val uVal = (uBuf[uvIdx.coerceIn(0, uBuf.capacity() - 1)].toInt() and 0xFF) - 128
                val vVal = (vBuf[uvIdx.coerceIn(0, vBuf.capacity() - 1)].toInt() and 0xFF) - 128

                val r = (1.164f * yVal + 1.596f * vVal).toInt().coerceIn(0, 255)
                val g = (1.164f * yVal - 0.392f * uVal - 0.813f * vVal).toInt().coerceIn(0, 255)
                val b = (1.164f * yVal + 2.017f * uVal).toInt().coerceIn(0, 255)

                pixels[y * srcW + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val fullBitmap = Bitmap.createBitmap(pixels, srcW, srcH, Bitmap.Config.ARGB_8888)
        return if (srcW != targetW || srcH != targetH) {
            val scaled = Bitmap.createScaledBitmap(fullBitmap, targetW, targetH, true)
            fullBitmap.recycle()
            scaled
        } else {
            fullBitmap
        }
    }

    private fun extractWithRetriever(uri: Uri): List<VideoFrameResult> {
        val list = mutableListOf<VideoFrameResult>()
        var detectedFrames = 0
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10000L
            val sampleIntervalMs = 200L * sampleEveryNFrames.coerceAtLeast(1)

            Log.d(TAG, "Retriever: duration=${durationMs}ms, interval=${sampleIntervalMs}ms")

            var timeMs = 0L
            var frameIdx = 0
            while (timeMs < durationMs) {
                val bmp = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bmp != null) {
                    val scaled = if (bmp.width > maxDimension || bmp.height > maxDimension) {
                        val scale = maxDimension.toFloat() / maxOf(bmp.width, bmp.height)
                        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                    } else {
                        bmp
                    }
                    val result = detectPose(scaled, frameIdx, timeMs)
                    if (result.landmarks != null && result.landmarks.landmarks.isNotEmpty()) {
                        detectedFrames++
                    }
                    list.add(result)
                    if (scaled !== bmp) bmp.recycle()
                }
                timeMs += sampleIntervalMs
                frameIdx++
            }
            Log.d(TAG, "Retriever: ${list.size} frames, $detectedFrames with pose")
        } catch (e: Exception) {
            Log.e(TAG, "Retriever error: ${e.message}", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return list
    }

    private fun detectPose(bitmap: Bitmap, frameIndex: Int, timestampMs: Long): VideoFrameResult {
        if (poseLandmarker == null) {
            return VideoFrameResult(frameIndex, timestampMs, null, null)
        }
        return try {
            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
            val result = poseLandmarker.detect(mpImage)
            val landmarks = if (result.landmarks().isNotEmpty()) {
                result.landmarks().map { lmList ->
                    lmList.map { lm ->
                        NormalizedLandmark(lm.x(), lm.y(), lm.z(), lm.visibility().orElse(1.0f))
                    }
                }
            } else {
                emptyList()
            }
            VideoFrameResult(frameIndex, timestampMs, VideoPoseResult(landmarks, null), bitmap, bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Log.e(TAG, "Frame $frameIndex: pose error: ${e.message}")
            VideoFrameResult(frameIndex, timestampMs, null, null)
        }
    }

    fun release() {}
}