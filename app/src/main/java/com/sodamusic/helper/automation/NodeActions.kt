package com.sodamusic.helper.automation

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.ven.assists.AssistsCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds

/**
 * 安全点击与页面动作封装。
 *
 * 点击三级回退：ACTION_CLICK → 最近可点击祖先 → 坐标手势。
 * 所有节点访问都用 runCatching 兜底（StaleObjectException），
 * 所有 suspend 动作都直接受协程取消影响，stop 时立刻中断等待。
 */
class NodeActions(private val scanner: NodeScanner) {

    /**
     * 点击一个节点。返回是否「点击动作已成功发出」。
     * 点击前重新校验可见性，避免点到已消失（离屏/折叠）的按钮。
     */
    suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (!isVisible(node)) return false
        // 一级：节点自身支持 ACTION_CLICK。
        if (runCatching { with(AssistsCore) { node.click() } }.getOrDefault(false)) {
            delay(CLICK_SETTLE_MS.milliseconds)
            return true
        }
        // 二级：向上找最近的可点击容器（按钮文字常挂在不可点击的 TextView 上）。
        val parent = runCatching { with(AssistsCore) { node.findFirstParentClickable() } }.getOrNull()
        if (parent != null &&
            runCatching { with(AssistsCore) { parent.click() } }.getOrDefault(false)
        ) {
            delay(CLICK_SETTLE_MS.milliseconds)
            return true
        }
        // 三级：按节点中心点做坐标手势。
        val bounds = runCatching { with(AssistsCore) { node.getBoundsInScreen() } }.getOrNull()
            ?: return false
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val clicked = safeGestureClick(bounds.exactCenterX(), bounds.exactCenterY())
        if (clicked) delay(CLICK_SETTLE_MS.milliseconds)
        return clicked
    }

    /** 找到关键词对应的最佳节点并点击；界面可能正在渲染，因此最多重试两次。 */
    suspend fun clickFirst(keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        repeat(CLICK_ATTEMPTS) { attempt ->
            val hit = scanner.findBest(keywords) ?: return@repeat
            if (clickNode(hit.node)) return true
            if (attempt < CLICK_ATTEMPTS - 1) delay(RETRY_INTERVAL_MS.milliseconds)
        }
        return false
    }

    /** 按屏幕坐标做一次手势点击（OCR 命中、无文字图标等场景使用）。 */
    suspend fun tapAt(x: Float, y: Float): Boolean {
        if (x <= 0f || y <= 0f) return false
        val clicked = safeGestureClick(x, y)
        if (clicked) delay(TAP_SETTLE_MS.milliseconds)
        return clicked
    }

    /** 按屏幕比例坐标点击（无文字 Tab、无文本图标这类元素的兜底手段）。 */
    suspend fun tapRatio(xRatio: Float, yRatio: Float): Boolean {
        val width = appWidth()
        val height = appHeight()
        if (width <= 0 || height <= 0) return false
        // 比例 → 屏幕像素后，再交给 assists 的 getX/getY 做（设计基准 → 实际屏幕）换算。
        val rawX = (width * xRatio.coerceIn(0f, 1f)).toInt()
        val rawY = (height * yRatio.coerceIn(0f, 1f)).toInt()
        val x = runCatching { AssistsCore.getX(width, rawX) }.getOrDefault(rawX)
        val y = runCatching { AssistsCore.getY(height, rawY) }.getOrDefault(rawY)
        return tapAt(x.toFloat(), y.toFloat())
    }

    /**
     * 在屏幕比例矩形内找「最像按钮」的可点击节点。
     *
     * 用于发现页右上角那种无 text / 无 desc 的图标：按位置筛出候选，
     * 只保留自身或祖先可点击的，再取面积最小的（图标本身而不是整条顶部栏）。
     */
    fun findClickableInRegion(
        leftRatio: Float,
        topRatio: Float,
        rightRatio: Float,
        bottomRatio: Float,
    ): NodeHit? {
        val width = appWidth()
        val height = appHeight()
        if (width <= 0 || height <= 0) return null
        val left = (width * leftRatio.coerceIn(0f, 1f)).toInt()
        val top = (height * topRatio.coerceIn(0f, 1f)).toInt()
        val right = (width * rightRatio.coerceIn(0f, 1f)).toInt()
        val bottom = (height * bottomRatio.coerceIn(0f, 1f)).toInt()
        return scanner.findAllInBounds(left, top, right, bottom)
            .filter { isClickableOrInClickableParent(it) }
            .minByOrNull { it.area }
    }

    fun back(): Boolean = runCatching { AssistsCore.back() }.getOrDefault(false)

    /** 对当前窗口根节点做一次前向滚动（用于入口列表需要下滑的场景）。 */
    fun scrollForward(): Boolean {
        val root = runCatching { AssistsCore.getAccessibilityRootNodes() }
            .getOrNull()
            .orEmpty()
            .firstOrNull()
            ?: return false
        return runCatching { with(AssistsCore) { root.scrollForward() } }.getOrDefault(false)
    }

    /** 当前前台包名；服务未连接时返回空串。 */
    fun currentPackage(): String =
        runCatching { AssistsCore.getPackageName() }.getOrNull().orEmpty()

    /** 拉起指定包名，失败（未安装/无启动入口）返回 false。 */
    suspend fun launchPackage(packageName: String): Boolean {
        return packageName.isNotBlank() && try {
            AssistsCore.launchApp(packageName)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 用显式 Intent 拉起目标（例如汽水音乐首页 MainActivity）。
     *
     * 为什么不用 launchPackage(pkg)：launchApp(packageName) 只是把**已有 task** 提到前台，
     * 如果广告 Activity 就在同一个 task 顶部，会被原样拉回来，仍然停在广告页。
     */
    suspend fun launchIntent(intent: Intent): Boolean = try {
        AssistsCore.launchApp(intent)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        false
    }

    /** 在 [timeoutMs] 内等待任一关键词出现（只读判定，零尺寸节点也算，广告页文案常是 0 高度）。 */
    suspend fun awaitAny(
        keywords: List<String>,
        timeoutMs: Long,
        intervalMs: Long = DEFAULT_POLL_MS,
    ): Boolean {
        if (keywords.isEmpty()) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (currentCoroutineContext().isActive) {
            if (scanner.containsAnyText(keywords)) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(intervalMs.milliseconds)
        }
        return false
    }

    /** 在 [timeoutMs] 内等待关键词全部消失。 */
    suspend fun awaitGone(
        keywords: List<String>,
        timeoutMs: Long,
        intervalMs: Long = DEFAULT_POLL_MS,
    ): Boolean {
        if (keywords.isEmpty()) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (currentCoroutineContext().isActive) {
            if (!scanner.containsAnyText(keywords)) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(intervalMs.milliseconds)
        }
        return false
    }

    private fun isVisible(node: AccessibilityNodeInfo): Boolean =
        runCatching { with(AssistsCore) { node.isVisible(null, true) } }.getOrDefault(false)

    /** 自身可点击，或最近的祖先可点击（图标文字常挂在不可点击的子 View 上）。 */
    private fun isClickableOrInClickableParent(hit: NodeHit): Boolean = runCatching {
        hit.node.isClickable || with(AssistsCore) { hit.node.findFirstParentClickable() } != null
    }.getOrDefault(false)

    /** 屏幕（应用可见区）宽高，供模板定位等外部逻辑使用。 */
    fun screenWidth(): Int = appWidth()

    fun screenHeight(): Int = appHeight()

    /** 应用可见区域尺寸；直接取不到时退回 app bounds。 */
    private fun appWidth(): Int {
        val direct = runCatching { AssistsCore.getAppWidthInScreen() }.getOrDefault(0)
        if (direct > 0) return direct
        return runCatching { AssistsCore.getAppBoundsInScreen()?.width() ?: 0 }.getOrDefault(0)
    }

    private fun appHeight(): Int {
        val direct = runCatching { AssistsCore.getAppHeightInScreen() }.getOrDefault(0)
        if (direct > 0) return direct
        return runCatching { AssistsCore.getAppBoundsInScreen()?.height() ?: 0 }.getOrDefault(0)
    }

    private suspend fun safeGestureClick(x: Float, y: Float): Boolean = try {
        AssistsCore.gestureClick(x, y, GESTURE_DURATION_MS)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        false
    }

    private companion object {
        /** 点击后给界面一点反应时间（毫秒）。 */
        const val CLICK_SETTLE_MS = 350L
        const val RETRY_INTERVAL_MS = 400L
        const val CLICK_ATTEMPTS = 2
        const val GESTURE_DURATION_MS = 80L
        const val DEFAULT_POLL_MS = 400L

        /** 手势点击后等待界面响应（毫秒）。 */
        const val TAP_SETTLE_MS = 500L
    }
}
