package com.sodamusic.helper.ui

import com.sodamusic.helper.automation.AutomationSettings
import com.sodamusic.helper.databinding.DialogAdvancedSettingsBinding

/**
 * 高级设置弹窗的表单值 → 设置模型。
 *
 * 弹窗只暴露 5 个字段（包名 / 每日上限 / 广告等待 / 每轮时长 / 提示音），
 * 其余字段（floatingWindowEnabled 等）由各自入口管理，合并时用 AutomationSettings.copy 原样保留。
 */
internal data class SettingsForm(
    val targetPackage: String,
    val dailyLimit: Int,
    val adMaxWaitSeconds: Int,
    val minutesPerClaim: Int,
    val soundEnabled: Boolean,
) {
    /** 合并回设置模型：同一个 copy 搞定，其余字段不动。 */
    fun applyTo(current: AutomationSettings): AutomationSettings = current.copy(
        targetPackage = targetPackage,
        dailyLimit = dailyLimit.coerceIn(MIN_DAILY_LIMIT, MAX_DAILY_LIMIT),
        adMaxWaitSeconds = adMaxWaitSeconds.coerceIn(MIN_AD_WAIT, MAX_AD_WAIT),
        minutesPerClaim = minutesPerClaim.coerceIn(MIN_MINUTES, MAX_MINUTES),
        soundEnabled = soundEnabled,
    )

    companion object {
        private const val MIN_DAILY_LIMIT = 1
        private const val MAX_DAILY_LIMIT = 100
        private const val MIN_AD_WAIT = 10
        private const val MAX_AD_WAIT = 600
        private const val MIN_MINUTES = 1
        private const val MAX_MINUTES = 600

        /** 把当前设置回填到弹窗控件，并返回初始表单值。 */
        fun of(current: AutomationSettings, binding: DialogAdvancedSettingsBinding): SettingsForm {
            binding.editTargetPackage.setText(current.targetPackage)
            binding.editDailyLimit.setText(current.dailyLimit.toString())
            binding.editAdWait.setText(current.adMaxWaitSeconds.toString())
            binding.editMinutesPerClaim.setText(current.minutesPerClaim.toString())
            binding.switchClaimSound.isChecked = current.soundEnabled
            return SettingsForm(
                targetPackage = current.targetPackage,
                dailyLimit = current.dailyLimit,
                adMaxWaitSeconds = current.adMaxWaitSeconds,
                minutesPerClaim = current.minutesPerClaim,
                soundEnabled = current.soundEnabled,
            )
        }

        /** 读回用户输入（非法/空值回落到当前设置）。 */
        fun read(binding: DialogAdvancedSettingsBinding, current: AutomationSettings): SettingsForm = SettingsForm(
            targetPackage = binding.editTargetPackage.text?.toString().orEmpty().trim()
                .ifBlank { current.targetPackage },
            dailyLimit = binding.editDailyLimit.text?.toString()?.toIntOrNull() ?: current.dailyLimit,
            adMaxWaitSeconds = binding.editAdWait.text?.toString()?.toIntOrNull() ?: current.adMaxWaitSeconds,
            minutesPerClaim = binding.editMinutesPerClaim.text?.toString()?.toIntOrNull()
                ?: current.minutesPerClaim,
            soundEnabled = binding.switchClaimSound.isChecked,
        )
    }
}
