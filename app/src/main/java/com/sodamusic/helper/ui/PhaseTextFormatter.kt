package com.sodamusic.helper.ui

import android.content.Context
import com.sodamusic.helper.R
import com.sodamusic.helper.automation.AutomationUiState
import com.sodamusic.helper.automation.EnginePhase

/**
 * 阶段文案统一出口：首页 hero 与悬浮窗胶囊都用它，避免两处映射漂移。
 *
 * - 非 compact（首页 hero）：广告播放中（带秒数）> 引擎写入的 phaseDetail > 按 EnginePhase 兜底的长句
 * - compact（悬浮窗胶囊）：**短句 + 数字**，每句 ≤ 6 个字符，保证 68dp 宽一行放得下
 */
object PhaseTextFormatter {

    /** 引擎把「让行」状态写在 phaseDetail 里时的识别关键字。 */
    private const val YIELD_HINT = "让行"

    fun format(context: Context, state: AutomationUiState, compact: Boolean = false): String {
        if (compact) return formatCompact(context, state)
        if (state.phase == EnginePhase.WATCHING_AD && state.adMaxSeconds > 0) {
            return context.getString(R.string.phase_watching_ad, state.adElapsedSeconds)
        }
        if (state.phaseDetail.isNotBlank()) return state.phaseDetail
        return when (state.phase) {
            EnginePhase.IDLE -> context.getString(R.string.hero_subtitle_idle)
            EnginePhase.LAUNCHING -> context.getString(R.string.phase_launching)
            EnginePhase.NAVIGATING -> context.getString(R.string.phase_navigating)
            EnginePhase.SCANNING -> context.getString(R.string.phase_scanning)
            EnginePhase.CLICK_AD -> context.getString(R.string.phase_click_ad)
            EnginePhase.WATCHING_AD -> context.getString(R.string.phase_watching_ad, state.adElapsedSeconds)
            EnginePhase.CLOSING_AD -> context.getString(R.string.phase_closing_ad)
            EnginePhase.CLAIMING -> context.getString(R.string.phase_claiming)
            // 冷却中引擎会写 phaseDetail（如"第 N 轮结束…"），兜底文案不带秒数
            EnginePhase.COOLDOWN -> context.getString(R.string.phase_cooldown_idle)
            EnginePhase.STOPPED -> context.getString(R.string.hero_subtitle_idle)
            EnginePhase.ERROR -> state.lastError ?: context.getString(R.string.phase_error)
        }
    }

    /**
     * 悬浮窗专用短文案：
     * 待机 / 入口 / 12s / 已领 / 复位 / 等回 / 停止 / 出错 …（竖排每行一个字，最长 3 行）
     */
    private fun formatCompact(context: Context, state: AutomationUiState): String {
        // 广告播放：优先剩余秒数，竖排显示为「12」+ 换行 +「s」；没有倒计时则「广告」
        if (state.phase == EnginePhase.WATCHING_AD) {
            if (state.adMaxSeconds > 0) {
                val remainingSeconds = (state.adMaxSeconds - state.adElapsedSeconds).coerceAtLeast(0)
                return context.getString(R.string.phase_compact_ad_countdown, remainingSeconds)
            }
            return context.getString(R.string.phase_compact_ad)
        }
        // 让行（前台被其它 App 占用）：引擎只写在 phaseDetail 里
        if (state.phaseDetail.contains(YIELD_HINT)) {
            return context.getString(R.string.phase_compact_yield)
        }
        return when (state.phase) {
            EnginePhase.IDLE -> context.getString(R.string.phase_compact_idle)
            EnginePhase.LAUNCHING -> context.getString(R.string.phase_compact_launching)
            EnginePhase.NAVIGATING -> context.getString(R.string.phase_compact_navigating)
            EnginePhase.SCANNING -> context.getString(R.string.phase_compact_scanning)
            EnginePhase.CLICK_AD -> context.getString(R.string.phase_compact_click_ad)
            EnginePhase.WATCHING_AD -> context.getString(R.string.phase_compact_ad)
            EnginePhase.CLOSING_AD -> context.getString(R.string.phase_compact_closing_ad)
            EnginePhase.CLAIMING -> context.getString(R.string.phase_compact_claim)
            EnginePhase.COOLDOWN -> context.getString(R.string.phase_compact_reset)
            EnginePhase.STOPPED -> context.getString(R.string.phase_compact_stopped)
            EnginePhase.ERROR -> context.getString(R.string.phase_compact_error)
        }
    }
}
