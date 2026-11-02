package com.sodamusic.helper.ui.floating

/** 悬浮窗的两种形态，用 sealed 让分支保持穷尽。 */
internal sealed interface PanelMode {
    /** 完整胶囊：52x104dp，可拖动、双击最小化、按钮起停。 */
    data object Capsule : PanelMode

    /** 最小化圆点：36dp 视觉 + 44dp 热区，点按/长按恢复、可拖动。 */
    data object Minimized : PanelMode
}

/** 日志/无障碍文案用的中文形态名。 */
internal val PanelMode.label: String
    get() = when (this) {
        PanelMode.Capsule -> "胶囊"
        PanelMode.Minimized -> "圆点"
    }

