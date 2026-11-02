package com.sodamusic.helper.automation.flow

import com.sodamusic.helper.automation.FlowKeywords
import com.sodamusic.helper.automation.NodeScanner

// automation 层单一常量来源：关键词、坐标比例、阈值、时间参数。
// 用顶层常量（不是 object），各文件 import ...flow.* 即可直接引用。

/** stepper2 step tag（日志排查用，不影响执行）。 */
internal const val STEP_OPEN_ROUND = "A-open-round"
internal const val STEP_ENTRY = "B-entry"
internal const val STEP_WATCH = "C-watch-ad"
internal const val STEP_SETTLE = "D-settle"

/** 领取成功浮窗提示时长（毫秒）。 */
internal const val CLAIM_TOAST_MS = 1500L

/** 领取成功提示音（assets 路径，文件缺失时静默）。 */
internal const val CLAIM_SOUND_ASSET = "sounds/claim.mp3"

/**
 * 汽水音乐首页 Activity 的完整类名：显式启动它才能把广告 Activity 从同一 task 顶部顶掉。
 * 实测 am start -n com.luna.music/com.luna.biz.main.main.MainActivity 可立即退回主界面。
 */
internal const val MAIN_ACTIVITY_CLASS = "com.luna.biz.main.main.MainActivity"

/** 右上角入口的 resource-id（真机实测抓得到）。 */
internal const val DISCOVER_ENTRY_VIEW_ID = "com.luna.music:id/vu"

/** 底部「发现」tab 与右上角入口的坐标兜底（PLK110 / 1272x2772 实测比例）。 */
internal const val DISCOVER_TAB_X_RATIO = 0.10f
internal const val DISCOVER_TAB_Y_RATIO = 0.93f
// 入口徽章实测：手动点 (1090, 218) @1272x2772 立刻进广告；
// 原来的 0.90/0.079 → (1145,219) 落在图标右侧的「15天」文字上，点不中。
internal const val DISCOVER_ENTRY_X_RATIO = 0.857f
internal const val DISCOVER_ENTRY_Y_RATIO = 0.0787f

/** 入口徽章区域（实测 1272x2772 上的 x∈[1000,1240]、y∈[140,320] 换算成比例）。 */
internal const val ENTRY_REGION_X_MIN_RATIO = 1000f / 1272f
internal const val ENTRY_REGION_X_MAX_RATIO = 1240f / 1272f
internal const val ENTRY_REGION_Y_MIN_RATIO = 140f / 2772f
internal const val ENTRY_REGION_Y_MAX_RATIO = 320f / 2772f

/** 倒计时归零后的复查等待（防素材切换抖动）。 */
internal const val COUNTDOWN_ZERO_CONFIRM_MS = 1000L

/** 广告窗口消失需要连续多少次采样（500ms/次，3 次≈1.5s，且期间无广告文案）才当作完成候选。 */
internal const val WINDOW_GONE_POLLS = 3

/** 窗口消失判据生效前，至少已播放的秒数（防止刚进广告就误判）。 */
internal const val MIN_PLAY_SECONDS_BEFORE_WINDOW_GONE = 5

/** 窗口消失候选后的复查等待。 */
internal const val WINDOW_GONE_CONFIRM_MS = 800L

/** 播放态文案连续消失多久仍未见窗口变化，才按超时处理（20s）。 */
internal const val TEXT_MISSING_TIMEOUT_MS = 20_000L

/** 提示页「继续观看」最多点几次，超过按本轮失败处理（不判 no-stock）。 */
internal const val MAX_PROMPT_CONTINUE = 3

/** 提示页点完「继续观看」后的观察窗口。 */
internal const val PROMPT_SETTLE_MS = 1500L

/** 复位后等奖励浮层出现的时长 / 关闭动作的观察窗口。 */
internal const val REWARD_SHEET_DETECT_MS = 1000L
internal const val REWARD_SHEET_SETTLE_MS = 700L

/** 连续多少轮卡在提示页/拿不到有效广告就结束本次运行。 */
internal const val MAX_PROMPT_FAIL_ROUNDS = 3

/** 「领取成功」出现但一直没有播放态证据时，最多观察几次轮询（500ms/次）后丢弃本轮。 */
internal const val DONE_WITHOUT_PLAYING_POLLS = 6

/** 倒计时日志最小间隔（剩余 >5s 时按此降频，避免每 500ms 刷屏）。 */
internal const val COUNTDOWN_LOG_INTERVAL_MS = 5000L

/** 奖励浮层文案里「第N天」的解析。 */
internal val REWARD_DAY_REGEX = Regex("第\\s*(\\d{1,2})\\s*天")

/** 奖励浮层「关闭」类节点文本（节点优先路径）。 */
internal val REWARD_SHEET_CLOSE_KEYWORDS = listOf("关闭", "×", "X", "close", "Close")

/** 「已离开主界面但没读到广告文案」时的复查等待。 */
internal const val ENTRY_SETTLE_RECHECK_MS = 800L

/** 提示页强特征（出现即认定提示页）。 */
internal val PROMPT_STRONG_TEXTS = listOf("继续解锁", "继续观看")

/** 提示页弱特征：「再看」必须与「免费听」同时出现才成立（「免费听13天」是主界面常驻角标）。 */
internal const val PROMPT_WEAK_TEXT = "再看"
internal const val PROMPT_WEAK_COMPANION = "免费听"

/** 广告链路判定文案：播放态 + 领取成功 + 提示页强特征（**绝不含「免费听」等主界面常驻词**）。 */
internal val AD_PAGE_TEXTS = FlowKeywords.PLAYING_HINTS + FlowKeywords.AD_DONE + PROMPT_STRONG_TEXTS

/** 奖励浮层强特征（唯一性较高）。 */
internal val REWARD_SHEET_STRONG_TEXTS = listOf("免费时长畅听", "看2个视频", "看1个视频")

/** 奖励浮层弱特征：命中 ≥2 个才认定为浮层（单命可能是主界面文案）。 */
internal val REWARD_SHEET_SECONDARY_TEXTS = listOf("已领取", "立即解锁", "提前享")

/** 开轮「不在主界面」时先 back 一次再查的等待。 */
internal const val OPEN_ROUND_BACK_SETTLE_MS = 900L

/** 广告后免单/奖励弹窗的特征文案（命中 ≥2 个即认定）。 */
internal val POST_AD_UPSELL_TEXTS = listOf("领取奖励", "坚持退出", "立即使用")

/** 直播间判定：窗口类名特征 + 特征文案（说点什么 / 观众 / 礼物 / 直播间）。 */
internal val LIVE_ROOM_CLASS_HINTS = listOf("LivePlayer", "LunaSmoothLive")
internal val LIVE_ROOM_TEXTS = listOf("观众", "礼物", "直播间")

/** 直播间关闭：点击后等待、以及显式拉起主界面后的等待（毫秒）。 */
internal const val LIVE_ROOM_CLOSE_SETTLE_MS = 900L
internal const val LIVE_ROOM_RELAUNCH_WAIT_MS = 1500L

/** 我们按 back 之后多久内的「非目标前台」认定为 back 造成的（毫秒）。 */
internal const val BACK_CAUSED_EXIT_WINDOW_MS = 3000L

/** 结算类临时浮层的类名特征（这些不是广告 Activity，无广告文案时视为已离开广告页）。 */
internal val TRANSIENT_OVERLAY_CLASS_HINTS = listOf("Sheet", "Dialog", "BottomSheet")

/** 广告窗口类名特征（与 NodeScanner.AD_WINDOW_CLASS_HINT 一致）。 */
internal const val AD_WINDOW_CLASS_HINT = NodeScanner.AD_WINDOW_CLASS_HINT

/** 无障碍窗口事件新鲜度：事件到达后多久内可直接判定「已在广告」。 */
internal const val AD_WINDOW_EVENT_FRESH_MS = 1500L

/** 广告落地页劫持时，先按返回键试探的次数与每次等待。 */
internal const val HIJACK_BACK_ATTEMPTS = 2
internal const val HIJACK_BACK_SETTLE_MS = 900L

/** 复位成功后多久内出现的第三方前台，判定为「广告落地页残留跳转」（按劫持处理，不让行）。 */
internal const val POST_RESET_HIJACK_WINDOW_MS = 10_000L

/** 每轮最多处理几次广告落地页残留跳转，超过才让行。 */
internal const val MAX_POST_RESET_HIJACKS = 3

/** 广告落地页残留场景的让行时长（远短于用户在用手机的 60s）。 */
internal const val POST_RESET_YIELD_MAX_MS = 15_000L

/** 连续多少轮广告落地页残留都无法恢复，就结束本次运行。 */
internal const val MAX_POST_RESET_GIVEUP = 3

/** 复位前按返回键收起覆盖层后的等待。 */
internal const val COVER_BACK_SETTLE_MS = 700L

/** 开轮前台分流最多重试几轮（自己/桌面/系统界面这些可恢复情形）。 */
internal const val OPEN_ROUND_FOREGROUND_ATTEMPTS = 4

/** Android 系统界面包名（通知栏/状态栏下拉时前台是它）。 */
internal const val SYSTEM_UI_PACKAGE = "com.android.systemui"

/** 常见桌面包名兜底（PackageManager 因包可见性解析不到时用）。 */
internal val KNOWN_LAUNCHER_PACKAGES = listOf(
    "com.miui.home",
    "com.oppo.launcher",
    "com.huawei.android.launcher",
    "com.hihonor.android.launcher",
    "com.android.launcher3",
    "com.google.android.apps.nexuslauncher",
    "com.bbk.launcher2",
    "com.sec.android.app.launcher",
    "com.android.launcher",
)

/** 连续多少次「未进广告链路」判定无库存并结束。 */
internal const val MAX_NO_STOCK = 3

/** 我们自己的包名：前台是它说明是用户自己打开助手，不算广告劫持。 */
internal const val SELF_PACKAGE = "com.sodamusic.helper"

/** 刚进入广告页多久内的第三方前台判定为「广告落地页劫持」（毫秒）。 */
internal const val AD_HIJACK_WINDOW_MS = 30_000L

/** 单轮内最多容忍几次广告劫持复位（超过则结束该轮，防死循环）。 */
internal const val MAX_HIJACKS_PER_ROUND = 3

/** 连续多少轮都因劫持超限而结束，就结束本次运行。 */
internal const val MAX_HIJACK_ROUNDS = 3

/** 劫持复位后重新进入广告前的等待。 */
internal const val HIJACK_RETRY_DELAY_MS = 1500L

internal const val WATCH_POLL_MS = 500L
internal const val VERIFY_POLL_MS = 300L
internal const val CONTINUE_SETTLE_MS = 800L
internal const val TAB_SETTLE_MS = 1200L

/** 「发现」tab 递减策略：back 后等待 / 坐标点击后的校验窗口 / 坐标最多点几次。 */
internal const val TAB_BACK_SETTLE_MS = 600L
internal const val TAB_COORD_VERIFY_MS = 1200L
internal const val DISCOVER_TAB_COORD_ATTEMPTS = 2
/** 入口点击后的校验窗口：真机节点刷新有延迟（1.2s 还读不到，2s 足够），内部 300ms 轮询。 */
internal const val ENTRY_VERIFY_MS = 2000L
internal const val NO_STOCK_BACKOFF_MS = 2000L
internal const val ROUND_SETTLE_MS = 1200L
internal const val LAUNCH_WAIT_MIN_MS = 2000L
internal const val LAUNCH_WAIT_MAX_MS = 3000L

/** 前台仍是我们自己时，拉起重试次数与间隔。 */
internal const val LAUNCH_ATTEMPTS = 3
internal const val LAUNCH_RETRY_INTERVAL_MS = 1500L

/** S4 复位：校验窗口 / 轮询 / 成功后停顿。 */
internal const val RESET_VERIFY_MS = 1500L
internal const val RESET_POLL_MS = 300L
internal const val RESET_SETTLE_MS = 800L

/** S5 让行：轮询 / 最长等待 / 日志节流。 */
internal const val YIELD_POLL_MS = 2000L
internal const val YIELD_MAX_MS = 60_000L
internal const val YIELD_LOG_INTERVAL_MS = 10_000L

/** 观看广告期间校验前台包名的间隔（秒）。 */
internal const val FOREGROUND_CHECK_SECONDS = 2
