package io.relimus.zflow.compat.xposed.patch

import android.app.Activity
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.relimus.zflow.compat.xposed.util.PatchLog
import io.relimus.zflow.compat.xposed.util.PatchSettings
import io.relimus.zflow.compat.xposed.util.Reflect
import java.lang.ref.WeakReference
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
 * 判定"是否在小窗"必须能覆盖**三条**进入/离开小窗的路径：
 *
 * | 路径 | 应用行为 | 可用信号 |
 * |---|---|---|
 * | 桌面图标长按启动小窗 | 新进程/新 Activity 在虚拟屏上创建 | `onCreate`/`onResume` 带 displayId≠0 |
 * | 多任务上滑启动小窗 | Activity **不重建、不 resume**，任务被搬到虚拟屏 | `onMovedToDisplay` |
 * | 右上角「展开」回主屏 | 任务被搬回主屏，Z-Flow **抑制 relaunch**，Activity 不重建 | 同上 + hook 点实时反查 |
 *
 * ### 为什么不能只靠缓存的 displayId
 *
 * 早期实现把 displayId 缓存在进程里，只在 `onResume` / `onMovedToDisplay` 刷新。这有两个缺口：
 *
 * 1. **首次读取早于追踪**：Activity 在 `onCreate` 里就会读 status_bar_height，而那时两个回调
 *    都还没跑，缓存停在上一个值（新进程是主屏 0）→ 冷启动小窗的第一次布局不生效。
 * 2. **展开时缓存过期**：Z-Flow 的展开是 `moveRootTaskToDisplay(rootTaskId, DEFAULT_DISPLAY)`
 *    且 `HookReload.suppressNextRelaunch` 抑制重建，Activity 被复用。若应用在这次搬运后的
 *    重排里再次读取 status_bar_height，而缓存还停在虚拟屏的 displayId 上，就会继续返回 0
 *    ——**小窗里被删掉的顶部留白，在展开态没有补回来，系统状态栏直接压住标题栏**。
 *
 * 因此现在改为：**hook 点用弱引用拿到当前 Activity，实时反查它真实所在的 displayId**
 * （`Activity.getDisplay()` 是活查询），缓存只作为拿不到 Activity 时的兜底。
 * 同时把追踪前移到 `Activity.onCreate`（`proceed` 之前），让进程内的第一次读取就已经是
 * 正确的显示设备。
 *
 * ## 兜底：展开后重建界面（默认关闭）
 *
 * 有些应用是在 `onCreate` 里把状态栏高度一次性烤进 padding / 占位 View 的，窗口尺寸变化时
 * 不会重新读取。这类应用即使判定正确，展开后也保留着"小窗阶段拿到的 0"，
 * 只能靠重建 Activity 让布局重新算一遍。
 *
 * 因此提供 [PatchSettings.KEY_REBUILD_ON_EXPAND]：仅在
 * 「本进程确实归零过」+「回到主屏」+「宽限期内应用没有再来读 status_bar_height」三个条件
 * 同时成立时才重建一次，避免对正常应用乱重建。
 *
 * ## 边界
 *
 * - 只对加入了作用域的应用生效。
 * - 只覆盖 `getDimensionPixelSize` 这一条路径；用 `getDimension` /
 *   `getDimensionPixelOffset` / 硬编码 dp 的应用不受影响。
 * - 主屏行为完全不变（实时反查为主屏时直接返回）。
 * - 若所有追踪 hook 都装不上，`currentDisplayId` 会停在主屏（0），补丁静默不生效 ——
 *   宁可漏改也不误改主屏。
 */
object StatusBarDimenFix {

    private const val NAME = "StatusBarDimenFix"

    /** 展开到主屏后，等应用自己重新读取状态栏高度的宽限期。 */
    private const val EXPAND_GRACE_MS = 800L

    /** 当前所在显示设备；由生命周期 hook 更新。默认主屏 → 不改任何行为。 */
    @Volatile
    private var currentDisplayId: Int = Display.DEFAULT_DISPLAY

    /** `android:dimen/status_bar_height` 在本进程资源里的 id；-1 = 尚未解析。 */
    @Volatile
    private var statusBarHeightResId: Int = -1

    /** 保证同一进程内只装一次。 */
    private val installed = AtomicBoolean(false)

    /**
     * 是否已装上显示设备追踪 hook（任一回调）。仅用于日志与自检，
     * 判定本身优先走 [resolveDisplayId] 的实时反查。
     */
    @Volatile
    private var trackingHooked = false

    /** 最近一次进入前台的 Activity：hook 点用它实时反查所在显示设备。 */
    @Volatile
    private var lastActivity: WeakReference<Activity>? = null

    /** 本进程最近一次读取 status_bar_height 的时间（uptimeMillis）。 */
    @Volatile
    private var lastReadAt: Long = 0L

    /** 本进程是否真的把 status_bar_height 归零过（决定展开后是否需要兜底重建）。 */
    @Volatile
    private var everZeroed = false

    /** 最近一次「非主屏 → 主屏」切换的时间；-1 = 没有待判定的展开。 */
    @Volatile
    private var expandAt: Long = -1L

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

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
        val resourcesHooked = hookGetDimensionPixelSize(module)

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
     * 追踪应用当前所在显示设备。三条路径都要覆盖：
     *
     * - `onCreate`：Activity 首次创建（**proceed 之前**登记，让 onCreate 里的读取就正确）
     * - `onResume`：Activity 在前台被创建/恢复（桌面长按启动小窗走这条）
     * - `onMovedToDisplay`：任务被搬到另一显示设备且**不重建**（多任务上滑、右上角展开都走这条）
     */
    private fun hookActivityDisplayTracking(module: XposedModule, classLoader: ClassLoader): Boolean {
        val activityClass = Reflect.findClass("android.app.Activity", classLoader) ?: run {
            PatchLog.debug("$NAME: android.app.Activity 未找到")
            return false
        }

        val createHooked = hookOnCreate(module, activityClass)
        val onResumeHooked = hookOnResume(module, activityClass)
        val movedHooked = hookOnMovedToDisplay(module, activityClass)

        trackingHooked = createHooked || onResumeHooked || movedHooked
        return trackingHooked
    }

    /**
     * `Activity.onCreate(Bundle)`：最早的可用时机。
     *
     * 在 `proceed` **之前**登记 -- 应用在 onCreate 里就会读 status_bar_height
     * （很多"宽额头"就是这一处算出来的）。
     */
    private fun hookOnCreate(module: XposedModule, activityClass: Class<*>): Boolean {
        val onCreate = Reflect.findMethodExact(
            activityClass,
            "onCreate",
            android.os.Bundle::class.java,
        ) ?: run {
            PatchLog.debug("$NAME: Activity.onCreate(Bundle) 未找到")
            return false
        }

        return runCatching {
            module.hook(onCreate).intercept { chain ->
                val activity = chain.thisObject as? Activity
                rememberActivity(activity)
                updateDisplayId(displayIdOf(activity), "onCreate")
                chain.proceed()
            }
            PatchLog.debug("$NAME: 已挂钩 Activity.onCreate（首个读取点）")
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
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                rememberActivity(activity)
                updateDisplayId(displayIdOf(activity), "onResume")
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
     * 系统在「任务被移到另一显示设备且 Activity 未重建」时调用 —— 多任务上滑开小窗、
     * 小窗展开回主屏都是它。签名固定为 (int, Configuration)，跨版本稳定。
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
                rememberActivity(chain.thisObject as? Activity)
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

    private fun rememberActivity(activity: Activity?) {
        if (activity == null) return
        lastActivity = WeakReference(activity)
    }

    /** 从 Activity 实时取 displayId；API 26-29 上不可用时返回 null。 */
    private fun displayIdOf(activity: Activity?): Int? =
        runCatching { activity?.display?.displayId }.getOrNull()

    private fun updateDisplayId(displayId: Int?, source: String) {
        if (displayId == null) return
        val previous = currentDisplayId
        if (displayId == previous) return
        PatchLog.debug("$NAME: 前台显示设备切换 $previous → $displayId（$source）")
        currentDisplayId = displayId

        // 非主屏 → 主屏 = 用户点了「展开」：小窗阶段被清零的顶部留白需要在展开态补回来
        if (previous != Display.DEFAULT_DISPLAY && displayId == Display.DEFAULT_DISPLAY) {
            onExpandedToDefault(source)
        }
    }

    /**
     * 解析当前应采用的显示设备 id。
     *
     * 优先用当前 Activity **实时反查**：Z-Flow 展开时抑制 relaunch，Activity 被复用，
     * 缓存的 displayId 很容易停在虚拟屏上，只有活查询才跟得上。
     * 拿不到 Activity（进程极早期 / 应用尚未创建任何 Activity）时退回缓存。
     */
    private fun resolveDisplayId(): Int {
        val activity = lastActivity?.get()
        val live = displayIdOf(activity)
        if (live != null) {
            updateDisplayId(live, "hook 点实时反查")
            return live
        }
        return currentDisplayId
    }

    private fun hookGetDimensionPixelSize(module: XposedModule): Boolean {
        val resourcesClass = Reflect.findClass("android.content.res.Resources", null) ?: run {
            PatchLog.debug("$NAME: Resources 类未找到")
            return false
        }
        val methods = Reflect.findMethods(resourcesClass, "getDimensionPixelSize")
        if (methods.isEmpty()) {
            PatchLog.debug("$NAME: Resources.getDimensionPixelSize 未找到")
            return false
        }
        PatchLog.debug("$NAME: getDimensionPixelSize 重载数=${methods.size}")

        var ok = false
        for (m in methods) {
            // 只关心 (int) 这个重载
            if (m.parameterCount != 1 || m.parameterTypes[0] != Int::class.javaPrimitiveType) {
                PatchLog.debug("$NAME: 跳过重载 (${m.parameterTypes.joinToString { it.simpleName }})")
                continue
            }
            val hooked = runCatching {
                module.hook(m).intercept { chain -> handle(chain) }
                true
            }.onFailure {
                PatchLog.warn("$NAME: 挂钩 Resources.getDimensionPixelSize(int) 失败", it)
            }.getOrDefault(false)
            ok = ok || hooked
        }

        if (ok) PatchLog.debug("$NAME: 已挂钩 Resources.getDimensionPixelSize(int)")
        return ok
    }

    private fun handle(chain: XposedInterface.Chain): Any? {
        val result = chain.proceed()

        // 先做最便宜的判定：绝大多数调用都不是 status_bar_height，直接放行
        val res = chain.thisObject as? Resources ?: return result
        val id = chain.getArg(0) as? Int ?: return result
        val targetId = resolveStatusBarHeightResId(res)
        if (targetId == 0 || id != targetId) return result

        if (!PatchSettings.isEnabled(null, PatchSettings.KEY_STATUSBAR_DIMEN_FIX)) return result

        lastReadAt = SystemClock.uptimeMillis()

        val displayId = resolveDisplayId()
        if (displayId == Display.DEFAULT_DISPLAY) return result

        everZeroed = true
        PatchLog.debug("$NAME: displayId=$displayId 非主屏，status_bar_height $result → 0")
        return 0
    }

    /**
     * 「展开」到主屏时的兜底修复。
     *
     * 只有在下面三条同时成立时才重建界面，避免对正常应用乱重建：
     * 1. 本进程确实把状态栏高度归零过（说明小窗里改过它的布局）；
     * 2. 用户打开了 [PatchSettings.KEY_REBUILD_ON_EXPAND]；
     * 3. 宽限期内应用**没有**再来读 status_bar_height —— 说明它把 0 烤进了 padding /
     *    占位 View，靠窗口重排是补不回来的。
     */
    private fun onExpandedToDefault(source: String) {
        if (!everZeroed) {
            PatchLog.debug("$NAME: 展开到主屏（$source），本进程未归零过，无需额外处理")
            return
        }
        if (!PatchSettings.isEnabled(null, PatchSettings.KEY_REBUILD_ON_EXPAND, default = false)) {
            PatchLog.debug("$NAME: 展开到主屏（$source），兜底重建开关已关闭")
            return
        }

        val mark = SystemClock.uptimeMillis()
        expandAt = mark
        PatchLog.debug("$NAME: 展开到主屏（$source），${EXPAND_GRACE_MS}ms 后检查应用是否重新取值")
        mainHandler.postDelayed({ maybeRebuildAfterExpand(mark) }, EXPAND_GRACE_MS)
    }

    private fun maybeRebuildAfterExpand(mark: Long) {
        if (expandAt != mark) return
        expandAt = -1L

        if (resolveDisplayId() != Display.DEFAULT_DISPLAY) {
            PatchLog.debug("$NAME: 展开检查：已不在主屏，跳过")
            return
        }
        if (lastReadAt >= mark) {
            PatchLog.debug("$NAME: 展开检查：应用已重新读取状态栏高度，不重建")
            return
        }

        val activity = lastActivity?.get() ?: run {
            PatchLog.debug("$NAME: 展开检查：没有可用的 Activity 引用，跳过重建")
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            PatchLog.debug("$NAME: 展开检查：Activity 已结束，跳过重建")
            return
        }

        PatchLog.info(
            "$NAME: 展开后应用未重新读取状态栏高度（0 已被烤进布局），重建 Activity 一次"
        )
        // 重建后新 Activity 在 onCreate 里会重新取值（此时已在主屏），不需要再触发
        everZeroed = false
        runCatching { activity.recreate() }.onFailure {
            PatchLog.warn("$NAME: Activity.recreate() 失败", it)
        }
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
