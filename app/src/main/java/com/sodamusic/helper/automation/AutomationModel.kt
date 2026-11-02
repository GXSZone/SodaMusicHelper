package com.sodamusic.helper.automation

/** 自动领取流程所处的阶段。 */
enum class EnginePhase {
    IDLE,
    LAUNCHING,
    NAVIGATING,
    SCANNING,
    CLICK_AD,
    WATCHING_AD,
    CLOSING_AD,
    CLAIMING,
    COOLDOWN,
    STOPPED,
    ERROR,
}

/** 日志级别，UI 按级别着色。 */
enum class LogLevel {
    INFO,
    ACTION,
    SUCCESS,
    WARN,
    ERROR,
}

/** 一条运行日志。id 用于列表稳定刷新。 */
data class LogEntry(
    val id: Long,
    val timeMillis: Long,
    val level: LogLevel,
    val message: String,
)

/** UI 直接渲染的运行时状态快照。 */
data class AutomationUiState(
    val running: Boolean = false,
    val phase: EnginePhase = EnginePhase.IDLE,
    val phaseDetail: String = "",
    val foregroundPackage: String = "",
    val todayCount: Int = 0,
    val todayMinutes: Int = 0,
    val adElapsedSeconds: Int = 0,
    val adMaxSeconds: Int = 0,
    val round: Int = 0,
    val lastError: String? = null,
) {
    val adProgress: Float
        get() = if (adMaxSeconds <= 0) 0f else (adElapsedSeconds.toFloat() / adMaxSeconds).coerceIn(0f, 1f)
}

/**
 * 可持久化的自动化配置（v2：只保留真正需要用户决定的东西）。
 *
 * 关键词、入口坐标、广告页判定文案等全部内置为常量，用户无需设置。
 */
data class AutomationSettings(
    val targetPackage: String = DEFAULT_TARGET_PACKAGE,
    val autoLaunchTarget: Boolean = true,
    val dailyLimit: Int = 10,
    val adMaxWaitSeconds: Int = 120,
    val minutesPerClaim: Int = 30,
    val floatingWindowEnabled: Boolean = true,
    /** 领取成功提示音开关（读 assets/sounds/claim.mp3，文件缺失时静默）。 */
    val soundEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_TARGET_PACKAGE = "com.luna.music"
    }
}

/**
 * 流程内置常量（v2 常量集中处，改版时只改这里）。
 *
 * 页面判定全部基于无障碍节点文本/描述；识别不到才用坐标兜底。
 */
object FlowKeywords {
    /** 底部导航文字，用于判断是否在主界面。 */
    val MAIN_TABS = listOf("发现", "听抖音", "音乐", "福利", "我的")

    /** 底部「发现」tab 文案。 */
    val DISCOVER_TAB = listOf("发现")

    // ---------------------------------------------------------------
    // 广告链路的四个页面态（真机截图逐态确认，2026-11-01 22:38）
    //
    //  ① 提示页  ：「再看29秒继续解锁 / 第9日免费听 / 继续观看 / 放弃奖励」
    //  ② 播放页   ：左上「广告」，右上「NN秒后可领取奖励」，底部「查看详情」
    //  ③ 完成弹窗 ：「领取成功 ✕ / 再看1个视频提前得 / 领取奖励 / 坚持退出」
    //  ④ 奖励浮层 ：「恭喜获得第N天免费时长畅听 / 第N天畅听 已领取 / 立即解锁第N天畅听」
    // ---------------------------------------------------------------

    /**
     * ① 提示页特征。
     *
     * 注意：**不要**把「免费听」放进来 —— 汽水音乐主界面右下角常驻「免费听13天」权益角标，
     * 放进来会让主界面被判成广告页（曾经导致"开轮永远是界面残留 → 复位失败"）。
     */
    val PROMPT_HINTS = listOf("继续解锁", "继续观看", "再看")

    /** ① 提示页里唯一允许点击的项（开始播放）。 */
    val PROMPT_CONTINUE = listOf("继续观看", "继续解锁")

    /** ① 提示页里的负向项，绝不点击。 */
    val PROMPT_FORBIDDEN = listOf("放弃奖励", "立即购买", "查看详情", "立即解锁")

    /** ② 播放页特征（注意真机有「秒后可领奖励」缺「取」字的变体）。 */
    val PLAYING_HINTS = listOf("秒后可领取奖励", "秒后可领奖励")

    /** ② 播放页里的负向项（点击会跳详情页/电商页）。 */
    val PLAYING_FORBIDDEN = listOf("查看详情", "立即购买", "极速下载", "立即安装")

    /** ② 中途弹窗里允许点击的项。 */
    val AD_CONTINUE = listOf("继续观看")

    /** ③ 完成判据：弹窗顶部「领取成功」。 */
    val AD_DONE = listOf("领取成功")

    /** ③ 完成弹窗里的负向项：点了会再跳一个视频或放弃奖励，一律不点。 */
    val AD_QUIT = listOf("坚持退出", "换一个", "领取奖励", "再看1个视频", "再看2个视频")

    /** ④ 领取成功后盖在主界面上的奖励浮层特征（会遮住底部 tab）。 */
    val REWARD_SHEET_HINTS = listOf("免费时长畅听", "已领取", "立即解锁", "提前享", "看2个视频", "看1个视频")

    /** ④ 奖励浮层里绝不点击的项。 */
    val REWARD_SHEET_FORBIDDEN = listOf("立即解锁", "看2个视频", "看1个视频", "立即购买", "开通")

    /** 广告页整体特征（用于「是否还在广告链路里」的粗判定）。 */
    val AD_PAGE_HINTS = PLAYING_HINTS + PROMPT_HINTS + AD_DONE

    /** 倒计时文案里的秒数正则（兼容「34秒后可领取奖励」「29秒后可领奖励」）。 */
    val AD_COUNTDOWN_REGEX = Regex("(\\d{1,3})\\s*秒")

    /** 字面量兜底：倒计时文案整体特征。 */
    const val AD_COUNTDOWN_HINT = "秒后可领"

    /** ④ 奖励浮层右上角关闭 X 的坐标比例（1272×2772 实测 ≈ (1126,1403)）。 */
    const val REWARD_SHEET_CLOSE_X = 0.885f
    const val REWARD_SHEET_CLOSE_Y = 0.506f

    /** ① 提示页「继续观看」按钮的坐标兜底比例（实测 ≈ (628,1520)）。 */
    const val PROMPT_CONTINUE_X = 0.494f
    const val PROMPT_CONTINUE_Y = 0.548f

    /** ② 播放页右上角关闭 X 的坐标比例（实测 ≈ (1176,158)）。 */
    const val AD_CLOSE_X = 0.925f
    const val AD_CLOSE_Y = 0.057f
}
