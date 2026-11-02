package com.sodamusic.helper.accessibility

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.view.accessibility.AccessibilityEvent
import com.sodamusic.helper.automation.AutomationRuntime
import com.sodamusic.helper.automation.LogLevel
import com.sodamusic.helper.ui.floating.FloatingPanelController
import com.ven.assists.service.AssistsService
import com.ven.assists.service.AssistsServiceListener
import com.ven.assists.window.AssistsWindowManager

/**
 * 无障碍服务：**只做注册与转发**，业务全部在 [AutomationRuntime]。
 *
 * 继承 assists 的 AssistsService，并向它的全局 listener 列表注册一个回调；
 * 注意 listeners 是全局静态列表，注册前先移除旧的自己，避免回调翻倍。
 * 这里不重写 onAccessibilityEvent / onInterrupt 等分发方法——listener 已经收到全部回调。
 */
class SodaAccessibilityService : AssistsService() {

    private val runtimeListener = object : AssistsServiceListener {
        override fun onServiceConnected(service: AssistsService) = handleConnected(service)
        override fun onAccessibilityEvent(event: AccessibilityEvent) =
            AutomationRuntime.onAccessibilityEvent(event)
        override fun onUnbind() = AutomationRuntime.onServiceDisconnected()
        override fun onInterrupt() = AutomationRuntime.onServiceDisconnected()
        override fun screenCaptureEnable() =
            AutomationRuntime.log(LogLevel.INFO, "无障碍截图已就绪")
    }

    override fun onCreate() {
        super.onCreate()
        attachListener()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detachListener()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detachListener()
        super.onDestroy()
    }

    /** 服务连上：初始化窗口管理器、debug 模板定位、悬浮窗，再把控制权交给运行时。 */
    private fun handleConnected(service: AssistsService) {
        runCatching { AssistsWindowManager.init(service) }
        installTemplateLocatorIfDebug()
        // 服务是进程里"一定会被系统拉起"的钩子：进程重启后也把悬浮窗挂上（幂等）。
        runCatching { FloatingPanelController.ensureAttached(service) }
        AutomationRuntime.onServiceConnected(service)
    }

    /** 注册到 assists 的全局 listener 列表（先移除旧的自己，防止重复注册）。 */
    private fun attachListener() {
        val listeners = AssistsService.listeners
        runCatching { listeners.remove(runtimeListener); listeners.add(runtimeListener) }
            .onFailure { AutomationRuntime.log(LogLevel.WARN, "注册无障碍回调失败：${it.message}") }
    }

    private fun detachListener() {
        runCatching { AssistsService.listeners.remove(runtimeListener) }
    }

    /**
     * debug 包才安装 OpenCV 模板定位（入口第 4 档兜底）。
     *
     * 用反射而不是直接 import：assists-opcv 只在 debugImplementation 里，
     * release 编译期不能出现任何 com.ven.assists.opcv / org.opencv 符号；类不存在时静默。
     */
    private fun installTemplateLocatorIfDebug() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        runCatching {
            val cls = Class.forName("com.sodamusic.helper.opcv.OpencvEntryLocator")
            cls.getMethod("installIfDebug").invoke(cls.getDeclaredConstructor().newInstance())
        }.onFailure {
            AutomationRuntime.log(LogLevel.INFO, "[debug] 模板定位未启用：${it.message}")
        }
    }
}
