package com.sodamusic.helper.automation.flow
import com.sodamusic.helper.automation.util.*
import com.sodamusic.helper.automation.FlowKeywords
import com.sodamusic.helper.automation.flow.*

import com.sodamusic.helper.automation.template.TemplateEntryLocatorHolder

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

// 发现 tab → 入口四档 → 等广告页。

internal suspend fun AdClaimFlow.enterAd(round: Int): Boolean {
        // 注意：这里**不再**复用「已在广告页就跳过导航」。陈旧浮层/残留广告页会被误当成"本轮已在广告里"，
        // 曾经导致没看广告却凭空记账。开轮已经过 resetIfNeeded()，走到这里还不在主界面就先复位。
        if (inAdPage() || isRewardSheetVisible()) {
            // 免单/奖励弹窗只是盖在主界面上，不算「广告页残留」：收掉即可，不要白白多复位一次。
            if (isPostAdUpsellSheet()) {
                log(LogLevel.INFO, "第 $round 轮：检测到免单弹窗，已收起后继续")
                closeRewardSheetIfAny()
            } else if (activeAdTextVisible()) {
                log(LogLevel.WARN, "第 $round 轮：进入导航前仍检测到广告页残留，先复位")
                if (!resetToMain()) {
                    log(LogLevel.WARN, "第 $round 轮：复位失败，本轮放弃")
                    return false
                }
            } else {
                // 只有浮层类文案（奖励浮层等）：收起后继续导航，无需复位
                closeRewardSheetIfAny()
            }
        }
        setPhase(EnginePhase.NAVIGATING, "点「发现」tab…")
        if (!openDiscoverTab()) {
            log(LogLevel.WARN, "第 $round 轮：没能点到「发现」tab")
            return false
        }
        if (inAdPage()) return true

        setPhase(EnginePhase.NAVIGATING, "点右上角入口…")
        if (tryEntry()) {
            logAdWindowConfirmed()
            return true
        }
        // 判定放宽：只有「既没进广告页、也没离开主界面」才算真失败；
        // 若点击后页面已变化（仍在汽水音乐但不在一开始的主界面），先按「已进入发现页」重试入口一次。
        if (!isOnMainFeed()) {
            log(LogLevel.INFO, "入口点击后已离开主界面但还没读到广告文案，先复查是否已进入广告链路")
            delay(ENTRY_SETTLE_RECHECK_MS.milliseconds)
            if (inAdPage()) {
                logAdWindowConfirmed()
                log(LogLevel.ACTION, "入口：广告链路已就绪（节点延迟刷新），不再重复点击")
                return true
            }
            log(LogLevel.INFO, "复查仍未进入广告链路，按「已进入发现页」重试入口一次")
            if (tryEntry()) {
                logAdWindowConfirmed()
                return true
            }
            if (inAdPage()) return true
        }
        log(LogLevel.WARN, "入口 命中路径=FAIL（重试后仍未进广告页，前台=${actions.currentPackage()}）")
        return false
    }

    /** 点右上角入口的三级候选：id/vu → 区域可点节点 → 坐标；每级 2.5s 校验。 */

internal suspend fun AdClaimFlow.tryEntry(): Boolean {
        // 实测 id/vu 节点点击几乎总是进不了广告页，先打坐标能省掉白等：
        // a) 坐标 → b) id/vu 节点 → c) 区域可点节点，每档校验窗口 [ENTRY_VERIFY_MS]（内部 300ms 轮询）。
        if (inAdPage()) {
            logAdWindowConfirmed()
            log(LogLevel.ACTION, "入口：当前已在广告链路内，无需再点")
            return true
        }
        if (tapCoordSafely(DISCOVER_ENTRY_X_RATIO, DISCOVER_ENTRY_Y_RATIO)) {
            log(LogLevel.ACTION, "入口 命中路径=COORD($DISCOVER_ENTRY_X_RATIO, $DISCOVER_ENTRY_Y_RATIO)")
            if (awaitAdPage(ENTRY_VERIFY_MS)) return true
            log(LogLevel.WARN, "坐标点击后 ${ENTRY_VERIFY_MS}ms 未进广告链路，转 id/vu 节点")
        }
        // 安全闸门拒绝 = 当前已在播放态/提示页，说明广告其实已经开了，直接算成功
        if (inAdPage()) {
            logAdWindowConfirmed()
            log(LogLevel.ACTION, "入口：安全闸门拒绝坐标点击，判定已进入广告链路")
            return true
        }
        val byId = scanner.findByViewIdActive(DISCOVER_ENTRY_VIEW_ID)
            ?: scanner.findByViewId(DISCOVER_ENTRY_VIEW_ID)
        if (byId != null && actions.clickNode(byId.node)) {
            log(LogLevel.ACTION, "入口 命中路径=NODE(id/vu)")
            if (awaitAdPage(ENTRY_VERIFY_MS)) return true
            log(LogLevel.WARN, "id/vu 点击后 ${ENTRY_VERIFY_MS}ms 未进广告链路，转区域节点")
        }
        if (inAdPage()) return true
        // c) 区域可点击节点：实测广告入口徽章的容器是 clickable LinearLayout
        //    (1048,175)-(1230,262)；这里按比例换算后取「落在区域内、isClickable=true、面积最小」的节点。
        val screenW = actions.screenWidth()
        val screenH = actions.screenHeight()
        val regionLeft = (screenW * ENTRY_REGION_X_MIN_RATIO).toInt()
        val regionRight = (screenW * ENTRY_REGION_X_MAX_RATIO).toInt()
        val regionTop = (screenH * ENTRY_REGION_Y_MIN_RATIO).toInt()
        val regionBottom = (screenH * ENTRY_REGION_Y_MAX_RATIO).toInt()
        val region = scanner.findClickableInActiveBounds(regionLeft, regionTop, regionRight, regionBottom)
        if (region != null) {
            log(
                LogLevel.ACTION,
                "入口 命中路径=NODE(区域 ${region.bounds.flattenToString()}，区域=[$regionLeft,$regionTop][$regionRight,$regionBottom])",
            )
            if (actions.clickNode(region.node)) {
                if (awaitAdPage(ENTRY_VERIFY_MS)) return true
                log(LogLevel.WARN, "区域节点点击后未进广告链路，转模板匹配")
            }
        } else {
            log(LogLevel.INFO, "[debug] 入口区域无 clickable 节点（区域=[$regionLeft,$regionTop][$regionRight,$regionBottom]）")
        }
        // d) 模板匹配（第 4 档兜底）：实现由 debug 源集注入，release 下 Holder 为 null 只打 debug 日志
        val locator = TemplateEntryLocatorHolder.impl
        if (locator != null) {
            val point = runCatching { locator.locateEntry(actions.screenWidth(), actions.screenHeight()) }.getOrNull()
            if (point != null) {
                log(
                    LogLevel.ACTION,
                    "入口 命中路径=TEMPLATE(坐标=${point.first}, ${point.second}, 分数=${"%.3f".format(TemplateEntryLocatorHolder.lastMatchScore)})",
                )
                if (canTapSafely() && actions.tapAt(point.first.toFloat(), point.second.toFloat())) {
                    if (awaitAdPage(ENTRY_VERIFY_MS)) return true
                    log(LogLevel.WARN, "模板匹配点击后仍未进广告链路")
                }
            } else {
                log(
                    LogLevel.INFO,
                    "[debug] 模板匹配未命中：${TemplateEntryLocatorHolder.lastMatchNote}" +
                        "（maxVal=${"%.3f".format(TemplateEntryLocatorHolder.lastMatchScore)}，阈值=${TemplateEntryLocatorHolder.MATCH_THRESHOLD}）",
                )
            }
        } else {
            log(LogLevel.INFO, "[debug] 模板定位未启用（release 包不包含 OpenCV）")
        }
        return inAdPage()
    }

    /**
     * 点底部「发现」tab，递减策略：
     * a) 文本/可点父节点；
     * b) 失败 → back 一次让页面从「音乐播放页」回到列表，再取文本重试（若被退到别处则重新拉起）；
     * c) 再失败 → 坐标 (0.10, 0.93)，每次等 1.2s 校验（发现文案或入口 id 出现），最多点 2 次。
     */

internal suspend fun AdClaimFlow.openDiscoverTab(): Boolean {
        if (clickDiscoverTabByText()) return true
        log(LogLevel.INFO, "「发现」tab 没有无障碍文本，先按返回键离开播放页再重试")
        runCatching { actions.back() }
        delay(TAB_BACK_SETTLE_MS.milliseconds)
        if (!isTargetPackage(actions.currentPackage())) {
            log(LogLevel.WARN, "返回后离开了汽水音乐（前台=${actions.currentPackage()}），重新拉起")
            launchTarget()
            delay(TAB_BACK_SETTLE_MS.milliseconds)
        }
        if (clickDiscoverTabByText()) return true
        repeat(DISCOVER_TAB_COORD_ATTEMPTS) { attempt ->
            log(LogLevel.INFO, "「发现」tab 改用坐标点击（第 ${attempt + 1} 次）")
            if (!canTapSafely()) return@repeat
            if (!actions.tapRatio(DISCOVER_TAB_X_RATIO, DISCOVER_TAB_Y_RATIO)) return@repeat
            delay(TAB_COORD_VERIFY_MS.milliseconds)
            if (isOnDiscoverPage()) return true
        }
        return false
    }

internal suspend fun AdClaimFlow.clickDiscoverTabByText(): Boolean {
        if (!actions.clickFirst(FlowKeywords.DISCOVER_TAB)) return false
        delay(TAB_SETTLE_MS.milliseconds)
        return true
    }

    /** 是否已到发现页：前台是目标包，且（活动窗口有「发现」文案 或 右上角入口 id 已出现）。 */

internal suspend fun AdClaimFlow.awaitAdPage(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (currentCoroutineContext().isActive) {
            // 窗口事件比节点树刷新更快：类名变成 ExcitingVideo 就立刻算进入
            if (inAdPage() || isAdWindowEventFresh()) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(VERIFY_POLL_MS.milliseconds)
        }
        return false
    }

    /** 点击闸门：只有前台是目标包时才允许点击。 */

internal fun AdClaimFlow.mainActivityIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(settings.targetPackage, MAIN_ACTIVITY_CLASS)
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }

    /** 在 [timeoutMs] 内等「前台==目标包 且 节点里没有广告文案」。 */

internal suspend fun AdClaimFlow.launchTarget(): Boolean {
        if (actions.launchIntent(mainActivityIntent())) return true
        return actions.launchPackage(settings.targetPackage)
    }

    /** 本次运行开始时（用户点了开始）主动拉起一次目标应用。 */

internal suspend fun AdClaimFlow.launchTargetOnce() {
        val pkg = actions.currentPackage()
        updateState { copy(foregroundPackage = pkg) }
        if (isTargetPackage(pkg) || !settings.autoLaunchTarget) return
        log(LogLevel.ACTION, "拉起汽水音乐…")
        if (!launchTarget()) {
            log(LogLevel.WARN, "拉起失败，请确认已安装 ${settings.targetPackage}")
            return
        }
        delay(Random.nextLong(LAUNCH_WAIT_MIN_MS, LAUNCH_WAIT_MAX_MS).milliseconds)
        val now = actions.currentPackage()
        updateState { copy(foregroundPackage = now) }
        when {
            isTargetPackage(now) -> log(LogLevel.ACTION, "拉起成功：前台=${settings.targetPackage}")
            now == appContext.packageName -> log(LogLevel.WARN, "拉起后仍停在本应用，稍后重试拉起")
            else -> log(LogLevel.WARN, "拉起后前台是 ${now.ifBlank { "未知" }}，交给让行")
        }
    }

    /**
     * 确保前台是汽水音乐。
     *
     * - 前台就是我们自己（com.sodamusic.helper）→ 这是**拉起失败**，不是用户操作：
     *   最多重试 [LAUNCH_ATTEMPTS] 次、间隔 [LAUNCH_RETRY_INTERVAL_MS]，仍失败则返回 false（结束本次运行），
     *   **绝不傻等 60s**。
     * - 前台是第三方 App / 桌面 → 走让行（不点击不 back，最多 60s）。
     */

internal suspend fun AdClaimFlow.ensureTargetForeground(): Boolean {
        var pkg = actions.currentPackage()
        updateState { copy(foregroundPackage = pkg) }
        if (isTargetPackage(pkg)) return true
        if (pkg == appContext.packageName) {
            repeat(LAUNCH_ATTEMPTS) { attempt ->
                log(LogLevel.WARN, "拉起重试（第 ${attempt + 1} 次，前台仍为本应用）")
                launchTarget()
                delay(LAUNCH_RETRY_INTERVAL_MS.milliseconds)
                pkg = actions.currentPackage()
                updateState { copy(foregroundPackage = pkg) }
                if (isTargetPackage(pkg)) {
                    log(LogLevel.ACTION, "拉起成功：前台=${settings.targetPackage}")
                    return true
                }
                if (pkg != appContext.packageName) return yieldToUser(pkg)
            }
            log(LogLevel.WARN, "拉起汽水音乐失败（前台仍为本应用）")
            return false
        }
        // 复位成功后 10s 内出现第三方前台 → 广告落地页还在跳（实测 com.danlan.xiaolan / systemui 等），按劫持处理
        val msSinceReset = System.currentTimeMillis() - lastResetSuccessAt
        if (lastResetSuccessAt > 0L && msSinceReset <= POST_RESET_HIJACK_WINDOW_MS) {
            postResetHijackAttempts++
            if (postResetHijackAttempts <= MAX_POST_RESET_HIJACKS) {
                log(
                    LogLevel.WARN,
                    "检测到广告落地页残留跳转（前台=$pkg，复位后 ${msSinceReset / 1000}s），复位并继续下一轮",
                )
                if (resetToMain()) {
                    postResetGiveUpStreak = 0
                    return true
                }
                log(LogLevel.WARN, "落地页残留复位失败，进入 ${POST_RESET_YIELD_MAX_MS / 1000}s 短暂让行")
            } else {
                log(
                    LogLevel.WARN,
                    "本轮广告落地页残留跳转已达 $MAX_POST_RESET_HIJACKS 次，进入 ${POST_RESET_YIELD_MAX_MS / 1000}s 短暂让行",
                )
            }
            // 落地页残留专用短让行（15s）：超时就再复位一次并继续下一轮
            if (yieldToUser(pkg, POST_RESET_YIELD_MAX_MS, "广告落地页残留")) {
                postResetGiveUpStreak = 0
                return true
            }
            log(LogLevel.WARN, "让行（广告落地页残留）超时，再复位一次并继续下一轮")
            if (resetToMain()) {
                postResetGiveUpStreak = 0
                return true
            }
            postResetGiveUpStreak++
            if (postResetGiveUpStreak >= MAX_POST_RESET_GIVEUP) {
                log(
                    LogLevel.INFO,
                    "连续 $postResetGiveUpStreak 次广告落地页残留都无法恢复，结束本次运行",
                )
                return false
            }
            return true
        }
        // 普通场景（用户自己在用手机）：60s 让行
        return yieldToUser(pkg, YIELD_MAX_MS, "用户在用手机")
    }

    /** 从广告首次出现播放态算起，已播放的秒数（用于完成日志核对是否真的看够时长）。 */

internal suspend fun AdClaimFlow.yieldToUser(
        initial: String,
        maxWaitMs: Long = YIELD_MAX_MS,
        reason: String = "用户在用手机",
    ): Boolean {
        setPhase(EnginePhase.NAVIGATING, "让行（$reason）：等待回到汽水音乐…")
        val deadline = System.currentTimeMillis() + maxWaitMs
        var pkg = initial
        var lastLoggedPkg = ""
        var lastLogAt = 0L
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (now >= deadline) {
                log(LogLevel.INFO, "让行（$reason）超时（${maxWaitMs / 1000}s）")
                return false
            }
            if (pkg != lastLoggedPkg || now - lastLogAt >= YIELD_LOG_INTERVAL_MS) {
                lastLoggedPkg = pkg
                lastLogAt = now
                log(LogLevel.INFO, "让行（$reason）：等待回到汽水音乐（当前前台=${pkg.ifBlank { "未知" }}）")
            }
            delay(YIELD_POLL_MS.milliseconds)
            pkg = actions.currentPackage()
            updateState { copy(foregroundPackage = pkg) }
            if (isTargetPackage(pkg)) return true
        }
        return false
    }

    // ---------- 工具 ----------

    /**
     * 拉起目标应用：**优先显式组件 Intent**（NEW_TASK|CLEAR_TOP|SINGLE_TOP，与复位同一套），
     * 失败再用 AssistsCore.launchApp(packageName) 兜底。
     *
     * 注意：launchApp(packageName) 走的是 getLaunchIntentForPackage，若清单缺少包可见性
     * （Android 11+ queries）会直接返回 null → 拉起必失败。主 Manifest 已补 queries。
     */
