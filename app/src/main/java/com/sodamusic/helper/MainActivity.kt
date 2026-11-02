package com.sodamusic.helper

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.CompoundButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.sodamusic.helper.accessibility.SodaAccessibilityService
import com.sodamusic.helper.automation.AutomationRuntime
import com.sodamusic.helper.automation.AutomationUiState
import com.sodamusic.helper.automation.EnginePhase
import com.sodamusic.helper.automation.LogEntry
import com.sodamusic.helper.databinding.ActivityMainBinding
import com.sodamusic.helper.databinding.DialogAdvancedSettingsBinding
import com.sodamusic.helper.ui.LogAdapter
import com.sodamusic.helper.ui.PhaseTextFormatter
import com.sodamusic.helper.ui.SettingsForm
import com.sodamusic.helper.ui.floating.FloatingPanelController
import com.sodamusic.helper.ui.isVisible
import com.sodamusic.helper.ui.setTextRes
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import kotlinx.coroutines.launch

/**
 * 首页：Material 3 纯 View + ViewBinding，只消费 [AutomationRuntime] 的公开签名
 * （state / logs / start / stop / settings / updateSettings），不直接触碰引擎内部流程。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logAdapter = LogAdapter()
    private var lastTopLogId = 0L

    /** 悬浮窗开关：开 → 校验无障碍服务后挂窗口；关 → 移除。 */
    private val floatingSwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (isChecked) enableFloatingWindow() else disableFloatingWindow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSystemBars()
        setupHeroCard()
        setupLogList()
        setupClicks()
        observeRuntime()

        refreshServiceStatus()
        renderState(AutomationRuntime.state.value)
        renderLogs(AutomationRuntime.logs.value)
        syncFloatingWindowWithSettings()
    }

    override fun onResume() {
        super.onResume()
        // 先声明"Activity 在前台"，避免引擎状态回调把悬浮窗放出来挡住首页
        FloatingPanelController.setActivityResumed(true)
        refreshServiceStatus()
        syncFloatingWindowWithSettings()
        FloatingPanelController.setVisible(false)
    }

    override fun onPause() {
        super.onPause()
        FloatingPanelController.setActivityResumed(false)
        // 离开 App 才把悬浮窗放出来；不写持久化设置，已挂载时不会重复 add
        if (AutomationRuntime.settings().floatingWindowEnabled && AssistsService.getOrNull() != null) {
            if (!FloatingPanelController.isShowing()) FloatingPanelController.attach(this)
            FloatingPanelController.setVisible(true)
        }
    }

    /** 边到边：状态栏高度只加给 Toolbar，导航栏高度只加给滚动内容。 */
    private fun setupSystemBars() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            binding.toolbar.updatePadding(top = bars.top)
            binding.scroll.updatePadding(bottom = bars.bottom)
            insets
        }

        // 透明状态栏下，系统栏图标深浅跟随深/浅色主题取反
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
    }

    /** Hero 渐变：直接读品牌色资源（values / values-night 各一套），两种模式对比度都达标。 */
    private fun setupHeroCard() {
        binding.heroBody.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                ContextCompat.getColor(this, R.color.hero_gradient_start),
                ContextCompat.getColor(this, R.color.hero_gradient_end),
            ),
        ).apply {
            // 与 heroCard 的 28dp 圆角保持一致，避免外层裁切出现硬边
            cornerRadius = 28f * resources.displayMetrics.density
        }
    }

    private fun setupLogList() {
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logAdapter
    }

    private fun setupClicks() {
        binding.btnAdvanced.setOnClickListener { showAdvancedSettings() }
        binding.btnOpenAccessibility.setOnClickListener { AssistsCore.openAccessibilitySetting() }
        binding.btnRefreshStatus.setOnClickListener { refreshServiceStatus() }
        binding.switchFloatingWindow.setOnCheckedChangeListener(floatingSwitchListener)
        binding.btnClearLog.setOnClickListener { AutomationRuntime.clearLogs() }

        binding.btnStartStop.setOnClickListener {
            if (AutomationRuntime.isRunning()) {
                AutomationRuntime.stop()
                showSnack(R.string.toast_stopped)
            } else {
                startAutomation()
            }
        }

        // 优先用 assists 官方能力启动目标 App；返回 false 时用 packageManager 兜底（无障碍未连接也能打开）
        binding.btnOpenSoda.setOnClickListener {
            val targetPackage = AutomationRuntime.settings().targetPackage
            lifecycleScope.launch {
                val launched = runCatching { AssistsCore.launchApp(targetPackage) }.getOrDefault(false)
                if (!launched && !launchByPackageManager(targetPackage)) showSnack(R.string.toast_launch_failed)
            }
        }
    }

    // region 状态渲染

    private fun observeRuntime() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AutomationRuntime.state.collect(::renderState) }
                launch { AutomationRuntime.logs.collect(::renderLogs) }
            }
        }
    }

    private fun renderState(state: AutomationUiState) {
        binding.heroTitle.setTextRes(
            when {
                state.running -> R.string.hero_title_running
                state.phase in setOf(EnginePhase.STOPPED, EnginePhase.ERROR) -> R.string.hero_title_stopped
                else -> R.string.hero_title_idle
            },
        )
        // 阶段文案：与悬浮窗共用同一份映射，避免两处逻辑漂移
        binding.phaseDetailText.text = PhaseTextFormatter.format(this, state)
        binding.todayCountText.text = state.todayCount.toString()
        binding.todayMinutesText.text = getString(R.string.hero_minutes_value, state.todayMinutes)

        // 主按钮就是唯一的起停入口：运行中显示「停止自动领取」+ stop 图标
        binding.btnStartStop.setTextRes(
            if (state.running) R.string.action_stop_claim else R.string.action_start,
        )
        binding.btnStartStop.setIconResource(if (state.running) R.drawable.ic_stop else R.drawable.ic_play)

        // 广告等待进度：只有真正在看广告时才占位
        val watchingAd = state.phase == EnginePhase.WATCHING_AD && state.adMaxSeconds > 0
        binding.adProgress.isVisible = watchingAd
        if (watchingAd) binding.adProgress.setProgressCompat((state.adProgress * 100).toInt(), true)

        // 运行中禁用编辑类控件（高级设置入口）
        binding.btnAdvanced.isEnabled = !state.running
        binding.btnAdvanced.alpha = if (state.running) 0.38f else 1f
    }

    private fun renderLogs(logs: List<LogEntry>) {
        val hasLogs = logs.isNotEmpty()
        binding.rvLogs.isVisible = hasLogs
        binding.tvLogEmpty.isVisible = !hasLogs
        logAdapter.submitList(logs)

        // 日志是新→旧，新条目在最前，直接回到第 0 条
        val topLogId = logs.firstOrNull()?.id ?: 0L
        if (hasLogs && topLogId != lastTopLogId) binding.rvLogs.scrollToPosition(0)
        lastTopLogId = topLogId
    }

    // endregion

    // region 无障碍服务状态

    /** 无障碍是否在系统里启用（assists 官方判断）；是否真正连上另用 AssistsService.getOrNull()。 */
    private fun isAccessibilityEnabled(): Boolean =
        AssistsCore.isA11yEnabled(this, SodaAccessibilityService::class.java)

    private fun refreshServiceStatus() {
        val enabled = isAccessibilityEnabled()
        val connected = AssistsService.getOrNull() != null
        val chip = binding.serviceStatusChip

        chip.setTextRes(if (enabled) R.string.status_chip_enabled else R.string.status_chip_disabled)
        // 已启用且已连接=主色，已启用未连接=次色提示，未启用=错误色警示
        val (containerAttr, contentAttr) = when {
            !enabled -> MaterialR.attr.colorErrorContainer to MaterialR.attr.colorOnErrorContainer
            connected -> MaterialR.attr.colorPrimaryContainer to MaterialR.attr.colorOnPrimaryContainer
            else -> MaterialR.attr.colorSecondaryContainer to MaterialR.attr.colorOnSecondaryContainer
        }
        chip.chipBackgroundColor = ColorStateList.valueOf(MaterialColors.getColor(chip, containerAttr))
        chip.setTextColor(MaterialColors.getColor(chip, contentAttr))

        binding.serviceHintText.setTextRes(
            if (enabled) R.string.service_hint_ready else R.string.service_hint_disabled,
        )

        val foregroundPackage = runCatching { AssistsCore.getPackageName() }.getOrDefault("")
        binding.currentPackageText.text = when {
            !enabled -> getString(R.string.current_package_disconnected)
            foregroundPackage.isNotBlank() -> getString(R.string.current_package_value, foregroundPackage)
            else -> getString(R.string.current_package_value, getString(R.string.current_package_unknown))
        }
    }

    // endregion

    // region 交互动作

    /** 兜底启动：assists 的 launchApp 未成功时走系统 Intent（不依赖无障碍服务）。 */
    private fun launchByPackageManager(targetPackage: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(launchIntent) }.isSuccess
    }

    /** 主按钮点击：未运行时启动；无障碍未连接只提示，不做状态回弹。 */
    private fun startAutomation() {
        if (!isAccessibilityEnabled()) {
            showSnack(R.string.toast_need_accessibility)
            return
        }
        AutomationRuntime.start(this)
        // start() 无返回值：引擎在服务未连接时会立刻置 ERROR，这里以状态流为准，不盲目报"已开始"
        showSnack(if (AutomationRuntime.state.value.running) R.string.toast_started else R.string.toast_need_accessibility)
    }

    // region 悬浮窗控制

    /** 打开悬浮窗：assists 浮窗是 TYPE_ACCESSIBILITY_OVERLAY，不需要悬浮窗权限，但依赖无障碍服务。 */
    private fun enableFloatingWindow() {
        if (AssistsService.getOrNull() == null) {
            floatingUnavailable()
            return
        }
        FloatingPanelController.attach(this) { floatingUnavailable() }
        if (FloatingPanelController.isShowing()) {
            persistFloatingEnabled(true)
        } else {
            floatingUnavailable()
        }
    }

    /** 关闭悬浮窗：移除窗口并持久化开关。 */
    private fun disableFloatingWindow() {
        FloatingPanelController.detach()
        persistFloatingEnabled(false)
    }

    /** 开关/窗口/设置三者对齐：服务未连接就不留窗口，开关显示真实状态（不写盘）。 */
    private fun syncFloatingWindowWithSettings() {
        if (AssistsService.getOrNull() == null) {
            if (FloatingPanelController.isShowing()) FloatingPanelController.detach()
            setFloatingSwitchChecked(false)
            return
        }
        val enabled = AutomationRuntime.settings().floatingWindowEnabled
        setFloatingSwitchChecked(enabled)
        when {
            enabled && !FloatingPanelController.isShowing() -> {
                FloatingPanelController.attach(this)
                if (!FloatingPanelController.isShowing()) setFloatingSwitchChecked(false)
            }

            !enabled && FloatingPanelController.isShowing() -> FloatingPanelController.detach()
        }
    }

    private fun floatingUnavailable() {
        setFloatingSwitchChecked(false)
        persistFloatingEnabled(false)
        showSnack(R.string.toast_need_accessibility)
    }

    /** 开关回填：先断监听再写值，避免"程序改状态 → 又触发监听"的循环。 */
    private fun setFloatingSwitchChecked(checked: Boolean) {
        with(binding.switchFloatingWindow) {
            if (isChecked == checked) return
            setOnCheckedChangeListener(null)
            isChecked = checked
            setOnCheckedChangeListener(floatingSwitchListener)
        }
    }

    private fun persistFloatingEnabled(enabled: Boolean) {
        val current = AutomationRuntime.settings()
        if (current.floatingWindowEnabled == enabled) return
        AutomationRuntime.updateSettings(current.copy(floatingWindowEnabled = enabled))
    }

    // endregion

    // endregion

    private fun showAdvancedSettings() {
        val dialogBinding = DialogAdvancedSettingsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this).apply { setContentView(dialogBinding.root) }
        val current = AutomationRuntime.settings()
        SettingsForm.of(current, dialogBinding)

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnSave.setOnClickListener {
            // 5 个字段 → data class → 一次 copy 合并，其余字段（悬浮窗开关）保持原值
            AutomationRuntime.updateSettings(SettingsForm.read(dialogBinding, current).applyTo(current))
            syncFloatingWindowWithSettings()
            showSnack(R.string.settings_saved)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showSnack(messageRes: Int) {
        Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_SHORT).show()
    }
}
