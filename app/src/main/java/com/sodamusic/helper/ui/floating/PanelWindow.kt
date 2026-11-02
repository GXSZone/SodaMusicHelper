package com.sodamusic.helper.ui.floating

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.sodamusic.helper.automation.AutomationRuntime
import com.sodamusic.helper.automation.LogLevel
import com.sodamusic.helper.databinding.FloatingMinimizedBinding
import com.sodamusic.helper.databinding.FloatingPanelBinding
import com.sodamusic.helper.settings.AppPreferences
import com.ven.assists.window.AssistsWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 两种形态的窗口与其布局参数（胶囊 52x104dp / 圆点 44dp 热区）：
 * 负责把视图交给 assists 挂上/摘掉、显示时强制恢复可见（assists 隐藏只是设 GONE）、
 * 以及圆点位置的记忆与校正。
 */
internal class PanelWindow(
    private val context: Context,
    private val scopeProvider: () -> CoroutineScope?,
    private val drag: PanelDragHandler,
) {

    val capsule: FloatingPanelBinding = FloatingPanelBinding.inflate(LayoutInflater.from(context))
    val minimized: FloatingMinimizedBinding = FloatingMinimizedBinding.inflate(LayoutInflater.from(context))

    private val capsuleParams = createCapsuleParams()
    private val minimizedParams = createMinimizedParams()

    /** 当前形态的窗口是否真的挂在 assists 上（App 前台时会被摘掉）。 */
    var attached = false
        private set

    init {
        PanelAppearance.styleCapsule(context, capsule)
        PanelAppearance.styleMinimized(context, minimized)
    }

    fun view(mode: PanelMode): View = when (mode) {
        PanelMode.Capsule -> capsule.root
        PanelMode.Minimized -> minimized.root
    }

    fun params(mode: PanelMode): WindowManager.LayoutParams = when (mode) {
        PanelMode.Capsule -> capsuleParams
        PanelMode.Minimized -> minimizedParams
    }

    fun add(mode: PanelMode): Boolean {
        val view = view(mode)
        val uniqueId = when (mode) {
            PanelMode.Capsule -> CAPSULE_ID
            PanelMode.Minimized -> MINIMIZED_ID
        }
        val added = runCatching {
            AssistsWindowManager.add(view, params(mode), isStack = false, isTouchable = true, uniqueId = uniqueId)
        }.getOrNull()
        attached = added != null
        if (attached) {
            // assists 在「同 uniqueId 已有旧窗口」时会把旧 view 设成 GONE，这里显式拉回来
            view.isVisibleCompat()
        }
        return attached
    }

    fun remove(mode: PanelMode) {
        if (!attached) return
        runCatching { AssistsWindowManager.removeWindow(view(mode)) }
        attached = false
    }

    /** 显示：窗口还在就恢复可见（必要时刷新布局），状态漂移则移除重加。 */
    fun show(mode: PanelMode): Boolean {
        val view = view(mode)
        val lp = params(mode)
        val inWindow = runCatching { AssistsWindowManager.contains(view) }.getOrDefault(false)
        if (!inWindow) return add(mode)

        attached = true
        view.isVisibleCompat()
        if (view.isAttachedToWindow) {
            move(view, lp)
        } else {
            removeCached(view)
            if (!add(mode)) return false
        }
        return true
    }

    /** 强制可见 + 不透明（assists 隐藏窗口时是设 GONE，不会自动恢复）。 */
    fun ensureVisible(mode: PanelMode) {
        view(mode).isVisibleCompat()
    }

    fun move(view: View, lp: WindowManager.LayoutParams) {
        val scope = scopeProvider() ?: return
        scope.launch { runCatching { AssistsWindowManager.updateViewLayout(view, lp) } }
    }

    /** 圆点位置：有保存坐标就按当前屏幕重新钳制，否则底部居中（等一帧拿到真实 insets）。 */
    fun refineMinimizedPosition() {
        minimized.root.post {
            val metrics = minimized.root.resources.displayMetrics
            val touch = context.dp(PanelSpec.DOT_TOUCH_DP)
            drag.refreshInsets(minimized.root)
            val top = drag.insets.top
            val bottom = drag.bottomReserve(context)
            val maxX = (metrics.widthPixels - touch).coerceAtLeast(0)
            val maxY = (metrics.heightPixels - touch - bottom).coerceAtLeast(top)
            val prefs = prefs()
            val savedX = prefs.readFloatingMinimizedX()
            val savedY = prefs.readFloatingMinimizedY()
            if (savedX >= 0 && savedY >= 0) {
                minimizedParams.x = savedX.coerceIn(0, maxX)
                minimizedParams.y = savedY.coerceIn(top, maxY)
            } else {
                minimizedParams.x = ((metrics.widthPixels - touch) / 2).coerceAtLeast(0)
                minimizedParams.y = (metrics.heightPixels - bottom - context.dp(PanelSpec.DOT_BOTTOM_GAP_DP))
                    .coerceIn(top, maxY)
            }
            move(minimized.root, minimizedParams)
        }
    }

    /** 拖动结束：坐标落盘 + 记一条日志（不是每帧）。 */
    fun commitMinimizedPosition(lp: WindowManager.LayoutParams) {
        runCatching {
            prefs().apply {
                writeFloatingMinimizedX(lp.x)
                writeFloatingMinimizedY(lp.y)
            }
        }
        AutomationRuntime.log(LogLevel.ACTION, "悬浮窗：圆点已移动到 (" + lp.x + ", " + lp.y + ")")
    }

    private fun removeCached(view: View) {
        runCatching { AssistsWindowManager.removeWindow(view) }
        attached = false
    }

    private fun createCapsuleParams(): WindowManager.LayoutParams =
        AssistsWindowManager.createLayoutParams().apply {
            width = context.dp(PanelSpec.CAPSULE_WIDTH_DP)
            height = context.dp(PanelSpec.CAPSULE_HEIGHT_DP)
            gravity = Gravity.TOP or Gravity.START
            x = context.dp(PanelSpec.CAPSULE_START_X_DP)
            y = (context.resources.displayMetrics.heightPixels * PanelSpec.CAPSULE_START_Y_RATIO).toInt()
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }

    private fun createMinimizedParams(): WindowManager.LayoutParams {
        val touch = context.dp(PanelSpec.DOT_TOUCH_DP)
        val metrics = context.resources.displayMetrics
        val bottom = drag.bottomReserve(context)
        val maxX = (metrics.widthPixels - touch).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - touch - bottom).coerceAtLeast(0)
        val prefs = prefs()
        val savedX = prefs.readFloatingMinimizedX()
        val savedY = prefs.readFloatingMinimizedY()
        return AssistsWindowManager.createLayoutParams().apply {
            width = touch
            height = touch
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX >= 0) savedX.coerceIn(0, maxX) else ((metrics.widthPixels - touch) / 2).coerceAtLeast(0)
            y = if (savedY >= 0) {
                savedY.coerceIn(0, maxY)
            } else {
                (metrics.heightPixels - bottom - context.dp(PanelSpec.DOT_BOTTOM_GAP_DP)).coerceAtLeast(0)
            }
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }

    private fun prefs() = AppPreferences(context)

    private companion object {
        const val CAPSULE_ID = "soda_panel"
        const val MINIMIZED_ID = "soda_panel_min"
    }
}

/** 恢复可见 + 重置透明度（不引入额外 import 的小工具方法）。 */
private fun View.isVisibleCompat() {
    visibility = View.VISIBLE
    alpha = 1f
}
