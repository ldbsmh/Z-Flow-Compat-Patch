package io.relimus.zflow.compat.xposed.patch

import android.app.Activity
import android.os.SystemClock
import android.view.Display
import android.view.View
import io.github.libxposed.api.XposedModule
import io.relimus.zflow.compat.xposed.util.PatchLog
import io.relimus.zflow.compat.xposed.util.PatchSettings
import io.relimus.zflow.compat.xposed.util.Reflect
import io.relimus.zflow.compat.xposed.util.VirtualDisplayTracker
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通用（版本无关）微信小窗顶部布局修复 —— 实验开关，默认关闭。
 *
 * ## 为什么要另起一套
 *
 * [WeChatStatusBarFix] 依赖微信的混淆名（`ui.bl` / `ui.ol`、`u0` / `H0`、`I0` / `L0` …），
 * 微信每个版本洗牌一次就要重新逆向一轮。本补丁换思路：**只挂不随版本变化的东西**。
 *
 * ## 第一层：把微信骗进「平台多窗口」分支
 *
 * 微信自己的布局代码里有一条多窗口分支（8.0.76/8.0.79 均存在，见 README 第 26 节）：
 *
 * ```java
 * if (H) { margin = 0; maskHeight = 0; }   // H == isInMultiWindowMode()
 * else   { margin = p1; maskHeight = p2; } // 普通分支，会把全屏的 124px 写回来
 * ```
 *
 * 虚拟屏里 `H` 恒为 `false`，所以微信永远走普通分支，全屏残留的顶部偏移被反复写回，
 * 这就是「双行标题栏」和「展开后缩回变宽」的根因。
 *
 * 微信自己已经写好了「多窗口就清零」的正确逻辑，那就让它认为自己在多窗口里：
 * hook `Activity.isInMultiWindowMode()`（API 24 起的**框架方法，永不混淆**），
 * 当当前 Activity 位于虚拟屏时返回 `true`。
 *
 * 这样微信不需要任何类名/方法名适配，直接走它自己为分屏/自由窗口写的那条路径。
 *
 * ## 第二层：中和 `Main_top_marign` 缓存
 *
 * 通讯录/发现/聊天容器会读全屏阶段写下的缓存（键名 `Main_top_marign`，原文拼写如此），
 * 这是「展开后缩回变宽」的另一半原因。8.0.76 起读取者从 1 个变成 3 个，逐个 hook
 * 读取方法必然越追越多；改为在**读取入口**统一拦截：
 *
 * hook `SharedPreferences.getInt/getBoolean`（系统 API），键名匹配时在虚拟屏返回
 * "无缓存"。键名是微信自己写死的字符串常量，跨版本稳定。
 *
 * ## 边界与代价
 *
 * - 只在微信进程、只在虚拟屏生效；主屏完全放行。
 * - 开关默认关闭，且**改动后需完整结束微信进程**才重新安装（hook 点在进程启动时装）。
 * - 微信的多窗口分支还可能影响其他 UI（例如分屏相关的底栏/拖拽行为），因此保留独立
 *   开关，便于与已验证的 [WeChatStatusBarFix] 对照测试。
 * - 任一 hook 装不上都只降级为"该层不生效"，不影响微信正常运行。
 */
object UniversalMultiWindowFix {

    private const val NAME = "UniversalMultiWindowFix"
    private const val WECHAT_PKG = "com.tencent.mm"

    /** 微信全屏阶段写入的顶部偏移缓存（键名原文拼写就是 `marign`）。 */
    private const val KEY_TOP_MARGIN = "Main_top_marign"

    /** 是否允许读取上面的缓存。 */
    private const val KEY_NEED_READ_TOP_MARGIN = "Main_need_read_top_margin"

    /**
     * 微信首页容器类。类名是微信自己写的、不参与混淆（`com.tencent.mm.ui.conversation`），
     * 8.0.5x ~ 8.0.79 一直存在。
     */
    private const val MAIN_UI_CLASS = "com.tencent.mm.ui.conversation.MainUI"

    /**
     * 可能重写 `isInMultiWindowMode()` 的微信类。
     *
     * 这个是**框架方法名**：任何重写它的类都必须保持同名（否则 super 调用就断了），
     * 所以不可能是混淆产物。类本身是微信的大类，低于重混淆阈值，跨版本稳定。
     */
    private val OVERRIDE_CLASSES = listOf(
        "com.tencent.mm.ui.LauncherUI",
        "com.tencent.mm.ui.conversation.MainUI",
        "com.tencent.mm.ui.MMFragmentActivity",
    )

    /**
     * 偏好读取入口的候选实现类。
     *
     * - `MultiProcessSharedPreferences`：微信自己的多进程偏好封装，位于 `sdk.platformtools`，
     *   包名不参与混淆；命中它就够了，内层实现不需要重复 hook。
     * - `SharedPreferencesImpl`：系统实现，作为兜底（微信未走多进程封装时）。
     */
    private val PREFS_CLASSES = listOf(
        "com.tencent.mm.sdk.platformtools.MultiProcessSharedPreferences",
        "android.app.SharedPreferencesImpl",
    )

    private val frameworkHooked = AtomicBoolean(false)
    private val hookedOverrides = ConcurrentHashMap.newKeySet<String>()
    private val hookedPrefs = ConcurrentHashMap.newKeySet<String>()

    /** 首次命中日志去重：同一条 (`label@displayId`、键名) 只打一次 info，详细日志不受影响。 */
    private val loggedFlagHits = ConcurrentHashMap.newKeySet<String>()
    private val loggedPrefHits = ConcurrentHashMap.newKeySet<String>()
    private val loggedWriterHits = ConcurrentHashMap.newKeySet<String>()
    private val loggedTopOffset = ConcurrentHashMap.newKeySet<Int>()

    /** 开关缓存：这两个 hook 点调用很热，不能每次都读远程偏好。 */
    @Volatile
    private var cachedEnabled: Boolean = false

    @Volatile
    private var lastEnabledCheck: Long = 0L

    fun install(module: XposedModule, classLoader: ClassLoader, pkg: String) {
        if (pkg != WECHAT_PKG) return

        // 默认关闭：实验开关不能越过已验证的 WeChatStatusBarFix
        if (!enabled()) {
            PatchLog.debug("$NAME: 开关已关闭（默认关闭），跳过安装")
            return
        }

        PatchLog.info("$NAME: 开始安装（版本无关模式）")

        // 两条路线同时开会互相覆盖：旧方案把首页状态栏高度归零，新方案让微信按多窗口
        // 布局，最终状态取决于调用顺序。这里只提醒，不强行改用户的开关。
        if (PatchSettings.isEnabled(PatchSettings.KEY_WECHAT_STATUSBAR_FIX)) {
            PatchLog.warn(
                "$NAME: 检测到「微信小窗标题栏重复修复」同时开启，两者会互相覆盖；" +
                    "对照验证时建议只开一个"
            )
        }

        // 第二层依赖进程级显示设备判定
        VirtualDisplayTracker.install(module, classLoader)

        val flagHooked = hookActivityFlag(module, classLoader)
        hookOverrides(module, classLoader)
        val prefsHooked = hookPrefs(module, classLoader)
        val writerHooked = hookMarginWriter(module, classLoader)
        val offsetHooked = hookTopOffset(module, classLoader)

        when {
            writerHooked && offsetHooked ->
                PatchLog.info(
                    "$NAME: 安装完成 —— 写入钳制 + 标题栏位移钳制生效" +
                        if (flagHooked || prefsHooked) "（多窗口诱导/缓存中和作为兜底）" else ""
                )
            writerHooked ->
                PatchLog.info("$NAME: 安装完成 —— 写入钳制生效（直接改写入口，不再依赖 H）")
            offsetHooked ->
                PatchLog.info("$NAME: 安装完成 —— 仅标题栏位移钳制生效")
            flagHooked ->
                PatchLog.info("$NAME: 安装完成 —— 仅多窗口诱导生效（未定位到写入方法）")
            prefsHooked ->
                PatchLog.info("$NAME: 安装完成 —— 仅缓存中和生效（未挂上 isInMultiWindowMode）")
            else ->
                PatchLog.warn("$NAME: 四个 hook 点都未装上，补丁未生效")
        }
    }

    // ---------------------------------------------------------------- 第一层

    /** hook `Activity.isInMultiWindowMode()`：虚拟屏返回 true，其余放行。 */
    private fun hookActivityFlag(module: XposedModule, classLoader: ClassLoader): Boolean {
        if (!frameworkHooked.compareAndSet(false, true)) return true

        val activityClass = Reflect.findClass("android.app.Activity", classLoader) ?: run {
            frameworkHooked.set(false)
            PatchLog.warn("$NAME: 未找到 android.app.Activity")
            return false
        }
        val method = Reflect.findMethodExact(activityClass, "isInMultiWindowMode") ?: run {
            frameworkHooked.set(false)
            PatchLog.warn("$NAME: Activity.isInMultiWindowMode() 不存在（API < 24）")
            return false
        }

        val ok = hookFlag(module, method, "android.app.Activity")
        if (!ok) frameworkHooked.set(false)
        return ok
    }

    /**
     * 微信若重写了 `isInMultiWindowMode()`，调用就不会走到框架实现，必须单独挂钩。
     * 方法名是框架名（重写不能改名），所以候选只是"类"。
     */
    private fun hookOverrides(module: XposedModule, classLoader: ClassLoader) {
        for (className in OVERRIDE_CLASSES) {
            val key = "$className@${System.identityHashCode(classLoader)}"
            if (!hookedOverrides.add(key)) continue

            val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
            if (clazz == null) {
                hookedOverrides.remove(key)
                PatchLog.debug("$NAME: 未找到候选类 $className")
                continue
            }
            val override = clazz.declaredMethods.firstOrNull {
                it.name == "isInMultiWindowMode" && it.parameterCount == 0
            }
            if (override == null) {
                PatchLog.debug("$NAME: $className 未重写 isInMultiWindowMode（走框架实现，已覆盖）")
                continue
            }
            hookFlag(module, override, className)
        }
    }

    private fun hookFlag(module: XposedModule, method: Method, label: String): Boolean {
        val hooked = runCatching {
            module.hook(method).intercept { chain ->
                val host = chain.thisObject ?: return@intercept chain.proceed()
                if (!enabled()) return@intercept chain.proceed()

                val displayId = displayIdOf(host)
                if (displayId == null || displayId == Display.DEFAULT_DISPLAY) {
                    return@intercept chain.proceed()
                }

                if (loggedFlagHits.add("$label@$displayId")) {
                    PatchLog.info(
                        "$NAME: 首次命中 $label 在 displayId=$displayId，" +
                            "isInMultiWindowMode 已改为 true"
                    )
                }
                true
            }
            PatchLog.info("$NAME: 已挂钩 $label#isInMultiWindowMode → 虚拟屏返回 true")
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 $label#isInMultiWindowMode 失败", it)
        }.getOrDefault(false)

        return hooked
    }

    /** 优先读 Activity 自己的 display（逐实例准确）；其它对象尝试 getContext()。 */
    private fun displayIdOf(host: Any): Int? {
        if (host is Activity) {
            return runCatching { host.display?.displayId }.getOrNull()
        }
        val getContext = Reflect.findMethod(host.javaClass, "getContext", 0) ?: return null
        val ctx = Reflect.callMethod(host, getContext) as? android.content.Context ?: return null
        return runCatching { ctx.display?.displayId }.getOrNull()
    }

    // ---------------------------------------------------------------- 第三层：写入钳制

    /**
     * 直接改写首页顶部偏移的**写入点**，不再试图影响 `H` 的取值。
     *
     * 首次实测日志（2026-11-04 21:26）证明：`isInMultiWindowMode()` 在虚拟屏命中了 174 次，
     * 但双行/小点照样出现 —— 说明微信的 `H` 不是从这个框架方法取值（大概率来自
     * `Configuration` 或它自己的工具类），第一层诱导打不中要害。
     *
     * 换个位置：微信首页的顶部偏移只有一个写入口，8.0.76 是 `MainUI.u0(int, int)`。
     * 它在多窗口分支里本来就把两个参数清零，所以**在调用点直接把参数换成 (0, 0)**
     * 等价于「让微信永远走多窗口分支」，且完全不需要知道 `H` 从哪来。
     *
     * 定位方式不依赖混淆名：`MainUI` 类名稳定，方法按「唯一的 (int,int)void」签名锁定。
     * 若某版本有多于一个候选（或一个都没有），只打警告并降级，不动手。
     */
    private fun hookMarginWriter(module: XposedModule, classLoader: ClassLoader): Boolean {
        val clazz = Reflect.findClass(MAIN_UI_CLASS, classLoader) ?: run {
            PatchLog.warn("$NAME: 未找到 $MAIN_UI_CLASS，写入钳制不可用")
            return false
        }
        val intType = Int::class.javaPrimitiveType ?: return false
        val candidates = clazz.declaredMethods.filter {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == intType &&
                it.parameterTypes[1] == intType &&
                it.returnType == Void.TYPE
        }
        if (candidates.size != 1) {
            PatchLog.warn(
                "$NAME: $MAIN_UI_CLASS 中 (II)V 方法数=${candidates.size}，" +
                    "无法唯一定位写入点，已跳过（不影响其它两层）"
            )
            return false
        }

        val method = candidates.first()
        val getActivity = Reflect.findMethod(clazz, "getActivity", 0)

        val hooked = runCatching {
            module.hook(method).intercept { chain ->
                val host = chain.thisObject ?: return@intercept chain.proceed()
                if (!enabled()) return@intercept chain.proceed()

                val activity = (host as? Activity)
                    ?: (Reflect.callMethod(host, getActivity) as? Activity)
                val displayId = runCatching { activity?.display?.displayId }.getOrNull() ?: -1
                if (displayId == Display.DEFAULT_DISPLAY || displayId < 0) {
                    return@intercept chain.proceed()
                }

                val margin = chain.getArg(0) as? Int ?: 0
                val mask = chain.getArg(1) as? Int ?: 0

                if (loggedWriterHits.add("${method.name}@$displayId")) {
                    val h = Reflect.getField(host, "H")
                    PatchLog.info(
                        "$NAME: 首次命中写入点 ${method.name}($margin,$mask) " +
                            "H=$h displayId=$displayId → 强制改写为 (0,0)"
                    )
                } else if (margin != 0 || mask != 0) {
                    PatchLog.debug(
                        "$NAME: ${method.name}($margin,$mask) displayId=$displayId → (0,0)"
                    )
                }

                // 微信多窗口分支的等价写法：margin=0、maskHeight=0
                chain.proceed(arrayOf(0, 0))
            }
            PatchLog.info(
                "$NAME: 已挂钩 $MAIN_UI_CLASS#${method.name}(int,int) → 虚拟屏强制 (0,0)"
            )
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 $MAIN_UI_CLASS#${method.name}(II) 失败", it)
        }.getOrDefault(false)

        return hooked
    }

    // ---------------------------------------------------------------- 第四层：标题栏位移

    /**
     * 兜住「写入点之外的第二条路径」：`ActionBarContainer.translationY`。
     *
     * 2026-11-04 22:21 实测：`status_bar_height` 已经归零，`MainUI` 的写入点一次都没被调用，
     * 但自动快照里标题栏仍然是 `ActionBarContainer @0,90 ty=90.0` —— 90px = 25dp × 3.6，
     * 说明微信没读我们改过的资源，而是走了「读不到就用 25dp 兜底」的分支。
     *
     * 既然源头不止一个，就改在**结果**上：`View.setTranslationY(float)` 是框架方法，
     * 永远不混淆；虚拟屏里凡是把标题栏容器往下推的正值，一律改成 0。
     *
     * 只处理 `*ActionBarContainer`、只处理 `> 0`、只在虚拟屏：向上隐藏标题栏的动画
     * （负值）保持原样，主屏完全放行。
     */
    private fun hookTopOffset(module: XposedModule, classLoader: ClassLoader): Boolean {
        val viewClass = Reflect.findClass("android.view.View", classLoader) ?: run {
            PatchLog.warn("$NAME: 未找到 android.view.View，标题栏位移钳制不可用")
            return false
        }
        val floatType = Float::class.javaPrimitiveType ?: return false
        val method = Reflect.findMethodExact(viewClass, "setTranslationY", floatType) ?: run {
            PatchLog.warn("$NAME: View.setTranslationY(float) 未找到")
            return false
        }

        val hooked = runCatching {
            module.hook(method).intercept { chain ->
                val value = chain.getArg(0) as? Float ?: 0f
                // 热点方法（每帧动画都会走）：不满足条件的调用尽早放行
                if (value <= 0f) return@intercept chain.proceed()
                if (!enabled()) return@intercept chain.proceed()

                val host = chain.thisObject as? View ?: return@intercept chain.proceed()
                if (!host.javaClass.name.endsWith("ActionBarContainer")) {
                    return@intercept chain.proceed()
                }

                val displayId = runCatching { host.display?.displayId }.getOrNull()
                    ?: VirtualDisplayTracker.displayId
                if (displayId == Display.DEFAULT_DISPLAY) return@intercept chain.proceed()

                if (loggedTopOffset.add(displayId)) {
                    PatchLog.info(
                        "$NAME: displayId=$displayId 标题栏被下移 ${value.toInt()}px → 强制 0，" +
                            "调用方=${callerChain()}"
                    )
                }
                chain.proceed(arrayOf<Any>(0f))
            }
            PatchLog.info(
                "$NAME: 已挂钩 android.view.View#setTranslationY(float) → 虚拟屏标题栏不再下移"
            )
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 View.setTranslationY(float) 失败", it)
        }.getOrDefault(false)

        return hooked
    }

    /**
     * 首次命中时打印调用链，用来确认到底是谁把标题栏推下去的。
     * 只保留微信自己的帧，最多 5 层，避免日志被 framework 栈刷屏。
     */
    private fun callerChain(): String {
        val frames = Throwable().stackTrace
            .asSequence()
            .filter { it.className.startsWith("com.tencent.mm") }
            .filterNot { it.className.contains("UniversalMultiWindowFix") }
            .take(5)
            .map { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
            .toList()
        return if (frames.isEmpty()) "?" else frames.joinToString(" < ")
    }

    // ---------------------------------------------------------------- 第二层

    private fun hookPrefs(module: XposedModule, classLoader: ClassLoader): Boolean {
        val intType = Int::class.javaPrimitiveType ?: return false
        val boolType = Boolean::class.javaPrimitiveType ?: return false

        var ok = false
        for (className in PREFS_CLASSES) {
            val key = "$className@${System.identityHashCode(classLoader)}"
            if (!hookedPrefs.add(key)) {
                ok = true
                continue
            }

            val clazz = Reflect.findClass(className, classLoader)
            if (clazz == null) {
                hookedPrefs.remove(key)
                PatchLog.debug("$NAME: 未找到偏好实现类 $className")
                continue
            }

            val getInt = Reflect.findMethodExact(clazz, "getInt", String::class.java, intType)
            val getBoolean = Reflect.findMethodExact(clazz, "getBoolean", String::class.java, boolType)
            if (getInt == null && getBoolean == null) {
                hookedPrefs.remove(key)
                PatchLog.debug("$NAME: $className 无 getInt/getBoolean(String, …)")
                continue
            }

            var classOk = false
            if (getInt != null) classOk = hookPrefInt(module, getInt, className) || classOk
            if (getBoolean != null) classOk = hookPrefBoolean(module, getBoolean, className) || classOk
            ok = ok || classOk
        }
        return ok
    }

    private fun hookPrefInt(module: XposedModule, method: Method, label: String): Boolean =
        runCatching {
            module.hook(method).intercept { chain ->
                // 只碰这一个键，其余键连显示设备判定都不做，避免高频调用上的额外开销
                val key = chain.getArg(0) as? String
                if (key == KEY_TOP_MARGIN && enabled() && VirtualDisplayTracker.isVirtual) {
                    PatchLog.debug(
                        "$NAME: $label 拦截 $KEY_TOP_MARGIN " +
                            "(displayId=${VirtualDisplayTracker.displayId}) → -1"
                    )
                    if (loggedPrefHits.add(KEY_TOP_MARGIN)) {
                        PatchLog.info("$NAME: 首次拦截 $KEY_TOP_MARGIN → -1（虚拟屏忽略全屏缓存）")
                    }
                    -1
                } else {
                    chain.proceed()
                }
            }
            PatchLog.info("$NAME: 已挂钩 $label#getInt(String, int) → 虚拟屏中和顶部偏移缓存")
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 $label#getInt 失败", it)
        }.getOrDefault(false)

    private fun hookPrefBoolean(module: XposedModule, method: Method, label: String): Boolean =
        runCatching {
            module.hook(method).intercept { chain ->
                val key = chain.getArg(0) as? String
                if (key == KEY_NEED_READ_TOP_MARGIN && enabled() && VirtualDisplayTracker.isVirtual) {
                    PatchLog.debug(
                        "$NAME: $label 拦截 $KEY_NEED_READ_TOP_MARGIN " +
                            "(displayId=${VirtualDisplayTracker.displayId}) → false"
                    )
                    if (loggedPrefHits.add(KEY_NEED_READ_TOP_MARGIN)) {
                        PatchLog.info(
                            "$NAME: 首次拦截 $KEY_NEED_READ_TOP_MARGIN → false（虚拟屏不再读缓存）"
                        )
                    }
                    false
                } else {
                    chain.proceed()
                }
            }
            PatchLog.info("$NAME: 已挂钩 $label#getBoolean(String, boolean) → 虚拟屏关闭缓存读取开关")
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 $label#getBoolean 失败", it)
        }.getOrDefault(false)

    // ---------------------------------------------------------------- 设置

    /**
     * 带缓存地读开关：`isInMultiWindowMode` 与偏好读取都可能在布局循环里被高频调用，
     * 不能每次都走远程偏好。2 秒粒度足够，用户切换开关后重新进前台即生效。
     */
    private fun enabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastEnabledCheck > ENABLED_CACHE_MS) {
            cachedEnabled = PatchSettings.isEnabled(
                PatchSettings.KEY_UNIVERSAL_MULTIWINDOW_FIX,
                default = false,
            )
            lastEnabledCheck = now
        }
        return cachedEnabled
    }

    private const val ENABLED_CACHE_MS = 2_000L
}
