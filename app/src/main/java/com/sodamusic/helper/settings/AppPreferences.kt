package com.sodamusic.helper.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sodamusic.helper.automation.AutomationSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2 持久化：6 个设置字段 + 今日统计 + 悬浮窗记忆（最小化状态 / 圆点坐标）。
 *
 * v1 的关键词（入口/看广告/关闭/领取）与 OCR/插件相关字段已删除，schema 升到 4，
 * 升级时直接丢弃这些旧键（用户无需再维护关键词）。
 */
class AppPreferences(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * 应用启动时调用：处理跨天重置、清理废弃键，并把规范化后的设置写回一次，
     * 便于用户与调试者在 SharedPreferences 里直接看到可编辑项。
     */
    @Synchronized
    fun initialize() {
        rolloverIfNeeded()
        if (readInt(KEY_SCHEMA_VERSION, 0) < CURRENT_SCHEMA_VERSION) {
            edit {
                LEGACY_KEYS.forEach(::remove)
                putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            }
        }
        writeSettings(readSettings())
    }

    @Synchronized
    fun readSettings(): AutomationSettings {
        val defaults = AutomationSettings()
        return AutomationSettings(
            targetPackage = readString(KEY_TARGET_PACKAGE, defaults.targetPackage)
                .trim()
                .ifBlank { defaults.targetPackage },
            autoLaunchTarget = readBoolean(KEY_AUTO_LAUNCH, defaults.autoLaunchTarget),
            // 数值做兜底裁剪，避免误输入把引擎卡住
            dailyLimit = readInt(KEY_DAILY_LIMIT, defaults.dailyLimit)
                .coerceIn(MIN_DAILY_LIMIT, MAX_DAILY_LIMIT),
            adMaxWaitSeconds = readInt(KEY_AD_MAX_WAIT, defaults.adMaxWaitSeconds)
                .coerceIn(MIN_AD_WAIT_SECONDS, MAX_AD_WAIT_SECONDS),
            minutesPerClaim = readInt(KEY_MINUTES_PER_CLAIM, defaults.minutesPerClaim)
                .coerceIn(0, MAX_MINUTES_PER_CLAIM),
            floatingWindowEnabled = readBoolean(KEY_FLOATING_WINDOW, defaults.floatingWindowEnabled),
            soundEnabled = readBoolean(KEY_SOUND, defaults.soundEnabled),
        )
    }

    @Synchronized
    fun writeSettings(settings: AutomationSettings) {
        edit {
            putString(KEY_TARGET_PACKAGE, settings.targetPackage.trim())
            putBoolean(KEY_AUTO_LAUNCH, settings.autoLaunchTarget)
            putInt(KEY_DAILY_LIMIT, settings.dailyLimit)
            putInt(KEY_AD_MAX_WAIT, settings.adMaxWaitSeconds)
            putInt(KEY_MINUTES_PER_CLAIM, settings.minutesPerClaim)
            putBoolean(KEY_FLOATING_WINDOW, settings.floatingWindowEnabled)
            putBoolean(KEY_SOUND, settings.soundEnabled)
            putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        }
    }

    // region 悬浮窗记忆（最小化状态 + 圆点坐标）

    /** 是否处于"最小化为圆点"状态（默认 false = 完整胶囊）。 */
    @Synchronized
    fun readFloatingMinimized(): Boolean = readBoolean(KEY_FLOATING_MINIMIZED, false)

    @Synchronized
    fun writeFloatingMinimized(minimized: Boolean) {
        edit { putBoolean(KEY_FLOATING_MINIMIZED, minimized) }
    }

    /** 圆点 x 坐标（像素）；[NO_SAVED_POSITION] = 用户还没拖过，用底部居中默认位置。 */
    @Synchronized
    fun readFloatingMinimizedX(): Int = readInt(KEY_FLOATING_MINIMIZED_X, NO_SAVED_POSITION)

    @Synchronized
    fun readFloatingMinimizedY(): Int = readInt(KEY_FLOATING_MINIMIZED_Y, NO_SAVED_POSITION)

    @Synchronized
    fun writeFloatingMinimizedX(x: Int) {
        edit { putInt(KEY_FLOATING_MINIMIZED_X, x) }
    }

    @Synchronized
    fun writeFloatingMinimizedY(y: Int) {
        edit { putInt(KEY_FLOATING_MINIMIZED_Y, y) }
    }

    // endregion

    // region 今日统计

    @Synchronized
    fun readTodayCount(): Int {
        rolloverIfNeeded()
        return readInt(KEY_TODAY_COUNT, 0)
    }

    @Synchronized
    fun readTodayMinutes(): Int {
        rolloverIfNeeded()
        return readInt(KEY_TODAY_MINUTES, 0)
    }

    /** 累计一次领取，返回累计后的今日次数。 */
    @Synchronized
    fun incrementToday(count: Int, minutes: Int): Int {
        rolloverIfNeeded()
        val newCount = readInt(KEY_TODAY_COUNT, 0) + count.coerceAtLeast(0)
        val newMinutes = readInt(KEY_TODAY_MINUTES, 0) + minutes.coerceAtLeast(0)
        edit {
            putInt(KEY_TODAY_COUNT, newCount)
            putInt(KEY_TODAY_MINUTES, newMinutes)
        }
        return newCount
    }

    /** 跨天则把今日统计清零并更新日期。 */
    private fun rolloverIfNeeded() {
        val today = todayKey()
        if (readString(KEY_STATS_DATE, "") == today) return
        edit {
            putString(KEY_STATS_DATE, today)
            putInt(KEY_TODAY_COUNT, 0)
            putInt(KEY_TODAY_MINUTES, 0)
        }
    }

    private fun todayKey(): String = SimpleDateFormat(DATE_PATTERN, Locale.US).format(Date())

    // endregion

    // region 读写模板（避免每个字段重复 get/put 样板）

    private fun readString(key: String, default: String): String = preferences.getString(key, default) ?: default

    private fun readInt(key: String, default: Int): Int = preferences.getInt(key, default)

    private fun readBoolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        preferences.edit(commit = false, action = block)
    }

    // endregion

    private companion object {
        const val FILE_NAME = "soda_music_helper_preferences"

        // 设置字段
        const val KEY_TARGET_PACKAGE = "target_package"
        const val KEY_AUTO_LAUNCH = "auto_launch_target"
        const val KEY_DAILY_LIMIT = "daily_limit"
        const val KEY_AD_MAX_WAIT = "ad_max_wait_seconds"
        const val KEY_MINUTES_PER_CLAIM = "minutes_per_claim"
        const val KEY_FLOATING_WINDOW = "floating_window_enabled"
        const val KEY_SOUND = "claim_sound_enabled"

        // 悬浮窗记忆
        const val KEY_FLOATING_MINIMIZED = "floating_minimized"
        const val KEY_FLOATING_MINIMIZED_X = "floating_minimized_x"
        const val KEY_FLOATING_MINIMIZED_Y = "floating_minimized_y"
        const val NO_SAVED_POSITION = -1

        /** 配置结构版本：v2 精简后升到 4；升级时移除废弃键。 */
        const val KEY_SCHEMA_VERSION = "settings_schema_version"
        const val CURRENT_SCHEMA_VERSION = 4

        /** v1 遗留键（关键词 / OCR / 插件），升级时直接删除。 */
        val LEGACY_KEYS = listOf(
            "entry_keywords",
            "ad_keywords",
            "close_keywords",
            "reward_keywords",
            "ocr_fallback_enabled",
            "ocr_plugin_download_url",
        )

        // 今日统计
        const val KEY_STATS_DATE = "stats_date"
        const val KEY_TODAY_COUNT = "today_count"
        const val KEY_TODAY_MINUTES = "today_minutes"

        const val DATE_PATTERN = "yyyy-MM-dd"

        const val MIN_DAILY_LIMIT = 1
        const val MAX_DAILY_LIMIT = 100
        const val MIN_AD_WAIT_SECONDS = 10
        const val MAX_AD_WAIT_SECONDS = 600
        const val MAX_MINUTES_PER_CLAIM = 600
    }
}
