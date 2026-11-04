package io.relimus.zflow.compat.xposed.patch

import android.app.Activity
import android.content.res.Resources
import android.view.Display
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.relimus.zflow.compat.xposed.util.PatchLog
import io.relimus.zflow.compat.xposed.util.PatchSettings
import io.relimus.zflow.compat.xposed.util.Reflect
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通用补丁：在小窗（虚拟屏）上把「框架 status_bar_height」归零。
 *
 * ## 解决什么
 *
 * 一批应用会直接读框架尺寸来预留状态栏高度，与运行时 inset 无关：
 *
 * ```
 * int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
 * return ctx.getResources().getDimensionPixelSize(id);     // ← 本补丁的 hook 目标
 * ```
 *
 * 真机上这截被系统状态栏覆盖，看不出问题；虚拟屏上没有状态栏，这截就裸露成一条与内容
 * 无关的空白（用户俗称「宽额头」）。起点读书即为此类。
 *
 * ## 做法
 *
 * hook `Resources.getDimensionPixelSize(int)`，当入参是 `android:dimen/status_bar_height`
 * **且当前不在主屏**时返回 0。
 *
 * 相比 hook 具体应用的混淆方法，这里用的是**框架 API，跨版本稳定**，对任何"读框架状态栏
 * 尺寸"的应用都生效，不需要逐个逆向。
 *
 * ## 显示设备判定（关键）
 *
 * 判定"是否在小窗"必须能覆盖**两条**进入小窗的路径：
 *
 * | 路径 | 应用行为 | 可用信号 |
 * |---|---|---|
 * | 桌面图标长按启动小窗 | 新进程/新 Activity 在虚拟屏上创建 | `onResume` 带 displayId≠0 |
 * | 多任务上滑启动小窗 | Activity **不重建、不 resume**，任务被搬到虚拟屏 | `onMovedToDisplay` |
 *
 * 早期实现只挂 `onResume`，导致多任务上滑路径下缓存值停在主屏、补丁不生效。
 * 现同时挂 `Activity.onMovedToDisplay(int, Configuration)` —— 系统在「任务被移到
 * 另一显示设备且不重建」时调用它，AOSP 注释明确写明该回调就是给这类场景用的：
 *
 * > Use this callback to track changes to the displays if some activity functionality
 * > relies on an association with some display properties.
 *
 * 两个 hook 点共同覆盖了两条进入小窗的路径，见上表。判定用缓存值（读一次即可），
 * 缓存在 `onResume` / `onMovedToDisplay` 时刷新。
 *
 * ## 边界
 *
 * - 只对加入了作用域的应用生效。
 * - 只覆盖 `getDimensionPixelSize` 这一条路径；用 `getDimension` /
 *   `getDimensionPixelOffset` / 硬编码 dp 的应用不受影响。
 * - 主屏行为完全不变（判定为主屏时直接返回）。
 * - 若两个追踪 hook 都装不上，`currentDisplayId` 会停在主屏（0），补丁静默不生效 ——
 *   宁可漏改也不误改主屏。
 */
object StatusBarDimenFix {

    private const val NAME = "StatusBarDimenFix"

    /** 当前所在显示设备；由 onResume / onMovedToDisplay 更新。默认主屏 → 不改任何行为。 */
    @Volatile
    private var currentDisplayId: Int = Display.DEFAULT_DISPLAY

    /** `android:dimen/status_bar_height` 在本进程资源里的 id；-1 = 尚未解析。 */
    @Volatile
    private var statusBarHeightResId: Int = -1

    /** 保证同一进程内只装一次。 */
    private val installed = AtomicBoolean(false)

    /**
     * 是否已装上显示设备追踪 hook（onResume 或 onMovedToDisplay 任一）。
     * 仅用于日志与自检，判定本身只读 [currentDisplayId]。
     */
    @Volatile
    private var trackingHooked = false

    /** 归零日志去重：每个 displayId 只打一次 info。 */
    private val loggedZeroHits = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun install(module: XposedModule, classLoader: ClassLoader, pkg: String) {
        if (installed.get()) {
            PatchLog.debug("$NAME: 本进程已安装，跳过 (pkg=$pkg)")
            return
        }
        if (!PatchSettings.isEnabled(null, PatchSettings.KEY_STATUSBAR_DIMEN_FIX)) {
            PatchLog.info("$NAME: 开关已关闭，跳过安装")
            return
        }

        PatchLog.info("$NAME: 开始安装（pkg=$pkg）")

        val activityHooked = hookActivityDisplayTracking(module, classLoader)
        val resourcesHooked = hookDimensionReaders(module)

        if (activityHooked || resourcesHooked) {
            installed.set(true)
            PatchLog.info(
                "$NAME: 安装完成 —— 显示设备追踪=$activityHooked " +
                    "Resources.getDimensionPixelSize=$resourcesHooked"
            )
        } else {
            PatchLog.warn("$NAME: 两个 hook 点都未装上，补丁未生效")
        }
    }

    /**
     * 追踪应用当前所在显示设备。两条路径都要覆盖：
     *
     * - `onResume`：Activity 在前台被创建/恢复（桌面长按启动小窗走这条）
     * - `onMovedToDisplay`：任务被搬到另一显示设备且**不重建**（多任务上滑走这条）
     */
    private fun hookActivityDisplayTracking(module: XposedModule, classLoader: ClassLoader): Boolean {
        val activityClass = Reflect.findClass("android.app.Activity", classLoader) ?: run {
            PatchLog.debug("$NAME: android.app.Activity 未找到")
            return false
        }

        val onCreateHooked = hookOnCreate(module, activityClass)
        val onResumeHooked = hookOnResume(module, activityClass)
        val movedHooked = hookOnMovedToDisplay(module, activityClass)

        trackingHooked = onCreateHooked || onResumeHooked || movedHooked
        return trackingHooked
    }

    /**
     * `onCreate` 也要跟：新 Activity 在虚拟屏上创建时，第一次 `setContentView` / 首次布局
     * 往往发生在 `onResume` 之前。如果只挂 `onResume`，首次布局读到的 `status_bar_height`
     * 仍是真机值（微信就是在这个时机把标题栏下移 124px 的）。
     */
    private fun hookOnCreate(module: XposedModule, activityClass: Class<*>): Boolean {
        val onCreate = Reflect.findMethodExact(activityClass, "onCreate", android.os.Bundle::class.java)
            ?: run {
                PatchLog.debug("$NAME: Activity.onCreate(Bundle) 未找到")
                return false
            }
        return runCatching {
            module.hook(onCreate).intercept { chain ->
                // proceed 之前更新：onCreate 里的首次布局就要用新显示设备
                updateDisplayId(displayIdOf(chain.thisObject), "onCreate")
                chain.proceed()
            }
            PatchLog.debug("$NAME: 已挂钩 Activity.onCreate（首次布局前的显示设备）")
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 Activity.onCreate 失败", it)
        }.getOrDefault(false)
    }

    private fun displayIdOf(host: Any?): Int? {
        val activity = host as? Activity ?: return null
        return runCatching { activity.display?.displayId }.getOrNull()
    }

    private fun hookOnResume(module: XposedModule, activityClass: Class<*>): Boolean {
        val onResume = Reflect.findMethodExact(activityClass, "onResume") ?: run {
            PatchLog.debug("$NAME: Activity.onResume 未找到")
            return false
        }

        return runCatching {
            module.hook(onResume).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                updateDisplayId(runCatching { activity?.display?.displayId }.getOrNull(), "onResume")
                result
            }
            PatchLog.debug("$NAME: 已挂钩 Activity.onResume（前台显示设备）")
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 Activity.onResume 失败", it)
        }.getOrDefault(false)
    }

    /**
     * `Activity.onMovedToDisplay(int displayId, Configuration config)` 是 @hide 回调，
     * 系统在「任务被移到另一显示设备且 Activity 未重建」时调用 —— 正是多任务上滑
     * 开小窗的场景。签名固定为 (int, Configuration)，跨版本稳定。
     */
    private fun hookOnMovedToDisplay(module: XposedModule, activityClass: Class<*>): Boolean {
        val intPrimitive = Int::class.javaPrimitiveType ?: run {
            PatchLog.debug("$NAME: 无法取得 int 原始类型，跳过 onMovedToDisplay")
            return false
        }
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
                // 先更新再 proceed，确保被搬运后的首次取值就用新显示设备
                val displayId = chain.getArg(0) as? Int
                updateDisplayId(displayId, "onMovedToDisplay")
                chain.proceed()
            }
            PatchLog.debug("$NAME: 已挂钩 Activity.onMovedToDisplay（任务跨屏搬运）")
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 Activity.onMovedToDisplay 失败", it)
        }.getOrDefault(false)
    }

    private fun updateDisplayId(displayId: Int?, source: String) {
        if (displayId == null) return
        if (displayId == currentDisplayId) return
        PatchLog.debug("$NAME: 前台显示设备切换 $currentDisplayId → $displayId（$source）")
        currentDisplayId = displayId
    }

    /**
     * 解析当前应采用的显示设备 id。
     *
     * 说明：`Resources` 没有公开的 `getDisplay()`（实测 android-37 平台包中只有
     * `getDisplayMetrics()`），因此无法从 hook 点反查所属显示设备，只能用追踪缓存。
     * 追踪 hook 装不上时缓存停在主屏，补丁不生效（安全侧失败）。
     */
    private fun resolveDisplayId(): Int = currentDisplayId

    /**
     * hook 三个读尺寸的入口：`getDimensionPixelSize` / `getDimensionPixelOffset` / `getDimension`。
     *
     * 不同应用（以及同一应用的不同页面）用的不一定是同一个重载；只挂其中一个会出现
     * "某些页面改了、某些没改"的半生效状态。
     */
    private fun hookDimensionReaders(module: XposedModule): Boolean {
        val resourcesClass = Reflect.findClass("android.content.res.Resources", null) ?: run {
            PatchLog.debug("$NAME: Resources 类未找到")
            return false
        }
        var ok = false
        // 方法名 → 归零时返回的值（float 重载要 0f，否则类型不匹配）
        val targets = listOf(
            "getDimensionPixelSize" to (0 as Any),
            "getDimensionPixelOffset" to (0 as Any),
            "getDimension" to (0f as Any),
        )
        for ((name, zero) in targets) {
            val methods = Reflect.findMethods(resourcesClass, name)
            if (methods.isEmpty()) {
                PatchLog.debug("$NAME: Resources.$name 未找到")
                continue
            }
            for (m in methods) {
                if (m.parameterCount != 1 || m.parameterTypes[0] != Int::class.javaPrimitiveType) {
                    continue
                }
                val hooked = runCatching {
                    module.hook(m).intercept { chain -> handle(chain, zero) }
                    true
                }.onFailure {
                    PatchLog.warn("$NAME: 挂钩 Resources.$name(int) 失败", it)
                }.getOrDefault(false)
                if (hooked) PatchLog.debug("$NAME: 已挂钩 Resources.$name(int)")
                ok = ok || hooked
            }
        }
        return ok
    }

    private fun handle(chain: XposedInterface.Chain, zero: Any): Any? {
        val result = chain.proceed()

        val displayId = resolveDisplayId()
        if (displayId == Display.DEFAULT_DISPLAY) return result
        if (!PatchSettings.isEnabled(null, PatchSettings.KEY_STATUSBAR_DIMEN_FIX)) return result

        val res = chain.thisObject as? Resources ?: return result
        val id = chain.getArg(0) as? Int ?: return result

        val targetId = resolveStatusBarHeightResId(res)
        if (targetId == 0) return result
        if (id != targetId) return result

        if (loggedZeroHits.add(displayId)) {
            PatchLog.info(
                "$NAME: displayId=$displayId 非主屏，status_bar_height $result → " +
                    if (zero is Float) "0.0" else "0"
            )
        }
        return zero
    }

    /** 解析并缓存 `android:dimen/status_bar_height` 的 id。 */
    private fun resolveStatusBarHeightResId(res: Resources): Int {
        val cached = statusBarHeightResId
        if (cached >= 0) return cached

        val id = runCatching {
            res.getIdentifier("status_bar_height", "dimen", "android")
        }.getOrDefault(0)

        if (id == 0) {
            PatchLog.debug("$NAME: 本进程资源中不存在 android:dimen/status_bar_height")
        } else {
            PatchLog.debug("$NAME: 解析到 status_bar_height 资源 id=0x${Integer.toHexString(id)}")
        }
        statusBarHeightResId = id
        return id
    }
}
