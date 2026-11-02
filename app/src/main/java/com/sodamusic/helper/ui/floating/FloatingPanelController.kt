package com.sodamusic.helper.ui.floating

import android.content.Context
import com.sodamusic.helper.automation.AutomationRuntime
import com.sodamusic.helper.automation.AutomationUiState
import com.sodamusic.helper.automation.LogLevel
import com.sodamusic.helper.settings.AppPreferences
import com.sodamusic.helper.ui.isVisible
import com.ven.assists.service.AssistsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮窗的生命周期与形态切换（窗口由 assists 的 AssistsWindowManager 承载）。
 *
 * 分工：[PanelAppearance] 决定长什么样，[PanelDragHandler] 处理手势，[PanelWindow] 负责挂载/摘掉与位置记忆，
 * 这里只负责「什么时候创建、显示、隐藏、切换形态」以及状态渲染与持久化标记。
 *
 * 为什么不用 AssistsWindowWrapper / WindowMinimizeManager：前者会套自带容器把胶囊撑大、拖动热区在它自己的按钮上；
 * 后者是 assists 给自家窗口做最小化的内部工具（固定图标、默认左上角、无点击回调、hide 只设 GONE 留透明死区）。
 */
object FloatingPanelController {

    private var appContext: Context? = null
    private var mode: PanelMode = PanelMode.Capsule

    private var window: PanelWindow? = null
    private var drag: PanelDragHandler? = null
    private var breath: BreathingAnimator? = null
    private var scope: CoroutineScope? = null

    /** MainActivity 是否在前台；决定「引擎在后台跑起来」时要不要自动显示。 */
    private var activityResumed = false

    fun isShowing(): Boolean = window != null

    fun isMinimized(): Boolean = window != null && mode is PanelMode.Minimized

    /** MainActivity 的 onResume/onPause 调这里；只记录状态，显隐仍由 Activity 显式调 [setVisible]。 */
    fun setActivityResumed(resumed: Boolean) {
        activityResumed = resumed
    }

    /** 进程刚起、用户还没打开过 App（如 debug 广播直启引擎）时调一次：设置开着且服务已连接就挂上窗口。 */
    fun ensureAttached(context: Context) {
        if (!AutomationRuntime.settings().floatingWindowEnabled) return
        if (AssistsService.getOrNull() == null) return
        attach(context.applicationContext)
    }

    /** 创建窗口并挂上；无障碍服务未连接时回调 [onUnavailable] 让调用方提示。必须在主线程调用。 */
    fun attach(context: Context, onUnavailable: (() -> Unit)? = null) {
        if (isShowing()) {
            setVisible(true)
            return
        }
        val ctx = context.applicationContext
        if (AssistsService.getOrNull() == null) {
            onUnavailable?.invoke()
            return
        }

        val handler = PanelDragHandler(
            scopeProvider = { scope },
            onMinimize = ::minimize,
            onRestore = ::restore,
            onPositionCommitted = { window?.commitMinimizedPosition(it) },
        )
        val panelWindow = PanelWindow(ctx, { scope }, handler)
        handler.bindCapsule(panelWindow.capsule, panelWindow.params(PanelMode.Capsule))
        handler.bindMinimized(panelWindow.minimized, panelWindow.params(PanelMode.Minimized))

        appContext = ctx
        window = panelWindow
        drag = handler
        breath = BreathingAnimator(panelWindow.capsule.floatingStatusHalo)
        mode = if (prefs(ctx).readFloatingMinimized()) PanelMode.Minimized else PanelMode.Capsule
        setupActionListener(ctx, panelWindow)

        if (!panelWindow.add(mode)) {
            clear()
            onUnavailable?.invoke()
            return
        }
        applyState()

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { started ->
            started.launch {
                AutomationRuntime.state.collect { state ->
                    render(state)
                    autoShowIfRunning(state)
                }
            }
        }
    }

    /** 彻底移除窗口并停止收集状态；重复调用安全。 */
    fun detach() {
        drag?.cancel()
        drag = null
        breath?.cancel()
        breath = null
        scope?.cancel()
        scope = null
        window?.remove(mode)
        clear()
        appContext = null
    }

    /**
     * App 前台隐藏 / 切后台显示（按当前形态 add/remove）。
     * 注意 assists 隐藏窗口只是把 View 设成 GONE，所以「显示」必须每次把 visibility 拉回 VISIBLE。
     */
    fun setVisible(show: Boolean) {
        val panelWindow = window ?: return
        if (!show) {
            panelWindow.remove(mode)
            return
        }
        if (!panelWindow.show(mode)) return
        applyState()
        val view = panelWindow.view(mode)
        AutomationRuntime.log(
            LogLevel.INFO,
            "悬浮窗：显示（形态=" + mode.label + "，visibility=" + view.visibility + "）",
        )
    }

    // region 形态切换

    private fun minimize() {
        if (mode is PanelMode.Minimized) return
        switchMode(PanelMode.Minimized, "悬浮窗：已最小化为圆点")
    }

    private fun restore() {
        if (mode !is PanelMode.Minimized) return
        switchMode(PanelMode.Capsule, "悬浮窗：已恢复胶囊")
    }

    private fun switchMode(target: PanelMode, logMessage: String) {
        val panelWindow = window ?: return
        val ctx = appContext ?: return
        panelWindow.remove(mode)
        mode = target
        prefs(ctx).writeFloatingMinimized(target is PanelMode.Minimized)
        if (panelWindow.add(mode)) applyState()
        AutomationRuntime.log(LogLevel.ACTION, logMessage)
    }

    // endregion

    // region 渲染

    private fun applyState() {
        val state = AutomationRuntime.state.value
        window?.ensureVisible(mode)
        when (mode) {
            PanelMode.Capsule -> renderCapsule(state)
            PanelMode.Minimized -> window?.let {
                PanelAppearance.renderMinimized(it.minimized, state.running)
                it.refineMinimizedPosition()
            }
        }
    }

    private fun render(state: AutomationUiState) {
        when (mode) {
            PanelMode.Capsule -> renderCapsule(state)
            PanelMode.Minimized -> window?.let { PanelAppearance.renderMinimized(it.minimized, state.running) }
        }
    }

    private fun renderCapsule(state: AutomationUiState) {
        val panelWindow = window ?: return
        val animator = breath ?: return
        PanelAppearance.renderCapsule(panelWindow.capsule.root.context, panelWindow.capsule, state, animator)
    }

    /** 引擎在后台跑起来时自动显示；running==false 时保持现状，不主动隐藏。 */
    private fun autoShowIfRunning(state: AutomationUiState) {
        if (!state.running || activityResumed) return
        if (!AutomationRuntime.settings().floatingWindowEnabled) return
        val panelWindow = window ?: return
        if (panelWindow.attached && panelWindow.view(mode).isVisible) return
        setVisible(true)
    }

    // endregion

    private fun setupActionListener(context: Context, panelWindow: PanelWindow) {
        panelWindow.capsule.floatingActionButton.setOnClickListener {
            if (AutomationRuntime.isRunning()) AutomationRuntime.stop() else AutomationRuntime.start(context)
        }
    }

    private fun prefs(context: Context) = AppPreferences(context)

    private fun clear() {
        window = null
        mode = PanelMode.Capsule
    }
}
