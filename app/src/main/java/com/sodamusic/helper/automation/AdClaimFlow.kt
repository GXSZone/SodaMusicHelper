package com.sodamusic.helper.automation

import android.content.Context
import com.sodamusic.helper.automation.flow.*
import com.sodamusic.helper.automation.template.TemplateEntryLocatorHolder
import com.sodamusic.helper.automation.util.*
import com.sodamusic.helper.settings.AppPreferences
import com.ven.assists.stepper2.Step
import com.ven.assists.stepper2.StepConfig
import com.ven.assists.stepper2.StepScope
import com.ven.assists.utils.AudioPlayerUtil
import com.ven.assists.utils.CoroutineWrapper
import com.ven.assists.window.AssistsWindowManager
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * AdClaimFlow：stepper2 编排 + 业务状态持有。
 *
 * 页面判定 / 看广告采样 / 入口导航 / 复位分别在
 * flow/PageState.kt、flow/WatchAd.kt、flow/EntryNavigation.kt、flow/ResetFlow.kt（本类的扩展函数）。
 */
internal class AdClaimFlow(
    context: Context,
    internal val settings: AutomationSettings,
) {

    internal val appContext = context.applicationContext
    internal val preferences = AppPreferences(appContext).also { it.initialize() }
    internal val scanner = NodeScanner()
    internal val actions = NodeActions(scanner)

    internal var claimedCount = 0

    /** 最近一次「确认进入广告页」的时间戳，用于区分「广告落地页劫持」与「用户自己切走」。 */
    internal var adPageEnteredAt = 0L

    /** 当前这条广告是否已经记过领取（完成弹窗会持续存在，避免反复记账）。 */
    internal var claimedThisAd = false

    /** 最近一次「广告窗口出现在前台上」的时间戳（用于劫持窗口判定与日志）。 */
    internal var lastAdWindowSeenAt = 0L

    /** 上一次轮询时广告窗口是否在前台（用于只刷新一次时间戳）。 */
    internal var adWindowActiveLastPoll = false

    /** 最近一次复位成功（回到主界面 + 底部 tab 可见）的时间戳。 */
    internal var lastResetSuccessAt = 0L

    /** 本轮的「广告落地页残留跳转」已处理次数。 */
    internal var postResetHijackAttempts = 0

    /** 连续多少轮因广告落地页残留无法恢复（用于结束运行的保护阈值）。 */
    internal var postResetGiveUpStreak = 0

    /** 开轮时是否该直接结束运行（用户在用别的 App / 让行超时），不再按无库存重试。 */
    internal var openRoundGiveUp = false

    // ---- stepper2 调度状态（原来在 run() 局部变量里，现在跨 step 共享）----
    internal var round = 0
    internal var noStockStreak = 0
    internal var hijackRounds = 0
    internal var promptFailStreak = 0
    internal var hijackAttempts = 0


    suspend fun runAsSteps() {
        val maxPerDay = settings.dailyLimit.coerceAtLeast(1)
        log(LogLevel.INFO, "开始自动领取：目标 ${settings.targetPackage}，每日上限 $maxPerDay 次")
        updateState {
            copy(
                adMaxSeconds = settings.adMaxWaitSeconds,
                adElapsedSeconds = 0,
                todayCount = preferences.readTodayCount(),
                todayMinutes = preferences.readTodayMinutes(),
            )
        }
        // 启动阶段：只有我们自己/桌面/空白在前台时才拉起，用户在用别的 App 时绝不抢前台。
        val startPkg = actions.currentPackage()
        updateState { copy(foregroundPackage = startPkg) }
        if (isTargetPackage(startPkg) || startPkg.isBlank() ||
            startPkg == appContext.packageName || isLauncherPackage(startPkg)
        ) {
            launchTargetOnce()
        } else {
            log(LogLevel.INFO, "启动：用户正在使用 $startPkg，不主动拉起汽水音乐（进入让行）")
        }

        // 调度交给 stepper2：A 开轮 → B 入口 → C 看广告 → D 记账+复位 → A（返回 null 结束）。
        StepScope.run(StepConfig(tag = STEP_OPEN_ROUND, delayMs = 0)) { step -> stepA(step) }

        log(LogLevel.INFO, "本次共领取 $claimedCount 次，今日累计 ${preferences.readTodayCount()} 次")
    }

    // ---------- stepper2 四个步骤（业务判据都在各自 body 内，不跨 step 拆状态） ----------

    /** Step A「开轮」：上限检查 → 让行/开轮复位 → 进 B。 */

    internal suspend fun stepA(step: Step): Step? {
        val maxPerDay = settings.dailyLimit.coerceAtLeast(1)
        val claimedToday = preferences.readTodayCount()
        if (claimedToday >= maxPerDay) {
            log(LogLevel.SUCCESS, "已达每日上限（$claimedToday/$maxPerDay），自动结束")
            return null
        }
        if (!resetIfNeeded()) {
            if (openRoundGiveUp) {
                log(LogLevel.INFO, "开轮：用户未回到汽水音乐，结束本次运行（已领取 $claimedCount 次）")
                return null
            }
            noStockStreak++
            log(LogLevel.WARN, "第 ${round + 1} 轮：开轮复位失败，界面不在汽水音乐主界面（连续 $noStockStreak/$MAX_NO_STOCK）")
            if (noStockStreak >= MAX_NO_STOCK) {
                log(LogLevel.INFO, "连续 $MAX_NO_STOCK 次开轮复位失败，结束本次运行")
                return null
            }
            return step.repeat(StepConfig(tag = STEP_OPEN_ROUND, delayMs = NO_STOCK_BACKOFF_MS, repeatCountMax = MAX_NO_STOCK))
        }
        round++
        hijackAttempts = 0
        postResetHijackAttempts = 0
        updateState { copy(round = round) }
        log(LogLevel.INFO, "—— 第 $round 轮开始 ——")
        return step.next({ s -> stepB(s) }, StepConfig(tag = STEP_ENTRY, delayMs = 0))
    }

    /** Step B「入口」：坐标 → id/vu → 区域；失败按 no-stock 计次（超限结束），否则重复本步。 */

    internal suspend fun stepB(step: Step): Step? {
        if (enterAd(round)) {
            noStockStreak = 0
            adPageEnteredAt = System.currentTimeMillis()
            // 每个广告只允许记一次领取（防完成弹窗多次采样重复记账）
            claimedThisAd = false
            return step.next({ s -> stepC(s) }, StepConfig(tag = STEP_WATCH, delayMs = 0))
        }
        noStockStreak++
        val currentPkg = actions.currentPackage()
        log(
            LogLevel.WARN,
            "第 $round 轮：${ENTRY_VERIFY_MS}ms 内未进入广告链路（连续 $noStockStreak/$MAX_NO_STOCK，可能无库存）；" +
                "前台=${currentPkg.ifBlank { "未知" }}；" +
                "text=[${scanner.currentTextOnlySummary(12)}]；desc=[${scanner.currentDescOnlySummary(12)}]；" +
                "窗口=[${scanner.currentRootsSummary(5)}]",
        )
        if (noStockStreak >= MAX_NO_STOCK) {
            log(LogLevel.INFO, "连续 $MAX_NO_STOCK 次未进入广告页，结束本次运行（今日可能已无库存）")
            return null
        }
        resetToMain()
        return step.repeat(StepConfig(tag = STEP_ENTRY, delayMs = NO_STOCK_BACKOFF_MS, repeatCountMax = MAX_NO_STOCK))
    }

    /** Step C「看广告」：一次完整观看（四态判据/back 试探/进度同步都在 watchAd 内），结束后进 D。 */

    internal suspend fun stepC(step: Step): Step? {
        return when (watchAd()) {
            WatchOutcome.Finished -> {
                claimOnce()
                log(LogLevel.INFO, "第 $round 轮完成，准备下一轮")
                step.next({ s -> stepD(s) }, StepConfig(tag = STEP_SETTLE, delayMs = 0))
            }
            WatchOutcome.Hijacked -> {
                hijackAttempts++
                if (hijackAttempts > MAX_HIJACKS_PER_ROUND) {
                    log(LogLevel.WARN, "本轮广告被第三方劫持 $hijackAttempts 次（上限 $MAX_HIJACKS_PER_ROUND），结束本轮")
                    hijackRounds++
                    resetToMain()
                    if (hijackRounds >= MAX_HIJACK_ROUNDS) {
                        log(LogLevel.INFO, "连续 $hijackRounds 轮广告都被第三方劫持，结束本次运行")
                        null
                    } else {
                        step.next({ s -> stepA(s) }, StepConfig(tag = STEP_OPEN_ROUND, delayMs = ROUND_SETTLE_MS))
                    }
                } else {
                    log(LogLevel.INFO, "本轮广告劫持第 $hijackAttempts 次，复位后重新进入（同轮继续）")
                    step.repeat(StepConfig(tag = STEP_WATCH, delayMs = HIJACK_RETRY_DELAY_MS, repeatCountMax = MAX_HIJACKS_PER_ROUND + 1))
                }
            }
            WatchOutcome.LiveRoomAbandon -> {
                // 撞到直播间：本轮不算数（不 claimOnce、不写 stats），清进度后重开一轮
                resetAdProgress()
                log(LogLevel.INFO, "直播间已关闭，本轮放弃，重开一轮")
                step.next({ s -> stepA(s) }, StepConfig(tag = STEP_OPEN_ROUND, delayMs = ROUND_SETTLE_MS))
            }
            WatchOutcome.PromptFail -> {
                log(LogLevel.WARN, "第 $round 轮：提示页点了 $MAX_PROMPT_CONTINUE 次「继续观看」仍未进入播放，本轮按失败处理")
                nextAfterFailedRound(step, "连续 $promptFailStreak 轮都卡在提示页")
            }
            WatchOutcome.NoAd -> {
                log(LogLevel.WARN, "第 $round 轮：没有有效广告（残留浮层/无播放态证据），本轮不记账")
                nextAfterFailedRound(step, "连续 $promptFailStreak 轮都没拿到有效广告")
            }
            WatchOutcome.Timeout -> {
                log(LogLevel.WARN, "广告等待超过 ${settings.adMaxWaitSeconds}s，本轮不计次数")
                hijackRounds = 0
                promptFailStreak = 0
                step.next({ s -> stepD(s) }, StepConfig(tag = STEP_SETTLE, delayMs = 0))
            }
            WatchOutcome.UserLeft -> {
                log(LogLevel.INFO, "用户正在使用手机，停止本次运行（已领取 $claimedCount 次）")
                null
            }
            WatchOutcome.Cancelled -> null
        }
    }

    /** 失败轮收尾：累加 promptFailStreak，超限结束，否则复位后回 A。 */

    internal suspend fun nextAfterFailedRound(step: Step, message: String): Step? {
        promptFailStreak++
        resetToMain()
        if (promptFailStreak >= MAX_PROMPT_FAIL_ROUNDS) {
            log(LogLevel.INFO, "$message，结束本次运行")
            return null
        }
        return step.next({ s -> stepA(s) }, StepConfig(tag = STEP_OPEN_ROUND, delayMs = ROUND_SETTLE_MS))
    }

    /** Step D「记账+复位」：复位（含关奖励浮层/校验主界面）后回到 A 开始下一轮。 */

    internal suspend fun stepD(step: Step): Step? {
        if (!resetToMain()) log(LogLevel.WARN, "复位未确认成功（下一轮会再尝试）")
        hijackRounds = 0
        promptFailStreak = 0
        postResetGiveUpStreak = 0
        return step.next({ s -> stepA(s) }, StepConfig(tag = STEP_OPEN_ROUND, delayMs = ROUND_SETTLE_MS))
    }

    // ---------- S0 / S1 ----------

    /**
     * 开轮复位：确保界面停在「汽水音乐主界面 + 底部 tab 可见」。
     *
     * 顺序与「完成后复位」完全一致：残留奖励浮层先关（NODE→BACK→COORD）→ 残留广告页走显式复位 →
     * 最后校验底部 tab。任一环节不通过就返回 false，由调用方决定重试/结束。
     */

    internal fun claimOnce(): Boolean {
        if (claimedThisAd) return false
        claimedThisAd = true
        recordClaim()
        resetAdProgress()
        return true
    }

    /** S3 记账：广告完成即 +1 次、+minutesPerClaim 分钟。 */

    internal fun recordClaim() {
        setPhase(EnginePhase.CLAIMING, "记录领取…")
        val todayCount = preferences.incrementToday(1, settings.minutesPerClaim)
        claimedCount++
        AutomationRuntime.syncTodayStats()
        log(
            LogLevel.SUCCESS,
            "领取成功：本次第 $claimedCount 次，今日第 $todayCount 次（+${settings.minutesPerClaim} 分钟）",
        )
        // 领取成功提示：浮窗 toast + 提示音（协程统一交给 assists 的 CoroutineWrapper，失败不影响流程）
        CoroutineWrapper.launch {
            runCatching {
                with(AssistsWindowManager) {
                    "已领 +${settings.minutesPerClaim} 分钟".overlayToast(CLAIM_TOAST_MS)
                }
            }
            if (settings.soundEnabled) {
                runCatching { AudioPlayerUtil.playFromAssets(appContext, CLAIM_SOUND_ASSET) }
                    .onFailure { log(LogLevel.INFO, "[debug] 提示音播放失败（assets 缺失/解码失败）：${it.message}") }
            }
        }
    }

    // ---------- S4 复位 ----------

    /**
     * S4 复位：把汽水音乐拉回首页，离开广告 Activity。
     *
     * 关键：launchApp(packageName) 只是把已有 task 提到前台，而广告 Activity 就在同一 task 里，
     * 结果会被原样拉回广告页（真机实测：完成弹窗出现后永远出不去）。
     * 所以优先用显式 ComponentName 启动 MainActivity（实测立即退出 ExcitingVideoActivity），
     * 失败再用 launchApp(packageName) 兜底一次。
     */
}
