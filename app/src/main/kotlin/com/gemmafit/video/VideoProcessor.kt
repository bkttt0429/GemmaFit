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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
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
        val decodeLatch = CountDownLatch(1)
        var decodeError: Exception? = null
        val yuvQueue = LinkedBlockingQueue<Bitmap>()
        val processResults = mutableListOf<VideoFrameResult>()
        var processJob: Job? = null
        var outputColorFormat = -1

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
                        val image = try { mc.getOutputImage(index) } catch (e: Exception) {
                            Log.e(TAG, "getOutputImage: ${e.message}")
                            null
                        }
                        if (image != null) {
                            try {
                                val bmp = yuvImageToBitmap(image, width, height, targetW, targetH)
                                yuvQueue.put(bmp)
                            } catch (e: Exception) {
                                Log.e(TAG, "YUV convert failed (${width}x${height}): ${e.message}", e)
                            } finally {
                                image.close()
                            }
                        } else if (info.size > 0) {
                            val buf = try { mc.getOutputBuffer(index) } catch (_: Exception) { null }
                            if (buf != null) {
                                try {
                                    buf.position(info.offset)
                                    buf.limit(info.offset + info.size)
                                    val bmp = bufferToBitmap(buf, width, height, targetW, targetH, outputColorFormat)
                                    yuvQueue.put(bmp)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Buffer convert failed: ${e.message}", e)
                                }
                            }
                        }
                    }
                    outputCount++
                    mc.releaseOutputBuffer(index, false)
                }

                override fun onOutputFormatChanged(mc: MediaCodec, fmt: MediaFormat) {
                    outputColorFormat = try { fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT) } catch (_: Exception) { -1 }
                    Log.d(TAG, "Output format: color=$outputColorFormat")
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Codec error: ${e.message}")
                    decodeError = e
                    decodeLatch.countDown()
                }
            }, handler)

            decoder!!.configure(decodeFormat, null, null, 0)
            decoder!!.start()

            // Launch parallel pose detection coroutine (runs concurrently with decode)
            val timeStep = durationUs / maxOf(1L, (durationUs / (1000L * 1000L / 30L * sampleEveryNFrames)).coerceAtLeast(1L)) / 1000L
            processJob = CoroutineScope(Dispatchers.Default).launch {
                var frameIdx = 0
                var eosSeen = false
                while (!eosSeen || yuvQueue.isNotEmpty()) {
                    val bmp = try {
                        yuvQueue.poll(100, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        return@launch
                    }
                    if (bmp != null) {
                        val tsMs = frameIdx * timeStep
                        val result = detectPose(bmp, frameIdx, tsMs)
                        processResults.add(result)
                        frameIdx++
                    }
                    eosSeen = eosReached.get()
                }
            }

            val timeoutSeconds = (durationUs / 1_000_000L).coerceAtLeast(10L) + 15L
            decodeLatch.await(timeoutSeconds, TimeUnit.SECONDS)
            runBlocking { processJob?.join() }

            Log.d(TAG, "Codec decoded ${processResults.size} frames")

        } catch (e: Exception) {
            Log.e(TAG, "Codec extraction error: ${e.message}", e)
            decodeError = e
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            handlerThread.quitSafely()
            extractor.release()
            while (true) { yuvQueue.poll()?.recycle() ?: break }
        }

        if (decodeError != null && processResults.isEmpty()) {
            throw decodeError!!
        }
        if (processResults.isEmpty()) {
            Log.w(TAG, "Codec produced 0 frames, falling back to Retriever")
            return extractWithRetriever(uri)
        }

        val detectedCount = processResults.count { it.landmarks != null && it.landmarks.landmarks.isNotEmpty() }
        Log.d(TAG, "Codec result: ${processResults.size} frames, $detectedCount with pose")
        return processResults
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
        val yMax = yBuf.capacity() - 1
        val uMax = uBuf.capacity() - 1
        val vMax = vBuf.capacity() - 1
        val scaleX = srcW.toFloat() / targetW.toFloat()
        val scaleY = srcH.toFloat() / targetH.toFloat()
        val pixels = IntArray(targetW * targetH)
        for (ty in 0 until targetH) {
            val sy = (ty * scaleY).toInt().coerceIn(0, srcH - 1)
            for (tx in 0 until targetW) {
                val sx = (tx * scaleX).toInt().coerceIn(0, srcW - 1)
                val yIdx = (sy * yRowStride + sx).coerceIn(0, yMax)
                val uvIdx = ((sy shr 1) * uvRowStride + (sx shr 1) * uvPixelStride).coerceIn(0, minOf(uMax, vMax))
                val yVal = (yBuf[yIdx].toInt() and 0xFF) - 16
                val uVal = (uBuf[uvIdx].toInt() and 0xFF) - 128
                val vVal = (vBuf[uvIdx].toInt() and 0xFF) - 128
                val r = (1.164f * yVal + 1.596f * vVal).toInt().coerceIn(0, 255)
                val g = (1.164f * yVal - 0.392f * uVal - 0.813f * vVal).toInt().coerceIn(0, 255)
                val b = (1.164f * yVal + 2.017f * uVal).toInt().coerceIn(0, 255)
                pixels[ty * targetW + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, targetW, targetH, Bitmap.Config.ARGB_8888)
    }

    private fun bufferToBitmap(buf: java.nio.ByteBuffer, srcW: Int, srcH: Int, targetW: Int, targetH: Int, colorFormat: Int): Bitmap {
        val scaleX = srcW.toFloat() / targetW.toFloat()
        val scaleY = srcH.toFloat() / targetH.toFloat()
        val pixels = IntArray(targetW * targetH)
        val raw = ByteArray(buf.remaining())
        buf.get(raw)
        buf.rewind()

        for (ty in 0 until targetH) {
            val sy = (ty * scaleY).toInt().coerceIn(0, srcH - 1)
            for (tx in 0 until targetW) {
                val sx = (tx * scaleX).toInt().coerceIn(0, srcW - 1)
                val idx = (sy * srcW + sx).coerceIn(0, (raw.size / 3).coerceAtLeast(1) - 1)
                // Assume I420/NV12 planar layout — extract Y, U, V from raw bytes
                val yOff = srcW * srcH
                val uOff = yOff + yOff / 4
                val yi = (sy * srcW + sx).coerceIn(0, yOff - 1)
                val uvi = (yOff + (sy / 2) * (srcW / 2) + (sx / 2)).coerceIn(0, raw.size - 1)
                val yVal = (raw[yi].toInt() and 0xFF) - 16
                val uVal = (raw[uvi].toInt() and 0xFF) - 128
                val vVal = (raw[minOf(uvi + 1, raw.size - 1)].toInt() and 0xFF) - 128
                var r = (1.164f * yVal + 1.596f * vVal).toInt()
                var g = (1.164f * yVal - 0.392f * uVal - 0.813f * vVal).toInt()
                var b = (1.164f * yVal + 2.017f * uVal).toInt()
                pixels[ty * targetW + tx] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(pixels, targetW, targetH, Bitmap.Config.ARGB_8888)
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