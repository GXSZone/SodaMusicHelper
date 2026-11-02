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

// 看广告采样状态机。

internal suspend fun AdClaimFlow.watchAd(): WatchOutcome {
        setPhase(EnginePhase.WATCHING_AD, "观看广告中…")
        updateState { copy(adMaxSeconds = settings.adMaxWaitSeconds, adElapsedSeconds = 0) }
        val startAt = System.currentTimeMillis()
        var lastSecond = -1
        var lastCountdown: Int? = null
        // 真实倒计时基数（首次解析到的 N）：UI 进度以它为准，超时仍用 settings.adMaxWaitSeconds
        var countdownBase: Int? = null
        var sawPlaying = false
        var playingStartedAt = 0L
        var textMissingSince = 0L
        var windowGonePolls = 0
        var windowGoneCandidateLogged = false
        var windowGoneDebugLogged = false
        // 广告期间用户切到的非目标应用（空 = 一直在目标应用内）；用于日志去重与超时说明
        var awayPkg = ""
        // 我们最近一次按返回键的时间（0 = 没按过）：用来区分「back 把汽水退掉」和「用户自己切走」
        var wePressedBackAt = 0L
        var promptAttempts = 0
        var forbiddenLogged = false
        var quitLogged = false
        var rewardConfirmLogged = false
        var doneSeenPolls = 0
        var adStartLogged = false
        var lastCountdownLogAt = 0L
        var lastPkgCheckSecond = -FOREGROUND_CHECK_SECONDS
        while (currentCoroutineContext().isActive) {
            val elapsedSecond = ((System.currentTimeMillis() - startAt) / 1000L).toInt()
            if (elapsedSecond != lastSecond) {
                lastSecond = elapsedSecond
                val base = countdownBase
                if (base == null) {
                    // 解析不到倒计时：保持原逻辑（按经过时间递增，adMaxSeconds 用设置值）
                    updateState {
                        copy(
                            adMaxSeconds = settings.adMaxWaitSeconds,
                            adElapsedSeconds = elapsedSecond,
                            phaseDetail = "观看广告 $elapsedSecond/${settings.adMaxWaitSeconds}s",
                        )
                    }
                } else {
                    // 已有真实倒计时：进度由 countdown 更新，这里只刷新文案
                    updateState { copy(phaseDetail = "观看广告，剩余 ${(base - elapsedSecond).coerceAtLeast(0)}s") }
                }
            }
            if (elapsedSecond >= settings.adMaxWaitSeconds) {
                if (awayPkg.isNotBlank()) {
                    log(
                        LogLevel.WARN,
                        "广告中：用户切走较久（前台=$awayPkg），本轮放弃，不计次（已播放 ${playedSecondsSince(playingStartedAt)}s）",
                    )
                }
                return WatchOutcome.Timeout
            }
            // 广告中每 2s 检查前台：**先判前台是否目标应用，再判是否广告页**。
            //  · 非目标应用（用户自己切走 / 桌面 / systemui / 第三方落地页）→ 绝不按返回键，只等待；
            //  · 目标应用但不是广告页（汽水内部漂移：直播间 / 内嵌页 / 播放页）→ 允许 back 试探。
            if (elapsedSecond - lastPkgCheckSecond >= FOREGROUND_CHECK_SECONDS) {
                lastPkgCheckSecond = elapsedSecond
                val pkg = actions.currentPackage()
                updateState { copy(foregroundPackage = pkg) }
                if (!isTargetPackage(pkg)) {
                    // 是我们刚才按返回键把汽水退掉的（3s 内）：这不是用户在用别的 App，要自己收拾
                    val causedByUs = wePressedBackAt > 0 &&
                        System.currentTimeMillis() - wePressedBackAt <= BACK_CAUSED_EXIT_WINDOW_MS
                    if (causedByUs) {
                        wePressedBackAt = 0
                        log(
                            LogLevel.WARN,
                            "广告中：back 把汽水退到了 ${pkg.ifBlank { "未知" }}，显式拉起 MainActivity 收尾",
                        )
                        runCatching { actions.launchIntent(mainActivityIntent()) }
                        delay(LAUNCH_WAIT_MIN_MS.milliseconds)
                        if (sawPlaying &&
                            playedSecondsSince(playingStartedAt) >= MIN_PLAY_SECONDS_BEFORE_WINDOW_GONE
                        ) {
                            log(
                                LogLevel.ACTION,
                                "广告完成：back 退出后按已完成处理（已播放 ${playedSecondsSince(playingStartedAt)}s）",
                            )
                            claimOnce()
                            return WatchOutcome.Finished
                        }
                        log(LogLevel.WARN, "广告中：back 退出且没有播放证据，本轮不记账")
                        return WatchOutcome.NoAd
                    }
                    // 用户自己在用别的 App：只记录 + 等待，不干扰
                    if (pkg != awayPkg) {
                        awayPkg = pkg
                        log(
                            LogLevel.WARN,
                            "广告中：前台是 ${pkg.ifBlank { "未知" }}（非目标应用），不按返回键，等待（已播放 ${playedSecondsSince(playingStartedAt)}s）",
                        )
                    }
                    delay(WATCH_POLL_MS.milliseconds)
                    continue
                }
                if (awayPkg.isNotBlank()) {
                    log(LogLevel.INFO, "广告中：用户已回到汽水音乐（前台=${settings.targetPackage}），继续采样")
                    awayPkg = ""
                }
                // 前台是目标应用但不在广告页 → 汽水内部漂移（直播间 / 内嵌页 / 播放页）
                if (!inAdPage()) {
                    // ① 直播间：先点右上角 X；不行就显式拉起 MainActivity；**绝不按返回键**
                    if (isLiveRoomPage()) {
                        log(LogLevel.INFO, "检测到直播间，点击右上角关闭（本轮不计数，重开）")
                        if (closeLiveRoomOnce()) {
                            if (inAdPage()) {
                                log(
                                    LogLevel.ACTION,
                                    "广告中：直播间关闭后已回到广告页，继续观看（保留 ${playedSecondsSince(playingStartedAt)}s 进度）",
                                )
                                lastPkgCheckSecond = elapsedSecond
                                continue
                            }
                            log(LogLevel.INFO, "直播间已关闭，本轮放弃，重开一轮")
                            return WatchOutcome.LiveRoomAbandon
                        }
                        log(LogLevel.WARN, "直播间关闭无效，显式启动 MainActivity 退出（不按返回键）")
                        runCatching { actions.launchIntent(mainActivityIntent()) }
                        delay(LIVE_ROOM_RELAUNCH_WAIT_MS.milliseconds)
                        if (inAdPage()) {
                            log(
                                LogLevel.ACTION,
                                "广告中：拉起 MainActivity 后回到广告页，继续观看（保留 ${playedSecondsSince(playingStartedAt)}s 进度）",
                            )
                            lastPkgCheckSecond = elapsedSecond
                            continue
                        }
                        log(LogLevel.INFO, "直播间已关闭，本轮放弃，重开一轮")
                        return WatchOutcome.LiveRoomAbandon
                    }
                    if (isAdEndedOnTarget()) {
                        log(LogLevel.ACTION, "广告中：回到汽水音乐且广告已结束（窗口=${currentWindowLabel()}）")
                        if (sawPlaying &&
                            playedSecondsSince(playingStartedAt) >= MIN_PLAY_SECONDS_BEFORE_WINDOW_GONE
                        ) {
                            claimOnce()
                            return WatchOutcome.Finished
                        }
                        log(LogLevel.WARN, "广告中：广告已结束但无播放证据，本轮不记账")
                        return WatchOutcome.NoAd
                    }
                    // ② 其它内部漂移：点右上角关闭/退出 → 显式拉起 MainActivity → 最后才 back
                    log(
                        LogLevel.INFO,
                        "广告中：内部漂移（窗口=${currentWindowLabel()}），优先点击右上角关闭控件",
                    )
                    val closeControl = scanner.findTopRightCloseControl()
                    if (closeControl != null) {
                        log(
                            LogLevel.ACTION,
                            "广告中：内部漂移，点击右上角关闭（desc=${closeControl.text}，坐标=${closeControl.centerX.toInt()},${closeControl.centerY.toInt()}）",
                        )
                        if (canTapSafely() && actions.clickNode(closeControl.node)) {
                            delay(COVER_BACK_SETTLE_MS.milliseconds)
                            if (inAdPage()) {
                                log(
                                    LogLevel.ACTION,
                                    "广告中：关闭控件点击后已回到广告页，继续观看（保留 ${playedSecondsSince(playingStartedAt)}s 进度）",
                                )
                                lastPkgCheckSecond = elapsedSecond
                                continue
                            }
                            log(LogLevel.INFO, "广告中：点击关闭控件后仍未回到广告页，改为显式拉起 MainActivity")
                        }
                    } else {
                        log(LogLevel.INFO, "[debug] 广告中：没找到右上角关闭控件，改为显式拉起 MainActivity")
                    }
                    // ③ 显式拉起 MainActivity（NEW_TASK|CLEAR_TOP|SINGLE_TOP）
                    runCatching { actions.launchIntent(mainActivityIntent()) }
                    delay(LIVE_ROOM_RELAUNCH_WAIT_MS.milliseconds)
                    if (inAdPage()) {
                        log(
                            LogLevel.ACTION,
                            "广告中：拉起 MainActivity 后回到广告页，继续观看（保留 ${playedSecondsSince(playingStartedAt)}s 进度）",
                        )
                        lastPkgCheckSecond = elapsedSecond
                        continue
                    }
                    if (isAdEndedOnTarget()) {
                        if (sawPlaying &&
                            playedSecondsSince(playingStartedAt) >= MIN_PLAY_SECONDS_BEFORE_WINDOW_GONE
                        ) {
                            log(
                                LogLevel.ACTION,
                                "广告完成：漂移后广告已结束（已播放 ${playedSecondsSince(playingStartedAt)}s）",
                            )
                            claimOnce()
                            return WatchOutcome.Finished
                        }
                        log(LogLevel.WARN, "广告中：漂移后广告已结束但没有播放证据，本轮不记账")
                        return WatchOutcome.NoAd
                    }
                    // ④ 最后手段：back（记录是我们按的，若把汽水退掉要自己拉回来）
                    log(LogLevel.WARN, "广告中：点击与拉起均无效，最后按一次返回键")
                    wePressedBackAt = System.currentTimeMillis()
                    when (probeBackToAdPage(pkg, playingStartedAt)) {
                        BackProbe.RESTORED -> {
                            lastPkgCheckSecond = elapsedSecond
                            continue
                        }
                        BackProbe.AD_ENDED -> {
                            if (sawPlaying &&
                                playedSecondsSince(playingStartedAt) >= MIN_PLAY_SECONDS_BEFORE_WINDOW_GONE
                            ) {
                                log(
                                    LogLevel.ACTION,
                                    "广告完成：返回后广告已结束（已播放 ${playedSecondsSince(playingStartedAt)}s）",
                                )
                                claimOnce()
                                return WatchOutcome.Finished
                            }
                            log(LogLevel.WARN, "广告中：返回后已离开广告页但没有播放证据，本轮不记账")
                            return WatchOutcome.NoAd
                        }
                        BackProbe.FAILED -> {
                            log(LogLevel.WARN, "广告中：两次返回均未回到广告页，复位并继续本轮")
                            if (!resetToMain()) log(LogLevel.WARN, "广告中：复位未成功，下一轮再试")
                            return WatchOutcome.Hijacked
                        }
                    }
                }
            }
            // ---------- ④ 奖励浮层：**永远不作为完成判据**，也不触发记账 ----------
            if (isRewardSheetVisible()) {
                if (claimedThisAd) {
                    if (!rewardConfirmLogged) {
                        rewardConfirmLogged = true
                        log(LogLevel.SUCCESS, "奖励到账：${rewardSheetSummary()}（二次确认）")
                    }
                    return WatchOutcome.Finished
                }
                log(
                    LogLevel.WARN,
                    "发现奖励浮层但本轮尚未看过任何广告（残留浮层），丢弃本轮、不记账：${rewardSheetSummary()}",
                )
                return WatchOutcome.NoAd
            }
            // ---------- ③ 完成弹窗「领取成功」：必须先前见过播放态才允许记账 ----------
            if (scanner.containsAnyText(FlowKeywords.AD_DONE)) {
                if (sawPlaying) {
                    log(
                        LogLevel.ACTION,
                        "广告完成：文本「领取成功」（已播放 ${playedSecondsSince(playingStartedAt)}s，窗口=${currentWindowLabel()}）",
                    )
                    claimOnce()
                    return WatchOutcome.Finished
                }
                doneSeenPolls++
                if (doneSeenPolls == 1) {
                    log(LogLevel.WARN, "看到「领取成功」但本轮还没见过播放态，先不记账，继续观察")
                }
                if (doneSeenPolls >= DONE_WITHOUT_PLAYING_POLLS) {
                    log(LogLevel.WARN, "「领取成功」持续 ${doneSeenPolls} 次轮询但没有播放态证据，本轮不记账（丢弃）")
                    return WatchOutcome.NoAd
                }
            } else {
                doneSeenPolls = 0
            }
            // ---------- ② 播放态 ----------
            val countdown = scanner.findCountdownSeconds()
            // 窗口判据单独取：完成判据 c 用它（广告 Activity 消失才说明广告真的结束）
            val windowActive = scanner.isAdWindowActive(settings.targetPackage)
            val playing = scanner.containsAnyText(FlowKeywords.PLAYING_HINTS) || countdown != null
            if (playing) {
                if (!sawPlaying) playingStartedAt = System.currentTimeMillis()
                sawPlaying = true
                textMissingSince = 0L
            } else if (sawPlaying) {
                // 切换素材 / 加载下一段时文案会短暂消失，这里当噪声处理，绝不据此判定完成
                if (textMissingSince == 0L) {
                    textMissingSince = System.currentTimeMillis()
                    log(LogLevel.INFO, "[debug] 广告文案暂时消失（忽略，可能是切换素材）")
                } else if (windowActive && System.currentTimeMillis() - textMissingSince >= TEXT_MISSING_TIMEOUT_MS) {
                    log(LogLevel.WARN, "广告文案连续消失 ${TEXT_MISSING_TIMEOUT_MS / 1000}s 但广告窗口仍在，按超时处理")
                    return WatchOutcome.Timeout
                }
            }
            // 广告窗口消失的连续采样计数（完成判据 c）：必须连续「不在广告窗口」且期间没有任何广告文案
            val adTextVisible = activeAdTextVisible()
            windowGonePolls = if (!windowActive && !adTextVisible) windowGonePolls + 1 else 0
            if (countdown != null) {
                // 每个广告开始只打一条，避免刷屏；同时把真实倒计时基数同步给 UI
                if (!adStartLogged) {
                    adStartLogged = true
                    countdownBase = countdown
                    log(LogLevel.ACTION, "广告开始：倒计时 ${countdown}s（窗口=${currentWindowLabel()}）")
                    updateState { copy(adMaxSeconds = countdown, adElapsedSeconds = 0) }
                }
                // 广告换素材/重播时基数可能变大：以新的更大值为准
                val base = countdownBase ?: countdown
                if (countdown > base) {
                    countdownBase = countdown
                    updateState { copy(adMaxSeconds = countdown, adElapsedSeconds = 0) }
                }
                if (countdown != lastCountdown) {
                    lastCountdown = countdown
                    // 降频：剩余 ≤5s 全打；否则仅在秒数变化且距上次 ≥5s 时打
                    val nowMs = System.currentTimeMillis()
                    if (countdown <= 5 || nowMs - lastCountdownLogAt >= COUNTDOWN_LOG_INTERVAL_MS) {
                        lastCountdownLogAt = nowMs
                        log(LogLevel.INFO, "广告倒计时命中路径=NODE，剩余 ${countdown}s")
                    }
                    val currentBase = countdownBase ?: countdown
                    // UI 进度 = 已播放秒数 = 基数 - 剩余（悬浮窗显示真实剩余 25→24→…→1）
                    updateState {
                        copy(
                            adMaxSeconds = currentBase,
                            adElapsedSeconds = (currentBase - countdown).coerceIn(0, currentBase),
                            phaseDetail = "观看广告，剩余 ${countdown}s",
                        )
                    }
                }
            } else if (playing && !adStartLogged) {
                // 有些广告不暴露倒计时：只报一次「播放态已确认」
                adStartLogged = true
                log(LogLevel.ACTION, "广告开始：播放态已确认（未解析到倒计时，窗口=${currentWindowLabel()}）")
            }
            // ---------- 完成判据 a：倒计时归零（命中后等 1s 复查，防素材切换抖动） ----------
            if (sawPlaying && countdown != null && countdown <= 0) {
                log(LogLevel.ACTION, "广告完成候选：倒计时归零，1s 后复查确认")
                delay(COUNTDOWN_ZERO_CONFIRM_MS.milliseconds)
                val recheck = scanner.findCountdownSeconds()
                if (recheck == null || recheck <= 0) {
                    log(
                        LogLevel.ACTION,
                        "广告完成：倒计时归零（已播放 ${playedSecondsSince(playingStartedAt)}s，窗口=${currentWindowLabel()}）",
                    )
                    claimOnce()
                    return WatchOutcome.Finished
                }
                log(LogLevel.INFO, "[debug] 倒计时复查还在计时（${recheck}s），继续观看")
                continue
            }
            // ---------- 完成判据 c：广告窗口消失（连续 3 次采样≈1.5s 且期间无广告文案，已播放 ≥5s）→ 800ms 复查 ----------
            if (sawPlaying &&
                windowGonePolls >= WINDOW_GONE_POLLS &&
                playedSecondsSince(playingStartedAt) >= MIN_PLAY_SECONDS_BEFORE_WINDOW_GONE
            ) {
                // 候选日志每个广告最多一条，避免刷屏（复查失败也不重复打）
                if (!windowGoneCandidateLogged) {
                    windowGoneCandidateLogged = true
                    log(
                        LogLevel.ACTION,
                        "广告完成候选：广告窗口已消失（已播放 ${playedSecondsSince(playingStartedAt)}s，连续 ${windowGonePolls} 次无广告文案），800ms 后复查",
                    )
                }
                delay(WINDOW_GONE_CONFIRM_MS.milliseconds)
                val stillGone = !scanner.isAdWindowActive(settings.targetPackage) && !activeAdTextVisible()
                if (stillGone) {
                    log(
                        LogLevel.ACTION,
                        "广告完成：广告窗口消失（已播放 ${playedSecondsSince(playingStartedAt)}s，窗口=${currentWindowLabel()}）",
                    )
                    claimOnce()
                    return WatchOutcome.Finished
                }
                if (!windowGoneDebugLogged) {
                    windowGoneDebugLogged = true
                    log(LogLevel.INFO, "[debug] 窗口复查又回到广告页，继续观看（后续同类候选静默）")
                }
                windowGonePolls = 0
                continue
            }
            if (playing) {
                // 播放中唯一允许的点击：中途「继续观看」弹窗
                if (scanner.containsAnyText(FlowKeywords.AD_CONTINUE)) {
                    if (canTapSafely() && actions.clickFirst(FlowKeywords.AD_CONTINUE)) {
                        log(LogLevel.INFO, "播放中途弹窗：点「继续观看」继续观看（命中路径=NODE）")
                    } else {
                        log(LogLevel.WARN, "出现「继续观看」但点击失败，稍后重试")
                    }
                    delay(CONTINUE_SETTLE_MS.milliseconds)
                    continue
                }
                if (!forbiddenLogged && scanner.containsAnyText(FlowKeywords.PLAYING_FORBIDDEN)) {
                    log(LogLevel.INFO, "播放页出现「查看详情/立即购买」等，按要求只记录不点击")
                    forbiddenLogged = true
                }
                delay(WATCH_POLL_MS.milliseconds)
                continue
            }
            // ---------- ① 提示页：点「继续观看」开始播放（判定已收紧，单命「免费听」不算） ----------
            if (isPromptPageActive()) {
                if (!quitLogged && scanner.containsAnyText(FlowKeywords.AD_QUIT)) {
                    log(LogLevel.INFO, "出现「领取奖励/再看1个视频/坚持退出」等，按要求只记录不点击")
                    quitLogged = true
                }
                if (promptAttempts >= MAX_PROMPT_CONTINUE) return WatchOutcome.PromptFail
                promptAttempts++
                log(LogLevel.ACTION, "提示页：点「继续观看」开始播放（第 $promptAttempts/$MAX_PROMPT_CONTINUE 次）")
                if (!clickPromptContinue()) log(LogLevel.WARN, "提示页「继续观看」点击未发出")
                delay(PROMPT_SETTLE_MS.milliseconds)
                continue
            }
            if (!quitLogged && scanner.containsAnyText(FlowKeywords.AD_QUIT)) {
                log(LogLevel.INFO, "出现「领取奖励/再看1个视频/坚持退出」等，按要求只记录不点击")
                quitLogged = true
            }
            delay(WATCH_POLL_MS.milliseconds)
        }
        return WatchOutcome.Cancelled
    }
    /** 进度归零：广告完成 / 复位时把悬浮窗的倒计时进度清掉。 */

    internal enum class BackProbe { RESTORED, AD_ENDED, FAILED }

    /**
     * 广告中检测到「已不在广告页」时，按返回键把用户带回广告。
     *
     * 不做任何页面分类（直播间/落地页/第三方 App 一视同仁）：
     * 最多 back [HIJACK_BACK_ATTEMPTS] 次，每次等 [HIJACK_BACK_SETTLE_MS] 后复查。
     * 成功返回 RESTORED（调用方保留倒计时与已播放时长，继续观看）。
     */

internal suspend fun AdClaimFlow.probeBackToAdPage(pkg: String, playingStartedAt: Long): BackProbe {
        for (attempt in 1..HIJACK_BACK_ATTEMPTS) {
            log(
                LogLevel.WARN,
                "广告中：前台跳到 ${pkg.ifBlank { "未知" }}，按返回键试探（第 $attempt 次）",
            )
            runCatching { actions.back() }
            delay(HIJACK_BACK_SETTLE_MS.milliseconds)
            val afterPkg = actions.currentPackage()
            updateState { copy(foregroundPackage = afterPkg) }
            if (isTargetPackage(afterPkg)) {
                if (inAdPage()) {
                    log(
                        LogLevel.ACTION,
                        "广告中：返回后已回到广告页，继续观看（保留 ${playedSecondsSince(playingStartedAt)}s 进度）",
                    )
                    return BackProbe.RESTORED
                }
                // 前台回到汽水音乐但已经不在广告页 → 广告真的结束了
                log(LogLevel.ACTION, "广告中：返回后前台=${afterPkg}（窗口=${currentWindowLabel()}）已不在广告页")
                return BackProbe.AD_ENDED
            }
            log(
                LogLevel.INFO,
                "广告中：第 $attempt 次返回后前台=${afterPkg.ifBlank { "未知" }}，仍未回到广告页",
            )
        }
        return BackProbe.FAILED
    }

    /**
     * 是否停在「广告后的免单/奖励弹窗」。
     *
     * 真机形态：「再看2个视频提前得 / 第19日免费听 / 领取奖励 / 坚持退出 / ✕」。
     * 它同时含「再看」+「免费听」，会被提示页判据误判成广告链路，导致复位先报一次失败。
     * 判据：不含广告页强特征（继续解锁 / 继续观看），且满足其一：
     *  · 含「再看」+「免费听」；
     *  · 「领取奖励 / 坚持退出 / 立即使用」命中 ≥2 个。
     */

internal suspend fun AdClaimFlow.clickPromptContinue(): Boolean {
        if (!canTapSafely()) return false
        if (actions.clickFirst(FlowKeywords.PROMPT_CONTINUE)) {
            log(LogLevel.ACTION, "提示页「继续观看」命中路径=NODE")
            return true
        }
        log(LogLevel.INFO, "提示页「继续观看」没有无障碍文本，改用坐标 ${FlowKeywords.PROMPT_CONTINUE_X}, ${FlowKeywords.PROMPT_CONTINUE_Y}")
        return tapCoordSafely(FlowKeywords.PROMPT_CONTINUE_X, FlowKeywords.PROMPT_CONTINUE_Y)
    }

    /** 在 [timeoutMs] 内等底部 tab 文本出现（奖励浮层关掉后才会露出）。 */
