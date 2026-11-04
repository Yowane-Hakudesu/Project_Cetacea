package com.whalechan.agent.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.whalechan.agent.core.WhaleLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * 无障碍驱动层。
 *
 * 职责：
 *  1. 用 getRootInActiveWindow() 抓节点树，转成 LLM 能读的紧凑文本（UiSnapshot）；
 *  2. 用 dispatchGesture 注入点击 / 长按 / 滑动 / 拖拽（坐标级）；
 *  3. 用 performAction(ACTION_CLICK / ACTION_SET_TEXT) 做节点级精确操作；
 *  4. 可选：takeScreenshot() 给视觉模型提供截图（API 30+，不需要 MediaProjection）。
 *
 * 所有操控都由本进程在设备上发起，不依赖 ADB、不依赖电脑端。
 */
class WhaleAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WhaleA11y"
        private const val MAX_NODES = 160
        private const val MAX_DEPTH = 30
        private const val TAP_DURATION_MS = 60L

        /** 冷启动默认等待上限；调用方可以传更长。 */
        const val DEFAULT_LAUNCH_TIMEOUT_MS = 5_000L

        /** 全局单例：Executor / Agent 通过它判断服务是否在线。 */
        @Volatile
        var instance: WhaleAccessibilityService? = null
            private set

        fun isReady(): Boolean = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastPackage: String? = null

    @Volatile
    private var lastEventAt: Long = 0L

    // ────────────────────────── 生命周期 ──────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        WhaleLog.i("无障碍服务已连接：canRetrieveWindowContent=${serviceInfo?.canRetrieveWindowContent}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        lastEventAt = System.currentTimeMillis()
        when (safeEvent.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                safeEvent.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { lastPackage = it }
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {
        // 系统要求实现；本服务不依赖中断回调
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        WhaleLog.w("无障碍服务被解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        captureExecutor.shutdownNow()
        WhaleLog.w("无障碍服务已销毁")
        super.onDestroy()
    }

    // ────────────────────────── 界面抓取 ──────────────────────────

    /**
     * 抓取当前活动窗口的节点树。
     * - 过滤不可见 / 纯容器节点，减少 token；
     * - 可点击但无文字的容器，用后代文字兜底当 label；
     * - 到 maxNodes 截止，并标记 truncated。
     */
    fun snapshot(maxNodes: Int = MAX_NODES): UiSnapshot {
        val (width, height) = screenSize()
        val root = runCatching { rootInActiveWindow }.getOrNull()
            ?: return UiSnapshot(
                packageName = lastPackage.orEmpty(),
                screenWidth = width,
                screenHeight = height,
                nodes = emptyList(),
                truncated = true,
                takenAt = System.currentTimeMillis()
            )

        val packageName = runCatching { root.packageName?.toString() }.getOrNull()
            .orEmpty()
            .ifBlank { lastPackage.orEmpty() }

        val nodes = ArrayList<UiNode>(maxNodes)
        var truncated = false

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)

        while (queue.isNotEmpty()) {
            if (nodes.size >= maxNodes) {
                truncated = true
                break
            }
            val (node, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) continue

            if (depth == 0 || safeVisible(node)) {
                toUiNode(node, nodes.size, depth)?.let { nodes.add(it) }
                val childCount = safeChildCount(node)
                for (index in 0 until childCount) {
                    runCatching { node.getChild(index) }.getOrNull()?.let { queue.addLast(it to depth + 1) }
                }
            }
        }

        return UiSnapshot(
            packageName = packageName,
            screenWidth = width,
            screenHeight = height,
            nodes = nodes,
            truncated = truncated,
            takenAt = System.currentTimeMillis()
        )
    }

    private fun toUiNode(node: AccessibilityNodeInfo, ref: Int, depth: Int): UiNode? {
        val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
        val desc = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }.getOrDefault("")
        val hint = runCatching { node.hintText?.toString()?.trim().orEmpty() }.getOrDefault("")
        val className = runCatching { node.className?.toString().orEmpty() }.getOrDefault("")

        val clickable = runCatching { node.isClickable }.getOrDefault(false)
        val longClickable = runCatching { node.isLongClickable }.getOrDefault(false)
        val editable = runCatching { node.isEditable }.getOrDefault(false) ||
            className.endsWith("EditText") || className.endsWith("AutoCompleteTextView")
        val scrollable = runCatching { node.isScrollable }.getOrDefault(false)
        val password = runCatching { node.isPassword }.getOrDefault(false)
        val enabled = runCatching { node.isEnabled }.getOrDefault(true)
        val checked = runCatching { node.isChecked }.getOrDefault(false)
        val focused = runCatching { node.isFocused }.getOrDefault(false)

        val interactive = clickable || longClickable || editable || scrollable || checked

        var label = text.ifBlank { desc }.ifBlank { hint }
        if (label.isBlank() && (clickable || longClickable)) {
            label = firstDescendantText(node, 3)
        }

        // 既没文字、也不能交互的节点对 LLM 毫无价值
        if (label.isBlank() && !interactive) return null

        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        if (rect.isEmpty && !interactive) return null

        return UiNode(
            ref = ref,
            text = text,
            desc = desc,
            hint = hint,
            label = label.take(120),
            className = className,
            clickable = clickable,
            longClickable = longClickable,
            editable = editable,
            scrollable = scrollable,
            password = password,
            enabled = enabled,
            checked = checked,
            focused = focused,
            bounds = rect,
            depth = depth
        )
    }

    private fun firstDescendantText(node: AccessibilityNodeInfo, remainingDepth: Int): String {
        if (remainingDepth < 0) return ""
        val count = safeChildCount(node)
        for (index in 0 until count) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            val text = runCatching { child.text?.toString()?.trim().orEmpty() }.getOrDefault("")
            if (text.isNotEmpty()) return text
            val desc = runCatching { child.contentDescription?.toString()?.trim().orEmpty() }.getOrDefault("")
            if (desc.isNotEmpty()) return desc
            val nested = firstDescendantText(child, remainingDepth - 1)
            if (nested.isNotEmpty()) return nested
        }
        return ""
    }

    private fun safeVisible(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.isVisibleToUser }.getOrDefault(true)

    private fun safeChildCount(node: AccessibilityNodeInfo): Int =
        runCatching { node.childCount }.getOrDefault(0)

    private fun screenSize(): Pair<Int, Int> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = getSystemService(WindowManager::class.java)
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }
    } catch (t: Throwable) {
        val metrics = resources.displayMetrics
        metrics.widthPixels to metrics.heightPixels
    }

    fun currentPackage(): String = runCatching { rootInActiveWindow?.packageName?.toString() }
        .getOrNull()
        .orEmpty()
        .ifBlank { lastPackage.orEmpty() }

    /**
     * 等待目标应用真正进入前台。
     *
     * `startActivity()` 是异步的：调用返回时新应用还没渲染，`rootInActiveWindow`
     * 仍然指向上一个应用（或 null/白屏）。这里轮询两个信号：
     *  1. `rootInActiveWindow.packageName` 已经是目标包名；
     *  2. `TYPE_WINDOW_STATE_CHANGED` 事件记录到的 `lastPackage` 已经是目标包名。
     *
     * 只有根节点存在且包名匹配才算“到了”；随后调用方还应再 delay 一段时间等开屏/骨架屏。
     *
     * @return true = 在超时前进入目标应用；false = 超时
     */
    suspend fun awaitAppLaunch(packageName: String, timeoutMs: Long = DEFAULT_LAUNCH_TIMEOUT_MS): Boolean {
        if (packageName.isBlank()) return false
        val deadline = SystemClock.uptimeMillis() + timeoutMs.coerceAtLeast(200L)
        while (SystemClock.uptimeMillis() < deadline) {
            val root = runCatching { rootInActiveWindow }.getOrNull()
            val rootPackage = runCatching { root?.packageName?.toString() }.getOrNull()
            if (root != null && rootPackage == packageName) return true
            // 根节点还不可用时，事件里的包名也能说明窗口已经切过去了，再等半轮
            if (rootPackage == null && lastPackage == packageName) {
                delay(120)
                val retryRoot = runCatching { rootInActiveWindow }.getOrNull()
                if (retryRoot != null &&
                    runCatching { retryRoot.packageName?.toString() }.getOrNull() == packageName
                ) {
                    return true
                }
            }
            delay(100)
        }
        return false
    }

    /** 当前窗口的节点数，用于诊断“白屏/空树”。 */
    fun currentNodeCount(): Int {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return 0
        var count = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && count < 400) {
            val node = queue.removeFirst()
            count++
            for (index in 0 until safeChildCount(node)) {
                runCatching { node.getChild(index) }.getOrNull()?.let { queue.addLast(it) }
            }
        }
        return count
    }

    // ────────────────────────── 手势注入 ──────────────────────────

    private fun dispatchPath(
        path: Path,
        durationMs: Long,
        onResult: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (path.isEmpty) return false
        return try {
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(16L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        onResult?.invoke(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        onResult?.invoke(false)
                    }
                },
                mainHandler
            )
        } catch (t: Throwable) {
            WhaleLog.w("dispatchGesture 失败", t)
            onResult?.invoke(false)
            false
        }
    }

    fun tapAt(x: Float, y: Float): Boolean =
        dispatchPath(Path().apply { moveTo(x, y) }, TAP_DURATION_MS)

    fun longPressAt(x: Float, y: Float, durationMs: Long = 650L): Boolean =
        dispatchPath(Path().apply { moveTo(x, y) }, durationMs)

    /**
     * 滑动。start/end 都留了边距：
     *  - 底部 80px 是导航手势区，从那里起手会被系统当成返回/回桌面；
     *  - 顶部 60px 是状态栏，下拉会拉出通知面板。
     */
    fun swipe(direction: String, distanceRatio: Float = 0.6f, durationMs: Long = 280L): Boolean {
        val (width, height) = screenSize()
        if (width <= 0 || height <= 0) return false

        val ratio = distanceRatio.coerceIn(0.2f, 0.9f)
        val centerX = width / 2f
        val centerY = height / 2f
        val verticalSpan = height * ratio * 0.8f
        val horizontalSpan = width * ratio * 0.8f

        val points = when (direction) {
            "up" -> floatArrayOf(centerX, centerY + verticalSpan / 2, centerX, centerY - verticalSpan / 2)
            "down" -> floatArrayOf(centerX, centerY - verticalSpan / 2, centerX, centerY + verticalSpan / 2)
            "left" -> floatArrayOf(centerX + horizontalSpan / 2, centerY, centerX - horizontalSpan / 2, centerY)
            else -> floatArrayOf(centerX - horizontalSpan / 2, centerY, centerX + horizontalSpan / 2, centerY)
        }

        val startX = points[0].coerceIn(8f, width - 8f)
        val startY = points[1].coerceIn(60f, height - 80f)
        val endX = points[2].coerceIn(8f, width - 8f)
        val endY = points[3].coerceIn(60f, height - 80f)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return dispatchPath(path, durationMs)
    }

    fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 600L): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return dispatchPath(path, durationMs)
    }

    // ────────────────────────── 节点查找与操作 ──────────────────────────

    fun findNodesByText(text: String, exact: Boolean = false): List<AccessibilityNodeInfo> {
        if (text.isBlank()) return emptyList()
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()

        val found = LinkedHashSet<AccessibilityNodeInfo>()
        runCatching { root.findAccessibilityNodeInfosByText(text) }
            .getOrNull()
            ?.forEach { node -> if (matchesText(node, text, exact)) found.add(node) }

        // findAccessibilityNodeInfosByText 不保证覆盖 contentDescription，补一次有界扫描
        if (found.isEmpty()) scanForText(root, text, exact, found, 0, intArrayOf(0))
        return found.toList()
    }

    private fun matchesText(node: AccessibilityNodeInfo, text: String, exact: Boolean): Boolean {
        val nodeText = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
        val nodeDesc = runCatching { node.contentDescription?.toString().orEmpty() }.getOrDefault("")
        return if (exact) {
            nodeText.equals(text, ignoreCase = true) || nodeDesc.equals(text, ignoreCase = true)
        } else {
            nodeText.contains(text, ignoreCase = true) || nodeDesc.contains(text, ignoreCase = true)
        }
    }

    private fun scanForText(
        node: AccessibilityNodeInfo,
        text: String,
        exact: Boolean,
        out: MutableSet<AccessibilityNodeInfo>,
        depth: Int,
        visited: IntArray
    ) {
        if (depth > 25 || visited[0] > 500) return
        visited[0]++
        if (matchesText(node, text, exact)) out.add(node)
        val count = safeChildCount(node)
        for (index in 0 until count) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            scanForText(child, text, exact, out, depth + 1, visited)
        }
    }

    /**
     * 点击节点：先向上找最近的可点击祖先执行 ACTION_CLICK，
     * 找不到就按节点 bounds 的中心做手势点击兜底。
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (hops < 6) {
            val target = current ?: break
            val clickable = runCatching { target.isClickable }.getOrDefault(false)
            val enabled = runCatching { target.isEnabled }.getOrDefault(true)
            if (clickable && enabled) {
                val clicked = runCatching {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (clicked) return true
            }
            current = runCatching { target.parent }.getOrNull()
            hops++
        }

        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        return if (!rect.isEmpty) tapAt(rect.exactCenterX(), rect.exactCenterY()) else false
    }

    fun tapByText(text: String, exact: Boolean = false): Boolean {
        val candidates = findNodesByText(text, exact)
        for (node in candidates) {
            if (clickNode(node)) return true
        }
        val first = candidates.firstOrNull() ?: return false
        val rect = Rect()
        runCatching { first.getBoundsInScreen(rect) }
        return if (!rect.isEmpty) tapAt(rect.exactCenterX(), rect.exactCenterY()) else false
    }

    /**
     * 按快照里的节点点击：先用 label 在当前树里重新找（界面可能已轻微变化），
     * 找不到再退回快照记录的坐标。
     */
    fun tapSnapshotNode(node: UiNode, exactText: Boolean = false): Boolean {
        if (node.label.isNotBlank()) {
            val live = findNodesByText(node.label, exactText)
            for (candidate in live) {
                if (clickNode(candidate)) return true
            }
        }
        if (node.hasBounds) return tapAt(node.bounds.exactCenterX(), node.bounds.exactCenterY())
        return false
    }

    fun findEditableNode(preferBounds: Rect? = null): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val editables = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 500 && editables.size < 40) {
            val node = queue.removeFirst()
            visited++
            val className = runCatching { node.className?.toString().orEmpty() }.getOrDefault("")
            val isEditable = runCatching { node.isEditable }.getOrDefault(false) ||
                className.endsWith("EditText")
            if (isEditable && safeVisible(node)) editables.add(node)
            val count = safeChildCount(node)
            for (index in 0 until count) {
                runCatching { node.getChild(index) }.getOrNull()?.let { queue.addLast(it) }
            }
        }

        if (editables.isEmpty()) return null

        if (preferBounds != null && !preferBounds.isEmpty) {
            val hit = editables.firstOrNull { candidate ->
                val rect = Rect()
                runCatching { candidate.getBoundsInScreen(rect) }
                Rect.intersects(rect, preferBounds)
            }
            if (hit != null) return hit
        }

        return editables.firstOrNull { runCatching { it.isFocused }.getOrDefault(false) }
            ?: editables.first()
    }

    fun hasEditable(): Boolean = findEditableNode() != null

    /**
     * 写入文本：优先 ACTION_SET_TEXT；失败时退化为“点一下聚焦 + 剪贴板粘贴”。
     *
     * @param allowPassword 只有用户在设置里显式打开「允许代填凭据」时才为 true；
     *        默认这一层就拒绝密码框，不依赖上层策略。
     */
    fun setText(node: AccessibilityNodeInfo, value: String, allowPassword: Boolean = false): Boolean {
        val isPassword = runCatching { node.isPassword }.getOrDefault(false)
        if (isPassword && !allowPassword) {
            WhaleLog.w("拒绝向密码框写入文本（未开启代填凭据）")
            return false
        }
        if (isPassword) {
            WhaleLog.w("按用户设置向密码框写入文本")
        }

        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val set = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }.getOrDefault(false)
        if (set) return true

        return try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("whale", value))
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) tapAt(rect.exactCenterX(), rect.exactCenterY())
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (t: Throwable) {
            WhaleLog.w("文本输入回退失败", t)
            false
        }
    }

    // ────────────────────────── 全局动作 ──────────────────────────

    fun performBack(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)

    fun performHome(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)

    fun performRecents(): Boolean =
        runCatching { performGlobalAction(GLOBAL_ACTION_RECENTS) }.getOrDefault(false)

    // ────────────────────────── 可选：截图给视觉模型 ──────────────────────────

    /**
     * API 30+ 可直接调用 AccessibilityService.takeScreenshot()，不需要 MediaProjection 授权。
     * 需要 accessibility_service_config.xml 里声明 canTakeScreenshot="true"。
     * 返回 JPEG 的 Base64（最长边缩到 720px），失败返回 null。
     */
    suspend fun screenshotBase64OrNull(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            suspendCancellableCoroutine { continuation ->
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    captureExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val encoded = runCatching { encodeScreenshot(result) }.getOrNull()
                            if (continuation.isActive) continuation.resume(encoded)
                        }

                        override fun onFailure(errorCode: Int) {
                            WhaleLog.w("takeScreenshot 失败 code=$errorCode")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            WhaleLog.w("takeScreenshot 异常", t)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun encodeScreenshot(result: ScreenshotResult): String? {
        val buffer = result.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            hardware.recycle()
            val scaled = scaleDown(software, 720)
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, output)
            if (scaled !== software) scaled.recycle()
            software.recycle()
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            runCatching { buffer.close() }
        }
    }

    private fun scaleDown(source: Bitmap, targetWidth: Int): Bitmap {
        if (source.width <= targetWidth) return source
        val ratio = targetWidth.toFloat() / source.width
        val height = max(1, (source.height * ratio).toInt())
        return Bitmap.createScaledBitmap(source, targetWidth, height, true)
    }

    /** 最近一次无障碍事件时间，用来判断界面是否已经稳定。 */
    fun lastEventTimestamp(): Long = lastEventAt
}
