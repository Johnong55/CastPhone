package com.newcast.app.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Enables ultra-fast remote shutter & direct touch-to-focus / remote tap control on the stock
 * Pixel Camera (Google Camera) app over Hotspot/Wi-Fi without requiring root.
 */
class RemoteShutterAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isRunning.value = true
        Log.i(TAG, "RemoteShutterAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We inspect the active window strictly on-demand when a remote command arrives.
    }

    override fun onInterrupt() {
        // Nothing to interrupt
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        _isRunning.value = false
        super.onDestroy()
    }

    /**
     * Triggers the shutter / start-stop recording button on stock Pixel Camera (or other camera apps).
     */
    fun triggerCameraShutter(): Pair<Boolean, String> {
        val root = activeAppRoot()
            ?: return false to "Không đọc được cửa sổ hiện tại — hãy mở Pixel Camera"

        val pkg = root.packageName?.toString().orEmpty()

        // 1) Exact match by well-known Pixel Camera / Android Camera shutter view IDs
        val idMatch = findBestNode(root) { node ->
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            node.isClickable && SHUTTER_VIEW_ID_KEYWORDS.any { id.endsWith(it) || id.contains(it) }
        }
        if (idMatch != null && idMatch.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true to "Đã bấm chụp / quay trên $pkg"
        }

        // 2) Match by Accessibility contentDescription (English + Vietnamese)
        val descMatch = findBestNode(root) { node ->
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val text = node.text?.toString()?.lowercase().orEmpty()
            node.isClickable && (
                SHUTTER_DESC_KEYWORDS.any { desc.contains(it) || text.contains(it) }
            )
        }
        if (descMatch != null && descMatch.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true to "Đã kích hoạt nút chụp / quay"
        }

        // 3) Fallback positional tap ONLY when a camera app is in the foreground
        if (pkg.contains("camera", ignoreCase = true)) {
            val (w, h) = getDisplayBounds()
            val isLandscape = w > h
            val tapped = if (isLandscape) {
                dispatchNormalizedTap(0.885f, 0.50f)
            } else {
                dispatchNormalizedTap(0.50f, 0.845f)
            }
            return if (tapped) {
                true to "Đã chạm vị trí nút chụp trên Camera"
            } else {
                false to "Không thể thực hiện cử chỉ chụp"
            }
        }

        return false to "Hãy mở app Pixel Camera trước khi bấm chụp (đang mở: ${pkg.substringAfterLast('.')})"
    }

    fun dispatchNormalizedTap(normX: Float, normY: Float): Boolean {
        val (w, h) = getDisplayBounds()
        if (w <= 0 || h <= 0) return false
        val x = (normX.coerceIn(0.01f, 0.99f) * w)
        val y = (normY.coerceIn(0.01f, 0.99f) * h)

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        Log.d(TAG, "dispatchTap (${x.toInt()}, ${y.toInt()}) on ${w}x${h}")
        return dispatchGesture(gesture, null, null)
    }

    fun dispatchNormalizedLongPress(
        normX: Float,
        normY: Float,
        durationMs: Long = 550L
    ): Boolean {
        val (w, h) = getDisplayBounds()
        if (w <= 0 || h <= 0) return false
        val x = (normX.coerceIn(0.01f, 0.99f) * w)
        val y = (normY.coerceIn(0.01f, 0.99f) * h)

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(350L, 1800L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        Log.d(TAG, "dispatchLongPress (${x.toInt()}, ${y.toInt()}) ${durationMs}ms")
        return dispatchGesture(gesture, null, null)
    }

    fun dispatchNormalizedSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 220L
    ): Boolean {
        val (w, h) = getDisplayBounds()
        if (w <= 0 || h <= 0) return false
        val sx = x1.coerceIn(0.01f, 0.99f) * w
        val sy = y1.coerceIn(0.01f, 0.99f) * h
        val ex = x2.coerceIn(0.01f, 0.99f) * w
        val ey = y2.coerceIn(0.01f, 0.99f) * h

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(60L, 1000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        Log.d(TAG, "dispatchSwipe (${sx.toInt()},${sy.toInt()})->(${ex.toInt()},${ey.toInt()})")
        return dispatchGesture(gesture, null, null)
    }

    fun performRemoteNav(action: String): Boolean {
        return when (action.uppercase()) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> false
        }
    }

    private fun activeAppRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        return windows.firstOrNull { it.isActive && it.root != null }?.root
    }

    private fun findBestNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++
            if (predicate(node)) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun getDisplayBounds(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.maximumWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val dm = resources.displayMetrics
            android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return bounds.width() to bounds.height()
    }

    companion object {
        private const val TAG = "NewCastAccShutter"

        @Volatile
        var instance: RemoteShutterAccessibilityService? = null
            private set

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val SHUTTER_VIEW_ID_KEYWORDS = listOf(
            "shutter_button",
            "camera_shutter",
            "video_shutter",
            "capture_button",
            "record_button"
        )

        private val SHUTTER_DESC_KEYWORDS = listOf(
            "take photo",
            "take picture",
            "start video",
            "stop video",
            "start recording",
            "stop recording",
            "shutter",
            "chụp ảnh",
            "chụp hình",
            "bắt đầu quay",
            "dừng quay",
            "ngừng quay",
            "quay video"
        )

        fun isEnabledInSettings(context: Context): Boolean {
            if (_isRunning.value && instance != null) return true
            return try {
                val expected = ComponentName(context, RemoteShutterAccessibilityService::class.java)
                    .flattenToString()
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                enabled.split(':').any { it.equals(expected, ignoreCase = true) }
            } catch (_: Exception) {
                false
            }
        }
    }
}
