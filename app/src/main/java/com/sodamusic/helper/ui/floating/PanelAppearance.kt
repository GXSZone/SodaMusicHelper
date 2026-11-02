package com.sodamusic.helper.ui.floating

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import androidx.core.content.ContextCompat
import com.sodamusic.helper.R
import com.sodamusic.helper.automation.AutomationUiState
import com.sodamusic.helper.databinding.FloatingMinimizedBinding
import com.sodamusic.helper.databinding.FloatingPanelBinding
import com.sodamusic.helper.ui.PhaseTextFormatter

/** 悬浮窗尺寸/手势规格（dp，用时经 [dp] 换算成像素）。 */
internal object PanelSpec {
    const val CAPSULE_WIDTH_DP = 52
    const val CAPSULE_HEIGHT_DP = 104
    const val CAPSULE_CORNER_DP = 26f
    const val CAPSULE_START_X_DP = 24
    const val CAPSULE_START_Y_RATIO = 0.45f

    const val DOT_TOUCH_DP = 44
    const val DOT_VISUAL_DP = 36
    /** 圆点距可见底部（导航栏之上）的间隙。 */
    const val DOT_BOTTOM_GAP_DP = 48

    const val DRAG_THRESHOLD_DP = 12f
    const val DRAG_ALPHA = 0.85f
    const val DOUBLE_TAP_MS = 300L
    const val BREATH_DURATION_MS = 1200L
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

/** 渐变胶囊 / 圆点 / 状态点的视觉生成，全部是"给视图刷样式"的纯函数（不持有状态，除呼吸动画对象）。 */
internal object PanelAppearance {

    /** 1dp 半透明白描边。 */
    private const val STROKE_COLOR = 0x40FFFFFF
    /** 底部偏移阴影。 */
    private const val SHADOW_COLOR = 0x33000000
    /** 状态点外圈光晕（白色 18%）。 */
    private const val HALO_COLOR = 0x2EFFFFFF
    /** 按钮底：常态白色 18% / 按下 60% / 禁用 35%。 */
    private const val BUTTON_NORMAL_COLOR = 0x2EFFFFFF
    private val BUTTON_PRESSED_COLOR = 0x99FFFFFF.toInt()
    private const val BUTTON_DISABLED_COLOR = 0x59FFFFFF
    /** 状态点：运行中亮绿，待机半透明白。 */
    private val DOT_RUNNING_COLOR = 0xFFB9F6CA.toInt()
    private val DOT_IDLE_COLOR = 0x80FFFFFF.toInt()

    /** 胶囊：渐变底 + 描边 + 底部 2dp 偏移阴影 + 光晕 + 圆形按钮底。 */
    fun styleCapsule(context: Context, binding: FloatingPanelBinding) {
        with(binding) {
            root.background = pill(
                context = context,
                cornerDp = PanelSpec.CAPSULE_CORNER_DP,
                shape = GradientDrawable.RECTANGLE,
            )
            root.alpha = 1f
            floatingStatusHalo.background = oval(HALO_COLOR)
            floatingPhaseText.setTextColor(Color.WHITE)
            floatingActionButton.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), oval(BUTTON_PRESSED_COLOR))
                addState(intArrayOf(-android.R.attr.state_enabled), oval(BUTTON_DISABLED_COLOR))
                addState(intArrayOf(), oval(BUTTON_NORMAL_COLOR))
            }
            floatingActionButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
    }

    /** 圆点：同款渐变 + 描边 + 阴影，中心状态点待机色。 */
    fun styleMinimized(context: Context, binding: FloatingMinimizedBinding) {
        with(binding) {
            floatingMinimizedCircle.background = pill(
                context = context,
                cornerDp = PanelSpec.DOT_VISUAL_DP / 2f,
                shape = GradientDrawable.OVAL,
            )
            paint(dot = floatingMinimizedDot, running = false)
            root.alpha = 1f
        }
    }

    /** 胶囊内容：阶段文案 + 状态点 + 按钮图标/无障碍文案。 */
    fun renderCapsule(
        context: Context,
        binding: FloatingPanelBinding,
        state: AutomationUiState,
        breath: BreathingAnimator,
    ) {
        with(binding) {
            floatingPhaseText.text = PhaseTextFormatter.format(context, state, compact = true)
            paint(dot = floatingStatusDot, running = state.running)
            breath.update(state.running)
            floatingActionButton.setImageResource(if (state.running) R.drawable.ic_stop else R.drawable.ic_play)
            floatingActionButton.contentDescription = context.getString(
                if (state.running) R.string.action_stop else R.string.action_start,
            )
        }
    }

    /** 圆点内容：只有中心状态色（运行=亮绿，待机=半透明白）。 */
    fun renderMinimized(binding: FloatingMinimizedBinding, running: Boolean) {
        paint(dot = binding.floatingMinimizedDot, running = running)
    }

    private fun paint(dot: View, running: Boolean) {
        dot.background = oval(if (running) DOT_RUNNING_COLOR else DOT_IDLE_COLOR)
    }

    private fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** 渐变主体 + 1dp 白描边 + 下移 2dp 的暗色阴影（LayerDrawable 叠加，不依赖 drawable/）。 */
    private fun pill(context: Context, cornerDp: Float, shape: Int): LayerDrawable {
        val radius = context.dp(cornerDp).toFloat()
        val shadow = GradientDrawable().apply {
            this.shape = shape
            cornerRadius = radius
            setColor(SHADOW_COLOR)
        }
        val body = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                ContextCompat.getColor(context, R.color.hero_gradient_start),
                ContextCompat.getColor(context, R.color.hero_gradient_end),
            ),
        ).apply {
            this.shape = shape
            cornerRadius = radius
            setStroke(context.dp(1f), STROKE_COLOR)
        }
        val offset = context.dp(2f)
        return LayerDrawable(arrayOf(shadow, body)).apply {
            setLayerInset(0, 0, offset, 0, 0)
            setLayerInset(1, 0, 0, 0, offset)
        }
    }
}

/** 状态点光晕的呼吸动画（运行中 alpha 0.5↔1，停止时复位）。 */
internal class BreathingAnimator(private val halo: View) {

    private var animator: ObjectAnimator? = null

    fun update(running: Boolean) {
        if (running) {
            if (animator == null) {
                animator = ObjectAnimator.ofFloat(halo, View.ALPHA, 0.5f, 1f).apply {
                    duration = PanelSpec.BREATH_DURATION_MS
                    repeatMode = ObjectAnimator.REVERSE
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
            }
        } else {
            cancel()
            halo.alpha = 1f
        }
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }
}
