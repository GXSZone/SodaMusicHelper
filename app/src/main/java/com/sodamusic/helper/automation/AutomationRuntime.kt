package com.sodamusic.helper.automation

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import com.sodamusic.helper.settings.AppPreferences
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import com.ven.assists.stepper2.Step
import com.ven.assists.window.AssistsWindowManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 自动化总门面：UI 只依赖本对象。
 *
 * 职责：持有运行时状态、日志、配置，并在后台协程中驱动 [AdClaimFlow]。
 * 所有耗时动作都在 [Dispatchers.Default] 上执行，主线程只做状态读取。
 */
object AutomationRuntime {

    private const val MAX_LOG_SIZE = 300

    /** logcat 标签：adb logcat -s SodaAuto 即可观察引擎状态推进。 */
    private const val LOG_TAG = "SodaAuto"

    /** 透明常亮层的窗口 tag。 */
    private const val KEEP_SCREEN_TAG = "sodaKeepScreenOn"

    /**
     * 无障碍服务断开后最多自动恢复的次数：超过就停止自动恢复，避免"断开—重连"死循环刷日志。
     */
    private const val MAX_RECOVERY_ATTEMPTS = 3

    private val _state = MutableStateFlow(AutomationUiState())
    val state: StateFlow<AutomationUiState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private var preferences: AppPreferences? = null

    /** 保存 applicationContext，供无障碍服务重连后自动恢复运行时创建流程。 */
    private var appContext: Context? = null

    /**
     * 用户意图运行标志：
     * - 用户点「开始」→ true；用户点「停止」→ false；
     * - 异常退出、无障碍服务断开 → 只清 job，**保留 true**，服务重连后自动恢复。
     */
    private val userIntent = AtomicBoolean(false)

    /**
     * 服务是否真正连上：onServiceConnected 置 true，onUnbind/onDestroy 置 false。
     * AssistsService.getOrNull() 在 onCreate 就非空、isA11yEnabled 只看系统设置，都不能代表「已连接」。
     */
    private val serviceConnected = AtomicBoolean(false)

    /** 本次会话里已经自动恢复过几次。 */
    private var recoveryAttempts = 0

    /** 日志自增 id；引擎在 Default、无障碍回调在主线程，必须原子自增。 */
    private val logSeed = AtomicLong(0L)

    /** 最近一次目标应用窗口切换的类名 / 时间（无障碍回调写、引擎读，跨线程用 @Volatile）。 */
    @Volatile
    private var lastWindowClassNameValue: String = ""

    @Volatile
    private var lastWindowChangedAtValue: Long = 0L

    /**
     * 运行令牌：每次启动、停止都自增。旧协程的 finally 只有令牌仍是自己时才写状态、释放常亮，
     * 避免「停止后立刻再开始」时旧 finally 把新一轮标成已停止。
     */
    private val runSeq = AtomicLong(0L)

    /** 当前 stepper2 作业 id（null = 没有在跑）。调度完全交给 stepper2 的 Step.launch / Step.stop。 */
    @Volatile
    private var stepJobId: String? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        this.appContext = appContext
        if (preferences != null) return
        preferences = AppPreferences(appContext).also { it.initialize() }
        val settings = currentSettings()
        updateState { copy(todayCount = statsCount(), todayMinutes = statsMinutes()) }
        log(LogLevel.INFO, "引擎就绪：目标应用 ${settings.targetPackage}，每日上限 ${settings.dailyLimit} 次")
    }

    fun settings(): AutomationSettings = preferences?.readSettings() ?: AutomationSettings()

    fun updateSettings(settings: AutomationSettings) {
        preferences?.writeSettings(settings)
        log(LogLevel.INFO, "设置已更新：每日上限 ${settings.dailyLimit} 次，广告等待 ${settings.adMaxWaitSeconds}s")
    }

    /** 用户点击「开始」：记录用户意图并把恢复计数清零，然后走内部启动。 */
    fun start(context: Context) {
        userIntent.set(true)
        recoveryAttempts = 0
        startInternal(context.applicationContext)
    }

    private fun startInternal(appContext: Context) {
        // 用 running + stepJobId 双去重：避免与用户刚点「开始」或自动恢复重复启动。
        if (_state.value.running || stepJobId != null) return
        if (!isServiceConnected()) {
            log(LogLevel.WARN, "无障碍服务未连接，无法开始")
            updateState {
                copy(running = false, phase = EnginePhase.ERROR, lastError = "无障碍服务未连接")
            }
            return
        }
        val snapshot = currentSettings()
        val myRun = runSeq.incrementAndGet()
        val jobId = "soda-engine-" + System.currentTimeMillis()
        stepJobId = jobId
        updateState {
            copy(
                running = true,
                phase = EnginePhase.LAUNCHING,
                phaseDetail = "启动汽水音乐…",
                todayCount = statsCount(),
                todayMinutes = statsMinutes(),
                adElapsedSeconds = 0,
                adMaxSeconds = snapshot.adMaxWaitSeconds,
                round = 0,
                lastError = null,
            )
        }
        log(LogLevel.INFO, "开始自动领取（每日上限 ${snapshot.dailyLimit} 次）")
        // 流程跑动期间不能息屏，否则节点会失效。
        // 注意：assists 内置的 keepScreenOn 会盖一层 50% 黑遮罩 + 中央红字提示，会把广告画面压暗；
        // 所以这里用 AssistsWindowManager 自己挂一个「透明 + FLAG_NOT_TOUCHABLE」的常亮层。
        acquireKeepScreenOn()
        // 调度交给 stepper2：Step.launch 内部在 IO 协程按「delay → impl → 下一步」驱动，
        // 停止用 Step.stop(jobId)，不再自己维护 CoroutineScope / Job。
        Step.showLog = false
        Step.launch(jobId) {
            var failure: Throwable? = null
            try {
                // 流程对象在后台线程创建，避免任何 SharedPreferences 访问落到主线程。
                AdClaimFlow(appContext, snapshot).runAsSteps()
            } catch (cancel: CancellationException) {
                log(LogLevel.INFO, "流程已取消")
                throw cancel
            } catch (t: Throwable) {
                // stepper2 的 StepScope.run 会把协程取消包成 StepError（error="... was cancelled"），
                // 这种情况是**正常停止**，不能当异常：只记 INFO、状态 STOPPED、不写 lastError。
                if (isCancellation(t)) {
                    log(LogLevel.INFO, "流程已取消")
                } else {
                    failure = t
                    log(LogLevel.ERROR, "流程异常终止：${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                // 只有令牌仍属于本轮（没被 stop 或新一轮 start 取代）才收尾，
                // 否则会把新一轮标成「已停止」并撤掉它的常亮层。
                if (runSeq.get() == myRun) {
                    val error = failure
                    updateState {
                        copy(
                            running = false,
                            phase = if (error == null) EnginePhase.STOPPED else EnginePhase.ERROR,
                            phaseDetail = if (error == null) "已停止" else "出错：${error.message.orEmpty()}",
                            lastError = error?.let { it.message ?: it.javaClass.simpleName },
                            adElapsedSeconds = 0,
                            todayCount = statsCount(),
                            todayMinutes = statsMinutes(),
                        )
                    }
                    releaseKeepScreenOn()
                }
                stepJobId = null
            }
        }
    }

    /** 判断一个异常是不是「协程被取消」（含 stepper2 包装后的 StepError）。 */
    private fun isCancellation(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur is CancellationException) return true
            val name = cur.javaClass.simpleName
            val msg = cur.message.orEmpty()
            if (name.contains("StepError", ignoreCase = true) ||
                msg.contains("was cancelled", ignoreCase = true) ||
                msg.contains("JobCancellationException", ignoreCase = true)
            ) {
                return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    fun stop(reason: String = "") {
        // reason 为空 = 用户主动停止（UI 按钮）；带 reason 的是内部原因（如服务断开），保留运行意图。
        if (reason.isBlank()) userIntent.set(false)
        val jobId = stepJobId
        if (!_state.value.running && jobId == null) return
        // 先作废令牌：被取消的 step 作业进入 finally 时不再写状态，收尾由这里负责。
        runSeq.incrementAndGet()
        log(LogLevel.INFO, if (reason.isBlank()) "已停止" else "已停止：$reason")
        updateState {
            copy(running = false, phase = EnginePhase.STOPPED, phaseDetail = "已停止", adElapsedSeconds = 0)
        }
        jobId?.let { runCatching { Step.stop(it) } }
        stepJobId = null
        releaseKeepScreenOn()
    }

    fun isRunning(): Boolean = _state.value.running

    /** 透明常亮层：全屏但不可见、不接收触摸，只为让系统保持亮屏。 */
    private fun acquireKeepScreenOn() {
        val context = appContext ?: return
        if (AssistsWindowManager.contains(KEEP_SCREEN_TAG)) return
        runCatching {
            val view = FrameLayout(context).apply {
                keepScreenOn = true
                setBackgroundColor(Color.TRANSPARENT)
            }
            val params = AssistsWindowManager.createLayoutParams().apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            AssistsWindowManager.add(
                view = view,
                layoutParams = params,
                isStack = false,
                isTouchable = false,
                viewTag = KEEP_SCREEN_TAG,
            )
        }.onFailure { log(LogLevel.WARN, "常亮层添加失败：${it.message}") }
    }

    private fun releaseKeepScreenOn() {
        runCatching { AssistsWindowManager.removeWindow(KEEP_SCREEN_TAG as Any) }
    }

    fun onServiceConnected(service: AssistsService) {
        serviceConnected.set(true)
        val pkg = currentPackageSafely()
        log(LogLevel.SUCCESS, "无障碍服务已连接")
        updateState { copy(foregroundPackage = pkg) }
        maybeAutoResume()
    }

    /**
     * 无障碍服务断开。
     *
     * 注意排查陷阱：`adb shell uiautomator dump` 会注册 UiAutomation，系统会因此**解绑**我们的
     * 无障碍服务（logcat 里常见 "UiAutomationService already registered"）。所以真机测试时不要
     * 一边跑引擎一边 dump，否则会被误判成「引擎卡死 / 自己停了」，其实是被调试工具踢下线。
     */
    fun onServiceDisconnected() {
        serviceConnected.set(false)
        if (_state.value.running) stop("无障碍服务断开")
        updateState { copy(foregroundPackage = "") }
        log(LogLevel.WARN, "无障碍服务断开")
    }

    /**
     * 服务重连后的自动恢复：仅当用户意图仍在、当前确实没有在跑、且未超过恢复上限时重启流程。
     * 与用户手动「开始」用 job.isActive / running 双去重，不会重复启动。
     */
    private fun maybeAutoResume() {
        if (!userIntent.get()) return
        if (_state.value.running || stepJobId != null) return
        val context = appContext ?: return
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            userIntent.set(false)
            log(LogLevel.WARN, "无障碍服务反复断开（已自动恢复 $recoveryAttempts 次），停止自动恢复")
            return
        }
        recoveryAttempts++
        log(LogLevel.INFO, "无障碍服务重连，自动恢复运行（第 $recoveryAttempts 次）")
        startInternal(context)
    }

    /**
     * 高频回调，只读事件自带的包名，不遍历窗口树（getPackageName 开销大，
     * 只在流程需要「当前真实窗口包名」时调用）。
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != _state.value.foregroundPackage) {
            updateState { copy(foregroundPackage = pkg) }
        }
        // 窗口切换：记录目标应用的 Activity 类名。广告页是 ExcitingVideoActivity，
        // 引擎用它可以「事件一到就判定已进入广告」，不必等节点树刷新。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg == settings().targetPackage) {
            lastWindowClassNameValue = event.className?.toString().orEmpty()
            lastWindowChangedAtValue = System.currentTimeMillis()
        }
    }

    /** 最近一次目标应用窗口切换的 Activity 类名（空串表示还没收到过）。 */
    fun lastWindowClassName(): String = lastWindowClassNameValue

    /** 最近一次目标应用窗口切换的时间戳（毫秒）。 */
    fun lastWindowChangedAt(): Long = lastWindowChangedAtValue

    fun log(level: LogLevel, message: String) {
        val entry = LogEntry(logSeed.incrementAndGet(), System.currentTimeMillis(), level, message)
        // update {} 是原子的 read-modify-write，多线程同时写日志不会丢条目。
        _logs.update { logs -> (listOf(entry) + logs).take(MAX_LOG_SIZE) }
        // 再镜像一份到 logcat（TAG=SodaAuto），方便 adb 观察；不影响 StateFlow 行为。
        runCatching { Log.println(logcatPriority(level), LOG_TAG, message) }
    }

    /** LogLevel → android.util.Log 优先级（ACTION/SUCCESS 归到 INFO）。 */
    private fun logcatPriority(level: LogLevel): Int = when (level) {
        LogLevel.INFO, LogLevel.ACTION, LogLevel.SUCCESS -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    /** 领取/跨天后把持久化的今日统计同步到 UI 状态。 */
    internal fun syncTodayStats() {
        val count = statsCount()
        val minutes = statsMinutes()
        updateState { copy(todayCount = count, todayMinutes = minutes) }
    }

    /** 服务是否真正连上（由无障碍服务回调维护，见 serviceConnected 字段注释）。 */
    private fun isServiceConnected(): Boolean = serviceConnected.get()

    private fun currentSettings(): AutomationSettings = settings()

    private fun currentPackageSafely(): String =
        runCatching { AssistsCore.getPackageName() }.getOrNull().orEmpty()

    private fun statsCount(): Int = preferences?.readTodayCount() ?: 0

    private fun statsMinutes(): Int = preferences?.readTodayMinutes() ?: 0

    /**
     * 原子更新状态。
     *
     * 引擎在 Dispatchers.Default、UI/无障碍回调在主线程，若用
     * _state.value = _state.value.block() 会丢失并发更新（极端情况 running 被回写）。
     * MutableStateFlow.update {} 内部用 CAS 重试，保证每次基于最新值修改。
     */
    internal fun updateState(block: AutomationUiState.() -> AutomationUiState) {
        _state.update { current -> current.block() }
    }
}