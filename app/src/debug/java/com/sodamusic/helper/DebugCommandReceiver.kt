package com.sodamusic.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sodamusic.helper.automation.AutomationRuntime
import com.sodamusic.helper.automation.LogLevel
import com.sodamusic.helper.ui.floating.FloatingPanelController
import com.ven.assists.AssistsCore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试专用：通过 adb 广播启停引擎 / 导出节点树，便于做"截图 + 日志"闭环验证。
 * 只存在于 debug 源集，release 包不会包含（见 app/src/debug/AndroidManifest.xml）。
 *
 *   adb shell am broadcast -a com.sodamusic.helper.DEBUG_START
 *   adb shell am broadcast -a com.sodamusic.helper.DEBUG_STOP
 *   adb shell am broadcast -a com.sodamusic.helper.DEBUG_DUMP   # 导出节点树 JSON
 *
 * 为什么用 assists 的 saveRootNodeTreeJson 而不是 uiautomator dump：
 * adb shell uiautomator dump 会注册 UiAutomation，系统因此解绑我们的无障碍服务，
 * 表现为引擎"自己停了"；这个广播在进程内导出，不影响服务。
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                AutomationRuntime.start(context.applicationContext)
                // debug 直启时 Activity 从未 resume：补一次挂载，否则后台跑起来看不到胶囊
                FloatingPanelController.ensureAttached(context.applicationContext)
            }
            ACTION_STOP -> AutomationRuntime.stop("debug stop")
            ACTION_DUMP -> dumpNodeTree(context.applicationContext)
        }
    }

    /** 导出当前所有窗口的节点树到 cacheDir，并把绝对路径写进日志（adb pull 可直接取）。 */
    private fun dumpNodeTree(context: Context) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "node-tree-$stamp.json")
        val saved = runCatching {
            AssistsCore.saveRootNodeTreeJson(
                file,
                true,
                AssistsCore.NodeLookupScope.AllWindows,
            )
        }.getOrNull()
        val path = (saved ?: file).absolutePath
        AutomationRuntime.log(LogLevel.INFO, "节点树已导出：$path（adb pull 可用）")
        val pkg = runCatching { AssistsCore.getPackageName() }.getOrNull().orEmpty()
        AutomationRuntime.log(LogLevel.INFO, "节点树导出时前台包：${pkg.ifBlank { "未知" }}")
    }

    private companion object {
        const val ACTION_START = "com.sodamusic.helper.DEBUG_START"
        const val ACTION_STOP = "com.sodamusic.helper.DEBUG_STOP"
        const val ACTION_DUMP = "com.sodamusic.helper.DEBUG_DUMP"
    }
}
