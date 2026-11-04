# 版本无关方案：诱导多窗口 + 中和偏好缓存

> 状态：**实验**（设置页开关「通用多窗口修复（实验）」，默认关闭）。
> 目标：微信升级后不再需要逐个版本逆向混淆名。

## 1. 为什么旧方案必然要跟版本

`WeChatStatusBarFix` 的定位链是「候选类名 + 方法名」：

```text
ui.bl / ui.ol          → 命中失败就整体静默跳过
MainUI.u0 / H0         → 签名扫描（相对稳定）
MainUI.H               → 按名字取字段
BaseAddressUIFragment.u0 / I0、FindMoreFriendsUI.L0、NewChattingTabUI.j
```

微信每次重混淆，工具类、方法名、字段名都可能整体洗牌。8.0.76 就是 `ui.ol → ui.bl`、
`u0 → y0`、`H0 → u0` 一起变，旧补丁因此全部落空。这种适配方式下，**补丁永远慢一个版本**。

## 2. 新方案的立足点：只依赖三类不变的东西

| 类型 | 实例 | 为什么稳定 |
| --- | --- | --- |
| 框架方法名 | `Activity.isInMultiWindowMode()`、`SharedPreferences.getInt(String,int)` | 系统 API，改了微信自己跑不起来 |
| 字符串常量 | `Main_top_marign`（原文拼写）、`Main_need_read_top_margin` | 微信自己写死的偏好键，迁移成本高 |
| 大类类名 | `LauncherUI`、`MainUI`、`MMFragmentActivity` | 类数量未过重混淆阈值，跨版本保持 |

注意：这些只用于**定位**，不用于**改写语义**。真正清零仍然由微信自己的代码完成。

## 3. 第一层：把微信骗进它自己的多窗口分支

微信首页布局代码（8.0.76 / 8.0.79 均存在）：

```java
// MainUI.u0(int, int)（8.0.79 为 H0）
if (H) { margin = 0; maskHeight = 0; }    // H == isInMultiWindowMode()
else if (current > 0) return;              // 普通分支：已有值就不再写
else { margin = p1; maskHeight = p2; }
```

虚拟屏里 `H` 恒为 `false`，于是：

1. 全屏阶段写入 `margin/mask = 124`；
2. 缩回小窗后没有任何代码把它清零（`y0` 还因为状态栏高度被归零而提前 return，
   连 `u0` 都不会调用）；
3. 124px 的遮罩永久残留 → 双行标题栏 / 页面被顶部覆盖 / 通讯录变宽。

微信为分屏、自由窗口写好了 `H=true` 的清零分支，**那就让它认为自己在多窗口里**：

```kotlin
// UniversalMultiWindowFix
module.hook(Activity::class.java.getDeclaredMethod("isInMultiWindowMode"))
    .intercept { chain ->
        val act = chain.thisObject as? Activity
        if (act?.display?.displayId != Display.DEFAULT_DISPLAY) true else chain.proceed()
    }
```

配合 `LauncherUI` / `MainUI` / `MMFragmentActivity` 上可能存在的同名重写（重写不能改名，
所以仍不是混淆名）。

预期效果：切屏 → `onConfigurationChanged` → 微信重新求值 `H=true` → `u0(0,0)` →
`margin=0 / mask=0`。通讯录、发现页也会走多窗口分支（`topReference.getTop()`、不读缓存）。

## 4. 第二层：中和 `Main_top_marign` 缓存

「展开后缩回变宽」的另一半原因是全屏阶段写下的偏好缓存。8.0.76 起读取者从一个变成三个，
逐个 hook 读取方法会越追越多，因此改在**读取入口**拦截：

```text
getInt("Main_top_marign", -1)        → 虚拟屏返回 -1（语义：没有可用缓存）
getBoolean("Main_need_read_top_margin") → 虚拟屏返回 false（别去读缓存）
```

hook 点是 `SharedPreferences.getInt/getBoolean(String, …)`，候选实现类：

- `com.tencent.mm.sdk.platformtools.MultiProcessSharedPreferences`（微信自己的多进程封装，包名不混淆）
- `android.app.SharedPreferencesImpl`（系统实现兜底）

因为 hook 点在读取入口，**新版本即使增加第四个读取者也不用改代码**。

判定"是否在虚拟屏"用的是进程级追踪器（`VirtualDisplayTracker`）：同时挂
`Activity.onResume` 与 `Activity.onMovedToDisplay(int, Configuration)`，覆盖
「桌面长按开小窗」和「多任务上滑搬运任务」两条路径。

## 5. 怎么验证

1. 设置页只打开「通用多窗口修复（实验）」，**关闭**上面的「微信小窗标题栏重复修复」；
2. 详细日志打开；
3. 完整结束微信所有进程，冷启动小窗。

日志应出现：

```text
UniversalMultiWindowFix: 已挂钩 android.app.Activity#isInMultiWindowMode → 虚拟屏返回 true
UniversalMultiWindowFix: 已挂钩 …MultiProcessSharedPreferences#getInt(String, int) → …
UniversalMultiWindowFix: displayId=N …isInMultiWindowMode → true（诱导多窗口清零分支）
UniversalMultiWindowFix: … 拦截 Main_top_marign (displayId=N) → -1
```

功能验证仍按四条老用例：冷启动小窗、展开、缩回、主屏恢复，另外加通讯录/发现/聊天页
各来回切 5 次。

## 6. 已知风险

| 风险 | 说明 | 处理 |
| --- | --- | --- |
| `H` 不是由 `isInMultiWindowMode()` 赋值 | 若微信改成读 `Configuration`，诱导无效 | 看日志无命中即判定；退回旧方案 |
| 多窗口分支影响别的 UI | 例如底栏、拖拽、动画按分屏逻辑处理 | 独立开关，随时关闭 |
| `isInMultiWindowMode` 调用很热 | 每次调用一次拦截 | 开关判断带 2s 缓存 |
| 微信改了偏好实现类 | 两个候选都未命中 | 日志会打印"未找到偏好实现类"，补一条候选即可 |

## 7. 与旧方案的关系

- 两者**不要同时开**：旧方案会把首页状态栏高度归零，新方案让微信走多窗口分支，
  可能互相覆盖，且日志会混在一起。
- 旧方案仍然是默认值，新方案验证通过后可以把旧方案的候选表逐步删除，届时
  `WeChatStatusBarFix` 只剩 Telegram 之外的通用逻辑。

## 8. 实测记录：微信 8.0.76（3141），2026-11-04

一次「小窗 → 展开 → 缩回」两轮的日志（LSPosed modules 导出，约 39 秒）：

| 时间 | 事件 |
| --- | --- |
| 21:19:34.326 | `wechat=false universal=true`，旧补丁关闭，新补丁开始安装 |
| 21:19:34.333 | 三个追踪点全部装上（onCreate / onResume / onMovedToDisplay） |
| 21:19:34.334 | 已挂钩 `Activity.isInMultiWindowMode`；LauncherUI / MainUI / MMFragmentActivity 均未重写 |
| 21:19:34.338 | 偏好层四个 hook 全部装上（`MultiProcessSharedPreferences` + `SharedPreferencesImpl`） |
| 21:19:37.932 | 显示设备 0 → 6（onCreate） |
| 21:19:38.090 ~ 47.493 | displayId=6 命中 26 次 |
| 21:19:49.391 | 显示设备 0 → 7 |
| 21:19:49.464 ~ 21:20:00.671 | displayId=7 命中 205 次 |
| 21:20:02.577 | 显示设备 0 → 8 |
| 21:20:02.651 ~ .957 | displayId=8 命中 15 次 |

结论：

1. **L1 完全生效**：246 次 `isInMultiWindowMode → true`，全部落在虚拟屏（6/7/8），
   没有一次在主屏误改；全过程无异常、无崩溃。
2. **L2 一次都没触发**，而且这是预期结果：微信的缓存读取分支本身就在
   `if (!inMultiWindow)` 里面 —— L1 让它认定自己在多窗口后，`Main_top_marign` 这条
   路径**根本不会被走到**。L2 因此只是"L1 失效时的兜底"，不是必现路径。
3. Z-Flow 每次进小窗都会新建一个虚拟显示屏（6 → 7 → 8），Activity 会重建；跟踪器
   期间出现 `6→0`、`7→0`，说明微信同一时刻在两种显示设备上都有 Activity，
   **进程级追踪天然不可靠**，能拿到 Activity/Context 的地方仍应直接读它自己的 display
   （L1 就是这么做的，所以不受影响）。
4. 打开详细日志后 246 行会刷屏，已改为：每次 (类, displayId) 只打一次 info +
   其余 debug；同时只装微信主进程，子进程（:push / :appbrand / :xweb）不再安装。

