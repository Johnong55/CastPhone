package com.newcast.app.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import com.newcast.app.model.VideoCodecType
import com.newcast.app.network.EncodedVideoFrame
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Event-driven asynchronous hardware MediaCodec decoder rendering directly to a Surface.
 *
 * Optimizations for zero-lag 60fps & minimal battery usage:
 * - Single dedicated URGENT_DISPLAY HandlerThread running MediaCodec.Callback (ZERO polling loops
 *   and ZERO CPU wakeups when the screen is static!)
 * - KEY_LOW_LATENCY = 1 & KEY_PRIORITY = 0 (realtime hardware scheduling)
 * - Immediate releaseOutputBuffer(..., true) on callback for minimum glass-to-glass latency
 * - Bounded 3-frame input buffer dropping stale P-frames so latency never accumulates
 * - Clean IDR sync gating after packet loss so green artifacts never appear
 */
class VideoDecoder(
    private val onVideoSizeChanged: (width: Int, height: Int) -> Unit,
    private val onNeedKeyframe: () -> Unit
) {
    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val renderedFramesCounter = AtomicInteger(0)

    // Accessed strictly on decoderHandler thread (lock-free single-threaded confinement)
    private val availableInputIndices = ArrayDeque<Int>(8)
    private val pendingFrames = ArrayDeque<EncodedVideoFrame>(4)
    private var awaitingKeyframe = true

    private var decoderThread: HandlerThread? = null
    private var decoderHandler: Handler? = null

    @Volatile
    private var outputSurface: Surface? = null

    private var codec: MediaCodec? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var configuredCodec: VideoCodecType = VideoCodecType.H264

    fun attachSurface(surface: Surface?) {
        synchronized(lock) {
            val previous = outputSurface
            outputSurface = surface
            if (surface == null) {
                stopLocked()
                return
            }

            val existingCodec = codec
            if (existingCodec != null && running.get() && previous != null && previous !== surface) {
                try {
                    existingCodec.setOutputSurface(surface)
                    onNeedKeyframe()
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "setOutputSurface failed (${e.message}), restarting decoder")
                }
            }

            if (configuredWidth > 0 && configuredHeight > 0) {
                startLocked(configuredWidth, configuredHeight, configuredCodec, surface)
            }
        }
    }

    fun configure(width: Int, height: Int, codecType: VideoCodecType) {
        synchronized(lock) {
            if (running.get() &&
                configuredWidth == width &&
                configuredHeight == height &&
                configuredCodec == codecType &&
                codec != null
            ) {
                return
            }

            configuredWidth = width.coerceAtLeast(160)
            configuredHeight = height.coerceAtLeast(160)
            configuredCodec = codecType
            onVideoSizeChanged(configuredWidth, configuredHeight)

            val surface = outputSurface ?: return
            startLocked(configuredWidth, configuredHeight, configuredCodec, surface)
        }
    }

    fun submitFrame(frame: EncodedVideoFrame) {
        if (!running.get()) return
        val handler = decoderHandler ?: return
        val activeGen = generation.get()

        handler.post {
            if (!running.get() || generation.get() != activeGen) return@post
            enqueueAndDrainOnDecoderThread(frame)
        }
    }

    fun consumeRenderedFramesCount(): Int = renderedFramesCounter.getAndSet(0)

    fun resetSync() {
        val handler = decoderHandler
        val activeGen = generation.get()
        handler?.post {
            if (generation.get() != activeGen) return@post
            awaitingKeyframe = true
            pendingFrames.clear()
        }
        onNeedKeyframe()
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    fun release() {
        synchronized(lock) {
            outputSurface = null
            stopLocked()
            try {
                decoderThread?.quitSafely()
            } catch (_: Exception) {
            }
            decoderThread = null
            decoderHandler = null
        }
    }

    private fun ensureThreadLocked(): Handler {
        val existing = decoderHandler
        if (existing != null && decoderThread?.isAlive == true) {
            return existing
        }
        val thread = HandlerThread("NewCastAsyncDecoder", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
            start()
        }
        val handler = Handler(thread.looper)
        decoderThread = thread
        decoderHandler = handler
        return handler
    }

    private fun startLocked(
        width: Int,
        height: Int,
        codecType: VideoCodecType,
        surface: Surface
    ) {
        stopLocked()
        if (!surface.isValid) return

        val handler = ensureThreadLocked()
        val myGen = generation.incrementAndGet()

        val mediaCodec = try {
            createAndConfigureDecoder(width, height, codecType, surface, handler, myGen)
        } catch (e: Exception) {
            Log.e(TAG, "Failed configuring decoder", e)
            return
        }

        handler.post {
            availableInputIndices.clear()
            pendingFrames.clear()
            awaitingKeyframe = true
        }

        codec = mediaCodec
        running.set(true)
        mediaCodec.start()
        onNeedKeyframe()
        Log.i(TAG, "Async decoder started: ${width}x${height} ${codecType.name}")
    }

    private fun createAndConfigureDecoder(
        width: Int,
        height: Int,
        codecType: VideoCodecType,
        surface: Surface,
        handler: Handler,
        myGen: Int
    ): MediaCodec {
        val callback = createAsyncCallback(myGen)
        val lowLatencyFormat = MediaFormat.createVideoFormat(codecType.mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        }
        try {
            return MediaCodec.createDecoderByType(codecType.mimeType).apply {
                setCallback(callback, handler)
                configure(lowLatencyFormat, surface, null, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Low-latency decoder format rejected (${e.message}), using baseline format")
        }

        val safeFormat = MediaFormat.createVideoFormat(codecType.mimeType, width, height)
        return MediaCodec.createDecoderByType(codecType.mimeType).apply {
            setCallback(callback, handler)
            configure(safeFormat, surface, null, 0)
        }
    }

    private fun createAsyncCallback(myGen: Int): MediaCodec.Callback {
        return object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                if (!running.get() || generation.get() != myGen) return
                availableInputIndices.addLast(index)
                drainInputsOnDecoderThread(mc)
            }

            override fun onOutputBufferAvailable(
                mc: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                if (!running.get() || generation.get() != myGen) {
                    try { mc.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                    return
                }
                try {
                    // Render immediately on next vsync with zero artificial buffering
                    mc.releaseOutputBuffer(index, true)
                    renderedFramesCounter.incrementAndGet()
                } catch (e: Exception) {
                    Log.w(TAG, "releaseOutputBuffer failed: ${e.message}")
                }
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                if (!running.get() || generation.get() != myGen) return
                Log.w(TAG, "Async decoder error: ${e.diagnosticInfo}")
                awaitingKeyframe = true
                onNeedKeyframe()
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                if (!running.get() || generation.get() != myGen) return
                val w = format.getInteger(MediaFormat.KEY_WIDTH)
                val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (w > 0 && h > 0) {
                    onVideoSizeChanged(w, h)
                }
            }
        }
    }

    private fun enqueueAndDrainOnDecoderThread(frame: EncodedVideoFrame) {
        if (frame.hadPacketLossBefore && !frame.isKeyframe && !frame.isConfig) {
            awaitingKeyframe = true
            onNeedKeyframe()
            return
        }

        if (awaitingKeyframe) {
            if (frame.isConfig || frame.isKeyframe) {
                if (frame.isKeyframe) {
                    awaitingKeyframe = false
                }
            } else {
                return
            }
        }

        // Strict max-3-frame bound guarantees zero latency buildup
        if (pendingFrames.size >= MAX_PENDING_FRAMES) {
            if (frame.isKeyframe || frame.isConfig) {
                pendingFrames.clear()
            } else {
                val oldest = pendingFrames.firstOrNull()
                if (oldest != null && !oldest.isKeyframe && !oldest.isConfig) {
                    pendingFrames.removeFirst()
                } else {
                    return
                }
            }
        }
        pendingFrames.addLast(frame)

        val mc = codec ?: return
        drainInputsOnDecoderThread(mc)
    }

    private fun drainInputsOnDecoderThread(mc: MediaCodec) {
        while (availableInputIndices.isNotEmpty() && pendingFrames.isNotEmpty()) {
            val inputIndex = availableInputIndices.removeFirst()
            val frame = pendingFrames.removeFirst()
            try {
                val inputBuf = mc.getInputBuffer(inputIndex) ?: continue
                inputBuf.clear()
                if (frame.data.size > inputBuf.capacity()) {
                    Log.w(TAG, "Frame larger than input buffer (${frame.data.size} > ${inputBuf.capacity()})")
                    mc.queueInputBuffer(inputIndex, 0, 0, frame.timestampUs, 0)
                    awaitingKeyframe = true
                    onNeedKeyframe()
                    continue
                }
                inputBuf.put(frame.data)
                val flags = when {
                    frame.isConfig -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    frame.isKeyframe -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                    else -> 0
                }
                mc.queueInputBuffer(inputIndex, 0, frame.data.size, frame.timestampUs, flags)
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "queueInputBuffer error: ${e.message}")
                }
                break
            }
        }
    }

    private fun stopLocked() {
        running.set(false)
        generation.incrementAndGet()
        decoderHandler?.removeCallbacksAndMessages(null)
        decoderHandler?.post {
            availableInputIndices.clear()
            pendingFrames.clear()
            awaitingKeyframe = true
        }
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    companion object {
        private const val TAG = "NewCastDecoder"
        private const val MAX_PENDING_FRAMES = 3
    }
}
