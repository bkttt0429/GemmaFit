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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
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
    val pass: VideoAnalysisPass = VideoAnalysisPass.FULL,
    val convertMs: Long = 0L,
    val poseMs: Long = 0L,
)

enum class VideoAnalysisPass {
    PREVIEW,
    FULL,
}

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
    private val targetAnalysisIntervalMs: Long = 100L,
    private val pass: VideoAnalysisPass = VideoAnalysisPass.FULL,
) {
    companion object {
        private const val TAG = "GemmaFit.VideoProc"
        private const val CODEC_FIRST_FRAME_TIMEOUT_MS = 3_000L
        private const val CODEC_IDLE_TIMEOUT_MS = 5_000L
    }

    private data class QueuedFrame(
        val bitmap: Bitmap,
        val timestampMs: Long,
        val convertMs: Long = 0L,
    )

    fun processVideo(uri: Uri): Flow<VideoFrameResult> = flow {
        val emitted = try {
            extractWithCodec(uri) { emit(it) }
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodec fallback: ${e.message}")
            0
        }
        if (emitted == 0) {
            extractWithRetriever(uri) { emit(it) }
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun extractWithCodec(
        uri: Uri,
        emitFrame: suspend (VideoFrameResult) -> Unit,
    ): Int {
        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open URI: ${e.message}", e)
            return 0
        }

        val videoTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: run {
            extractor.release()
            return 0
        }

        extractor.selectTrack(videoTrackIndex)
        val format = extractor.getTrackFormat(videoTrackIndex)
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val durationUs = format.getLong(MediaFormat.KEY_DURATION)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"

        Log.d(TAG, "Codec[$pass]: ${width}x${height}, duration=${durationUs / 1000}ms, mime=$mime, interval=${targetAnalysisIntervalMs}ms, longSide=$maxDimension")

        val scale = minOf(1f, maxDimension.toFloat() / maxOf(width, height))
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)

        val decodeFormat = MediaFormat.createVideoFormat(mime, width, height)

        val handlerThread = HandlerThread("FrameDecoder").also { it.start() }
        val handler = Handler(handlerThread.looper)
        val eosReached = AtomicBoolean(false)
        val decodeFinished = AtomicBoolean(false)
        val codecStopping = AtomicBoolean(false)
        val decodeLatch = CountDownLatch(1)
        var decodeError: Exception? = null
        val yuvQueue = LinkedBlockingQueue<QueuedFrame>()
        val resultQueue = LinkedBlockingQueue<VideoFrameResult>()
        var processJob: Job? = null
        var outputColorFormat = -1
        var emittedFrames = 0
        var codecWatchdogTriggered = false

        var decoder: MediaCodec? = null
        try {
            decoder = MediaCodec.createDecoderByType(mime)

            decoder!!.setCallback(object : MediaCodec.Callback() {
                private var outputCount = 0
                private var lastQueuedTimestampMs = Long.MIN_VALUE

                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    if (eosReached.get() || codecStopping.get()) return
                    try {
                        val buf = mc.getInputBuffer(index) ?: return
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            mc.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosReached.set(true)
                        } else {
                            mc.queueInputBuffer(index, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    } catch (e: IllegalStateException) {
                        if (!codecStopping.get()) {
                            Log.e(TAG, "Codec input error: ${e.message}", e)
                            decodeError = e
                        }
                        decodeFinished.set(true)
                        decodeLatch.countDown()
                    }
                }

                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        mc.releaseOutputBuffer(index, false)
                        decodeFinished.set(true)
                        decodeLatch.countDown()
                        return
                    }

                    val rawTimestampMs = (info.presentationTimeUs / 1000L).coerceAtLeast(0L)
                    val timestampMs = if (rawTimestampMs > lastQueuedTimestampMs || lastQueuedTimestampMs == Long.MIN_VALUE) {
                        rawTimestampMs
                    } else {
                        outputCount * targetAnalysisIntervalMs.coerceAtLeast(1L)
                    }
                    val shouldAnalyze = info.size > 0 &&
                        (lastQueuedTimestampMs == Long.MIN_VALUE ||
                            timestampMs - lastQueuedTimestampMs >= targetAnalysisIntervalMs.coerceAtLeast(1L))

                    if (shouldAnalyze) {
                        val image = try { mc.getOutputImage(index) } catch (e: Exception) {
                            Log.e(TAG, "getOutputImage: ${e.message}")
                            null
                        }
                        if (image != null) {
                            try {
                                val convertStart = System.currentTimeMillis()
                                val bmp = yuvImageToBitmap(image, width, height, targetW, targetH)
                                yuvQueue.put(QueuedFrame(bmp, timestampMs, System.currentTimeMillis() - convertStart))
                                lastQueuedTimestampMs = timestampMs
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
                                    val convertStart = System.currentTimeMillis()
                                    val bmp = bufferToBitmap(buf, width, height, targetW, targetH, outputColorFormat)
                                    yuvQueue.put(QueuedFrame(bmp, timestampMs, System.currentTimeMillis() - convertStart))
                                    lastQueuedTimestampMs = timestampMs
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
                    decodeFinished.set(true)
                    decodeLatch.countDown()
                }
            }, handler)

            decoder!!.configure(decodeFormat, null, null, 0)
            decoder!!.start()

            // Launch parallel pose detection coroutine (runs concurrently with decode)
            processJob = CoroutineScope(kotlin.coroutines.coroutineContext + Dispatchers.Default).launch {
                var frameIdx = 0
                var outputFinished = false
                while (isActive && (!outputFinished || yuvQueue.isNotEmpty())) {
                    val queued = try {
                        yuvQueue.poll(100, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        return@launch
                    }
                    if (queued != null) {
                        val result = detectPose(
                            bitmap = queued.bitmap,
                            frameIndex = frameIdx,
                            timestampMs = queued.timestampMs,
                            convertMs = queued.convertMs,
                        )
                        resultQueue.put(result)
                        frameIdx++
                    }
                    outputFinished = decodeFinished.get()
                }
            }

            val timeoutMs = ((durationUs / 1000L).coerceAtLeast(10_000L) + 15_000L)
            val waitStartMs = System.currentTimeMillis()
            var lastEmitMs = waitStartMs
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                val result = resultQueue.poll(100, TimeUnit.MILLISECONDS)
                if (result != null) {
                    emitFrame(result)
                    emittedFrames++
                    lastEmitMs = now
                }
                if (decodeFinished.get() && yuvQueue.isEmpty() && resultQueue.isEmpty() && processJob?.isCompleted == true) {
                    break
                }
                val noQueuedWork = yuvQueue.isEmpty() && resultQueue.isEmpty()
                if (emittedFrames == 0 && noQueuedWork && now - waitStartMs > CODEC_FIRST_FRAME_TIMEOUT_MS) {
                    Log.w(TAG, "Codec[$pass] no first frame after ${now - waitStartMs}ms; falling back to retriever")
                    codecWatchdogTriggered = true
                    codecStopping.set(true)
                    decodeFinished.set(true)
                    decodeLatch.countDown()
                    break
                }
                if (emittedFrames > 0 && noQueuedWork && !decodeFinished.get() && now - lastEmitMs > CODEC_IDLE_TIMEOUT_MS) {
                    Log.w(TAG, "Codec[$pass] idle after $emittedFrames frames; falling back to retriever")
                    codecWatchdogTriggered = true
                    codecStopping.set(true)
                    decodeFinished.set(true)
                    decodeLatch.countDown()
                    break
                }
            }
            if (!decodeFinished.get() && !codecWatchdogTriggered) {
                Log.w(TAG, "Codec[$pass] timed out after ${timeoutMs}ms")
            }
            if (codecWatchdogTriggered) {
                processJob?.cancelAndJoin()
            } else {
                processJob?.join()
            }
            while (true) {
                val result = resultQueue.poll() ?: break
                emitFrame(result)
                emittedFrames++
            }

            Log.d(TAG, "Codec[$pass] streamed $emittedFrames frames")

        } catch (e: Exception) {
            Log.e(TAG, "Codec extraction error: ${e.message}", e)
            decodeError = e
        } finally {
            codecStopping.set(true)
            decodeFinished.set(true)
            processJob?.let { job ->
                if (!job.isCompleted) {
                    job.cancelAndJoin()
                }
            }
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            handlerThread.quitSafely()
            try { handlerThread.join(1_000L) } catch (_: InterruptedException) {}
            extractor.release()
            while (true) { yuvQueue.poll()?.bitmap?.recycle() ?: break }
        }

        if (decodeError != null && emittedFrames == 0) {
            throw decodeError!!
        }
        if (emittedFrames == 0) {
            Log.w(TAG, "Codec[$pass] produced 0 frames")
        }
        return emittedFrames
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

    private suspend fun extractWithRetriever(
        uri: Uri,
        emitFrame: suspend (VideoFrameResult) -> Unit,
    ): Int {
        var emittedFrames = 0
        var detectedFrames = 0
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10000L
            val sampleIntervalMs = targetAnalysisIntervalMs.coerceAtLeast(33L)

            Log.d(TAG, "Retriever[$pass]: duration=${durationMs}ms, interval=${sampleIntervalMs}ms, longSide=$maxDimension")

            var timeMs = 0L
            var frameIdx = 0
            while (timeMs < durationMs) {
                val bmp = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bmp != null) {
                    val convertStart = System.currentTimeMillis()
                    val scaled = if (bmp.width > maxDimension || bmp.height > maxDimension) {
                        val scale = minOf(1f, maxDimension.toFloat() / maxOf(bmp.width, bmp.height))
                        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                    } else {
                        bmp
                    }
                    val result = detectPose(
                        bitmap = scaled,
                        frameIndex = frameIdx,
                        timestampMs = timeMs,
                        convertMs = System.currentTimeMillis() - convertStart,
                    )
                    if (result.landmarks != null && result.landmarks.landmarks.isNotEmpty()) {
                        detectedFrames++
                    }
                    emitFrame(result)
                    emittedFrames++
                    if (scaled !== bmp) bmp.recycle()
                }
                timeMs += sampleIntervalMs
                frameIdx++
            }
            Log.d(TAG, "Retriever[$pass]: $emittedFrames frames, $detectedFrames with pose")
        } catch (e: Exception) {
            Log.e(TAG, "Retriever error: ${e.message}", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return emittedFrames
    }

    private fun detectPose(
        bitmap: Bitmap,
        frameIndex: Int,
        timestampMs: Long,
        convertMs: Long,
    ): VideoFrameResult {
        if (poseLandmarker == null) {
            return VideoFrameResult(frameIndex, timestampMs, null, null, pass = pass, convertMs = convertMs)
        }
        return try {
            val poseStart = System.currentTimeMillis()
            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
            val result = poseLandmarker.detectForVideo(mpImage, timestampMs)
            val poseMs = System.currentTimeMillis() - poseStart
            val landmarks = if (result.landmarks().isNotEmpty()) {
                result.landmarks().map { lmList ->
                    lmList.map { lm ->
                        NormalizedLandmark(lm.x(), lm.y(), lm.z(), lm.visibility().orElse(0.0f))
                    }
                }
            } else {
                emptyList()
            }
            Log.d(TAG, "Frame[$pass] #$frameIndex t=${timestampMs}ms convert=${convertMs}ms pose=${poseMs}ms poses=${landmarks.size}")
            VideoFrameResult(
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                landmarks = VideoPoseResult(landmarks, null),
                bitmap = bitmap,
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                pass = pass,
                convertMs = convertMs,
                poseMs = poseMs,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Frame $frameIndex: pose error: ${e.message}")
            VideoFrameResult(frameIndex, timestampMs, null, null, pass = pass, convertMs = convertMs)
        }
    }

    fun release() {}
}
