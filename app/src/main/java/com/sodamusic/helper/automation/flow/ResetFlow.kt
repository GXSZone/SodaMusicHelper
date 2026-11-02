package com.sodamusic.helper.automation.flow
import com.sodamusic.helper.automation.util.*
import com.sodamusic.helper.automation.FlowKeywords
import com.sodamusic.helper.automation.flow.*

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import com.sodamusic.helper.automation.AdClaimFlow
import com.sodamusic.helper.automation.AutomationRuntime
import com.sodamusic.helper.automation.AutomationUiState
import com.sodamusic.helper.automation.EnginePhase
import com.sodamusic.helper.automation.LogLevel
import com.sodamusic.helper.automation.NodeActions
import com.sodamusic.helper.automation.NodeScanner

import com.sodamusic.helper.automation.flow.WatchOutcome
import com.sodamusic.helper.settings.AppPreferences
import com.ven.assists.AssistsCore
import com.ven.assists.stepper2.Step
import com.ven.assists.stepper2.StepConfig
import com.ven.assists.stepper2.StepScope
import com.ven.assists.utils.AudioPlayerUtil
import com.ven.assists.utils.AssistsNodeClassNames
import com.ven.assists.utils.CoroutineWrapper
import com.ven.assists.window.AssistsWindowManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

// 复位：开轮复位 / 完成后复位 / 等主界面。

internal suspend fun AdClaimFlow.resetIfNeeded(): Boolean {
        // 开轮期间也可能停在直播间（广告结束后漂过去）：同样点右上角 X，不行就显式拉起主界面，绝不按返回键。
        if (isTargetPackage(actions.currentPackage()) && isLiveRoomPage()) {
            log(LogLevel.INFO, "检测到直播间，点击右上角关闭（本轮不计数，重开）")
            if (closeLiveRoomOnce() && !isLiveRoomPage()) {
                log(LogLevel.INFO, "直播间已关闭，继续开轮")
            } else {
                log(LogLevel.WARN, "直播间关闭无效，显式启动 MainActivity")
                runCatching { actions.launchIntent(mainActivityIntent()) }
                delay(LIVE_ROOM_RELAUNCH_WAIT_MS.milliseconds)
            }
        }
        // 第一步永远先看「前台是谁」：绝不能在用户用别的 App 时抢前台。
        openRoundGiveUp = false
        var attempts = 0
        while (currentCoroutineContext().isActive && attempts < OPEN_ROUND_FOREGROUND_ATTEMPTS) {
            attempts++
            val pkg = actions.currentPackage()
            updateState { copy(foregroundPackage = pkg) }
            when {
                isTargetPackage(pkg) -> break
                pkg.isBlank() -> break
                // 自己在前台 = 拉起失败（不是用户操作）：重试拉起，不抢别人前台
                pkg == appContext.packageName -> {
                    log(LogLevel.WARN, "开轮：前台是本应用（拉起失败），先拉起汽水音乐")
                    if (!ensureTargetForeground()) return false
                }
                // 系统界面（下拉通知/状态栏）：收起即可，不算用户在别的 App
                pkg == SYSTEM_UI_PACKAGE -> {
                    log(LogLevel.INFO, "开轮：前台是系统界面，按返回键收起")
                    runCatching { actions.back() }
                    delay(COVER_BACK_SETTLE_MS.milliseconds)
                }
                // 桌面：用户可能刚回桌面，允许主动拉起
                isLauncherPackage(pkg) -> {
                    log(LogLevel.INFO, "开轮：前台是桌面，拉起汽水音乐")
                    launchTarget()
                    delay(RESET_VERIFY_MS.milliseconds)
                }
                // 其它任何 App：让行，不复位、不拉起
                else -> {
                    log(LogLevel.INFO, "开轮：用户正在使用 $pkg，让行")
                    if (!yieldToUser(pkg, YIELD_MAX_MS, "用户在用手机")) {
                        log(LogLevel.INFO, "开轮：让行超时，结束本次运行")
                        openRoundGiveUp = true
                        return false
                    }
                }
            }
        }
        val nowPkg = actions.currentPackage()
        updateState { copy(foregroundPackage = nowPkg) }
        if (!isTargetPackage(nowPkg)) {
            log(LogLevel.INFO, "开轮：当前前台=${nowPkg.ifBlank { "未知" }}，不是汽水音乐，结束本次运行")
            openRoundGiveUp = true
            return false
        }
        // 到这里前台一定是汽水音乐，才判断「界面是否干净」
        if (isCleanMainPage()) {
            log(LogLevel.INFO, "开轮：主界面（前台=${settings.targetPackage}，窗口=${currentWindowLabel()}）")
            return true
        }
        // 通用三步（不做页面分类）：① 已在主界面 → 直接开始；② back 一次再查；③ 显式启动 MainActivity。
        log(LogLevel.WARN, "开轮：不在主界面（窗口=${currentWindowLabel()}），先按返回键再查")
        if (isRewardSheetVisible()) closeRewardSheetIfAny()
        runCatching { actions.back() }
        delay(OPEN_ROUND_BACK_SETTLE_MS.milliseconds)
        if (isCleanMainPage()) {
            log(LogLevel.INFO, "开轮：返回后已在主界面（窗口=${currentWindowLabel()}）")
            return true
        }
        log(LogLevel.INFO, "开轮：返回无效，显式启动 MainActivity")
        resetToMain()
        val ok = isCleanMainPage()
        if (ok) {
            log(LogLevel.INFO, "开轮：复位后已回到主界面（窗口=${currentWindowLabel()}）")
        } else {
            log(LogLevel.WARN, "开轮：复位后仍不在主界面（前台=${actions.currentPackage()}，窗口=${currentWindowLabel()}）")
        }
        return ok
    }

    /** 前台是不是桌面（launcher）。先用 PackageManager 解析 HOME，解析不到再按常见包名兜底。 */

internal suspend fun AdClaimFlow.resetToMain(): Boolean {
        resetAdProgress()
        if (isCleanMainPage()) {
            // 已在主界面：只需处理可能盖在上面的奖励浮层
            closeRewardSheetIfAny()
            lastResetSuccessAt = System.currentTimeMillis()
            return true
        }
        setPhase(EnginePhase.LAUNCHING, "复位：回到汽水音乐主界面…")
        // ⓪ 前台不是汽水音乐时先 back 一次：收起 systemui / 通知面板 / 广告落地页浮层，
        //    否则后面的显式启动会被这些覆盖层挡住、底部 tab 也验不到。
        val beforePkg = actions.currentPackage()
        if (!isTargetPackage(beforePkg)) {
            log(LogLevel.INFO, "复位：前台=${beforePkg.ifBlank { "未知" }}，先按一次返回键收起覆盖层")
            runCatching { actions.back() }
            delay(COVER_BACK_SETTLE_MS.milliseconds)
        }
        // 显式启动 MainActivity
        log(LogLevel.ACTION, "复位：显式启动 MainActivity")
        // 启动后先把广告结算弹窗/免单弹窗收掉，再判主界面（避免被浮层残留判成失败）
        delay(REWARD_SHEET_DETECT_MS.milliseconds)
        closeRewardSheetIfAny()
        var entered = false
        if (actions.launchIntent(mainActivityIntent())) {
            entered = awaitMainPage(RESET_VERIFY_MS)
            if (!entered) log(LogLevel.WARN, "复位：显式启动后 ${RESET_VERIFY_MS}ms 内仍未回到主界面，改用包名拉起兜底")
        } else {
            log(LogLevel.WARN, "复位：显式启动失败，改用包名拉起兜底")
        }
        // ② 包名拉起兜底
        if (!entered) {
            entered = actions.launchPackage(settings.targetPackage) && awaitMainPage(RESET_VERIFY_MS)
            if (entered) log(LogLevel.ACTION, "复位：包名拉起兜底成功")
        }
        if (!entered) {
            log(LogLevel.WARN, "复位失败：${RESET_VERIFY_MS}ms 内既没回到主界面")
            return false
        }
        // ③ 奖励浮层 / 免单弹窗（复位后约 1s 出现）：识别到账 → 关闭
        delay(REWARD_SHEET_DETECT_MS.milliseconds)
        closeRewardSheetIfAny()
        // ④ 复位成功：前台是目标包 + 已离开广告页即可（tab 仅作可选校验，失败不阻塞）
        if (isCleanMainPage()) {
            log(LogLevel.ACTION, "复位成功：主界面（前台=${settings.targetPackage}，窗口=${currentWindowLabel()}）")
            if (!scanner.containsAnyTextActive(FlowKeywords.MAIN_TABS)) {
                log(LogLevel.WARN, "复位：底部 tab 文本暂不可见（不阻塞，下一步点「发现」时走文本/坐标兜底）")
            }
            lastResetSuccessAt = System.currentTimeMillis()
            delay(RESET_SETTLE_MS.milliseconds)
            return true
        }
        log(
            LogLevel.WARN,
            "复位：仍未确认主界面（前台=${actions.currentPackage()}，窗口=${currentWindowLabel()}" +
                if (lastWindowIsAdClass()) "，广告 Activity 仍在）" else "）",
        )
        return false
    }

    // ---------- ④ 奖励浮层 ----------

    /**
     * 活动窗口里是否出现**广告链路**文案。
     *
     * 关键：不能用 FlowKeywords.AD_PAGE_HINTS —— 它含 PROMPT_HINTS 里的「免费听」，
     * 而汽水音乐主界面右下角常驻「免费听13天」权益角标，会导致主界面被判成广告页。
     * 这里只拼「播放态 + 领取成功 + 提示页强特征」，弱特征走组合条件。
     */

internal suspend fun AdClaimFlow.awaitMainPage(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (currentCoroutineContext().isActive) {
            val pkg = actions.currentPackage()
            updateState { copy(foregroundPackage = pkg) }
            // 最小判据：前台是目标包 + 主界面判据成立即可（不等 tab、不等窗口 root 完整）
            if (isCleanMainPage()) {
                log(LogLevel.ACTION, "复位：前台=${settings.targetPackage} 窗口=${currentWindowLabel()} 已离开广告页")
                return true
            }
            if (System.currentTimeMillis() >= deadline) return false
            delay(RESET_POLL_MS.milliseconds)
        }
        return false
    }

    // ---------- S5 让行 ----------

    /** 用户在用别的 App：只观察不动作；超过 [YIELD_MAX_MS] 返回 false。 */
    /**
     * 让行：只观察不动作，等前台回到目标应用。
     *
     * @param maxWaitMs 最长等待（用户在用手机 60s；广告落地页残留 15s）
     * @param reason 日志标签：「用户在用手机」/「广告落地页残留」
     */

internal suspend fun AdClaimFlow.awaitMainTabs(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (currentCoroutineContext().isActive) {
            if (isTargetPackage(actions.currentPackage()) && scanner.containsAnyTextActive(FlowKeywords.MAIN_TABS)) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(RESET_POLL_MS.milliseconds)
        }
        return false
    }

    /** 汽水音乐首页的显式启动 Intent（组件类默认值见 MAIN_ACTIVITY_CLASS，换机型/改版时覆盖）。 */

internal fun AdClaimFlow.resetAdProgress() {
        updateState { copy(adElapsedSeconds = 0, adMaxSeconds = 0) }
    }

    /** 本轮广告只记一次账：完成弹窗会持续存在，用 claimedThisAd 防止重复计数。 */
