package com.newcast.app.sender

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import com.newcast.app.model.VideoCodecType
import com.newcast.app.network.VideoPacketFlags
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class EncodedOutput(
    val frameId: Int,
    val flags: Byte,
    val codecId: Byte,
    val timestampUs: Long,
    val data: ByteArray
)

/**
 * Hardware zero-copy screen encoder (MediaProjection -> VirtualDisplay -> MediaCodec Input Surface).
 *
 * Reuses a single VirtualDisplay across live preset/rotation changes (using VirtualDisplay.resize +
 * setSurface) to remain 100% compliant with Android 14/15/16 MediaProjection single-use token rules.
 */
class ScreenEncoder(
    private val mediaProjection: MediaProjection,
    private val densityDpi: Int,
    private val onEncodedFrame: (EncodedOutput) -> Unit,
    private val onEncoderError: (String) -> Unit
) {
    private val lock = Any()
    private val isActive = AtomicBoolean(false)
    private val frameCounter = AtomicInteger(0)

    private var codecThread: HandlerThread? = null
    private var codecHandler: Handler? = null

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var cachedConfigBytes: ByteArray? = null

    @Volatile
    var currentWidth: Int = 0
        private set

    @Volatile
    var currentHeight: Int = 0
        private set

    @Volatile
    var currentFps: Int = 60
        private set

    @Volatile
    var currentBitrateBps: Int = 8_000_000
        private set

    @Volatile
    var currentCodecType: VideoCodecType = VideoCodecType.H264
        private set

    fun start(
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        codecType: VideoCodecType
    ) {
        synchronized(lock) {
            stopCodecLocked()
            ensureThreadLocked()

            currentWidth = width
            currentHeight = height
            currentFps = fps
            currentBitrateBps = bitrateBps
            currentCodecType = codecType
            cachedConfigBytes = null

            val codec = createAndConfigureCodec(width, height, fps, bitrateBps, codecType)
            val surface = codec.createInputSurface()

            codec.setCallback(createCodecCallback(codecType), codecHandler)
            codec.start()

            mediaCodec = codec
            inputSurface = surface
            isActive.set(true)

            val existingVd = virtualDisplay
            if (existingVd == null) {
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "NewCastVirtualDisplay",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    codecHandler
                )
            } else {
                existingVd.resize(width, height, densityDpi)
                existingVd.surface = surface
            }

            Log.i(TAG, "Encoder started: ${width}x${height}@${fps}fps ${bitrateBps / 1_000_000f}Mbps ${codecType.name}")
        }
    }

    fun reconfigure(
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        codecType: VideoCodecType = currentCodecType
    ) {
        synchronized(lock) {
            if (virtualDisplay == null && !isActive.get()) return
            try {
                virtualDisplay?.surface = null
            } catch (_: Exception) {
            }
            start(width, height, fps, bitrateBps, codecType)
        }
    }

    fun setSuspended(suspended: Boolean) {
        synchronized(lock) {
            if (!isActive.get()) return
            try {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (suspended) 1 else 0)
                }
                mediaCodec?.setParameters(params)
                if (!suspended) {
                    requestSyncFrame()
                }
                Log.i(TAG, "Encoder suspended=$suspended")
            } catch (e: Exception) {
                Log.w(TAG, "setSuspended($suspended) failed: ${e.message}")
            }
        }
    }

    fun requestSyncFrame() {
        synchronized(lock) {
            if (!isActive.get()) return
            try {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
                mediaCodec?.setParameters(params)
            } catch (e: Exception) {
                Log.w(TAG, "requestSyncFrame failed: ${e.message}")
            }
        }
    }

    fun updateBitrate(newBitrateBps: Int) {
        synchronized(lock) {
            if (!isActive.get()) return
            try {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateBps)
                }
                mediaCodec?.setParameters(params)
                currentBitrateBps = newBitrateBps
            } catch (e: Exception) {
                Log.w(TAG, "updateBitrate failed: ${e.message}")
            }
        }
    }

    fun release() {
        synchronized(lock) {
            isActive.set(false)
            try {
                virtualDisplay?.surface = null
                virtualDisplay?.release()
            } catch (_: Exception) {
            }
            virtualDisplay = null
            stopCodecLocked()
            try {
                codecThread?.quitSafely()
            } catch (_: Exception) {
            }
            codecThread = null
            codecHandler = null
        }
    }

    private fun ensureThreadLocked() {
        if (codecThread == null) {
            val thread = object : HandlerThread("NewCastEncoderThread", Process.THREAD_PRIORITY_URGENT_DISPLAY) {}
            thread.start()
            codecThread = thread
            codecHandler = Handler(thread.looper)
        }
    }

    private fun stopCodecLocked() {
        isActive.set(false)
        try {
            mediaCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaCodec?.release()
        } catch (_: Exception) {
        }
        mediaCodec = null
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null
    }

    private fun createAndConfigureCodec(
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        codecType: VideoCodecType
    ): MediaCodec {
        // Attempt 1: Full ultra-low-latency hardware configuration
        val fullFormat = buildFormat(width, height, fps, bitrateBps, codecType, strictMinimal = false)
        try {
            return MediaCodec.createEncoderByType(codecType.mimeType).apply {
                configure(fullFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Full low-latency format rejected (${e.message}), trying safe fallback")
        }

        // Attempt 2: Safe baseline fallback if optional vendor keys were rejected
        val safeFormat = buildFormat(width, height, fps, bitrateBps, codecType, strictMinimal = true)
        return MediaCodec.createEncoderByType(codecType.mimeType).apply {
            configure(safeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun buildFormat(
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        codecType: VideoCodecType,
        strictMinimal: Boolean
    ): MediaFormat {
        return MediaFormat.createVideoFormat(codecType.mimeType, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // 2s IDR interval + on-demand REQUEST_IDR cuts bitrate spikes & radio power while keeping instant recovery
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)

            if (!strictMinimal) {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // Zero frame reordering latency
                // Repeat static frame at 2fps (500ms) instead of 10fps to save ~80% idle encoder/Wi-Fi power
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            }
        }
    }

    private fun createCodecCallback(codecType: VideoCodecType): MediaCodec.Callback {
        return object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Unused with Surface input
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                if (!isActive.get()) {
                    try {
                        codec.releaseOutputBuffer(index, false)
                    } catch (_: Exception) {
                    }
                    return
                }

                try {
                    val outBuf = codec.getOutputBuffer(index)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)

                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (isConfig) {
                            val cfg = ByteArray(info.size)
                            outBuf.get(cfg)
                            cachedConfigBytes = cfg
                            onEncodedFrame(
                                EncodedOutput(
                                    frameId = frameCounter.incrementAndGet(),
                                    flags = VideoPacketFlags.CONFIG,
                                    codecId = codecType.id,
                                    timestampUs = System.nanoTime() / 1000L,
                                    data = cfg
                                )
                            )
                        } else {
                            val cfg = cachedConfigBytes
                            val payload = if (isKeyframe && cfg != null) {
                                // Prepend SPS/PPS to every IDR frame for instant recovery over UDP
                                ByteArray(cfg.size + info.size).also { combined ->
                                    System.arraycopy(cfg, 0, combined, 0, cfg.size)
                                    outBuf.get(combined, cfg.size, info.size)
                                }
                            } else {
                                ByteArray(info.size).also { outBuf.get(it) }
                            }

                            val packetFlags = if (isKeyframe) {
                                VideoPacketFlags.KEYFRAME
                            } else {
                                VideoPacketFlags.NONE
                            }

                            onEncodedFrame(
                                EncodedOutput(
                                    frameId = frameCounter.incrementAndGet(),
                                    flags = packetFlags,
                                    codecId = codecType.id,
                                    timestampUs = System.nanoTime() / 1000L,
                                    data = payload
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Output buffer read error: ${e.message}")
                } finally {
                    try {
                        codec.releaseOutputBuffer(index, false)
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "MediaCodec error: ${e.diagnosticInfo}", e)
                if (isActive.get()) {
                    onEncoderError("Lỗi bộ mã hóa video: ${e.localizedMessage ?: e.errorCode}")
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Encoder output format: $format")
            }
        }
    }

    companion object {
        private const val TAG = "NewCastEncoder"
    }
}
