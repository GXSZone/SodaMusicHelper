package com.sodamusic.helper.opcv

import android.os.Build
import com.sodamusic.helper.automation.template.TemplateEntryLocator
import com.sodamusic.helper.automation.template.TemplateEntryLocatorHolder
import com.ven.assists.AssistsCore
import com.ven.assists.opcv.OpencvWrapper
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * 仅 debug 包可用的入口图标模板定位（assists-opcv + OpenCV）。
 *
 * 截图改用**无障碍 takeScreenshot**：
 * OpencvWrapper.getScreenMat() 内部走 assists-mp 的 MediaProjection（录屏）通道，
 * 需要用户授权录屏，我们没授权 → 永远返回 null；改成 AssistsCore.takeScreenshot()
 * （AccessibilityService#takeScreenshot，Android 11+，配置已开 canTakeScreenshot，不需要授权）。
 */
class OpencvEntryLocator : TemplateEntryLocator {

    override suspend fun locateEntry(screenWidth: Int, screenHeight: Int): Pair<Int, Int>? {
        TemplateEntryLocatorHolder.lastMatchScore = -1.0
        // 无障碍截图 API 是 Android 11(API 30) 才提供；minSdk 24 的低版本直接跳过
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            TemplateEntryLocatorHolder.lastMatchNote = "系统版本 ${Build.VERSION.SDK_INT} < 30，不支持无障碍截图"
            return null
        }

        // ① 无障碍截图（不需要录屏授权）
        val bitmap = runCatching { AssistsCore.takeScreenshot() }.getOrNull()
        if (bitmap == null) {
            TemplateEntryLocatorHolder.lastMatchNote = "无障碍截图失败（可能未授予 canTakeScreenshot 或服务需重启）"
            return null
        }

        var screen: Mat? = null
        var template: Mat? = null
        try {
            // ② Bitmap → Mat；无障碍截图是 RGBA，匹配前转 BGR，和模板通道一致
            val screenMat = Mat()
            screen = screenMat
            Utils.bitmapToMat(bitmap, screenMat)
            Imgproc.cvtColor(screenMat, screenMat, Imgproc.COLOR_RGBA2BGR)
            if (runCatching { screenMat.empty() }.getOrDefault(true)) {
                TemplateEntryLocatorHolder.lastMatchNote = "截图 Mat 为空"
                return null
            }

            // ③ 加载 assets 模板
            val templateMat = runCatching {
                OpencvWrapper.getTemplateFromAssets(TemplateEntryLocatorHolder.TEMPLATE_ASSET)
            }.getOrNull()
            if (templateMat == null || runCatching { templateMat.empty() }.getOrDefault(true)) {
                TemplateEntryLocatorHolder.lastMatchNote =
                    "模板缺失/无法解码：${TemplateEntryLocatorHolder.TEMPLATE_ASSET}"
                return null
            }
            template = templateMat

            // ④ 模板匹配
            val result = runCatching {
                OpencvWrapper.matchTemplateFromScreenToMinMaxLoc(screenMat, templateMat)
            }.getOrNull()
            if (result == null) {
                TemplateEntryLocatorHolder.lastMatchNote = "matchTemplate 调用失败"
                return null
            }
            TemplateEntryLocatorHolder.lastMatchScore = result.maxVal
            if (result.maxVal < TemplateEntryLocatorHolder.MATCH_THRESHOLD) {
                TemplateEntryLocatorHolder.lastMatchNote =
                    "分数不足（maxVal=%.3f < %.2f）".format(result.maxVal, TemplateEntryLocatorHolder.MATCH_THRESHOLD)
                return null
            }
            val loc = result.maxLoc
            if (loc == null) {
                TemplateEntryLocatorHolder.lastMatchNote = "匹配结果没有坐标"
                return null
            }
            val centerX = (loc.x + templateMat.cols() / 2.0).toInt()
            val centerY = (loc.y + templateMat.rows() / 2.0).toInt()
            TemplateEntryLocatorHolder.lastMatchNote = "命中"
            return centerX to centerY
        } catch (t: Throwable) {
            TemplateEntryLocatorHolder.lastMatchNote = "模板匹配异常：${t.message ?: t.javaClass.simpleName}"
            return null
        } finally {
            runCatching { template?.release() }
            runCatching { screen?.release() }
            runCatching { bitmap.recycle() }
        }
    }

    /** debug 启动点调用：初始化 OpenCV（库自带后台加载）并把自己注入 main 的 Holder。 */
    fun installIfDebug() {
        runCatching { OpencvWrapper.init() }
        TemplateEntryLocatorHolder.install(this)
    }
}
