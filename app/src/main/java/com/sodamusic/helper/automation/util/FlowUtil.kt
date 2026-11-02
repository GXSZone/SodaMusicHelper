package com.sodamusic.helper.automation.util
import com.sodamusic.helper.automation.flow.*
import com.sodamusic.helper.automation.FlowKeywords
import com.sodamusic.helper.automation.util.*

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

// 小工具。

internal suspend fun AdClaimFlow.tapCoordSafely(xRatio: Float, yRatio: Float): Boolean {
        if (!canTapSafely()) return false
        if (scanner.containsAnyText(FlowKeywords.PLAYING_HINTS) || scanner.findCountdownSeconds() != null) {
            log(LogLevel.WARN, "当前处于广告播放态，拒绝坐标点击（说明广告其实已开始，按已进入处理）")
            return false
        }
        return actions.tapRatio(xRatio, yRatio)
    }

    /** 提示页「继续观看」：节点优先，坐标 [FlowKeywords.PROMPT_CONTINUE_X]/Y 兜底。 */

internal fun AdClaimFlow.playedSecondsSince(startedAt: Long): Int =
        if (startedAt <= 0L) 0 else ((System.currentTimeMillis() - startedAt) / 1000L).toInt()

    /** 「广告中跳走」的返回试探结果。 */

internal fun AdClaimFlow.setPhase(phase: EnginePhase, detail: String) {
        updateState { copy(phase = phase, phaseDetail = detail) }
    }

internal fun AdClaimFlow.log(level: LogLevel, message: String) = AutomationRuntime.log(level, message)

internal fun AdClaimFlow.updateState(block: AutomationUiState.() -> AutomationUiState) =
        AutomationRuntime.updateState(block)

    /** 一次观看的结果（由 watchAd 返回，step C 据此决定下一步）。 */

internal fun AdClaimFlow.canTapSafely(): Boolean {
        val pkg = actions.currentPackage()
        updateState { copy(foregroundPackage = pkg) }
        val allowed = isTargetPackage(pkg)
        if (!allowed) log(LogLevel.WARN, "前台 ${pkg.ifBlank { "未知" }} 不是目标应用，跳过点击")
        return allowed
    }

