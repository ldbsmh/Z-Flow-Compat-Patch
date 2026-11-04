package io.relimus.zflow.compat.xposed.util

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 布局诊断快照（**只读**，不改任何状态）。
 *
 * 用 adb 广播触发，把"当前这个页面到底有哪些视图、各自多高、内边距/外边距多少"整棵
 * 打印到 logcat，用来在"正常 / 顶部小点 / 双行标题栏"三种状态之间做对比：
 *
 * ```bash
 * adb shell am broadcast -a io.relimus.zflow.compat.DUMP --es label normal -p com.tencent.mm
 * ```
 *
 * 之所以要做这个：日志只能证明 hook 命中，证明不了"哪一层多出了多少像素"。有了视图
 * 树就能直接读出是多出来一段 paddingTop、一个 topMargin，还是一个多出来的标题栏，
 * 从而判断该在哪一层拦截，而不是继续靠猜。
 *
 * 只在详细日志打开时安装；主屏也会打印，方便与虚拟屏对照。
 */
object LayoutDump {

    private const val NAME = "LayoutDump"
    const val ACTION = "io.relimus.zflow.compat.DUMP"

    private val installed = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    /** 进程内只保留一个已注册的接收者，避免每次 onResume 都多注册一个。 */
    @Volatile
    private var receiver: BroadcastReceiver? = null

    /** 每个虚拟屏自动快照的次数（进入首页、从通讯录返回都会各自触发一次 onResume）。 */
    private val autoDumpCount = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private const val AUTO_DUMP_LIMIT = 6

    /** 最近一次 resume 的 Activity，广播到达时以它为准。 */
    @Volatile
    private var current: WeakReference<Activity>? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        if (!PatchLog.verbose) {
            PatchLog.debug("$NAME: 详细日志未打开，跳过安装")
            installed.set(false)
            return
        }

        val activityClass = Reflect.findClass("android.app.Activity", classLoader) ?: run {
            installed.set(false)
            PatchLog.warn("$NAME: 未找到 android.app.Activity")
            return
        }

        val onResume = Reflect.findMethodExact(activityClass, "onResume") ?: run {
            installed.set(false)
            PatchLog.warn("$NAME: Activity.onResume 未找到")
            return
        }

        val ok = runCatching {
            module.hook(onResume).intercept { chain ->
                val activity = chain.thisObject as? Activity
                chain.proceed()
                if (activity != null) {
                    current = WeakReference(activity)
                    ensureReceiver(activity)
                    autoDump(activity)
                }
            }
            true
        }.onFailure {
            PatchLog.warn("$NAME: 挂钩 Activity.onResume 失败", it)
        }.getOrDefault(false)

        if (!ok) {
            installed.set(false)
            return
        }
        PatchLog.info("$NAME: 诊断已就绪，广播 $ACTION 可打印当前页面视图树")
    }


    /**
     * 进入虚拟屏后自动打一份布局快照（延迟 1.2 秒，等首帧测量完成）。
     *
     * 之前只能靠 adb 广播手动触发，设备一断线就没法取证；改成 onResume 自己触发后，
     * LSPosed 日志里就带着真实布局，不需要截图，也不需要额外的操作步骤。
     * 每个虚拟屏最多 [AUTO_DUMP_LIMIT] 次，避免日志爆量。
     */
    private fun autoDump(activity: Activity) {
        val displayId = runCatching { activity.display?.displayId }.getOrNull() ?: return
        if (displayId == android.view.Display.DEFAULT_DISPLAY) return
        val done = autoDumpCount[displayId] ?: 0
        if (done >= AUTO_DUMP_LIMIT) return
        autoDumpCount[displayId] = done + 1
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return
        val label = "auto-d" + displayId + "-n" + (done + 1) + "-" + activity.javaClass.simpleName
        runCatching {
            decor.postDelayed({
                runCatching { dumpActivity(activity, label) }
                    .onFailure { PatchLog.warn("$NAME: 自动快照失败", it) }
            }, 1200L)
        }
    }

    private fun ensureReceiver(activity: Activity) {
        if (receiver != null) return
        if (!receiverRegistered.compareAndSet(false, true)) return
        val context = activity.applicationContext ?: activity
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val label = intent?.getStringExtra("label") ?: "manual"
                val activityNow = current?.get()
                if (activityNow == null) {
                    PatchLog.warn("$NAME: 广播到达但没有可用的 Activity")
                    return
                }
                runCatching { dumpActivity(activityNow, label) }
                    .onFailure { PatchLog.warn("$NAME: 打印视图树失败", it) }
            }
        }
        val filter = IntentFilter(ACTION)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }.onFailure {
            PatchLog.warn("$NAME: 注册诊断广播失败: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
        if (registered == null) {
            receiverRegistered.set(false)
        } else {
            this.receiver = receiver
        }
    }


    /** 打印环境参数 + 视图树 + 首页字段。 */
    fun dumpActivity(activity: Activity, label: String) {
        val sb = StringBuilder()
        sb.append("===== DUMP[$label] ").append(activity.javaClass.name).append(" =====\n")

        val display = runCatching { activity.display }.getOrNull()
        sb.append("display=").append(display?.displayId ?: -1)
            .append(" state=").append(display?.state ?: -1)
            .append(" size=").append(display?.mode?.physicalWidth ?: -1)
            .append('x').append(display?.mode?.physicalHeight ?: -1)
            .append(" density=").append(activity.resources.displayMetrics.density)
            .append('\n')

        val statusBarPx = runCatching {
            val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id == 0) -1 else activity.resources.getDimensionPixelSize(id)
        }.getOrDefault(-2)
        sb.append("status_bar_height=").append(statusBarPx)

        val decor = runCatching { activity.window?.decorView }.getOrNull()
        if (decor != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                val insets = runCatching { decor.rootWindowInsets }.getOrNull()
                val top = runCatching {
                    insets?.getInsets(WindowInsets.Type.systemBars())?.top ?: -1
                }.getOrDefault(-3)
                val cutout = runCatching {
                    insets?.getInsets(WindowInsets.Type.displayCutout())?.top ?: -1
                }.getOrDefault(-3)
                sb.append(" insetsTop=").append(top).append(" cutoutTop=").append(cutout)
            }
        }
        val config = activity.resources.configuration
        sb.append(" multiWindow=").append(runCatching { activity.isInMultiWindowMode }.getOrDefault(false))
            .append(" windowingMode=").append(windowingModeOf(config))
            .append(" screen=").append(config.screenWidthDp).append('x').append(config.screenHeightDp)
            .append('\n')

        if (decor != null) {
            sb.append("decor ").append(decor.width).append('x').append(decor.height).append('\n')
            walk(decor, 0, sb, intArrayOf(400))
        }

        dumpKnownForms(activity, sb)

        PatchLog.info(sb.toString())
    }


    /** `Configuration.windowConfiguration.windowingMode`（反射读取，避免 compileSdk 差异）。 */
    private fun windowingModeOf(config: android.content.res.Configuration): Int {
        val wc = runCatching {
            config.javaClass.getMethod("getWindowConfiguration").invoke(config)
        }.getOrNull() ?: return -1
        return runCatching {
            wc.javaClass.getMethod("getWindowingMode").invoke(wc) as Int
        }.getOrDefault(-1)
    }

    /** 逐层打印：类名 / id / 尺寸 / 屏幕坐标 / 可见性 / padding / margin / 文字。 */
    private fun walk(view: View, depth: Int, sb: StringBuilder, budget: IntArray) {
        if (budget[0]-- <= 0) return
        if (depth > 14) return

        val loc = IntArray(2)
        runCatching { view.getLocationOnScreen(loc) }

        sb.append("  ".repeat(depth))
            .append(view.javaClass.name.substringAfterLast('.'))
            .append(" #").append(idName(view))
            .append(' ').append(view.width).append('x').append(view.height)
            .append(" @").append(loc[0]).append(',').append(loc[1])
            .append(" v").append(view.visibility)
            .append(" pad=").append(view.paddingLeft).append(',').append(view.paddingTop)
            .append(',').append(view.paddingRight).append(',').append(view.paddingBottom)

        val lp = view.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            sb.append(" m=").append(lp.leftMargin).append(',').append(lp.topMargin)
                .append(',').append(lp.rightMargin).append(',').append(lp.bottomMargin)
        }
        if (lp != null) {
            sb.append(" lp=").append(lp.width).append('x').append(lp.height)
        }
        if (view.translationY != 0f) sb.append(" ty=").append(view.translationY)
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (text.isNotEmpty()) sb.append(" text=\"").append(text.take(12)).append('"')
        }
        sb.append('\n')

        if (view is ViewGroup) {
            // 滚动容器只展开前几个孩子：既能看到列表首行（重复标题往往就藏在里面），
            // 又不会把几百个条目全部打印出来。
            val limit = if (view is AbsListView || view.javaClass.name.contains("RecyclerView")) 4
            else view.childCount
            for (i in 0 until minOf(view.childCount, limit)) {
                walk(view.getChildAt(i), depth + 1, sb, budget)
            }
            if (view.childCount > limit) {
                sb.append("  ".repeat(depth + 1)).append("…(")
                    .append(view.childCount - limit).append(" more children)\n")
            }
        }
    }

    /**
     * 打印已知页面类（首页 MainUI）的字段。
     *
     * `H` 是微信自己的"多窗口"标志；双行标题栏出现时它的真假能直接说明
     * `isInMultiWindowMode` 诱导有没有传到微信的布局判断里。
     */
    private fun dumpKnownForms(activity: Activity, sb: StringBuilder) {
        val fragments = fragmentsOf(activity)
        if (fragments.isEmpty()) {
            sb.append("fragments: <none>\n")
            return
        }
        for (fragment in fragments) {
            val name = fragment.javaClass.name
            if (!name.contains("MainUI") && !name.contains("LauncherUI")) continue

            sb.append("fragment ").append(name).append('\n')
            for (field in fragment.javaClass.declaredFields) {
                val type = field.type
                if (type != java.lang.Boolean.TYPE && type != Integer.TYPE) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(fragment)
                }.getOrNull() ?: continue
                // int 只打印"像高度"的值，避免把一堆无关键打印出来
                if (type == Integer.TYPE) {
                    val v = value as Int
                    if (v < 0 || v > 1000) continue
                }
                sb.append("  ").append(field.name).append('=').append(value).append('\n')
            }
        }
    }

    private fun fragmentsOf(activity: Activity): List<Any> {
        val out = mutableListOf<Any>()

        // androidx FragmentActivity
        val support = Reflect.findMethod(activity.javaClass, "getSupportFragmentManager", 0)
        if (support != null) {
            val fm = Reflect.callMethod(activity, support)
            val getFragments = fm?.let { Reflect.findMethod(it.javaClass, "getFragments", 0) }
            val list = Reflect.callMethod(fm, getFragments) as? List<*>
            list?.filterNotNull()?.forEach { out.add(it) }
        }

        // framework Activity
        if (out.isEmpty()) {
            val fm = runCatching { activity.fragmentManager }.getOrNull()
            val getFragments = fm?.let { Reflect.findMethod(it.javaClass, "getFragments", 0) }
            val list = Reflect.callMethod(fm, getFragments) as? List<*>
            list?.filterNotNull()?.forEach { out.add(it) }
        }
        return out
    }

    private fun idName(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return "-"
        return runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
            ?: "0x" + id.toString(16)
    }
}
