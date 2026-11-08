package io.relimus.zflow.compat.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceManager
import io.relimus.zflow.compat.BuildConfig
import io.relimus.zflow.compat.R
import io.relimus.zflow.compat.service.RemotePrefsBridge

/**
 * 设置页（卡片化 UI）。
 *
 * 写入分两路：
 * - 本地 SharedPreferences：给本模块应用自己看（开关界面状态）。
 * - 框架远程偏好：让被 hook 的目标进程读到开关（这是真正生效的那一路）。
 *
 * 注意：UI 为手写 XML（activity_settings.xml / item_setting_*），
 * 开关的 key、默认值、远程同步逻辑与旧 Preference 版完全一致，
 * Hook 侧（xposed 包）不感知任何 UI 变化。
 */
class SettingsActivity : AppCompatActivity(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences

    /** key -> 行视图（用于刷新开关状态） */
    private val switchRows = mutableMapOf<String, SwitchCompat>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // 补丁开关：微信 / 小窗空白 / Telegram 画中画
        bindSwitch(
            rowId = R.id.item_wechat,
            key = "wechat_statusbar_fix",
            default = true,
            iconRes = R.drawable.ic_patch_wechat,
            titleRes = R.string.pref_wechat_title,
            summaryRes = R.string.pref_wechat_summary,
        )
        bindSwitch(
            rowId = R.id.item_statusbar,
            key = "statusbar_dimen_fix",
            default = true,
            iconRes = R.drawable.ic_patch_statusbar,
            titleRes = R.string.pref_statusbar_title,
            summaryRes = R.string.pref_statusbar_summary,
        )
        bindSwitch(
            rowId = R.id.item_rebuild,
            key = "rebuild_on_expand",
            default = false,
            iconRes = R.drawable.ic_patch_statusbar,
            titleRes = R.string.pref_rebuild_title,
            summaryRes = R.string.pref_rebuild_summary,
        )
        bindSwitch(
            rowId = R.id.item_telegram,
            key = "telegram_pip_disable",
            default = true,
            iconRes = R.drawable.ic_patch_telegram,
            titleRes = R.string.pref_telegram_title,
            summaryRes = R.string.pref_telegram_summary,
        )

        // 调试：详细日志 / 日志输出到框架
        bindSwitch(
            rowId = R.id.item_verbose,
            key = "verbose_log",
            default = false,
            iconRes = R.drawable.ic_patch_debug,
            titleRes = R.string.pref_verbose_title,
            summaryRes = R.string.pref_verbose_summary,
        )
        bindSwitch(
            rowId = R.id.item_log,
            key = "log_to_framework",
            default = true,
            iconRes = R.drawable.ic_patch_debug,
            titleRes = R.string.pref_log_title,
            summaryRes = R.string.pref_log_summary,
        )

        // 关于：点击弹出模块信息
        val aboutRow = findViewById<LinearLayout>(R.id.item_about)
        aboutRow.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.pref_about_title)
                .setMessage(
                    getString(R.string.pref_about_summary) +
                        "\n\n包名: " + BuildConfig.APPLICATION_ID +
                        "\n版本: " + BuildConfig.VERSION_NAME,
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        aboutRow.findViewById<ImageView>(R.id.icon)
            .setImageResource(R.drawable.ic_patch_about)
        aboutRow.findViewById<TextView>(R.id.title)
            .setText(R.string.pref_about_title)
        aboutRow.findViewById<TextView>(R.id.summary)
            .setText(R.string.pref_about_summary)

        // 同步当前值：本地没有时以框架远程偏好为准
        syncFromRemote()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(this)
        // 回来时刷新一次，避免外部改动后显示过期状态
        refreshAllSwitches()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    /**
     * 绑定一个开关行：图标、标题、描述、开关状态与点击行为。
     *
     * 注意递归防护：SwitchCompat 的 checked 状态由用户点击或代码赋值触发
     * OnCheckedChangeListener，因此不另设 row 点击翻转（避免无限回调），
     * 整行点击只通过 switch.performClick() 复用 Switch 自身的点击逻辑。
     */
    private fun bindSwitch(
        rowId: Int,
        key: String,
        default: Boolean,
        iconRes: Int,
        titleRes: Int,
        summaryRes: Int,
    ) {
        val row = findViewById<LinearLayout>(rowId)
        val icon = row.findViewById<ImageView>(R.id.icon)
        val title = row.findViewById<TextView>(R.id.title)
        val summary = row.findViewById<TextView>(R.id.summary)
        val switch = row.findViewById<SwitchCompat>(R.id.switch_widget)

        icon.setImageResource(iconRes)
        title.setText(titleRes)
        summary.setText(summaryRes)
        switch.isChecked = prefs.getBoolean(key, default)
        switchRows[key] = switch

        // 整行可点：把点击转交给 Switch 自己处理（含水波纹与状态切换）
        row.setOnClickListener {
            switch.performClick()
        }
        switch.setOnCheckedChangeListener { _, isChecked ->
            onSwitchToggled(key, isChecked)
        }
    }

    /** 开关变化：写本地 + 写框架远程偏好，失败弹窗提示。 */
    private fun onSwitchToggled(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        val ok = RemotePrefsBridge.putBoolean(this, key, value)
        if (!ok) {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setMessage(getString(R.string.settings_write_failed))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** 启动时从框架远程偏好拉一次，避免重装/清数据后开关显示成默认值。 */
    private fun syncFromRemote() {
        val remote = RemotePrefsBridge.get(this) ?: return
        val editor = prefs.edit()

        for (key in PATCH_KEYS) {
            val value = remote.getBoolean(key, defaultOf(key))
            switchRows[key]?.isChecked = value
            editor.putBoolean(key, value)
        }
        for (key in LOG_KEYS) {
            val value = remote.getBoolean(key, defaultOf(key))
            switchRows[key]?.isChecked = value
            editor.putBoolean(key, value)
        }
        editor.apply()
    }

    /** 刷新所有开关为本地当前值（onResume 时调用）。 */
    private fun refreshAllSwitches() {
        for (key in PATCH_KEYS) {
            switchRows[key]?.isChecked = prefs.getBoolean(key, defaultOf(key))
        }
        for (key in LOG_KEYS) {
            switchRows[key]?.isChecked = prefs.getBoolean(key, defaultOf(key))
        }
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?,
    ) {
        // 本地被外部修改时同步 UI；本 Activity 自己写入的路径已在 onSwitchToggled 处理。
        if (key == null) return
        if (key !in PATCH_KEYS && key !in LOG_KEYS) return
        switchRows[key]?.isChecked = sharedPreferences.getBoolean(key, defaultOf(key))
    }

    private companion object {
        val PATCH_KEYS = listOf(
            "wechat_statusbar_fix",
            "statusbar_dimen_fix",
            "rebuild_on_expand",
            "telegram_pip_disable",
        )
        val LOG_KEYS = listOf(
            "verbose_log",
            "log_to_framework",
        )

        /** 默认值：补丁默认开（展开重建默认关），详细日志默认关，写框架日志默认开。 */
        fun defaultOf(key: String): Boolean = when (key) {
            "verbose_log" -> false
            "rebuild_on_expand" -> false
            "log_to_framework" -> true
            else -> true
        }
    }
}
