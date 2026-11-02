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

// 页面判定（纯读 + 关浮层），AdClaimFlow 的扩展函数。

internal fun AdClaimFlow.isCleanMainPage(): Boolean {
        if (!isTargetPackage(actions.currentPackage())) return false
        // 排除广告链路（关键词已收紧，不含主界面常驻的「免费听」）
        if (activeAdTextVisible()) return false
        // 广告结算类弹窗（AnimatedCommercialSheetDialog / BottomSheet…）不是广告 Activity：
        // 只要活动窗口里没有广告文案，就视为已经离开广告页（否则会被当成"仍在广告页"多按一次返回）。
        val cls = AutomationRuntime.lastWindowClassName()
        if (cls.isNotBlank() && isTransientOverlayClass(cls)) return true
        // 正证据：窗口类名是 MainActivity，或底部 tab 文本可见（二者取其一，避免偶发没有 tab 文本）
        // 注意：不再做「直播间 / 落地页」之类的页面分类判定——不在主界面时由通用的
        // 「back 一次 → 再查 → 显式启动」三步兜底（见 resetIfNeeded）。
        return activeWindowIsMainActivity() || scanner.containsAnyTextActive(FlowKeywords.MAIN_TABS)
    }

    /**
     * 是否在汽水的**直播间**（真机：LunaSmoothLivePlayerActivity）。
     *
     * 判据：活动窗口类名含 LivePlayer / LunaSmoothLive，
     * 或命中「说点什么」且（观众 / 礼物 / 直播间）≥1。
     * 注意：直播间里按 back 会**直接退出整个汽水**，所以这里只允许点 X / 显式拉起主界面，绝不按返回键。
     */

internal fun AdClaimFlow.isLauncherPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val resolved = runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            appContext.packageManager
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        if (resolved == pkg) return true
        val lower = pkg.lowercase()
        if (lower.contains("launcher") || lower.contains(".home")) return true
        return pkg in KNOWN_LAUNCHER_PACKAGES
    }

    /**
     * 干净主界面的**最小充分判据**（真机实测：要求底部 tab / 窗口 root 完整都会误判）：
     * 1) 前台必须是目标包（后台残留窗口不参与判定）；
     * 2) 类名明确是广告 Activity → 不干净；
     * 3) 其余情况只看「活动窗口里没有任何广告文案」，即可视为在主界面。
     * **不再要求底部 tab 文本**（tab 只用于「点发现」那一步，点不到有坐标兜底）。
     */

internal fun AdClaimFlow.isLiveRoomPage(): Boolean {
        val classes = scanner.activeWindowClassNames()
        if (classes.any { cls -> LIVE_ROOM_CLASS_HINTS.any { cls.contains(it, ignoreCase = true) } }) return true
        if (!scanner.containsAnyTextActive(listOf("说点什么"))) return false
        return scanner.containsAnyTextActive(LIVE_ROOM_TEXTS)
    }

    /** 点掉右上角关闭按钮（X），并等 900ms；返回是否已离开直播间。 */

internal suspend fun AdClaimFlow.closeLiveRoomOnce(): Boolean {
        val close = scanner.findTopRightCloseControl()
        if (close == null) {
            log(LogLevel.WARN, "[debug] 直播间：没找到右上角关闭控件")
            return !isLiveRoomPage()
        }
        log(
            LogLevel.ACTION,
            "直播间：点击右上角关闭（desc=${close.text}，坐标=${close.centerX.toInt()},${close.centerY.toInt()}）",
        )
        if (!canTapSafely() || !actions.clickNode(close.node)) {
            log(LogLevel.WARN, "直播间：右上角关闭点击失败")
            return !isLiveRoomPage()
        }
        delay(LIVE_ROOM_CLOSE_SETTLE_MS.milliseconds)
        return !isLiveRoomPage()
    }

    /** 广告 Activity 只有一个特征：excitingvideo（ExcitingVideoActivity），其它弹窗都不算。 */

internal fun AdClaimFlow.isAdWindowClass(cls: String): Boolean =
        cls.contains(AD_WINDOW_CLASS_HINT, ignoreCase = true)

    /** 结算类临时浮层：Sheet / Dialog / BottomSheet（广告结束后的结算弹窗）。 */

internal fun AdClaimFlow.isTransientOverlayClass(cls: String): Boolean =
        TRANSIENT_OVERLAY_CLASS_HINTS.any { cls.contains(it, ignoreCase = true) }

    /** 活动窗口类名是否为主界面 MainActivity。 */

internal fun AdClaimFlow.activeWindowIsMainActivity(): Boolean =
        scanner.activeWindowClassNames().any { cls ->
            cls.contains(MAIN_ACTIVITY_CLASS.substringAfterLast('.'), ignoreCase = true)
        }

    /** 进入广告页：发现 tab → 右上角入口；2.5s 内未进广告页即视为无库存。 */

internal fun AdClaimFlow.activeAdTextVisible(): Boolean {
        // 广告后的免单/奖励弹窗不算广告链路（否则复位会被判「仍在广告页」）
        if (isPostAdUpsellSheet()) return false
        return scanner.containsAnyTextActive(AD_PAGE_TEXTS) || isPromptPageActive()
    }

    /**
     * 提示页判定（收紧版）：
     * 命中「继续解锁 / 继续观看」 → 是提示页；
     * 只命中「再看」不算；「再看」+「免费听」同时出现才算（「免费听」单独出现是主界面角标）。
     */

internal fun AdClaimFlow.isPromptPageActive(): Boolean {
        if (scanner.containsAnyTextActive(PROMPT_STRONG_TEXTS)) return true
        return scanner.containsAnyTextActive(listOf(PROMPT_WEAK_TEXT)) &&
            scanner.containsAnyTextActive(listOf(PROMPT_WEAK_COMPANION))
    }

    /**
     * 是否出现奖励浮层（盖在主界面上）——只看当前活动窗口，且用组合条件，
     * 避免「已领取 / 立即解锁」这类可能出现在主界面的字眼单独命中就误判。
     */

internal fun AdClaimFlow.isRewardSheetVisible(): Boolean {
        if (!isTargetPackage(actions.currentPackage())) return false
        if (scanner.containsAnyTextActive(REWARD_SHEET_STRONG_TEXTS)) return true
        var secondary = 0
        for (text in REWARD_SHEET_SECONDARY_TEXTS) {
            if (scanner.containsAnyTextActive(listOf(text))) secondary++
            if (secondary >= 2) return true
        }
        return false
    }

    /** 从奖励浮层文案里解析「第N天」用于日志核对真的到账。 */

internal fun AdClaimFlow.rewardSheetSummary(): String {
        val text = scanner.currentTextSummary(20)
        val day = REWARD_DAY_REGEX.find(text)?.groupValues?.getOrNull(1)
        return if (day != null) "第${day}天免费时长畅听" else text.take(60)
    }

    /**
     * 关闭奖励浮层：**节点优先 → BACK → 坐标兜底**。
     *
     * 这层是从底部滑上来的，X 的位置随浮层高度变化，固定比例不可靠（真机实测 0.885/0.506 常打空），
     * 所以顺序改为：① 找「关闭/×/X/close」节点（含可点父节点回退）→ ② 按一次返回键（最稳）→
     * ③ 最后才用坐标兜底。日志区分 NODE / BACK / COORD 三种命中路径。
     */

internal suspend fun AdClaimFlow.closeRewardSheetIfAny(): Boolean {
        val upsell = isPostAdUpsellSheet()
        if (!isRewardSheetVisible() && !upsell) return true
        if (upsell) log(LogLevel.INFO, "复位：检测到免单/奖励弹窗，已收起")
        log(LogLevel.SUCCESS, "奖励到账：${rewardSheetSummary()}")

        // ① 节点优先：文本/描述为「关闭/×/X/close」的可点节点
        if (canTapSafely() && actions.clickFirst(REWARD_SHEET_CLOSE_KEYWORDS)) {
            delay(REWARD_SHEET_SETTLE_MS.milliseconds)
            if (!isRewardSheetVisible()) {
                log(LogLevel.ACTION, "奖励浮层已关闭（命中路径=NODE）")
                return true
            }
            log(LogLevel.WARN, "奖励浮层「关闭」节点点击后仍在，改用返回键")
        } else {
            log(LogLevel.INFO, "奖励浮层没有可用的关闭节点，改用返回键")
        }

        // ② 返回键（实测最稳）
        runCatching { actions.back() }
        delay(REWARD_SHEET_SETTLE_MS.milliseconds)
        if (!isRewardSheetVisible()) {
            log(LogLevel.ACTION, "奖励浮层已关闭（命中路径=BACK）")
            return true
        }
        log(LogLevel.WARN, "返回键后奖励浮层仍在，改用坐标兜底")

        // ③ 坐标兜底（X 会随浮层高度浮动，仅作最后手段）
        if (tapCoordSafely(FlowKeywords.REWARD_SHEET_CLOSE_X, FlowKeywords.REWARD_SHEET_CLOSE_Y)) {
            delay(REWARD_SHEET_SETTLE_MS.milliseconds)
            if (!isRewardSheetVisible()) {
                log(
                    LogLevel.ACTION,
                    "奖励浮层已关闭（命中路径=COORD 兜底 ${FlowKeywords.REWARD_SHEET_CLOSE_X}, ${FlowKeywords.REWARD_SHEET_CLOSE_Y}）",
                )
                return true
            }
        }
        log(LogLevel.WARN, "奖励浮层关闭失败")
        return false
    }

    /**
     * 坐标点击的安全闸门：前台必须是目标包，且（可选）当前不是广告播放态，
     * 避免坐标点落在广告内容上（播放页 / 落地页）。
     */

internal fun AdClaimFlow.isPostAdUpsellSheet(): Boolean {
        if (!isTargetPackage(actions.currentPackage())) return false
        if (scanner.findCountdownSeconds() != null) return false
        if (scanner.containsAnyTextActive(FlowKeywords.AD_CONTINUE)) return false
        if (scanner.containsAnyTextActive(listOf("继续解锁"))) return false
        val both = scanner.containsAnyTextActive(listOf("再看")) &&
            scanner.containsAnyTextActive(listOf("免费听"))
        if (both) return true
        var matched = 0
        for (text in POST_AD_UPSELL_TEXTS) {
            if (scanner.containsAnyTextActive(listOf(text))) matched++
            if (matched >= 2) return true
        }
        return false
    }

    /** 前台是目标应用、已经没有广告文案、且窗口类名不再是广告 Activity → 广告已结束。 */

internal fun AdClaimFlow.isAdEndedOnTarget(): Boolean =
        isTargetPackage(actions.currentPackage()) &&
            !inAdPage() &&
            !activeAdTextVisible() &&
            !lastWindowIsAdClass()

    /** 当前是否仍在广告页（回到目标包之后用）：窗口类名 / 广告文案 / 倒计时，任一命中即可。 */

internal fun AdClaimFlow.isAdPageStillVisible(): Boolean =
        adWindowActive() ||
            activeAdTextVisible() ||
            scanner.findCountdownSeconds() != null

    /** 最近一次窗口事件上报的类名是否为广告 Activity（ExcitingVideo…）。 */

internal fun AdClaimFlow.lastWindowIsAdClass(): Boolean {
        val cls = AutomationRuntime.lastWindowClassName()
        return cls.isNotBlank() && isAdWindowClass(cls)
    }

    /** 当前窗口类名（简化成短名，便于日志阅读）。 */

internal fun AdClaimFlow.currentWindowLabel(): String {
        val cls = AutomationRuntime.lastWindowClassName()
        return if (cls.isBlank()) "未上报" else cls.substringAfterLast('.')
    }

    /** 入口成功时打一条窗口日志（类名来自无障碍窗口事件，便于确认真的进了广告 Activity）。 */

internal fun AdClaimFlow.logAdWindowConfirmed() {
        val cls = AutomationRuntime.lastWindowClassName()
        if (cls.isBlank()) {
            log(LogLevel.ACTION, "广告窗口已确认：（窗口事件未上报类名，按窗口 root / 节点判定）")
        } else {
            log(LogLevel.ACTION, "广告窗口已确认：$cls")
        }
    }

    /**
     * 是否停在广告页。
     *
     * 判据顺序：① 广告窗口（root 类名含 ExcitingVideo 或窗口事件刚上报）——最稳，不受节点可见性影响；
     * ② 纯文本匹配 AD_PAGE_HINTS（不做 isVisible/面积过滤，因为倒计时节点高度为 0）。
     */

internal fun AdClaimFlow.inAdPage(): Boolean {
        // 第一道闸门：后台残留的广告窗口/节点不算当前页面
        if (!isTargetPackage(actions.currentPackage())) {
            adWindowActiveLastPoll = false
            return false
        }
        return adWindowActive() || scanner.containsAnyTextActive(FlowKeywords.AD_PAGE_HINTS)
    }

    /**
     * 广告窗口是否处于前台（最强判据）：任一 root 是 targetPackage 且类名含 ExcitingVideo；
     * 或无障碍窗口事件刚上报过广告 Activity。首次进入时刷新 [lastAdWindowSeenAt]。
     */

internal fun AdClaimFlow.adWindowActive(): Boolean {
        // 前台不是目标包时，广告窗口不可能在前台
        if (!isTargetPackage(actions.currentPackage())) {
            adWindowActiveLastPoll = false
            return false
        }
        // ① 活动窗口 root 类名含 excitingvideo；② 窗口事件刚上报过广告 Activity（兜底）
        val byRoot = scanner.isAdWindowActive(settings.targetPackage)
        val byEvent = isAdWindowEventFresh()
        val active = byRoot || byEvent
        if (active && !adWindowActiveLastPoll) lastAdWindowSeenAt = System.currentTimeMillis()
        adWindowActiveLastPoll = active
        return active
    }

    /** 窗口事件刚上报过广告 Activity（1.5s 内），用于节点还没刷新时提前判定。 */

internal fun AdClaimFlow.isAdWindowEventFresh(): Boolean {
        val cls = AutomationRuntime.lastWindowClassName()
        if (cls.isBlank() || !cls.contains(AD_WINDOW_CLASS_HINT, ignoreCase = true)) return false
        return System.currentTimeMillis() - AutomationRuntime.lastWindowChangedAt() <= AD_WINDOW_EVENT_FRESH_MS
    }

    /**
     * 是否已在汽水音乐主界面：前台是目标包 + 节点里没有广告文案 + 底部 5 个 tab 任一看得见。
     * 兼容「复位后停在音乐 tab 的歌曲页」这类情况（只要有底部导航算主界面，随后会点「发现」）。
     */

internal fun AdClaimFlow.isOnMainPage(): Boolean =
        isTargetPackage(actions.currentPackage()) &&
            !inAdPage() &&
            (scanner.containsAnyText(FlowKeywords.MAIN_TABS) || isRewardSheetVisible())

internal fun AdClaimFlow.isTargetPackage(pkg: String): Boolean =
        pkg.isNotBlank() && pkg == settings.targetPackage

internal fun AdClaimFlow.isOnDiscoverPage(): Boolean {
        if (!isTargetPackage(actions.currentPackage())) return false
        return scanner.containsAnyTextActive(FlowKeywords.DISCOVER_TAB) ||
            scanner.findByViewIdActive(DISCOVER_ENTRY_VIEW_ID) != null
    }

    /** 是否仍停在主界面（前台是目标包 且 活动窗口底部 tab 任一看得见）。 */

internal fun AdClaimFlow.isOnMainFeed(): Boolean =
        isTargetPackage(actions.currentPackage()) && scanner.containsAnyTextActive(FlowKeywords.MAIN_TABS)

    // ---------- S2 / S3 ----------

    /**
     * 广告链路：每 500ms 采样，按优先级判态（④奖励浮层 > ③完成 > ②播放 > ①提示）。
     *
     * 允许点击的只有：① 提示页「继续观看/继续解锁」（最多 [MAX_PROMPT_CONTINUE] 次）、
     * ② 播放中途的「继续观看」弹窗。
     * 绝不点击：领取奖励 / 再看1个视频 / 坚持退出 / 放弃奖励 / 查看详情 / 立即解锁。
     */
