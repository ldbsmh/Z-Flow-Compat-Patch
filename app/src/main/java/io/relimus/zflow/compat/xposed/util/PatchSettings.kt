package io.relimus.zflow.compat.xposed.util

import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface

/**
 * 读取模块设置。
 *
 * 运行在被 hook 的应用进程里读不到模块自己的 SharedPreferences，因此走
 * libxposed 的「远程偏好」通道：模块设置页写入，框架同步，宿主进程只读。
 *
 * 读取失败一律返回 [DEFAULT_ENABLED]，保证"读不到设置"不会误改用户行为。
 */
object PatchSettings {

    const val GROUP = "compat_settings"

    /**
     * 补丁开关 key（与设置页的 SwitchCompat 绑定 key 一致）。
     *
     * [KEY_UNIVERSAL_MULTIWINDOW_FIX] 默认关闭（保持"装了就有变化"的保守默认值），
     * 它是不依赖混淆名的版本无关方案，与已验证的 [KEY_WECHAT_STATUSBAR_FIX] 二选一。
     * 开启后 `StatusBarDimenFix` 会同时在微信主进程生效，两者是配套关系。
     */
    const val KEY_WECHAT_STATUSBAR_FIX = "wechat_statusbar_fix"
    const val KEY_UNIVERSAL_MULTIWINDOW_FIX = "universal_multiwindow_fix"
    const val KEY_STATUSBAR_DIMEN_FIX = "statusbar_dimen_fix"
    const val KEY_TELEGRAM_PIP_DISABLE = "telegram_pip_disable"
    const val KEY_VERBOSE_LOG = "verbose_log"
    const val KEY_LOG_TO_FRAMEWORK = "log_to_framework"

    private const val DEFAULT_ENABLED = true

    @Volatile
    private var remote: android.content.SharedPreferences? = null

    /**
     * 绑定远程偏好并立即同步一次日志开关。
     *
     * 远程偏好在被 hook 进程里是"框架推送的快照"，但框架在用户改设置后会把新值推给
     * 已加载的目标进程，并触发变更回调。这里注册监听，让"详细日志"开关也能在
     * 不重启目标应用的情况下即时生效。
     */
    fun bind(module: XposedModule) {
        remote = runCatching {
            module.getRemotePreferences(GROUP)
        }.onFailure {
            PatchLog.warn("远程偏好不可用，补丁开关按默认值(true)处理", it)
        }.getOrNull()

        syncLogSwitches()

        remote?.registerOnSharedPreferenceChangeListener { _, key ->
            when (key) {
                null, KEY_VERBOSE_LOG, KEY_LOG_TO_FRAMEWORK -> {
                    // 先同步日志开关，再用（可能刚打开的）详细日志报告变化
                    syncLogSwitches()
                    PatchLog.info("日志设置已更新: verbose=${PatchLog.verbose} logToFramework=${PatchLog.logToFramework}")
                }
                else -> PatchLog.info("设置[$key] 已更新")
            }
        }

        PatchLog.debug(
            "设置已载入: verbose=${PatchLog.verbose} " +
                "logToFramework=${PatchLog.logToFramework} " +
                "wechat=${isEnabled(KEY_WECHAT_STATUSBAR_FIX)} " +
                "universal=${isEnabled(KEY_UNIVERSAL_MULTIWINDOW_FIX, default = false)} " +
                "statusbar=${isEnabled(KEY_STATUSBAR_DIMEN_FIX)} " +
                "telegram=${isEnabled(KEY_TELEGRAM_PIP_DISABLE)}"
        )
    }

    private fun syncLogSwitches() {
        PatchLog.verbose = isEnabled(KEY_VERBOSE_LOG, default = false)
        PatchLog.logToFramework = isEnabled(KEY_LOG_TO_FRAMEWORK, default = true)
    }

    /** 只按 key 读取（多数调用点不需要 Context）。 */
    fun isEnabled(key: String, default: Boolean = DEFAULT_ENABLED): Boolean {
        val prefs = remote
        if (prefs == null) {
            PatchLog.debug("设置[$key] 无远程偏好，返回默认值 $default")
            return default
        }
        return runCatching {
            prefs.getBoolean(key, default)
        }.getOrElse {
            PatchLog.warn("读取设置[$key] 失败，返回默认值 $default", it)
            default
        }
    }

    /**
     * 需要 Context 的场景使用；远程偏好不依赖 Context，这里只是为了调用点方便。
     */
    fun isEnabled(context: Context?, key: String, default: Boolean = DEFAULT_ENABLED): Boolean =
        isEnabled(key, default)
}
