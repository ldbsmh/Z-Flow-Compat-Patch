package io.relimus.zflow.compat.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.relimus.zflow.compat.xposed.patch.StatusBarDimenFix
import io.relimus.zflow.compat.xposed.patch.TelegramPipDisableFix
import io.relimus.zflow.compat.xposed.patch.UniversalMultiWindowFix
import io.relimus.zflow.compat.xposed.patch.WeChatStatusBarFix
import io.relimus.zflow.compat.xposed.util.PatchLog
import io.relimus.zflow.compat.xposed.util.PatchSettings

/**
 * Z-Flow 兼容性补丁 —— 模块入口。
 *
 * 只做三件事，都是"某个应用在小窗里显示不正常"的针对性修复：
 * 1. [WeChatStatusBarFix]：微信小窗双标题栏
 * 2. [StatusBarDimenFix]：小窗顶部空白（读框架 status_bar_height 的应用，如起点读书）
 * 3. [TelegramPipDisableFix]：Telegram 系小窗只剩角落画面
 *
 * 另有实验性的 [UniversalMultiWindowFix]（默认关闭）：用"诱导微信进入多窗口分支 +
 * 中和偏好缓存"的版本无关方案替代第 1 项，供新版微信失效时对照使用。
 *
 * 每个补丁独立 try/catch：一个补丁不兼容不能影响另外两个。
 */
class CompatModule : XposedModule() {

    /** 当前宿主进程名；同一微信进程稍后还会收到 WebView 的 PackageReady。 */
    @Volatile
    private var hostProcessName: String = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        hostProcessName = param.processName
        PatchLog.attach(this)
        PatchLog.info(
            "模块载入: process=${param.processName} " +
                "systemServer=${param.isSystemServer} " +
                "api=${apiVersion} framework=${frameworkName} ${frameworkVersion}"
        )
        // 远程偏好在此可用；日志开关必须在任何补丁 init 之前同步好
        PatchSettings.bind(this)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // 现代 API 的作用域只在被勾选的应用进程内生效；
        // 这里不安装钩子，等 classloader 就绪后统一在 onPackageReady 处理。
        PatchLog.debug(
            "onPackageLoaded: pkg=${param.packageName} first=${param.isFirstPackage}"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName
        val cl = param.classLoader

        PatchLog.info("onPackageReady: pkg=$pkg classLoader=${cl.javaClass.name}")

        // 1) 微信：小窗双标题栏
        runPatch("WeChatStatusBarFix") { WeChatStatusBarFix.install(this, cl, pkg) }

        // 1b) 微信：版本无关的通用方案（实验，默认关闭）
        //     与 1) 是替代关系：两者同时开启时，旧的按名探测可能先命中并与多窗口分支
        //     互相覆盖，验证时建议只开一个。
        runPatch("UniversalMultiWindowFix") { UniversalMultiWindowFix.install(this, cl, pkg) }

        // 2) 通用：小窗顶部空白（任何加入作用域的应用都可能命中）
        //    微信进程必须排除：微信的状态栏高度由 WeChatStatusBarFix 统一处理，
        //    通用补丁会把框架 status_bar_height 改 0，与微信自己的状态栏预留
        //    逻辑打架，导致"功能项被顶部覆盖/布局错位"。
        val isWeChatProcess = hostProcessName == "com.tencent.mm" ||
            hostProcessName.startsWith("com.tencent.mm:")
        if (!isWeChatProcess) {
            runPatch("StatusBarDimenFix") { StatusBarDimenFix.install(this, cl, pkg) }
        } else {
            PatchLog.debug(
                "StatusBarDimenFix: process=$hostProcessName pkg=$pkg 位于微信进程，" +
                    "跳过（防止 WebView 的二次 PackageReady 绕过排除）"
            )
        }

        // 3) Telegram 系：关闭画中画
        runPatch("TelegramPipDisableFix") { TelegramPipDisableFix.install(this, cl, pkg) }
    }

    /**
     * 单个补丁的安装入口包装：任何异常都只记日志，不向上抛。
     * 现代 API 会把回调抛出的异常记录下来，但这里显式隔离能让日志更清楚是哪个补丁挂了。
     */
    private inline fun runPatch(name: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            PatchLog.error("$name 安装失败", t)
        }
    }

    /** 暴露给补丁工具类的框架接口。 */
    internal val xposed: XposedInterface get() = this
}
