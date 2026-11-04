package io.relimus.zflow.compat.xposed.util

import android.app.Activity
import android.view.Display
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级「当前是否在虚拟屏」追踪器。
 *
 * 有些 hook 点拿不到 Context（典型：`SharedPreferences.getInt(key, def)` 只有 key 和默认值），
 * 但又必须判断"现在是不是小窗"，于是统一由这里维护一个进程级的显示设备标记：
 *
 * - `Activity.onResume`：桌面长按启动小窗、冷启动等会重建/恢复 Activity 的路径；
 * - `Activity.onMovedToDisplay(int, Configuration)`：多任务上滑把任务搬到虚拟屏、
 *   Activity **不重建也不 resume** 的路径（framework 里该回调就是为此设计的）。
 *
 * 两条路径合起来覆盖 Z-Flow 的两种进小窗方式。判定失败时一律当主屏处理 ——
 * 宁可漏改，也不误改全屏行为。
 *
 * 注意：本追踪器只解决"判定"，不解决"取哪个 Activity"，因此只给没有 Context 的
 * hook 点使用；能拿到 Context/Activity 的地方应直接读它自己的 `display.displayId`，
 * 那比进程级缓存更准确。
 */
object VirtualDisplayTracker {

    private const val NAME = "DisplayTracker"

    @Volatile
    var displayId: Int = Display.DEFAULT_DISPLAY
        private set

    /** 当前是否位于虚拟屏（小窗）。 */
    val isVirtual: Boolean
        get() = displayId != Display.DEFAULT_DISPLAY

    private val installed = AtomicBoolean(false)

    /**
     * 安装追踪 hook；同一进程只装一次。返回是否至少装上了一个触发点。
     */
    fun install(module: XposedModule, classLoader: ClassLoader): Boolean {
        if (!installed.compareAndSet(false, true)) return true

        val activityClass = Reflect.findClass("android.app.Activity", classLoader)
        if (activityClass == null) {
            installed.set(false)
            PatchLog.warn("$NAME: 未找到 android.app.Activity，无法追踪显示设备")
            return false
        }

        val onResumeHooked = hookOnResume(module, activityClass)
        val movedHooked = hookOnMovedToDisplay(module, activityClass)
        val createHooked = hookOnCreate(module, activityClass)
        val ok = onResumeHooked || movedHooked || createHooked

        if (ok) {
            PatchLog.debug(
                "$NAME: 跟踪已安装 onCreate=$createHooked onResume=$onResumeHooked " +
                    "onMovedToDisplay=$movedHooked"
            )
        } else {
            installed.set(false)
            PatchLog.warn("$NAME: 三个触发点都未装上，依赖追踪的 hook 会停在主屏判定")
        }
        return ok
    }

    /**
     * `onCreate` 在页面初始化早期触发，且此时 `attach()` 已完成、`display` 已经准确。
     * 在 `proceed()` **之前**更新，才能覆盖 onCreate 期间读取偏好缓存的情况
     * （冷启动直接进小窗时尤其重要）。
     */
    private fun hookOnCreate(module: XposedModule, activityClass: Class<*>): Boolean {
        val onCreate = Reflect.findMethodExact(
            activityClass, "onCreate", android.os.Bundle::class.java
        ) ?: run {
            PatchLog.debug("$NAME: Activity.onCreate(Bundle) 未找到")
            return false
        }
        return runCatching {
            module.hook(onCreate).intercept { chain ->
                update(displayIdOf(chain.thisObject), "onCreate")
                chain.proceed()
            }
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 Activity.onCreate 失败", it)
        }.getOrDefault(false)
    }

    private fun hookOnResume(module: XposedModule, activityClass: Class<*>): Boolean {
        val onResume = Reflect.findMethodExact(activityClass, "onResume") ?: run {
            PatchLog.debug("$NAME: Activity.onResume 未找到")
            return false
        }
        return runCatching {
            module.hook(onResume).intercept { chain ->
                // proceed 之前更新：onResume 里可能立刻读缓存
                update(displayIdOf(chain.thisObject), "onResume")
                chain.proceed()
            }
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 Activity.onResume 失败", it)
        }.getOrDefault(false)
    }

    private fun hookOnMovedToDisplay(module: XposedModule, activityClass: Class<*>): Boolean {
        val intPrimitive = Int::class.javaPrimitiveType ?: return false
        val method = Reflect.findMethodExact(
            activityClass,
            "onMovedToDisplay",
            intPrimitive,
            android.content.res.Configuration::class.java,
        ) ?: run {
            PatchLog.debug("$NAME: Activity.onMovedToDisplay(int, Configuration) 未找到")
            return false
        }
        return runCatching {
            module.hook(method).intercept { chain ->
                // 先更新再 proceed：被搬运后的第一次取值就应该用新显示设备
                update(chain.getArg(0) as? Int, "onMovedToDisplay")
                chain.proceed()
            }
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 Activity.onMovedToDisplay 失败", it)
        }.getOrDefault(false)
    }

    private fun update(newDisplayId: Int?, source: String) {
        if (newDisplayId == null) return
        if (newDisplayId == displayId) return
        PatchLog.debug("$NAME: 显示设备 $displayId → $newDisplayId（$source）")
        displayId = newDisplayId
    }

    private fun displayIdOf(target: Any?): Int? {
        val activity = target as? Activity ?: return null
        return runCatching { activity.display?.displayId }.getOrNull()
    }
}
