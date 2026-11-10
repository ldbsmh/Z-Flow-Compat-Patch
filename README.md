# Z-Flow 兼容性补丁（独立版）

从 [Z-Flow](https://github.com/ldbsmh/Z-Flow) 中把兼容性修复补丁单独拆出来，做成轻量 Xposed 模块。
本项目不实现小窗本体，只处理应用进入 Z-Flow 虚拟屏后出现的兼容性问题。

> 当前微信方案已在 **微信 8.0.79（versionCode 3200，Tinker patch.client.ver=0x28004f34）** 上验证：
> 冷启动小窗、展开、再次缩回小窗后，微信首页、通讯录、发现页顶部布局均正常。

## 包含的三个补丁

| 补丁 | 修复内容 | 生效方式 |
| --- | --- | --- |
| **微信小窗顶部布局修复** | 修正首页透明重复标题行、通讯录/发现冷启动被顶部覆盖、通讯录跨窗口后顶部变宽 | 将微信加入 LSPosed 作用域；更新模块后完整结束微信进程再测试 |
| **小窗顶部空白修复** | 修正部分应用直接读取系统 `status_bar_height` 而产生顶部空白（如起点读书）；并保证「展开」回主屏后状态栏不会压住内容 | 将目标应用加入作用域；**微信进程会被自动排除**；个别应用需再打开「展开后重建界面」（见第 30 节） |
| **关闭 Telegram 画中画** | 修正 Telegram 系应用在小窗中只剩角落画面 | 将目标应用加入作用域；主屏画中画也会被关闭 |

---

# 微信 8.0.79 小窗修复：完整分析与迁移文档

本节记录最终修复方案、分析过程、踩过的坑和微信升级后的适配步骤。不要只照抄混淆类名；应先用日志和反编译确认新版本的实际调用链。

## 1. 最初症状

微信运行在 Z-Flow 创建的虚拟显示屏（`displayId != 0`）时，曾出现三组相互切换的状态：

1. **微信首页出现两行标题栏**
   - 第一行可以点击；
   - 第二行内容与第一行相同，处于透明轮廓下方，不能点击；
   - 本质不是 Android 系统状态栏真的绘制了两遍，而是微信会话列表的顶部容器/遮罩/偏移露出了一份重复内容。

2. **通讯录和发现顶部变宽**
   - 标题文字本身位置或样式变化不明显；
   - 标题栏底部多出约一个状态栏高度的同色空白；
   - 功能项被向下移动，但通常仍能点击。

3. **强行停止微信后直接冷启动小窗，通讯录/发现首项被覆盖**
   - 顶部看起来不再很宽；
   - 但“新的朋友”等第一项被标题栏盖住，无法点击；
   - 展开窗口再缩回小窗后，又变成顶部较宽但可以点击。

这些现象看起来相近，却不是同一个布局值造成的。最终确认至少涉及：

- 微信全局状态栏高度入口；
- 首页会话列表自己的顶部 margin / status-bar mask；
- 通讯录自己的顶部高度计算；
- 通讯录跨窗口缓存 `Main_top_marign`；
- 微信 Tinker 热更新后的实际 ClassLoader；
- 通用 `StatusBarDimenFix` 在微信进程中的误安装。

## 2. 关键原则：相同现象可能有不同原因

“顶部多一截”可能来自：

- `status_bar_height` 返回值；
- Fragment 根 View 的 `paddingTop`；
- 列表 `topMargin`；
- 状态栏遮罩 View 的 `height`；
- `rootView.findViewById(...).getTop()`；
- SharedPreferences 中缓存的主屏 margin；
- 多窗口状态未及时复位；
- base.apk 与 Tinker patch 中同名类不是同一个 `Class`。

因此不能看到空白就统一返回 `0`。必须确认具体页面在哪里消费这个值，以及冷启动、展开、缩回分别走哪条分支。

---

## 3. 日志给出的第一批证据

旧实现安装日志为：

```text
WeChatStatusBarFix: 命中候选类 com.tencent.mm.ui.ol
WeChatStatusBarFix: 精确命中 com.tencent.mm.ui.ol#h(Context)
WeChatStatusBarFix: 已挂钩 com.tencent.mm.ui.ol#h
```

但实际使用微信时，日志里完全没有：

```text
WeChatStatusBarFix: displayId=N 非主屏 → 状态栏高度置 0
```

与此同时，通用补丁不断输出：

```text
StatusBarDimenFix: displayId=N 非主屏，status_bar_height 124 → 0
```

这说明：

- “成功找到并挂钩方法”不等于“目标页面真的调用了该方法”；
- 旧微信专用 hook 没覆盖主界面；
- 真正持续改写微信布局的是通用资源 hook；
- 必须用 xref 查主界面的真实调用者，不能仅按方法名和返回类型猜测。

---

## 4. 反编译定位：`ol.h` 不是主界面入口

使用 MT MCP 打开微信 8.0.79 APK，对以下方法做 xref：

```text
com.tencent.mm.ui.ol#h(Context): int
com.tencent.mm.ui.ol#i(Context, int): int
```

### `ol.h(Context)` 的实际调用者

主要是：

- Finder / 视频号；
- 设置页；
- WebView；
- Live 等次级页面。

主界面三个 tab 不依赖它。因此旧方案“挂钩成功但不触发”完全符合运行日志。

### `ol.i(Context, int)` 的实际调用者

xref 明确包含：

```text
com.tencent.mm.ui.conversation.MainUI#C0 / K0
com.tencent.mm.ui.conversation.w7#run
com.tencent.mm.ui.contact.address.BaseAddressUIFragment#I0 / M0
com.tencent.mm.ui.FindMoreFriendsUI#a1 / N1
com.tencent.mm.ui.chatting.component.y2#w0
```

对应关系：

| 页面 | 关键调用类/方法 |
| --- | --- |
| 微信首页（会话列表） | `MainUI.C0/K0`、`conversation.w7.run` |
| 通讯录 | `BaseAddressUIFragment.I0/M0`、`dp5.h.run` |
| 发现 | `FindMoreFriendsUI.a1/N1` |
| 聊天页 | `chatting.component.y2.w0` |

因此微信 8.0.79 的中央状态栏高度入口是：

```java
com.tencent.mm.ui.ol.i(Context context, int fallback): int
```

`h(Context)` 只是部分页面使用的薄包装，并且内部仍会调用 `i(Context,int)`。

---

## 5. `ol.i` 的内部逻辑与“幽灵状态栏”

反编译得到的逻辑可简化为：

```java
int getStatusBarHeight(Context ctx, int fallback) {
    int insetTop = readWechatWindowInsets(ctx); // n3.g3.a(1).b
    if (insetTop >= dpToPx(ctx, 25)) {
        return insetTop;
    }

    int systemHeight = readInternalStatusBarHeight(ctx); // ol.k(...)

    if (systemHeight > 0 && ctx instanceof Activity) {
        int visibleBased = displayHeight - visibleDisplayFrame.height();
        if (visibleBased == 0) {
            visibleBased = visibleDisplayFrame.top;
        }

        // 微信只接受“比系统值更大、且差距小于 100px”的修正值
        if (visibleBased > systemHeight && visibleBased - systemHeight < 100) {
            systemHeight = visibleBased;
        }
    }

    return systemHeight > 0 ? systemHeight : fallback;
}
```

`ol.k(Context,int)` 会反射：

```text
com.android.internal.R$dimen.status_bar_height
```

然后通过 `Resources.getDimensionPixelSize()` 得到该设备的系统状态栏高度（实测 124px）。

在 Z-Flow 虚拟屏中，实际没有对应的系统状态栏：

```text
visibleBased ≈ 0
systemHeight = 124
```

但微信的修正条件只接受 `visibleBased > systemHeight`，因此不会采用 0，最终仍返回 124px。这就是最初的“幽灵状态栏高度”。

---

## 6. 为什么不能对所有 `ol.i` 调用统一返回 0

第一版中央入口修复曾在所有非主屏调用中直接返回 0。首页立即恢复正常，但产生新问题：

- 冷启动小窗时，通讯录和发现没有保留页面需要的顶部安全空间；
- “新的朋友”等第一项被标题栏覆盖；
- 展开后再缩回，微信重新计算/缓存了主屏高度，页面又变成较宽但可点击。

这证明三个页面虽然共享 `ol.i()`，但消费方式不同：

- 首页需要 `0` 来消除透明的重复行；
- 通讯录/发现不能简单统一归零；
- 页面级行为必须在全局高度入口之后继续分析。

最终策略是：

```text
非主屏 + 调用栈来自 com.tencent.mm.ui.conversation.* → 返回 0
其他页面调用 ol.i → 放行微信原逻辑
主屏 → 全部放行
```

当前实现通过调用栈识别会话列表：

```kotlin
val caller = Throwable().stackTrace.firstOrNull { frame ->
    val name = frame.className
    name.startsWith("com.tencent.mm.ui.conversation.") ||
        name == "com.tencent.mm.ui.conversation.MainUI"
}
```

这一步解决首页重复标题行，同时避免再次破坏通讯录和发现的点击区域。

---

## 7. 首页的真实布局链路

`MainUI.K0()` 会取：

```text
statusBarHeight = ol.i(context, -1)
actionBarHeight = kb5.o1.a(context)
showHeaderContainer = conversation.a6.B()
```

随后调用：

```text
MainUI.H0(actionBarMargin, statusBarHeight)
```

`H0()` 会同时修改：

- 会话列表 `RelativeLayout.LayoutParams.topMargin`；
- `MainUI.q` 对应的 status-bar mask View 高度。

其日志字符串包括：

```text
setListMargin origin: %d, topMargin: %d
setStatusBarMaskView height origin: %d, new: %d isInMultiWindowMode:%s
```

`conversation.w7.run()` 还会异步重试读取状态栏高度并再次调用 `H0()`：

```text
[trySetListViewMargin] try getStatusHeight again!
[trySetListViewMargin] now:%s old:%s
```

因此首页看到的“第二行”不是一个真正可交互的第二标题栏，而是列表 margin / mask View / header container 共同造成的透明轮廓，下面露出了重复内容。只对会话列表调用的 `ol.i()` 返回 0，可以让 `H0()` 使用正确的小窗偏移，同时不影响其他页面。

---

## 8. 通讯录与发现为什么不是同一套代码

通讯录与发现共享 `ol.i()` 作为一个输入，但之后的布局代码不同。

### 8.1 发现页

发现页主要通过：

```text
com.tencent.mm.ui.FindMoreFriendsUI#a1()
com.tencent.mm.ui.FindMoreFriendsUI#N1()
```

读取并应用状态栏高度。它没有通讯录那条 `Main_top_marign` 缓存分支，因此在“展开 → 缩回小窗”后可以自行恢复正常。

### 8.2 通讯录页

通讯录使用：

```text
com.tencent.mm.ui.contact.address.BaseAddressUIFragment#I0(): int
com.tencent.mm.ui.contact.address.BaseAddressUIFragment#M0(): void
Ldp5/h;#run(): void
```

`I0()` 不是简单返回 `ol.i()`，而是计算顶部总高度。简化逻辑如下：

```java
int actionBarHeight = ol.a(context);
int statusHeight = ol.i(context, -1);
View topReference = root.findViewById(0x7f0a00d3);
boolean inMultiWindow = LauncherUI.getInstance().isInMultiWindowMode();

if (!inMultiWindow) {
    boolean needRead = prefs.getBoolean("Main_need_read_top_margin", false);
    if (needRead || SDK_INT >= 31) {
        int cached = prefs.getInt("Main_top_marign", -1);
        if (cached >= 0) {
            return actionBarHeight + cached;
        }
    }
}

if (inMultiWindow && topReference != null) {
    statusHeight = topReference.getTop();
}

return actionBarHeight + statusHeight;
```

关键缓存键（注意微信原始拼写就是 `marign`）：

```text
Main_need_read_top_margin
Main_top_marign
```

`M0()` 与 `dp5.h.run()` 会把 `I0()` 结果写入 Fragment 根 View 的 `paddingTop`，并通过异步 Runnable 反复校正：

```text
[trySetParentViewPadding] try getStatusHeight again!
[trySetParentViewPadding] now:%s old:%s
```

这解释了最终残留问题：

1. 冷启动小窗时缓存尚未污染，通讯录正常；
2. 展开窗口后微信更新主屏 `Main_top_marign`；
3. 缩回虚拟屏时通讯录仍读取该缓存；
4. `I0()` 返回 `ActionBar + 主屏缓存高度`；
5. `M0()` / `dp5.h` 把它写入 `paddingTop`，所以只有通讯录重新变宽；
6. 发现没有这条缓存分支，因此表现不同。

### 最终通讯录修复

虚拟屏上 hook：

```text
BaseAddressUIFragment.I0()
```

只返回：

```text
ol.a(Context)  // 微信自己的 ActionBar 高度
```

即在小窗中忽略：

- `ol.i()` 的 status height；
- `topReference.getTop()`；
- `Main_top_marign`；
- 主屏多窗口阶段遗留的顶部偏移。

主屏 `displayId == 0` 时完全放行原逻辑。这样 `M0()` 和 `dp5.h.run()` 仍可正常执行，只是拿到正确的小窗顶部总高度，不需要继续 hook `setPadding()`。

---

## 9. Tinker 热更新：为什么“挂钩成功”仍可能无效

微信 8.0.79 的日志显示：

```text
TinkerLoader.tryLoad
classloader = dalvik.system.DelegateLastClassLoader
patch.client.ver=0x28004f34
patch.basepack.client.ver=0x28004f30
tinker_classN.apk
```

第一次 `onPackageReady` 提供的是 base.apk 的：

```text
dalvik.system.PathClassLoader
```

可以在其中找到并挂钩 `com.tencent.mm.ui.ol`，但 Tinker 随后加载补丁 dex。由于 `DelegateLastClassLoader` 优先使用补丁中的类，实际 UI 调用的 `ol` 与 base.apk 的 `ol` 可能是两个不同的 `Class` 对象。

典型症状：

```text
已挂钩 com.tencent.mm.ui.ol#i
```

但运行时一条 interceptor 日志都没有。这不一定是方法不再调用，也可能是挂错了 ClassLoader 中的同名类。

### 当前处理方式

1. 先在 base `PathClassLoader` 安装，兼容未加载 Tinker 或未覆盖目标类的情况；
2. 挂钩：

```text
com.tencent.tinker.loader.TinkerLoader#tryLoad(TinkerApplication)
```

3. `chain.proceed()` 完成后，从 Application context 优先取得当前 ClassLoader：

```kotlin
appContext.classLoader ?: Thread.currentThread().contextClassLoader
```

4. 若得到 `DelegateLastClassLoader`，在该 ClassLoader 中重新通过 `Class.forName(..., false, loader)` 查找并挂钩：

```text
com.tencent.mm.ui.ol#i(Context,int)
BaseAddressUIFragment#I0()
```

5. 使用：

```kotlin
ConcurrentHashMap.newKeySet<ClassLoader>()
```

按 ClassLoader 去重；使用 `AtomicBoolean` 确保 `TinkerLoader.tryLoad` 每进程只挂一次。

### 正常日志

```text
WeChatStatusBarFix: 开始探测目标 source=base loader=dalvik.system.PathClassLoader
WeChatStatusBarFix: 已挂钩 TinkerLoader.tryLoad，等待热更新类加载完成
WeChatStatusBarFix: Tinker 加载完成，重新探测 loader=dalvik.system.DelegateLastClassLoader
WeChatStatusBarFix: source=tinker 命中候选类 com.tencent.mm.ui.ol
WeChatStatusBarFix: source=tinker 已挂钩通讯录 I0()
```

---

## 10. 为什么微信必须按进程排除 `StatusBarDimenFix`

通用补丁会 hook：

```text
Resources.getDimensionPixelSize(int)
```

在虚拟屏上遇到：

```text
android:dimen/status_bar_height
```

就把返回值从 124 改为 0。这适合起点读书一类直接读取系统 dimen 的应用，但不适合微信，因为微信还有自己的 WindowInsets、页面 margin、遮罩和缓存逻辑。

最初仅按 `PackageReadyParam.packageName` 排除：

```kotlin
if (pkg != "com.tencent.mm") install StatusBarDimenFix
```

仍然失败。原因是同一个微信主进程稍后加载 WebView 时，会再次收到：

```text
onPackageReady: pkg=com.google.android.webview
```

于是通用补丁又在微信进程被安装，日志继续出现：

```text
StatusBarDimenFix: displayId=N 非主屏，status_bar_height 124 → 0
```

### 当前正确做法

在 `onModuleLoaded()` 保存真实宿主进程名：

```kotlin
hostProcessName = param.processName
```

按进程排除：

```kotlin
val isWeChatProcess = hostProcessName == "com.tencent.mm" ||
    hostProcessName.startsWith("com.tencent.mm:")
```

只要宿主是微信主进程或任意微信子进程，无论后续 `PackageReady` 报告微信、WebView 还是其他包，都不得安装 `StatusBarDimenFix`。

正常日志应为：

```text
StatusBarDimenFix: process=com.tencent.mm pkg=com.tencent.mm 位于微信进程，跳过
StatusBarDimenFix: process=com.tencent.mm pkg=com.google.android.webview 位于微信进程，跳过
```

并且不应再出现该微信进程的：

```text
StatusBarDimenFix: status_bar_height 124 → 0
```

---

## 11. 最终稳定方案总览

最终不是一个 hook，而是四层防线：

### A. 按真实微信进程排除通用资源补丁

目的：消除 `StatusBarDimenFix` 与微信页面布局的交叉改写。

```text
CompatModule.hostProcessName
→ com.tencent.mm / com.tencent.mm:* 全部跳过 StatusBarDimenFix
```

### B. 同时处理 base 与 Tinker ClassLoader

目的：确保 hook 作用于真正执行的类，而不仅是 base.apk 中的同名类。

```text
PathClassLoader → 安装一次
TinkerLoader.tryLoad 完成
DelegateLastClassLoader → 再安装一次
```

### C. 首页会话列表按调用栈归零

目的：只移除首页透明的重复标题行，不破坏其他页面安全空间。

```text
ol.i(Context,int)
+ displayId != 0
+ caller 属于 com.tencent.mm.ui.conversation.*
→ return 0
```

其他页面调用 `ol.i()`：

```text
chain.proceed()
```

### D. 通讯录单独修正顶部总高度

目的：去掉展开窗口后遗留的 `Main_top_marign`。

```text
BaseAddressUIFragment.I0()
+ displayId != 0
→ return ol.a(Context) // 只保留 ActionBar 高度
```

主屏：

```text
chain.proceed()
```

最终效果：

| 场景 | 首页 | 通讯录 | 发现 |
| --- | --- | --- | --- |
| 冷启动小窗 | 正常单行 | 正常、可点击 | 正常、可点击 |
| 小窗展开 | 原逻辑 | 原逻辑 | 原逻辑 |
| 展开后缩回小窗 | 正常单行 | 不读取主屏 margin，保持正常 | 正常 |
| 主屏 | 完全放行 | 完全放行 | 完全放行 |

---

## 12. 微信新版本适配流程

> 完整的定位方法论（锚点不变量表、版本差异速查表、五步定位法、排障速查）已独立成
> [docs/wechat-adaptation-guide.md](docs/wechat-adaptation-guide.md)；本节保留基于
> 日志的快速判读流程。8.0.76 的适配实例见第 25-29 节。

微信升级后不要立即替换候选名。按以下顺序确认。

### 第一步：保留旧版并采集详细日志

打开模块中的：

- 详细日志；
- 日志输出到框架。

完整结束微信所有进程，然后测试：

1. 主屏启动；
2. 冷启动小窗；
3. 切换首页、通讯录、发现；
4. 展开窗口；
5. 缩回小窗；
6. 再切换三个 tab。

检查是否出现：

```text
source=base 命中候选类
Tinker 加载完成
source=tinker 命中候选类
已挂钩 ...#i
已挂钩通讯录 I0()
首页调用 ... → 状态栏高度置 0
通讯录 I0() → 仅保留 ActionBar 高度
```

### 第二步：区分“类没找到”和“hook 没触发”

#### 类没找到

日志类似：

```text
候选类 com.tencent.mm.ui.ol 未在 source=tinker 找到
```

说明混淆类名变化，需要重新找 WeUIToolHelper 类。

#### 方法没找到

日志类似：

```text
无精确方法 i(Context, int)
```

说明方法名或签名变化。

#### 显示挂钩成功但没有运行日志

优先怀疑：

1. Tinker / 插件使用了另一个 ClassLoader；
2. 页面不再调用该方法；
3. 微信更新后页面换了新实现；
4. context 无法取得 displayId，走了保守放行。

不要只继续增加候选类名，应先做 xref 和 ClassLoader 检查。

### 第三步：寻找新的状态栏工具类

在 MT/JADX 中搜索以下稳定字符串：

```text
WeUIToolHelper
[fixStatusBarHeight]
[getStatusBarHeight]
[getStatusBarHeightFromSysR]
status_bar_height
com.android.internal.R$dimen
```

目标方法通常具备这些特征：

- `public static`；
- 返回 `int`；
- 参数包含 `Context` 与 fallback `int`；
- 内部引用 `WindowInsets`、`getWindowVisibleDisplayFrame` 或系统 dimen；
- 调用者包含微信首页、通讯录和发现页面。

确认后更新：

```kotlin
CANDIDATE_CLASSES
TARGET_METHOD
resolveTarget()
```

不要仅按“名称像以前”判断，必须检查 xref。

### 第四步：重新定位首页

对新状态栏入口做 xref，确认首页调用类。当前版本是：

```text
com.tencent.mm.ui.conversation.MainUI
com.tencent.mm.ui.conversation.w7
```

若包名变化，更新调用栈判断：

```kotlin
name.startsWith("新的会话列表包名")
```

判断标准：该调用路径最终负责会话列表 topMargin/status mask，而非 Finder、WebView 或聊天页。

### 第五步：重新定位通讯录顶部高度方法

当前类和方法：

```text
com.tencent.mm.ui.contact.address.BaseAddressUIFragment#I0(): int
```

可通过以下稳定特征搜索：

```text
Main_need_read_top_margin
Main_top_marign
getTopHeight statusHeight:%s, isInMultiWindowMode:%s
[trySetParentViewPadding]
```

目标方法通常：

- 无参数；
- 返回 `int`；
- 调用 ActionBar 高度方法；
- 调用状态栏高度方法；
- 判断 `LauncherUI.isInMultiWindowMode()`；
- 读取 `Main_top_marign`；
- 返回两者之和。

找到后更新：

```text
BaseAddressUIFragment 类名
I0 方法名
ol.a(Context) 或新版本 ActionBar 高度方法
```

### 第六步：确认 Tinker 是否仍存在

搜索：

```text
com.tencent.tinker.loader.TinkerLoader
tryLoad(TinkerApplication)
DelegateLastClassLoader
```

如果微信改用其他热更新框架：

- 找到“补丁 dex 完成加载”的回调；
- 在回调返回后读取真正的 ClassLoader；
- 再执行 `installForLoader()`；
- 继续按 ClassLoader 去重。

如果目标类只存在于 Tinker patch，而 base 中不存在，base 探测失败是可以接受的，但 Tinker 阶段必须成功。

### 第七步：逐场景回归

至少测试：

- 冷启动直接进入小窗；
- 主屏启动后移动到小窗；
- 小窗展开再缩回；
- 首页/通讯录/发现来回切换；
- 打开聊天页再返回；
- 微信进程重启；
- Tinker patch 更新后重启；
- 与 `WeChatTablet` 同时启用；
- 主屏布局无回归；
- 小程序/WebView 不受影响。

---

## 13. 推荐的日志判读表

| 日志 | 含义 | 行动 |
| --- | --- | --- |
| `source=base 命中候选类` | base 类找到 | 继续检查 Tinker |
| `Tinker 加载完成` | 热更新已生效 | 必须随后看到 source=tinker 探测 |
| `source=tinker 命中候选类` | 找到真正运行类 | 检查 interceptor 是否触发 |
| `首页调用 ... → 状态栏高度置 0` | 首页修复正在运行 | 正常 |
| `非主屏但非首页调用 → 放行` | 通讯录/发现等未被全局归零 | 正常 |
| `通讯录 I0() → 仅保留 ActionBar 高度` | 通讯录缓存修复正在运行 | 正常 |
| `无法取得 displayId，放行` | Context 不带显示屏 | 若对应页面仍异常，再增加 Activity/生命周期追踪兜底 |
| 微信进程出现 `StatusBarDimenFix ... 124 → 0` | 通用补丁被误装回微信 | 检查进程排除与 WebView PackageReady |
| 挂钩成功但无任何调用日志 | 可能挂错 ClassLoader/死方法 | 查 Tinker 和 xref |

---

## 14. 已验证过但不应重复采用的方案

### 仅 hook `ol.h(Context)`

失败原因：主界面不调用它。安装成功不代表有效。

### 微信与通用 `StatusBarDimenFix` 同时启用

失败原因：不同页面/时刻分别拿到 124 或 0，出现随机空白、覆盖和跳变。

### 只按 `packageName == com.tencent.mm` 排除通用补丁

失败原因：同一微信进程加载 WebView 时 `pkg=com.google.android.webview`，会重新安装通用 hook。

### 对所有非主屏 `ol.i()` 统一返回 0

失败原因：首页正常，但通讯录/发现冷启动首项被标题栏覆盖。

### 看到通讯录和发现都“顶部变宽”就认为同一原因

失败原因：两页只共享高度来源；通讯录还存在 `Main_top_marign` 和多窗口分支，发现没有。

### 直接清空通讯录 SharedPreferences 缓存

不推荐。缓存是微信全局状态的一部分，主动删除可能影响主屏和其他页面。当前方案只在虚拟屏拦截 `I0()` 返回值，范围更小且可逆。

---

## 15. 实现安全边界

当前补丁遵循：

1. **主屏永远放行**：`displayId == Display.DEFAULT_DISPLAY` 不改返回值；
2. **无法判断时放行**：`displayId == null` 不冒险；
3. **页面级修改**：首页和通讯录分别处理，不全局压平；
4. **不写微信数据**：不清除 `Main_top_marign`，只忽略其虚拟屏结果；
5. **失败静默降级**：混淆名变化时不崩溃微信；
6. **ClassLoader 去重**：避免重复安装同一目标；
7. **补丁相互隔离**：微信进程不安装通用状态栏资源 hook。

---

## 16. 工程与构建

- 框架 API：libxposed API 102；
- 工具链：AGP 9.2.1、Gradle 9.4.1、compileSdk 37；
- 设置同步：libxposed remote preferences；
- 工作流：推送到 `main` / `master` 自动构建；
- 产物：GitHub Actions → Artifacts；
- 工作流使用 debug key 签名，升级安装需保持同一构建签名来源。

本地构建：

```bash
./gradlew :app:assembleRelease
```

Windows 中文路径工作区需在 `gradle.properties` 保留 `android.overridePathCheck=true`
（AGP 9 起非 ASCII 项目路径默认硬性报错）。注意 `services.gradle.org` 的发行包下载
会 307 重定向到 GitHub，GitHub 不可达的网络下需改用镜像（如
`https://mirrors.cloud.tencent.com/gradle/gradle-9.4.1-bin.zip`）手动安装 Gradle。

## 安装

1. 安装 APK；
2. 在 LSPosed 启用模块；
3. 作用域勾选微信及需要修复的其他应用；
4. 完整结束目标应用进程后重新启动；
5. 微信测试必须覆盖冷启动小窗和展开后缩回。

## 与 Z-Flow 的关系

本仓库是从 Z-Flow 中提取的补丁集合，不替代 Z-Flow。两者可以共存，但兼容性补丁应只由本模块管理，避免重复 hook。

Z-Flow 上游已通过 [PR #1](https://github.com/ldbsmh/Z-Flow/pull/1) 移除这三项修复，把功能移交给本模块。

## 许可

本项目是 Z-Flow 的衍生作品，沿用 **GNU General Public License v3.0 or later**（见 [LICENSE](LICENSE)）。

- Copyright (C) 2021-2022 sunshine0523
- Copyright (C) 2023 DtHnAme
- Copyright (C) 2024-2026 relimus


---

# 微信 8.0.76 适配：混淆名映射与双行状态栏根治

以下内容基于对设备上 `微信_8.0.76.apk`（versionCode 3141，17 个 dex、236037 个类）
的反编译分析，分析方法与第 12 节一致：以不随混淆变化的字符串（日志、偏好键）与
框架方法名为锚点定位，再读 smali 确认语义。

## 25. 8.0.76 混淆名映射

| 用途 | 8.0.79 | 8.0.76 |
| --- | --- | --- |
| 状态栏高度工具类（WeUIToolHelper） | `com.tencent.mm.ui.ol` | `com.tencent.mm.ui.bl` |
| 状态栏高度主入口 | `ol.i(Context, int)` | `bl.i(Context, int)`（签名不变） |
| 系统状态栏高度反射读取 | `ol.k(Context, int)` | `bl.k(Context, int)` |
| ActionBar 高度 | `ol.a(Context)` | `bl.a(Context)` |
| 首页读取状态栏高度 | `MainUI.K0()` | `MainUI.y0()` |
| 首页写 margin/mask | `MainUI.H0(int, int)` | `MainUI.u0(int, int)` |
| 多窗口标志字段 | `MainUI.H` | `MainUI.H`（未变） |
| 首页异步重试 | `conversation.w7.run()` | `conversation.l7.run()` |
| 通讯录顶部总高度 | `BaseAddressUIFragment.I0()` | `BaseAddressUIFragment.u0()` |
| 通讯录写 paddingTop | `BaseAddressUIFragment.M0()` | `BaseAddressUIFragment.y0()` |

`MainUI`、`BaseAddressUIFragment`、`ConversationListView`、`LauncherUI` 等大类类名未混淆。
工具类 `ui.ol → ui.bl` 改名后旧候选列表全部落空，因此 8.0.76 上旧补丁会整体静默跳过。

## 26. 双行状态栏的真正根因：u0 在小窗里永远不会被调用

8.0.76 的首页布局链路（反编译还原）：

```java
// MainUI.y0()，由 m0(onTabCreate) / onConfigurationChanged 触发
int status = bl.i(context, -1);
if (status <= 0) {           // 归零补丁生效后，小窗里恒走此分支
    listView.post(new l7(this, showHeader));   // 异步重试
    return;                   // ← 不同步调用 u0！
}
u0(showHeader ? 0 : actionBarHeight, status);

// conversation.l7.run()，重试最多 3 次
int status = bl.i(context, -1);
if (status <= 0) { retry / give up; return; }  // ← 同样永远不调 u0
u0(showHeader ? 0 : actionBarHeight, status);
```

`u0(int, int)` 是会话列表 `topMargin` 与状态栏 mask 高度的**唯一写入者**，且带守卫：

```java
// u0 对 topMargin 与 mask 各执行一遍同样的守卫
if (currentValue > 0 && !H) return;   // H = isInMultiWindowMode，虚拟屏上恒为 false
if (H) { margin = 0; maskHeight = 0; }         // 多窗口：强制清零
else   { margin = p1; maskHeight = p2; }       // 普通：只有当前值 ≤ 0 才允许写入
```

于是死锁形成：

1. 全屏阶段 `y0/l7 → u0(actionBar, 124)` 写入 margin 与 mask=124；
2. 切回小窗后 `bl.i` 被补丁归零 → `y0` 与 `l7` 全部提前 return；
3. `u0` 一次都不会被调用 → 全屏写入的 124px mask 永久残留 → 双行状态栏；
4. 即便 `u0` 被调用，守卫（当前值 > 0 且 `H=false`）也会拒绝覆盖。

旧补丁挂在 `u0`（`H0`）上的“临时置 `H=true`”钩子因此是**死代码**——它等待的调用
永远不会发生。这就是“多次小窗-全屏-小窗切换后偶尔双行”的根源：只有切换过程伴随
Fragment 重建（视图回到 XML 默认的 0/0）时才碰巧恢复正常。

## 27. 根治方案

小窗中首页的正确布局（margin=0 / mask=0）与两个事实一致：冷启动小窗时视图保持 XML
默认值即为正确显示；微信自己在 `H=true`（平台多窗口）时的强制清零分支。因此：

1. **触发器**：hook `MainUI.onConfigurationChanged`（框架方法名永不混淆，跨屏切换
   必然触发）。微信自身逻辑（更新 `H`、调用 `y0`）执行完毕后仍在虚拟屏时，post
   一个清零任务到主线程（立即 + 延迟 300ms 各一次，等待 `l7` 的异步重试链跑完；
   执行时复查仍在虚拟屏，避免与用户的下一次切屏竞争）；
2. **清零动作**：临时把字段 `H` 置 true，反射调用 `u0(0, 0)`，让微信自己的
   “多窗口强制清零”分支把 topMargin 与 mask 归零，随后恢复 `H`。不自己写任何布局
   数学，最终状态由微信代码产生；
3. **兜底**：hook `u0` 本身——但不再按混淆名定位，改为在 `MainUI` 已声明方法中按
   `(II)V` 签名**唯一定位**（8.0.76 中仅 `u0` 匹配；命中多个则放弃并记日志）。
   虚拟屏上被调用时同样临时置 `H=true`，防止显示切换竞态期间带着全屏参数写入；
4. 主屏（`DEFAULT_DISPLAY`）一律放行：回全屏后 `y0 → u0(actionBar, 124)` 的守卫
   因当前值已为 0 而放行，正常恢复全屏布局。

## 28. 通讯录 / 发现 / 聊天容器：8.0.76 缓存读者扩容

8.0.76 中 `Main_top_marign` 全屏缓存的读取者从 1 个变成 3 个（写入者仍是
`ConversationListView.v(int, boolean)` 的 `saveTopMargin`）：

| 读取者 | 8.0.76 方法 | 返回语义 | 小窗拦截返回值 |
| --- | --- | --- | --- |
| `BaseAddressUIFragment`（通讯录） | `u0()I` | ActionBar + statusHeight/缓存 | 仅 `bl.a(ctx)` |
| `FindMoreFriendsUI`（发现） | `L0()I` | ActionBar + statusHeight/缓存（**8.0.76 新增缓存分支**） | 仅 `bl.a(ctx)` |
| `NewChattingTabUI`（聊天容器） | `j()I` | 纯 statusHeight/缓存（无 ActionBar） | `0` |

三者拦截条件不变：仅在虚拟屏（`displayId != DEFAULT_DISPLAY`）且开关打开时生效，
主屏完全放行。`FindMoreFriendsUI` 在 8.0.79 没有缓存分支（第 8.1 节），8.0.76 起
与通讯录同构，所以“展开后缩回只有某页变宽”的判断表需要按版本更新。

## 29. 8.0.76 验证要点

- `LSPosed` 日志应出现 `命中候选类 com.tencent.mm.ui.bl` 与三条
  `已挂钩…（仅保留 ActionBar 高度 / 状态栏高度归 0）`；
- 首页连续执行 5 次以上“小窗 ↔ 全屏”切换，每次切回小窗后顶部应只有一行标题，
  且详细日志出现 `首页清零 margin/mask（临时 H=true 走微信强制清零分支）`；
- 通讯录/发现页展开后缩回不应重新变宽；
- 回到全屏后首页顶部应恢复全屏状态栏高度（清零不能污染主屏）。

---

# 起点读书与通用顶部空白修复：原理、边界与适配

## 17. 起点读书问题的本质

起点读书在 Z-Flow 虚拟显示屏中会出现顶部空白（俗称“宽额头”）。与微信不同，它的主要问题不是某个复杂页面容器的缓存，而是应用代码直接读取 Android 框架中的固定尺寸：

```java
int id = context.getResources().getIdentifier(
    "status_bar_height", "dimen", "android"
);
int height = context.getResources().getDimensionPixelSize(id);
```

`android:dimen/status_bar_height` 是设备/系统资源。即使 Activity 被搬到一个没有系统状态栏的虚拟屏，它仍可能返回主屏尺寸，例如 124px。应用把这段高度继续作为 padding、margin 或标题栏占位，于是虚拟屏顶部出现无意义空白。

对起点读书 7.9.480（`com.qidian.QDReader`，versionCode 1996）的 APK 搜索 `status_bar_height`，可以看到多个业务和组件类直接引用它，包括：

```text
com.qidian.QDReader.component.util.*
com.qidian.QDReader.activityoptions.*
com.qidian.QDReader.comic.ui.QDComicReadingBaseActivity
com.qidian.QDReader.flutter.bridges.*
com.qd.ui.component.helper.*
com.qd.ui.component.util.*
com.yuewen.app.base_ui.breakpoints.floatingwindow.FloatingWindowRootView
```

这说明问题不是单个混淆工具方法，而是许多页面和组件共同使用框架资源。逐个 hook 业务类既脆弱又不完整，因此选择稳定的框架出口更合适。

## 18. 通用修复的 hook 点

当前补丁 hook：

```text
android.content.res.Resources#getDimensionPixelSize(int)
```

执行顺序：

1. 先 `chain.proceed()` 得到应用原本会收到的尺寸；
2. 确认当前显示屏不是主屏；
3. 确认设置中的通用顶部空白修复已开启；
4. 确认参数资源 ID 正好等于：

```text
android:dimen/status_bar_height
```

5. 仅该资源返回 0；其他任何 dimen 原样返回。

伪代码：

```kotlin
val result = chain.proceed()
if (currentDisplayId == Display.DEFAULT_DISPLAY) return result
if (!enabled) return result
if (requestedId != statusBarHeightResId) return result
return 0
```

资源 ID 通过当前进程 Resources 动态解析并缓存：

```kotlin
res.getIdentifier("status_bar_height", "dimen", "android")
```

不硬编码 `0x0105....`，避免 Android 版本或资源表变化。

## 19. 为什么要同时追踪 `onResume` 与 `onMovedToDisplay`

`Resources` 本身没有公开的 `getDisplay()` 可供 hook 点直接判断所属显示屏，因此补丁维护一个进程内的 `currentDisplayId`。

Z-Flow 有两种常见进入小窗的路径：

| 进入方式 | Activity 行为 | 追踪信号 |
| --- | --- | --- |
| 直接以小窗启动 | Activity 新建/恢复 | `Activity.onResume()` |
| 从最近任务把现有任务搬入小窗 | Activity 不一定重建或 resume | `Activity.onMovedToDisplay(int, Configuration)` |

仅 hook `onResume` 会漏掉第二种路径。最终实现：

```text
onResume：chain.proceed 后读取 activity.display.displayId
onMovedToDisplay：chain.proceed 前读取参数 displayId
```

`onMovedToDisplay` 必须先更新缓存再放行，确保任务搬屏后的第一次资源查询就使用新 displayId。

默认值是主屏 `0`。如果两个生命周期 hook 都无法安装，补丁宁可不生效，也不在未知状态下误改主屏。

## 20. 为什么这是“通用修复”，但必须排除微信

这个框架级 hook 适用于满足以下条件的应用：

- 虚拟屏没有系统状态栏；
- 应用直接读取 `android:dimen/status_bar_height`；
- 读取结果只用于预留顶部空白；
- 页面没有另一套复杂的 Insets、遮罩、缓存和多窗口布局状态。

起点读书符合这些条件。微信不符合：微信同时存在 WindowInsets、`ol.i()`、列表 margin、status-bar mask、页面 padding 和 `Main_top_marign` 缓存。把框架资源统一改 0 会导致部分页面有安全空间、部分页面没有，形成覆盖和跳变。

因此通用不等于无差别安装。微信进程必须按真实 `processName` 排除，包括其随后加载的 WebView PackageReady。

## 21. 通用补丁边界

当前只覆盖：

```text
Resources.getDimensionPixelSize(int)
```

以下路径不受影响：

```text
Resources.getDimension(int)
Resources.getDimensionPixelOffset(int)
WindowInsets
WindowInsetsCompat
getWindowVisibleDisplayFrame
硬编码 dp/px
应用自定义资源（非 android 包）
服务端下发或 SharedPreferences 缓存高度
```

如果新应用仍有顶部空白，先查它具体使用哪条路径，不要盲目扩大框架 hook。框架 hook 越宽，误伤概率越高。

## 22. 起点读书/其他应用升级后的适配步骤

1. 打开详细日志并冷启动小窗；
2. 确认日志有：

```text
StatusBarDimenFix: 前台显示设备切换 0 → N
StatusBarDimenFix: 解析到 status_bar_height 资源 id=...
StatusBarDimenFix: displayId=N 非主屏，status_bar_height X → 0
```

3. 若有日志但 UI 不变，搜索 APK：

```text
status_bar_height
getDimensionPixelSize
getDimensionPixelOffset
getDimension
WindowInsets
setPadding
topMargin
```

4. 判断目标页面是否仍走 `getDimensionPixelSize(int)`；
5. 若改成其他框架 API，优先增加**精确资源 ID + 非主屏 + 目标进程**条件，不应直接全局归零所有 dimen；
6. 测试冷启动小窗、主屏搬入小窗、小窗展开/缩回、阅读页/漫画页/Flutter 页面和登录页；
7. 主屏必须无变化。

### 日志判读

| 日志/现象 | 含义 |
| --- | --- |
| `显示设备切换 0 → N` | 小窗追踪正常 |
| `status_bar_height X → 0` | 资源 hook 命中 |
| 始终 displayId=0 | 生命周期路径没覆盖或任务未真正进入虚拟屏 |
| 没有解析资源 ID | 进程资源表或 API 路径变化 |
| 命中日志存在但页面仍空白 | 空白由另一种 API、硬编码值或页面容器造成 |
| 主屏顶部异常 | display 缓存被其他 Activity/多显示屏状态污染，应缩小作用域 |

---

# Telegram 系小窗画面修复：原理、代价与适配

## 23. Telegram 问题的本质

Telegram 播放视频时可能进入 Android 系统画中画（Picture-in-Picture，PiP）。如果用户上滑离开视频后，再从最近任务把该任务搬到 Z-Flow 虚拟屏，任务已经处于 PiP 状态。

虚拟屏中没有完整系统 UI 来承载 PiP 窗口，结果可能变成：

```text
角落里一个很小的视频画面 + 其余区域空白
```

问题发生顺序是：

```text
主屏进入 PiP
→ 任务卡进入最近任务
→ 整个 PiP 状态任务被搬到虚拟屏
→ 虚拟屏无法正常承载 PiP
```

所以等应用到达虚拟屏再修已经太晚。

## 24. 为什么不能只在小窗里退出 PiP

Android 公共 SDK 没有通用的：

```text
Activity.exitPictureInPictureMode()
```

应用进入 PiP 的时刻发生在主屏，而且应用无法提前知道用户接下来是否会把任务搬进 Z-Flow 小窗。仅根据 `displayId != 0` 阻止 PiP 无法拦住“先主屏 PiP，后搬屏”的路径。

最终策略只能是：

```text
对 Telegram 系应用完全禁用 PiP
```

代价是 Telegram 在主屏也不再支持系统画中画。模块设置开关关闭后可恢复原行为。

## 25. 为什么不能只 hook `enterPictureInPictureMode()`

Android 12 / API 31 起支持自动进入 PiP：

```java
PictureInPictureParams.Builder()
    .setAutoEnterEnabled(true)
```

Telegram 在新系统上的逻辑近似：

```java
if (useAutoEnterInPictureInPictureMode()) {
    return; // 不调用 Activity.enterPictureInPictureMode()
}
activity.enterPictureInPictureMode(params);
```

因此 API 31+ 上仅拦截：

```text
Activity.enterPictureInPictureMode(...)
```

可能完全没有效果，因为系统根据 `autoEnterEnabled` 自动切换 PiP，应用没有显式调用进入方法。

## 26. Telegram 最终 hook 方案

### 主手段：替换 PiP 参数

hook：

```text
Activity.setPictureInPictureParams(PictureInPictureParams)
```

将 Telegram 提交的参数替换为：

```kotlin
PictureInPictureParams.Builder()
    .setAutoEnterEnabled(false) // API 31+
    .build()
```

然后继续调用原方法：

```kotlin
chain.proceed(arrayOf(disabledParams))
```

这样系统不会在用户离开 Activity 时自动进入 PiP。

### 兜底：拒绝手动进入

同时 hook `Activity.enterPictureInPictureMode` 的所有重载，直接返回 `false`，覆盖：

- API 30 及更低版本；
- Telegram fork 的旧代码；
- 仍主动调用手动进入接口的路径。

组合结果：

```text
自动 PiP → autoEnterEnabled=false
手动 PiP → enterPictureInPictureMode 返回 false
```

## 27. 如何识别 Telegram fork

不能只按官方包名判断，因为 Telegram fork 包名很多。当前通过进程中稳定、不混淆的标记类识别：

```text
org.telegram.messenger.AndroidUtilities
org.telegram.ui.LaunchActivity
```

任一存在即判定为 Telegram 系。

曾考虑使用：

```text
org.telegram.messenger.pip.PipActivityHandler
```

但 Telegram 13.x 起相关 Kotlin 类可能被 R8 完全混淆，实测 PiP 逻辑可落到类似 `le.c` / `le.f` 的混淆类，不适合作为稳定标记。

标记类检测比包名更适合覆盖官方版和 fork；但若某 fork 重命名整个 `org.telegram` 命名空间，则需要为该 fork补充稳定标记类。

## 28. Telegram 日志与验证

正常安装日志：

```text
TelegramPipDisable: pkg=... 确认为 Telegram 系（标记类 ...），开始安装
TelegramPipDisable: 已挂钩 setPictureInPictureParams
TelegramPipDisable: enterPictureInPictureMode 重载数=N
TelegramPipDisable: 安装完成 —— 本进程不再进入画中画
```

视频页面更新参数时：

```text
TelegramPipDisable: setPictureInPictureParams → autoEnterEnabled=false
```

若走手动路径：

```text
TelegramPipDisable: enterPictureInPictureMode(...) → 拒绝进入
```

测试流程：

1. Telegram 内播放视频；
2. 上滑离开视频 Activity / 进入最近任务；
3. 确认主屏未进入系统 PiP；
4. 从最近任务打开 Z-Flow 小窗；
5. 确认不再出现角落小画面；
6. 关闭模块中的 Telegram 开关，重启 Telegram，确认 PiP 可恢复。

## 29. Telegram 升级或 fork 适配

### 标记类失效

日志：

```text
非 Telegram 系（未找到 AndroidUtilities, LaunchActivity），跳过
```

处理：

1. 反编译目标 fork；
2. 找长期稳定、启动时必加载且不混淆的类；
3. 优先选择 Application、LaunchActivity、大型公共工具类；
4. 加入 `TELEGRAM_MARKER_CLASSES`。

### hook 已安装但仍进入 PiP

检查：

- 是否出现 `setPictureInPictureParams → autoEnterEnabled=false`；
- fork 是否绕过 Activity API，直接与系统服务交互；
- 是否在模块加载前就配置了 PiP；
- 是否使用独立视频进程且该进程未进入 LSPosed 作用域；
- Android 新版本是否引入新的 PiP 参数或 API；
- 目标是不是应用内悬浮播放器，而非系统 PiP。

应用内悬浮播放器不属于 Android PiP，拦系统 API不会影响它。

### 安全边界

- 仅标记为 Telegram 系的进程安装 hook；
- 开关关闭时实时放行，不必卸载模块；
- `disabledParams` 只关闭自动进入，不伪造其他业务参数；
- 手动入口返回 `false`，符合 Android API“未进入 PiP”的语义；
- 已进入 PiP 后无法靠此补丁退出，必须在进入之前拦截；
- 主屏 PiP 同样会被禁用，这是设计代价而不是 bug。

---

## 30. 三类补丁的设计差异

| 应用/问题 | 根因 | 最佳 hook 层级 | 为什么 |
| --- | --- | --- | --- |
| 微信 | 页面级高度、遮罩、缓存、Tinker 类替换共同作用 | 微信业务方法 + 页面方法 | 框架资源 hook 过宽，会破坏其他页面 |
| 起点读书 | 多组件直接读取固定系统 `status_bar_height` | `Resources.getDimensionPixelSize(int)` 精确资源 ID | 调用点多，框架出口稳定且语义一致 |
| Telegram | 任务在主屏已进入系统 PiP，搬屏后状态被带入 | `setPictureInPictureParams` + `enterPictureInPictureMode` | 必须在进入 PiP 前阻止，等到虚拟屏再处理已来不及 |

通用经验：选择最窄、但能覆盖全部真实调用路径的 hook 层级。不是越底层越好，也不是业务层永远更安全；要看错误语义是否在所有调用者中一致。


---

## 31. 首页来回切换小窗后偶发双行：`MainUI.H0()` 旧布局值未被覆盖

最终压力测试发现：微信反复执行“虚拟屏 → 主屏展开 → 新虚拟屏”时，首页偶尔再次出现双行，但点击通讯录再切回首页立即恢复。

日志已经证明此时 `ol.i()` 修复一直正常：

```text
displayId=N 首页调用 MainUI / ConversationListView → 状态栏高度置 0
```

因此问题不再是状态栏高度入口，而是消费高度的 `MainUI.H0(int,int)`。

反编译可见，`H0()` 更新会话列表 `topMargin` 时有保护条件：旧 margin 已大于 0 且内部字段 `H` 尚未表示 multi-window 时，会跳过覆盖；status-bar mask View 高度也有同类条件。主屏阶段写入的非零值因此可能残留到新虚拟屏。切换一次其他 tab 会重新触发首页初始化，所以表面上“点通讯录再回来就好了”。

最终增加页面级修复：

```text
com.tencent.mm.ui.conversation.MainUI#H0(int,int)
```

仅在 `displayId != 0` 时：

1. 保存实例字段 `H`；
2. 临时设置 `H=true`；
3. 执行微信原始 `H0()`；
4. 微信自身将列表 `topMargin` 和 mask height 设为 0；
5. `finally` 恢复原字段值。

这样不直接反射操作具体 View，也不依赖 View ID；仅借用微信已有的多窗口清零分支。主屏完全放行，每次虚拟屏中的 `H0()` 都会纠正旧布局值。

正常日志：

```text
WeChatStatusBarFix: source=tinker 已挂钩首页 H0()，清除跨屏残留 margin/mask
WeChatStatusBarFix: displayId=N 首页 H0() 临时设 H=true，清零残留 margin/mask
```

若新版本字段 `H` 或方法 `H0` 混淆名变化，应通过以下稳定日志字符串重新定位：

```text
setListMargin isInMultiWindowMode
setListMargin origin
setStatusBarMaskView height origin
```

---

# 小窗「展开」后的状态栏留白：成因与修法

## 30. 现象：小窗里干净了，展开反而被状态栏压住

对齐「小窗顶部空白修复」之后，起点读书等应用在小窗里顶部不再有宽额头，
但点右上角「展开」回到主屏后，系统状态栏（时间、信号、通知图标）会压在应用自己的
标题栏上 —— 书架页最容易看出来：时钟与「起点读书／搜索／消息」挤在同一行。

这不是又一次「判定失败」，而是同一个 `0` 在两个窗口状态下的取值冲突：

| 窗口状态 | 有没有系统状态栏覆盖应用 | 应用应该预留的高度 | 补丁返回值 |
| --- | --- | --- | --- |
| 小窗（虚拟屏） | 没有 | 0 | 0 |
| 展开（主屏全屏） | 有 | `status_bar_height` | 必须恢复成原值 |

## 31. 成因：展开是「搬任务」，不是「重建 Activity」

Z-Flow 的展开路径是：

```text
FreeformWindow.startSunOsSurfaceControlledExpand()
  → FreeformManager.moveTaskFromDisplayToDefault(displayId, taskId)
    → HookReload.suppressNextRelaunch(rootTaskId)
    → ActivityTaskManager.moveRootTaskToDisplay(rootTaskId, DEFAULT_DISPLAY)
```

两个关键事实：

1. 任务被搬到主屏，`displayId` 真的变成 0；
2. `suppressNextRelaunch` 抑制了配置变化引发的重建，**Activity 复用原实例**，
   走 `onConfigurationChanged` / `onMovedToDisplay` 承接变化。

于是小窗里拿到的那个 `0` 有两条出口，任一条都会让展开态失去顶部留白：

- **判定滞后**：Activity 被复用后重排时再次读取 `status_bar_height`，而补丁缓存的
  `currentDisplayId` 还停在虚拟屏上（两个回调的时机不保证早于那次重排）→ 继续返回 0。
- **值被烤进布局**：应用只在 `onCreate` 读一次，写进 `padding` / 占位 `View` 的高度，
  之后窗口尺寸变化也不重算 → 无论判定多准，展开态都还是 0。

## 32. 修法：实时反查 + 可选重建（两层）

### 第一层：hook 点实时反查显示设备（默认生效）

不再只依赖缓存的 `currentDisplayId`：

```kotlin
// 弱引用记住当前 Activity，hook 点用活查询
val displayId = lastActivity?.get()?.display?.displayId ?: cachedDisplayId
```

`Activity.getDisplay()` 是活查询，任务被搬到主屏后立刻返回 0，与生命周期回调的时序无关。
同时把追踪前移到 `Activity.onCreate(Bundle)`（`proceed` **之前**登记），
覆盖「进程里的第一次读取就发生在 onCreate」这条路径 —— 小窗冷启动的空白也因此更稳。

### 第二层：展开后重建界面（`rebuild_on_expand`，默认关闭）

只针对上面第二条出口（值被烤进布局）。三个条件同时成立才动手：

1. 本进程**确实归零过**（小窗里改过它的布局）；
2. 用户打开了设置里的「展开后重建界面」；
3. 回到主屏后 800ms 宽限期内，应用**没有**再来读 `status_bar_height`
   —— 说明它不会自己重算。

满足即 `Activity.recreate()` 一次，让新 Activity 在 `onCreate` 里重新取值（此时已在主屏）。
代价是那次展开会有一次页面重载（书架页等于刷新一次），因此默认关闭、按应用开启。

### 为什么不做「窗口铺满就当归零」

看起来更聪明的判据是「窗口是否铺满显示屏」，但对虚拟屏是错的：虚拟屏本身就没有状态栏，
用户把小窗拉到接近虚拟屏大小同样是「铺满」，此时仍然应当归零。
判据必须落在「应用是否在主屏」上，而不是窗口尺寸上。

## 33. 验证与日志判读

1. 打开「详细日志」，冷启动小窗打开起点读书 → 书架顶部无空白；
2. 点「展开」回主屏 → 状态栏与标题栏分离，书架正常；

对应日志：

```text
StatusBarDimenFix: 前台显示设备切换 0 → N（onCreate）
StatusBarDimenFix: displayId=N 非主屏，status_bar_height 124 → 0
StatusBarDimenFix: 前台显示设备切换 N → 0（onMovedToDisplay）
```

| 展开后现象 | 日志特征 | 结论 |
| --- | --- | --- |
| 正常 | 有 `切换 N → 0`，之后不再出现 `→ 0` | 第一层已解决 |
| 仍被压住，且之后还有 `120 → 0` | 实时反查没有生效（`getDisplay()` 不可用） | 检查 ROM 是否屏蔽 `Activity.getDisplay()` |
| 仍被压住，且之后**没有**任何 `status_bar_height` 读取 | 值已被烤进布局 | 打开「展开后重建界面」，日志出现 `重建 Activity 一次` |
| 打开重建后每次展开都刷新页面但仍压住 | 应用在重建后也拿不到正确值 | 需要按第 22 节对目标版本做精确 hook |

## 34. 设置开关被写成关闭：远程偏好里的默认值固化

### 现象

补丁代码没动、作用域也在，但微信侧日志一直只有：

```text
设置已载入: verbose=true logToFramework=true wechat=false statusbar=true telegram=true
WeChatStatusBarFix: 开关已关闭，跳过安装
```

即：`wechat_statusbar_fix=false`，微信补丁**在安装阶段就被跳过**，后面的锚点探测根本不会跑。
小窗里双标题栏、通讯录顶部被覆盖等现象自然全部回来。

### 为什么会变成 false

远程偏好（框架侧那份 SharedPreferences）是**持久化**的，而且旧版设置页有两个坏习惯：

1. `syncFromRemote()` 对框架里**不存在**的键也用 `getBoolean(key, default)`，
   随后 `editor.putBoolean(key, value)` + 给 Switch 赋值 —— 一旦给 Switch 赋值时监听器已注册，
   就会触发 `onSwitchToggled()`，把「当前默认值」**当成用户操作写回框架**。
2. 于是某个曾经默认关闭过微信补丁的构建（例如带 `universal_multiwindow_fix` 的实验版）
   把 `false` 固化进了远程偏好；之后无论装哪个版本，读到的都是 `false`，
   而设置页每次打开又会再写一遍 `false`，形成自我强化。

### 修法

- 同步只处理框架里**真实存在**的键（`remote.contains(key)`），不再把默认值写进框架；
- 同步和 `onSharedPreferenceChange` 期间置 `syncing`，`onSwitchToggled` 直接返回，
  程序改 UI 不算用户操作，不写远程偏好。

### 已经变成 false 的机器怎么恢复

远程偏好里的旧值不会自动消失，需要手动把开关打开一次（此后才会写入 `true`）：

1. 打开模块设置页；
2. 打开「微信小窗标题栏重复修复」；
3. **完整结束微信进程**（设置 → 应用 → 强制停止，或 `am force-stop com.tencent.mm`）；
4. 重新以小窗打开微信，日志应出现：

```text
WeChatStatusBarFix: 开始安装（pkg=com.tencent.mm）
WeChatStatusBarFix: source=... 已挂钩首页 H0()，清除跨屏残留 margin/mask
```

若开关打开后仍然只有「跳过安装」，说明写入没有到框架（`settings_write_failed` 弹窗），
需要检查 LSPosed 里模块是否处于启用状态。
