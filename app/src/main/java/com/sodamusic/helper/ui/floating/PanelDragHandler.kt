package com.sodamusic.helper.ui.floating

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sodamusic.helper.R
import com.sodamusic.helper.databinding.FloatingMinimizedBinding
import com.sodamusic.helper.databinding.FloatingPanelBinding
import com.ven.assists.window.AssistsWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 系统栏高度（像素）。 */
internal data class Insets(val top: Int = 0, val bottom: Int = 0)

/**
 * 胶囊与圆点共用的手势层：
 * - 拖动（12dp 阈值、边界钳制、拖动中 alpha 0.85）
 * - 胶囊：本体重击 300ms 双击 → 最小化；按钮小位移算点击，超阈值转拖动
 * - 圆点：点按/长按 → 恢复；拖动结束才落盘位置
 *
 * 不持有窗口生命周期，只操作传入的 binding / LayoutParams，形态切换与落盘通过回调交给 controller。
 */
internal class PanelDragHandler(
    private val scopeProvider: () -> CoroutineScope?,
    private val onMinimize: () -> Unit,
    private val onRestore: () -> Unit,
    private val onPositionCommitted: (WindowManager.LayoutParams) -> Unit,
) {

    /** 最近一次读到的系统栏高度；controller 定位圆点时也要用。 */
    var insets: Insets = Insets()
        private set

    private var layoutJob: Job? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var dragging = false

    /** 上一次本体抬手时间（双击判定）；0 = 没有待配对的点击。 */
    private var lastTapUpTime = 0L

    private var longPressRunnable: Runnable? = null
    private var longPressHost: View? = null
    private var longPressFired = false

    /** 胶囊：本体拖动 + 双击最小化；按钮小位移算点击，超阈值转拖动。 */
    @SuppressLint("ClickableViewAccessibility")
    fun bindCapsule(binding: FloatingPanelBinding, lp: WindowManager.LayoutParams) {
        val threshold = binding.root.context.dp(PanelSpec.DRAG_THRESHOLD_DP)
        binding.root.setOnTouchListener { view, event ->
            onCapsuleTouch(view, binding.root, lp, threshold, isButton = false, event = event)
        }
        binding.floatingActionButton.setOnTouchListener { view, event ->
            onCapsuleTouch(view, binding.root, lp, threshold, isButton = true, event = event)
        }
    }

    /** 圆点：拖动挪位置；点按/长按恢复。 */
    @SuppressLint("ClickableViewAccessibility")
    fun bindMinimized(binding: FloatingMinimizedBinding, lp: WindowManager.LayoutParams) {
        val context = binding.root.context
        binding.root.contentDescription = context.getString(R.string.panel_minimized_desc)
        val threshold = context.dp(PanelSpec.DRAG_THRESHOLD_DP)
        binding.root.setOnTouchListener { view, event ->
            onMinimizedTouch(view, binding.root, lp, threshold, event)
        }
    }

    /** 取消长按与未完成的布局刷新（detach / 卸载时调用）。 */
    fun cancel() {
        cancelLongPress()
        layoutJob?.cancel()
        layoutJob = null
    }

    /** 读系统栏高度（旋转/手势导航切换后每次按下都刷新）。 */
    fun refreshInsets(view: View) {
        val bars = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ?: return
        insets = Insets(top = bars.top, bottom = bars.bottom)
    }

    /** 底部预留：优先真实 insets，退回系统 dimen，最后 0。 */
    fun bottomReserve(context: Context): Int =
        insets.bottom.takeIf { it > 0 } ?: navigationBarHeight(context)

    // region 胶囊

    private fun onCapsuleTouch(
        view: View,
        root: View,
        lp: WindowManager.LayoutParams,
        threshold: Int,
        isButton: Boolean,
        event: MotionEvent,
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(view, lp, event)
                // 按钮：不消费 DOWN，保留按下态与 click 能力；本体：直接消费
                return !isButton
            }

            MotionEvent.ACTION_MOVE -> {
                if (detectDrag(view, root, event, threshold, clearPressed = isButton)) {
                    dragTo(view, lp, event, capsule = true)
                    return true
                }
                return !isButton
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                finishGesture(root)
                if (isButton) return wasDragging
                if (wasDragging || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    lastTapUpTime = 0L
                    return true
                }
                handleDoubleTap()
                return true
            }

            else -> return false
        }
    }

    /** 300ms 内第二次抬手 → 最小化（两次点击都被吞掉，不会触发起停）。 */
    private fun handleDoubleTap() {
        val now = SystemClock.uptimeMillis()
        val isDoubleTap = lastTapUpTime != 0L && now - lastTapUpTime <= PanelSpec.DOUBLE_TAP_MS
        lastTapUpTime = if (isDoubleTap) 0L else now
        if (isDoubleTap) onMinimize()
    }

    // endregion

    // region 圆点

    private fun onMinimizedTouch(
        view: View,
        root: View,
        lp: WindowManager.LayoutParams,
        threshold: Int,
        event: MotionEvent,
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(view, lp, event)
                longPressFired = false
                scheduleLongPress(view)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (detectDrag(view, root, event, threshold, clearPressed = false)) {
                    cancelLongPress()
                    dragTo(view, lp, event, capsule = false)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                val wasDragging = dragging
                finishGesture(root)
                when {
                    event.actionMasked == MotionEvent.ACTION_CANCEL -> Unit
                    // 拖动结束：位置落盘 + 记一条日志（不是每帧）
                    wasDragging -> onPositionCommitted(lp)
                    !longPressFired -> onRestore()
                }
                return true
            }

            else -> return false
        }
    }

    /** 按住约 500ms 不动 = 长按恢复；拖动或抬手都会取消。 */
    private fun scheduleLongPress(view: View) {
        cancelLongPress()
        val runnable = Runnable {
            longPressRunnable = null
            if (!dragging) {
                longPressFired = true
                onRestore()
            }
        }
        longPressRunnable = runnable
        longPressHost = view
        view.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong() + 50L)
    }

    private fun cancelLongPress() {
        val runnable = longPressRunnable ?: return
        longPressRunnable = null
        longPressHost?.removeCallbacks(runnable)
        longPressHost = null
    }

    // endregion

    // region 手势公共部分

    private fun beginGesture(view: View, lp: WindowManager.LayoutParams, event: MotionEvent) {
        downRawX = event.rawX
        downRawY = event.rawY
        downWindowX = lp.x
        downWindowY = lp.y
        dragging = false
        refreshInsets(view)
    }

    /** 超过阈值就进入拖动（只标记一次），返回当前是否处于拖动中。 */
    private fun detectDrag(view: View, root: View, event: MotionEvent, threshold: Int, clearPressed: Boolean): Boolean {
        val moved = abs(event.rawX - downRawX) > threshold || abs(event.rawY - downRawY) > threshold
        if (!dragging && moved) {
            dragging = true
            if (clearPressed) view.isPressed = false
            root.alpha = PanelSpec.DRAG_ALPHA
        }
        return dragging
    }

    private fun dragTo(view: View, lp: WindowManager.LayoutParams, event: MotionEvent, capsule: Boolean) {
        val widthDp = if (capsule) PanelSpec.CAPSULE_WIDTH_DP else PanelSpec.DOT_TOUCH_DP
        val heightDp = if (capsule) PanelSpec.CAPSULE_HEIGHT_DP else PanelSpec.DOT_TOUCH_DP
        val (maxX, maxY) = bounds(view, widthDp, heightDp)
        lp.x = (downWindowX + (event.rawX - downRawX).toInt()).coerceIn(0, maxX)
        lp.y = (downWindowY + (event.rawY - downRawY).toInt()).coerceIn(insets.top, maxY)
        move(view, lp)
    }

    private fun finishGesture(root: View) {
        dragging = false
        root.alpha = 1f
    }

    /** 合法范围：x∈[0, 屏宽-w]，y∈[状态栏, 屏高-底栏-h]。 */
    private fun bounds(view: View, widthDp: Int, heightDp: Int): Pair<Int, Int> {
        val metrics = view.resources.displayMetrics
        val maxX = (metrics.widthPixels - view.context.dp(widthDp)).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - view.context.dp(heightDp) - bottomReserve(view.context))
            .coerceAtLeast(insets.top)
        return maxX to maxY
    }

    /** updateViewLayout 是 suspend：放到 controller 的 Main 协程里，且只保留最新一次。 */
    private fun move(view: View, lp: WindowManager.LayoutParams) {
        val scope = scopeProvider() ?: return
        layoutJob?.cancel()
        layoutJob = scope.launch { runCatching { AssistsWindowManager.updateViewLayout(view, lp) } }
    }

    private fun navigationBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id <= 0) 0 else runCatching { context.resources.getDimensionPixelSize(id) }.getOrDefault(0)
    }

    // endregion
}
