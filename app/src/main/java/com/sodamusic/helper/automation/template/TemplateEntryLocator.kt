package com.sodamusic.helper.automation.template

/**
 * 入口图标模板定位（第 4 档兜底）的**抽象**。
 *
 * 为什么是接口：OpenCV（assists-opcv）只打进 debug 包，release 包不能引用任何
 * com.ven.assists.opcv / org.opencv 符号；debug 源集实现它并通过
 * [TemplateEntryLocatorHolder.install] 注入，main 只面向接口编程。
 */
interface TemplateEntryLocator {
    /** 在当前屏幕里找入口图标；找到返回屏幕坐标（中心点），找不到/不可用返回 null。 */
    suspend fun locateEntry(screenWidth: Int, screenHeight: Int): Pair<Int, Int>?
}

/** 模板定位实现的全局持有者（release 下永远为 null，调用方只打一条 debug 日志并跳过）。 */
object TemplateEntryLocatorHolder {
    @Volatile
    var impl: TemplateEntryLocator? = null
        private set

    fun install(i: TemplateEntryLocator) {
        impl = i
    }

    /** 上一次模板匹配的 maxVal（-1 表示还没跑过/不可用），仅用于日志排查阈值。 */
    @Volatile
    var lastMatchScore: Double = -1.0

    /** 上一次模板匹配的附加说明（模板缺失 / 截图失败 / 分数不足等）。 */
    @Volatile
    var lastMatchNote: String = "尚未调用"

    /** 入口图标模板的 assets 路径（debug 实现使用）。 */
    const val TEMPLATE_ASSET = "templates/discover_entry.png"

    /** 模板匹配阈值。 */
    const val MATCH_THRESHOLD = 0.80
}
