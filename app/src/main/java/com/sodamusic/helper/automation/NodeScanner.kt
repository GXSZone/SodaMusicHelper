package com.sodamusic.helper.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.ven.assists.AssistsCore
import com.ven.assists.utils.AssistsNodeClassNames

/** 一次关键词扫描命中的节点快照。node 只在本次扫描内有效，点击前需重新校验。 */
data class NodeHit(
    val node: AccessibilityNodeInfo,
    val text: String,
    val bounds: Rect,
) {
    val centerX: Float get() = bounds.exactCenterX()
    val centerY: Float get() = bounds.exactCenterY()
    val area: Int get() = bounds.width() * bounds.height()
}

/**
 * 屏幕节点扫描器：把「关键词 → 可点击节点」这件事收在一处。
 *
 * 注意：assists-base 的节点操作（txt/des/isVisible/getBoundsInScreen/
 * findFirstParentClickable 等）都是声明在 [AssistsCore] 内部的
 * AccessibilityNodeInfo 扩展成员，必须写在 with(AssistsCore) 作用域里调用；
 * 而 getAllNodes/getPackageName 等无接收者的函数才是普通成员。
 *
 * 安全约定：
 * - 永远跳过自身包名（[SELF_PACKAGE]）的节点，避免点到自家 App；
 * - 所有节点访问都用 runCatching 包住，AccessibilityNodeInfo 随时可能因
 *   界面刷新而 StaleObjectException，扫描失败一律当作「没找到」；
 * - 只有可见且面积大于 0 的节点才算命中，避免命中被折叠/离屏的隐藏按钮。
 */
class NodeScanner(private val selfPackage: String = SELF_PACKAGE) {

    /**
     * 当前所有窗口的节点集合。
     *
     * 广告层可能挂在独立 window（无障碍配置已开 flagRetrieveInteractiveWindows），
     * 所以先按 getAccessibilityRootNodes() 逐个窗口遍历（含 root 自身），
     * 拿不到时才退回 getAllNodes()。
     */
    private fun collectNodes(): List<AccessibilityNodeInfo> =
        runCatching {
            AssistsCore.getAllNodes(scope = AssistsCore.NodeLookupScope.AllWindows)
        }.getOrNull().orEmpty()

    /**
     * **当前活动窗口**的节点集合。
     *
     * 页面态判定必须用它：无障碍开了 flagRetrieveInteractiveWindows 后，
     * 上一轮遗留、已经不在前台的广告窗口 root 仍然可枚举，
     * 用全窗口判定会把「后台残留广告窗口」当成当前页面（曾导致开轮永远判残留、复位永远失败）。
     */
    private fun collectActiveWindowNodes(): List<AccessibilityNodeInfo> =
        runCatching {
            AssistsCore.getAllNodes(scope = AssistsCore.NodeLookupScope.ActiveWindow)
        }.getOrNull().orEmpty()

    /**
     * **页面态只读判定**：只看当前活动窗口的纯文本（不做 isVisible / 面积过滤）。
     * 用于 inAdPage / 主界面 tab / 奖励浮层 / 发现页入口这类判定。
     */
    fun containsAnyTextActive(keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val nodes = collectActiveWindowNodes()
        if (nodes.isEmpty()) return false
        for (node in nodes) {
            if (runCatching { node.packageName?.toString() }.getOrNull() == selfPackage) continue
            val text = runCatching { buildText(node) }.getOrNull().orEmpty()
            if (text.isEmpty()) continue
            if (keywords.any { text.contains(it, ignoreCase = true) }) return true
        }
        return false
    }

    /** 当前活动窗口的所有 root 类名（用于窗口类型判定，如直播间 LivePlayerActivity）。 */
    fun activeWindowClassNames(): List<String> {
        val roots = runCatching {
            AssistsCore.getAccessibilityRootNodes(AssistsCore.NodeLookupScope.ActiveWindow)
        }.getOrNull().orEmpty()
        if (roots.isEmpty()) return emptyList()
        val names = LinkedHashSet<String>(roots.size)
        for (root in roots) {
            runCatching { root.className?.toString() }.getOrNull()?.let { if (it.isNotBlank()) names.add(it) }
        }
        return names.toList()
    }

    /** 活动窗口里命中了多少个（去重后的）关键词，用于「≥N 个特征才算某页面」的组合判定。 */
    fun countMatchedActiveTexts(keywords: List<String>): Int {
        if (keywords.isEmpty()) return 0
        val nodes = collectActiveWindowNodes()
        if (nodes.isEmpty()) return 0
        val matched = HashSet<String>(keywords.size)
        for (node in nodes) {
            if (runCatching { node.packageName?.toString() }.getOrNull() == selfPackage) continue
            val text = runCatching { buildText(node) }.getOrNull().orEmpty()
            if (text.isEmpty()) continue
            for (keyword in keywords) {
                if (text.contains(keyword, ignoreCase = true)) matched.add(keyword)
            }
            if (matched.size >= keywords.size) break
        }
        return matched.size
    }

    /** 返回所有命中关键词的可见节点。 */
    fun findAll(keywords: List<String>): List<NodeHit> {
        if (keywords.isEmpty()) return emptyList()
        val nodes = collectNodes()
        if (nodes.isEmpty()) return emptyList()
        val hits = ArrayList<NodeHit>(nodes.size)
        val seen = HashSet<String>(nodes.size)
        for (node in nodes) {
            val hit = runCatching { hitOf(node, keywords) }.getOrNull() ?: continue
            // 同一个按钮常有多层同名节点，按「文本 + 位置」去重。
            val key = hit.text + "@" + hit.bounds.flattenToString()
            if (seen.add(key)) hits.add(hit)
        }
        return hits
    }

    /** 优先「本身或其祖先可点击」，其次面积最小，得到一个最像按钮的命中。 */
    fun findBest(keywords: List<String>): NodeHit? =
        findAll(keywords).minWithOrNull(
            compareBy<NodeHit>({ clickableRank(it) }, { it.area }),
        )

    /**
     * **只读文本判定**：遍历所有窗口节点，只看 txt()+contentDescription 是否包含关键词。
     *
     * 不做任何 isVisible / 面积 / 可点击过滤。原因：汽水音乐广告页倒计时节点
     * bounds 高度是 0（实测 [674,144][1216,144]），[AssistsCore.isVisible] 也会返回 false，
     * 之前只放宽面积仍然漏判，造成「desc 里明明有 33秒后可领取奖励，却判成没进广告链路」。
     *
     * 只用于页面态判定：广告链路 / 播放态 / 完成弹窗 / 奖励浮层 / 底部 tab。
     * 真正要点击时仍走 [findBest] / [findAll] / [findAllInBounds]（保留面积过滤，避免点零尺寸节点）。
     */
    fun containsAnyText(keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val nodes = collectNodes()
        if (nodes.isEmpty()) return false
        for (node in nodes) {
            if (runCatching { node.packageName?.toString() }.getOrNull() == selfPackage) continue
            val text = runCatching { buildText(node) }.getOrNull().orEmpty()
            if (text.isEmpty()) continue
            // 纯文本判定：不做可见性/面积过滤（广告倒计时节点高度为 0，isVisible 也返回 false）
            if (keywords.any { text.contains(it, ignoreCase = true) }) return true
        }
        return false
    }

    /**
     * **窗口判据**（最稳，不受节点可见性影响）：任一窗口 root 的包名 == targetPackage
     * 且类名包含 [AD_WINDOW_CLASS_HINT]（汽水音乐广告页是
     * com.luna.music/com.ss.android.excitingvideo.ExcitingVideoActivity）。
     */
    fun isAdWindowActive(targetPackage: String): Boolean {
        if (targetPackage.isBlank()) return false
        // 只取当前活动窗口，后台残留的广告窗口不能算
        val roots = runCatching {
            AssistsCore.getAccessibilityRootNodes(AssistsCore.NodeLookupScope.ActiveWindow)
        }.getOrNull().orEmpty()
        for (root in roots) {
            val pkg = runCatching { root.packageName?.toString() }.getOrNull().orEmpty()
            if (pkg != targetPackage) continue
            val cls = runCatching { root.className?.toString() }.getOrNull().orEmpty()
            if (cls.contains(AD_WINDOW_CLASS_HINT, ignoreCase = true)) return true
        }
        return false
    }

    /** 当前**所有**窗口 root 的「包名/类名」摘要（含后台残留窗口，仅用于日志排查，不参与判定）。 */
    fun currentRootsSummary(limit: Int = 5): String {
        if (limit <= 0) return ""
        val roots = runCatching { AssistsCore.getAccessibilityRootNodes() }.getOrNull().orEmpty()
        val items = LinkedHashSet<String>()
        for (root in roots) {
            if (items.size >= limit) break
            val pkg = runCatching { root.packageName?.toString() }.getOrNull().orEmpty()
            val cls = runCatching { root.className?.toString() }.getOrNull().orEmpty()
            if (pkg.isEmpty() && cls.isEmpty()) continue
            items.add("${pkg.ifEmpty { "?" }}/${cls.ifEmpty { "?" }}")
        }
        return items.joinToString(" | ")
    }

    /** 当前界面是否出现任一关键词（点击用途：要求面积 > 0）。 */
    fun containsAny(keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val nodes = collectNodes()
        for (node in nodes) {
            if (runCatching { hitOf(node, keywords) }.getOrNull() != null) return true
        }
        return false
    }

    /**
     * 返回中心点落在给定屏幕矩形内的可见节点。
     *
     * 用于「无 text / 无 desc」的图标（例如发现页右上角入口）：只能按位置筛，
     * 再交给 [NodeActions.findClickableInRegion] 挑可点击的。
     */
    fun findAllInBounds(left: Int, top: Int, right: Int, bottom: Int): List<NodeHit> {
        if (left >= right || top >= bottom) return emptyList()
        // 无文字图标：用官方 findByTags 只取活动窗口里的 ImageView，再做区域过滤。
        val nodes = runCatching {
            AssistsCore.findByTags(
                className = AssistsNodeClassNames.ImageView,
                scope = AssistsCore.NodeLookupScope.ActiveWindow,
            )
        }.getOrNull().orEmpty()
        val hits = ArrayList<NodeHit>()
        val seen = HashSet<String>()
        for (node in nodes) {
            val hit = runCatching { hitInBounds(node, left, top, right, bottom) }.getOrNull() ?: continue
            val key = hit.text + "@" + hit.bounds.flattenToString()
            if (seen.add(key)) hits.add(hit)
        }
        return hits
    }

    /**
     * 找**屏幕右上角**的关闭/退出控件（真机证据：直播间关闭是
     * Button desc="关闭" id=.../n bounds=(1132,169)-(1258,295)，同一位置还有 ImageView desc="关闭"）。
     *
     * 顺序：① 文案含 关闭 / 退出 / 返回 且位于右上角（top < 15% 高、left > 70% 宽）；
     *       ② 右上角的 ImageButton / ImageView（纯图标 X）。
     * 多命中取面积最小的；只跳过零面积与自身包名。
     */
    fun findTopRightCloseControl(): NodeHit? {
        val width = runCatching { AssistsCore.getAppWidthInScreen() }.getOrNull() ?: 0
        val height = runCatching { AssistsCore.getAppHeightInScreen() }.getOrNull() ?: 0
        if (width <= 0 || height <= 0) return null
        val maxY = height * CLOSE_Y_MAX_RATIO
        val minX = width * CLOSE_X_MIN_RATIO
        val nodes = collectActiveWindowNodes()
        if (nodes.isEmpty()) return null

        // ① 文案档
        var best: NodeHit? = null
        for (node in nodes) {
            if (runCatching { node.packageName?.toString() }.getOrNull() == selfPackage) continue
            val text = runCatching { buildText(node) }.getOrNull().orEmpty()
            if (text.isEmpty()) continue
            if (CLOSE_KEYWORDS.none { text.contains(it, ignoreCase = true) }) continue
            val hit = runCatching {
                NodeHit(node, text, with(AssistsCore) { node.getBoundsInScreen() })
            }.getOrNull() ?: continue
            if (hit.area <= 0) continue
            if (hit.bounds.top > maxY || hit.centerX < minX) continue
            if (best == null || hit.area < best.area) best = hit
        }
        if (best != null) return best

        // ② 右上角图标档
        for (node in nodes) {
            if (runCatching { node.packageName?.toString() }.getOrNull() == selfPackage) continue
            val cls = runCatching { node.className?.toString() }.getOrNull().orEmpty()
            if (CLOSE_ICON_CLASS_HINTS.none { cls.contains(it, ignoreCase = true) }) continue
            val clickable = runCatching { node.isClickable }.getOrDefault(false) ||
                runCatching { with(AssistsCore) { node.findFirstParentClickable() } }.getOrNull() != null
            if (!clickable) continue
            val hit = runCatching {
                NodeHit(node, buildText(node), with(AssistsCore) { node.getBoundsInScreen() })
            }.getOrNull() ?: continue
            if (hit.area <= 0) continue
            if (hit.bounds.top > maxY || hit.centerX < minX) continue
            if (best == null || hit.area < best.area) best = hit
        }
        return best
    }


    /**
     * 在**当前活动窗口**里找「中心点落在指定像素矩形内、isClickable=true、面积最小」的节点。
     *
     * 用途：广告入口徽章是 clickable 的 LinearLayout（实测 (1048,175)-(1230,262)），
     * 里面的图标/文字节点都不可点击，只能按容器点。
     */
    fun findClickableInActiveBounds(left: Int, top: Int, right: Int, bottom: Int): NodeHit? {
        if (left >= right || top >= bottom) return null
        val nodes = collectActiveWindowNodes()
        if (nodes.isEmpty()) return null
        var best: NodeHit? = null
        for (node in nodes) {
            if (runCatching { node.packageName?.toString() }.getOrNull() == selfPackage) continue
            if (runCatching { node.isClickable }.getOrDefault(false).not()) continue
            val bounds = runCatching { with(AssistsCore) { node.getBoundsInScreen() } }.getOrNull() ?: continue
            if (bounds.width() <= 0 || bounds.height() <= 0) continue
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            if (centerX < left || centerX > right || centerY < top || centerY > bottom) continue
            val hit = NodeHit(node, buildText(node), bounds)
            if (best == null || hit.area < best.area) best = hit
        }
        return best
    }

    /** 只在本窗口活动窗口里按 resource-id 查找（避免命中后台残留窗口的旧节点）。 */
    fun findByViewIdActive(viewId: String): NodeHit? {
        if (viewId.isBlank()) return null
        val nodes = runCatching {
            AssistsCore.findById(id = viewId, scope = AssistsCore.NodeLookupScope.ActiveWindow)
        }.getOrNull().orEmpty()
        for (node in nodes) {
            val hit = runCatching { hitOfViewId(node, viewId) }.getOrNull() ?: continue
            return hit
        }
        return null
    }

    /**
     * 按 resource-id 精确查找（需要无障碍配置开启 flagReportViewIds，工程里已开）。
     * 优先用 assists 的 findById(id)，它拿不到时再遍历节点比对 viewIdResourceName。
     */
    fun findByViewId(viewId: String): NodeHit? {
        if (viewId.isBlank()) return null
        val byAssists = runCatching { AssistsCore.findById(viewId) }.getOrNull().orEmpty()
        for (node in byAssists) {
            val hit = runCatching { hitOfViewId(node, viewId) }.getOrNull() ?: continue
            return hit
        }
        for (node in collectNodes()) {
            val hit = runCatching { hitOfViewId(node, viewId) }.getOrNull() ?: continue
            return hit
        }
        return null
    }

    /**
     * 解析广告页倒计时文案（例如「34秒后可领取奖励」），返回剩余秒数；解析不到返回 null。
     * 命中多个时取最小值（同屏可能同时有「再看12秒」和主倒计时）。
     */
    fun findCountdownSeconds(): Int? {
        val nodes = collectNodes()
        var minSeconds: Int? = null
        for (node in nodes) {
            val text = runCatching { buildText(node) }.getOrNull()
            if (text.isNullOrBlank()) continue
            for (match in FlowKeywords.AD_COUNTDOWN_REGEX.findAll(text)) {
                val seconds = match.groupValues[1].toIntOrNull() ?: continue
                minSeconds = minSeconds?.let { minOf(it, seconds) } ?: seconds
            }
        }
        return minSeconds
    }

    /** 把当前界面可见文本拼成一行，写入日志方便改版后排查（不对外暴露节点）。 */
    fun currentTextSummary(limit: Int = 20): String {
        if (limit <= 0) return ""
        val nodes = collectNodes()
        val texts = LinkedHashSet<String>()
        for (node in nodes) {
            if (texts.size >= limit) break
            val text = runCatching { buildText(node) }.getOrNull()
            if (!text.isNullOrBlank()) texts.add(text)
        }
        return texts.joinToString(" | ")
    }

    /**
     * 只取 text 的摘要（与 contentDescription 分开）。
     *
     * 真机上广告倒计时是挂在 content-desc 上的（如「39秒后可领取奖励，关闭，按钮」），
     * 排查「明明看到了却判不到」时，这两类来源必须分开打日志。
     */
    fun currentTextOnlySummary(limit: Int = 12): String {
        if (limit <= 0) return ""
        val texts = LinkedHashSet<String>()
        for (node in collectNodes()) {
            if (texts.size >= limit) break
            val text = runCatching { with(AssistsCore) { node.txt() } }.getOrNull()
            if (!text.isNullOrBlank()) texts.add(text.trim())
        }
        return texts.joinToString(" | ")
    }

    /** 只取 contentDescription 的摘要（广告页文案大多在这里）。 */
    fun currentDescOnlySummary(limit: Int = 12): String {
        if (limit <= 0) return ""
        val texts = LinkedHashSet<String>()
        for (node in collectNodes()) {
            if (texts.size >= limit) break
            val desc = runCatching { with(AssistsCore) { node.des() } }.getOrNull()
            if (!desc.isNullOrBlank()) texts.add(desc.trim())
        }
        return texts.joinToString(" | ")
    }

    private fun hitOf(node: AccessibilityNodeInfo, keywords: List<String>): NodeHit? {
        if (node.packageName?.toString() == selfPackage) return null
        val text = buildText(node)
        if (text.isEmpty()) return null
        if (keywords.none { text.contains(it, ignoreCase = true) }) return null
        if (!isVisible(node)) return null
        val bounds = runCatching { with(AssistsCore) { node.getBoundsInScreen() } }.getOrNull()
            ?: return null
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        return NodeHit(node, text, bounds)
    }

    private fun hitOfViewId(node: AccessibilityNodeInfo, viewId: String): NodeHit? {
        if (node.packageName?.toString() == selfPackage) return null
        val nodeViewId = runCatching { node.viewIdResourceName }.getOrNull()
        if (nodeViewId != viewId) return null
        if (!isVisible(node)) return null
        val bounds = runCatching { with(AssistsCore) { node.getBoundsInScreen() } }.getOrNull()
            ?: return null
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        return NodeHit(node, buildText(node), bounds)
    }

    private fun hitInBounds(
        node: AccessibilityNodeInfo,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): NodeHit? {
        if (node.packageName?.toString() == selfPackage) return null
        if (!isVisible(node)) return null
        val bounds = runCatching { with(AssistsCore) { node.getBoundsInScreen() } }.getOrNull()
            ?: return null
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        // 用中心点落区判定，避免边缘被裁掉一点就整块匹配不上。
        if (bounds.centerX() < left || bounds.centerX() > right) return null
        if (bounds.centerY() < top || bounds.centerY() > bottom) return null
        return NodeHit(node, buildText(node), bounds)
    }

    /** 文本 + 无障碍描述；两者都为空则不算可读节点。 */
    private fun buildText(node: AccessibilityNodeInfo): String {
        val text = runCatching { with(AssistsCore) { node.txt() } }.getOrNull()
        val description = runCatching { with(AssistsCore) { node.des() } }.getOrNull()
        return when {
            text.isNullOrBlank() -> description.orEmpty()
            description.isNullOrBlank() -> text
            else -> "$text $description"
        }.trim()
    }

    private fun isVisible(node: AccessibilityNodeInfo): Boolean =
        runCatching { with(AssistsCore) { node.isVisible(null, true) } }.getOrDefault(false)

    private fun clickableRank(hit: NodeHit): Int = runCatching {
        when {
            hit.node.isClickable -> 0
            with(AssistsCore) { hit.node.findFirstParentClickable() } != null -> 0
            else -> 1
        }
    }.getOrDefault(2)

    companion object {
        /** 本 App 的包名，永不点击。 */
        const val SELF_PACKAGE = "com.sodamusic.helper"

        /** 广告沉浸页窗口类名特征（忽略大小写匹配）。 */
        const val AD_WINDOW_CLASS_HINT = "excitingvideo"

        /** 右上角关闭控件的文案特征。 */
        val CLOSE_KEYWORDS = listOf("关闭", "退出", "返回")

        /** 右上角纯图标关闭控件（X）的类名特征。 */
        val CLOSE_ICON_CLASS_HINTS = listOf("ImageButton", "ImageView")

        /** 右上角判定阈值：top < 15% 高、centerX > 70% 宽（实测关闭按钮 (1132,169)-(1258,295)）。 */
        const val CLOSE_Y_MAX_RATIO = 0.15f
        const val CLOSE_X_MIN_RATIO = 0.70f
    }
}
