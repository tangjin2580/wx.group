# wxgroup-reborn

> 微信会话分组 LSPosed 模块 —— 在会话列表里做「收纳组」（无联网、无账号、完全离线）

在微信会话列表顶部插入**可点开的收纳组行**，点进去就是一个二级页面，只显示这个组的成员会话；返回键回到上一级，效果类似微信自带的「折叠的群聊」。

模块本身：

- ✅ **完全离线**：无账号、无联网、无任何服务端校验，分组数据只存在本机。
- ✅ **libxposed API 102**：基于 `io.github.libxposed:api:102.0.0`（LSPosed 新版 API），不再依赖 legacy `de.robv.android.xposed:api:82`。旧代码风格通过 `xp/` 下的薄封装层保留。
- ✅ **签名可编辑**：微信的方法签名抽成一份 JSON，运行时读取，App 内即可修改，**无需重新打包**。
- ✅ **防御式 Hook**：每个 Hook 都包了 try/catch + 日志，单条签名填错只会导致该功能静默失效，不会崩微信。

---

## 功能

- **像「折叠群聊」一样在会话列表里直接创建收纳组**：本模块接管微信会话列表的 `BaseAdapter`（`jo5/y0`），在列表顶部插入**可折叠的收纳组行**。点一下收纳组行即可展开/收起，展开后该组内的联系人和群聊直接显示在其下方（由微信原生行渲染），其余位置则把这些会话**隐藏**——效果与微信自带的「折叠群聊」一致，而不是简单的背景着色。
- **微信界面内建分组**：长按任意聊天（个人或群聊 `@chatroom` 均可）→「添加到分组」→ 选已有分组，或直接选「**＋ 新建分组…**」当场建一个，命名后该会话即被收进收纳组。菜单里还会显示「**移出分组「X」**」（仅当该会话已在某分组里），一键把会话放回主列表。
- **分组归属互斥**：收纳组是「文件夹」语义，一个会话只待在一个分组里。把已在 A 组的会话加进 B 组会自动从 A 摘掉，并提示「已从 A 移到 B」。
- **自定义分组排序**：长按收纳组行 →「上移 / 下移 / 拖动排序…」。拖动排序页照抄微信「通讯录 → 标签 → 标签排序」的 ☰ 拖拽把手交互。顺序规则见下。
- **分组顺序规则**：置顶优先 → 手动 `order` → 创建时间（从分组 id 里编码的毫秒时间戳取）→ 名字。没手动排过时就是稳定的创建顺序；排过之后完全听用户的；新建分组排最后。
- 管理命名分组：创建 / 重命名 / 清空成员 / 删除（都在微信界面的长按菜单里）；置顶(pin) / 免打扰(mute) 在模块 App 里。
- 所有分组数据只存在本机，没有任何云端同步或激活服务器。
- **分组落点**：微信私有目录 `/data/user/0/com.tencent.mm/files/chatgroup_groups.json`（裸 JSON 数组），同时兼容 `{"groups":[…]}` 与 名字→成员 映射两种手写格式。

---

## 架构

```
app/src/main/java/io/github/wxgroup/reborn/
├── MainHook.kt                         # libxposed 102 入口：XposedModule，scope 限定 com.tencent.mm
├── hook/
│   ├── ConversationGroupHook.kt        # 入口 + 全部可变状态 + 通用工具（其它文件都是它的扩展函数）
│   ├── Models.kt                       # VEntry/LegacyModel/RvModel/FolderHolder + 视图类型常量
│   ├── ListHitTest.kt                  # 命中判定：哪个 ListView 是我们的、点在哪一行（只信 View.tag）
│   ├── TouchProbe.kt                   # 按下位置探测：屏幕坐标自算，纠正微信给错的 position
│   ├── ItemIntercept.kt                # 列表项点击/长按拦截：展开折叠、长按弹操作菜单
│   ├── ListViewPath.kt                 # ListView 分支：适配器 hook、虚拟模型构建、未读数统计
│   ├── RecyclerViewPath.kt             # RecyclerView 分支（其它机型/版本）
│   ├── MenuHooks.kt                    # 会话长按菜单：注入菜单项 + 接管 onMMMenuItemSelected
│   ├── GroupActions.kt                 # 分组增删改序、换图标、菜单动作落地（全部微信内弹窗）
│   ├── HostContext.kt                  # 拿 Activity / 跳模块页 / 从 View 里刨 username
│   ├── GroupCache.kt                   # 分组读取缓存、变更检测与自动刷新、落盘、列表重绘
│   └── FolderRow.kt                    # 收纳组行的 View 构建与绑定（图标 + 未读角标）
├── xp/
│   ├── XposedBridge.kt                 # 薄封装：libxposed 102 的 hook(Executable).intercept 适配
│   ├── XposedHelpers.kt                # findClass / findAndHookMethod / getObjectField / callMethod
│   └── XC_MethodHook.kt                # 兼容旧 before/after 回调 + MethodHookParam
├── core/
│   ├── GroupProvider.kt                # ContentProvider（fallback，多数机型被 AppsFilter 拦截，见下）
│   ├── GroupStore.kt                   # 分组读写 + 跨进程通道（以 /data/local/tmp 镜像为主）
│   ├── GroupModels.kt                  # WxGroup 数据类 + JSON 序列化
│   └── WeChatSignatures.kt             # 方法描述符 / 签名解析
├── util/
│   └── ReflectionUtil.kt               # 反射工具（候选名回退 / 子串搜索，版本兼容）
└── ui/
    ├── SettingsActivity.kt             # 入口（启动器）+「强杀微信」
    ├── GroupManageActivity.kt          # 分组管理 + 接收「添加会话到分组」意图
    ├── SortGroupsActivity.kt           # 分组排序页（☰ 拖拽把手，带跟手动画）
    └── SignatureActivity.kt            # 在 App 内编辑 / 保存 / 重置签名 JSON

app/src/main/res/raw/wechat_signatures.json   # 按微信版本的 Hook 签名配置（可编辑）
app/src/main/resources/META-INF/xposed/java_init.list  # libxposed 102 入口声明（MainHook）
```

**跨进程数据通道（关键实现细节）**  
Xposed 的 Hook 跑在**微信进程**里，而分组数据由**模块自己的 App 进程**创建和编辑。两个进程不能直接共享内存，需要一个跨进程通道。

在 Android 11+（本机 HyperOS / Xiaomi）上，常规的跨进程方案**全部被 `AppsFilter` 包可见性与 SELinux 拦截**，实测逐一失败：

| 方案                                           | 结果                                                                                            |
| -------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `ContentProvider`（exported + forceQueryable） | `Can't resolve content provider ... from package` —— AppsFilter 仍拦截（厂商 PMS 忽略 forceQueryable） |
| `createPackageContext(IGNORE_SECURITY)`      | `Application package ... not found` —— PackageManager 级拦截                                     |
| `XSharedPreferences`                         | 读出来为空 —— 同样被 AppsFilter/SELinux 挡住                                                            |
| 模块私有文件 `/data/data/.../files` 直接读            | `ENOENT` —— 微信进程根本看不到模块的数据目录                                                                  |
| `/sdcard/...` 直接读                            | `EACCES` —— FUSE 不允许任意 app 读                                                                  |

**唯一可行的通道：`/data/local/tmp/` 下的世界可写（1777）文件。**

- 微信进程**能读** `/data/local/tmp/`。
- 模块 App 进程**不能新建**该目录下的文件（`EACCES`，SELinux 把新建动作拦了），但**可以覆盖一个已存在的世界可写文件**。
- 因此做法：设备安装/首次运行时，用 `su` 预创建 `/data/local/tmp/wxgroup_reborn_groups.json` 并 `chmod 666`；之后模块 App 每次改动分组都 `writeText` 覆盖它（并再次 `chmod 666`），微信侧 `GroupStore.getGroupsXposed()` 直接 `File.readText()` 读取。
- `GroupProvider`（ContentProvider）保留为 fallback，在那些 forceQueryable 真正生效的机型上可用；当前设备走 tmp 文件。

> 静态签名另有「内置 raw 资源」兜底：模块类与资源会被 LSPosed 载入微信进程，直接用模块 ClassLoader 读 `res/raw/wechat_signatures.json`，完全不经过任何 IPC，保证 Hook 一定能在微信里挂上。

---

## 构建

### 环境要求

| 组件          | 版本                                          |
| ----------- | ------------------------------------------- |
| JDK         | 17                                          |
| Android SDK | platform android-37 + build-tools 36.0.0   |
| Gradle      | 8.13（仓库已带 wrapper）                        |
| AGP         | 8.13.2                                      |
| 网络          | 首次 sync 需要连 Maven Central + Google     |

### 命令

```bash
cd wxgroup-reborn
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
export ANDROID_SDK_ROOT=/Users/Mr.li/Library/Android/sdk   # 换成你自己的 SDK 路径
./gradlew :app:assembleDebug --no-daemon
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（当前约 2.9 MB）。

---

## 填写方法签名（按微信版本）

微信各版本之间会调整内部类名与方法位置，所以 `classes` 里给的是**跨版本相对稳定的类名**，而 `methods` 里的 `methodName` 默认是**空字符串** —— 这些 Hook 在填入真实签名前会被跳过。

`wechat_signatures.json` 结构：

```json
{
  "wechatVersion": "8.x.x",
  "classes": {
    "ConversationUI":   "com.tencent.mm.ui.conversation.BaseConversationUI",
    "ConversationBoxUI":"com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI",
    "LauncherUI":       "com.tencent.mm.ui.LauncherUI",
    "MvvmList":         "com.tencent.mm.plugin.mvvmlist.MvvmList",
    "Storage":          "com.tencent.mm.storage"
  },
  "methods": {
    "ConversationOnCreateContextMenu": { "class": "ConversationUI", "methodName": "", "params": ["android.view.ContextMenu", "android.view.View"], "ret": "void" },
    "ConversationOnMenuItemSelected":  { "class": "ConversationUI", "methodName": "", "params": ["int", "android.view.MenuItem"], "ret": "boolean" }
    /* …其余方法同理… */
  }
}
```

### 怎么填真实签名

1. 按你所用微信版本，找到目标方法，记录它的：方法名、`params`（参数类型）、`ret`（返回类型）。
   可以照着模块日志来对照：Hook 命中/跳过都会打日志，名字填错只会「跳过」而不会崩微信。
2. 填进 `methodName` / `params` / `ret`。
   - 参数/返回类型写法示例：`int`、`void`、`java.lang.String`、`[Ljava/lang/String;`、`android.view.View`、`android.view.ContextMenu`、`android.view.MenuItem`、`java.util.List`、`com.tencent.mm.storage.Conversation`。
   - `params` 留空数组 `[]` 表示无参。
3. 两种方式生效：
   - **构建期**：直接改 `app/src/main/res/raw/wechat_signatures.json` 后重新 `assembleDebug`。
   - **运行期（免重打包）**：打开模块 App → 签名设置 → 编辑 → 保存。这会写一份 `signature.json` 到 App 私有目录，覆盖内置默认；还提供「重置」回退到内置配置。

> 含 `"TODO"` 或为空字符串的 `methodName` 会被自动跳过，不会尝试 hook。

### 微信 8.0.78 实测签名（可直接用）

以下均为在 8.0.78 真机上运行时实测确认：

- **会话菜单是 `View.OnCreateContextMenuListener` 方式**，8.0.78 上菜单宿主类是 **`com.tencent.mm.ui.conversation.s3`**（实现 `OnCreateContextMenuListener`）；`onCreateContextMenu` 是接口方法，名字稳定，字段 `g` 即 talker。给它注入「添加到分组」菜单项并自带 `OnMenuItemClickListener`（8.0.x 已无 `onContextItemSelected`，微信改用 `OnMenuItemClickListener` 分发）。
- **会话列表是 `BaseAdapter`（`jo5/y0`），不是 RecyclerView！** 这是做内联折叠收纳组的基础。`getView` 按 `p1`（position）直接索引内部数据列表 `jo5/f.d`，**不调用 `getItem`**，所以安全地把真实行的虚拟 position 映射回真实 index 不会二次重映射。`getItem(position)` 返回 `k4`（`com.tencent.mm.storage.k4`），`k4` 经 `im/j2` 持有 `field_username`（= talker）。
- 内置 `wechat_signatures.json` 已填好 8.0.78 的全部 8 个适配器方法（用于内联收纳组），其中 4 个是 `BaseAdapter` **继承**方法（`isEnabled` / `areAllItemsEnabled` / `getItemViewType` / `getViewTypeCount`），需用「遍历超类 + `XposedBridge.hookMethod`」才能挂上（`findAndHookMethod` 只认本类声明方法）：

  | key | 方法 | 作用 |
  | --- | --- | --- |
  | `ConversationOnCreateContextMenu` | `s3.onCreateContextMenu` | 注入「添加到分组 / 新建分组 / 移出分组」菜单项 |
  | `ConversationGetCount` | `jo5/y0.getCount` | 返回「虚拟行数 = 真实数 + 收纳组头部数」 |
  | `ConversationGetItem` | `jo5/y0.getItem` | 虚拟位↔真实 index 映射 |
  | `ConversationGetView` | `jo5/y0.getView` | 头部位渲染折叠收纳组行；真实位把虚拟 position 还原成真实 index 后交给微信原生渲染 |
  | `ConversationGetItemViewType` / `ConversationGetViewTypeCount` | `BaseAdapter.*`（继承） | 给收纳组头部一个独立 viewType，避免 ListView 复用把自定义头部塞进错 scrap |
  | ~~`ConversationIsEnabled` / `ConversationAreAllItemsEnabled`~~ | `BaseAdapter.*`（继承） | **不要挂！** 让收纳组头部返回 `false` 会让 `AbsListView.onInterceptTouchEvent` 不进 `TOUCH_MODE_DOWN`，触摸被行 View 吃掉：从收纳组行上起手拖动无法滚动列表，且行必须自己 clickable 去和微信的监听器抢事件 → 偶发闪退。收纳组行照旧「可点」，点击统一由框架级 `AdapterView.performItemClick` 拦截。 |

> 微信在 `ListView.setAdapter` 时会把我们的 `jo5/y0` 包进 `HeaderViewListAdapter`，而 AOSP 的 `HeaderViewListAdapter` 会把 `isEnabled`/`getItemViewType`/`getViewTypeCount` 转发给被包裹的适配器并把头部偏移扣掉——所以虚拟 position 能原样到达我们的 Hook。

### 内联收纳组的核心算法（折叠群聊式）

`buildModel(realCount)` 产出 `List<VEntry>`：

1. 所有收纳组按（置顶 → 名称）排序，每组先放一个 `TYPE_HEADER` 行；
2. 展开（`expanded`）的组，依次把组内成员（且确实在列表里、`placed` 去重）以 `TYPE_REAL`（携带真实 index）插入；
3. 最后遍历真实 `[0, realCount)`，非组成员按原顺序以 `TYPE_REAL` 追加。
4. `getView`/`getItem`/`isEnabled`/`getItemViewType` 全部按虚拟 position 查这张表，真实行在交给微信原生渲染前把 `args[0]` 还原成真实 index。

> 换微信版本时若 `jo5/y0` / `jo5/f.d` / `k4.field_username` / `s3` 改名，需更新 `wechat_signatures.json` 的 `classes`/`methods` 与 `buildModel` 里的字段路径（`q.d` → 实际数据列表字段名）。

---

## 在 LSPosed 里安装与激活

1. 像装普通 App 一样安装 `app-debug.apk`。
2. 打开 **LSPosed Manager → 模块**，勾选启用 **wxgroup-reborn**。
3. 在该模块的 **作用域** 里，确保 **微信 (com.tencent.mm)** 已勾选（manifest 里也已内置默认作用域 `com.tencent.mm`）。
4. **（一次性，需 root）预创建跨进程镜像文件**——这是 8.0.78 / HyperOS 上的必要步骤，因为模块 App 进程在 SELinux 下无法自行在 `/data/local/tmp` 新建文件，只能覆盖已存在的世界可写文件：
   ```bash
   adb shell su -c 'touch /data/local/tmp/wxgroup_reborn_groups.json && chmod 666 /data/local/tmp/wxgroup_reborn_groups.json'
   ```
   > 若执行后该文件仍存在（大小 0 也无妨），说明通道已就绪；模块 App 每次改分组都会覆盖它。
5. 重启微信（或强制停止后重新打开）使 Hook 生效。
6. **在微信里使用**（全部操作都在微信界面内完成，不需要开模块 App）：
   - 会话列表顶部会出现**分组收纳行**（折叠态，点击展开/收起）。
   - 把联系人或群聊收进分组：**长按某个聊天 → 「添加到分组」** → 选已有分组，或选「＋ 新建分组…」当场建一个。
   - 把会话移出分组：**长按该会话 → 「移出分组「X」」**。若它已在某个分组里，这一项才会出现（会话被收进分组后仍能从分组内部长按到它）。
   - 调整分组顺序：**长按收纳组行 → 「上移」/「下移」/「拖动排序…」**。
   - 重命名 / 清空成员 / 删除分组：**长按收纳组行 → 对应菜单项**。
   - （分组数据改完会自动镜像到 `/data/local/tmp/wxgroup_reborn_groups.json`，微信会立刻刷新列表；必要时可重启微信。）

---

## 本次（8.0.78）实测中修复的关键问题

分组功能在真机验证时一度「完全不生效」，根因是三个隐蔽 bug，已全部修复：

1. **签名 JSON 字段名不匹配 → 所有 Hook 被静默跳过。**  
   `MethodDescriptor.fromJson` 读的是 `"name"` 字段，但 `wechat_signatures.json`（以及 `toJson`）用的是 `"methodName"`。导致解析后 `methodName` 永远为空 → `isValid==false` → 12 个方法**全部跳过**，模块等于空跑。修复：统一读 `"methodName"`（兼容旧的 `"name"`）。
2. **类名里的斜杠 `jo5/y0` → `findClass` 找不到类。**  
   形如 `jo5/y0` 的类名必须转成二进制名 `jo5.y0` 才能被 `XposedHelpers.findClass` 解析。修复：`ReflectionUtil.findClass` 里 `name.replace('/', '.')`。这是 `ConversationGetView`（视觉标记）能挂上的前提。
3. **基本类型参数 `int` 解析为 null → `getView` Hook 被跳过。**  
   `ReflectionUtil.resolveClass("int")` 原实现只处理引用类型，返回 null，使 `paramClasses.any { it == null }` 为真而跳过。修复：`resolveClass` 显式返回 `Int::class.javaPrimitiveType` 等基本类型 Class。

附带修正的体验问题：talker 提取对 `filehelper` 这类特殊账号被 `looksLikeUsername` 误判而回退失败 → 改为直接信任 `k4.field_username`（最可靠的字段），仅在为空时才做字段扫描兜底。

> 验证方法：开模块 App 建好分组后，重启微信并 `adb logcat | grep WxGroupReborn`，应看到 8 条「已挂载 Hook: …」与（收纳组生效时）`收纳组生效: 分组=N, 虚拟行=M (真实=K, 收拢成员=L)`。

### 内联收纳组重写时修复的新坑（8.0.78 / HyperOS 真机）

把「背景着色」升级为「内联折叠收纳组」（接管 `BaseAdapter`）时，又踩到并修掉以下真机崩溃/失效问题：

1. **`isEnabled` / `getItemViewType` 等继承方法挂不上** → 点收纳组头部时微信点击分发 NPE 崩溃（这正是早期 FAB 方案闪退的根因之一）。`findAndHookMethod(name, Class...)` 只认本类声明方法，而它们继承自 `BaseAdapter`。修复：新增 `hookInherited()`——遍历 `jo5/y0` 的超类/接口链找到目标 `Method`，再用 `XposedBridge.hookMethod` 挂，并用 `adapterClass.isInstance(thisObject)` 严格守卫只作用于会话适配器。
2. **`getCount()` 在未初始化时返回 `-1` 哨兵值** → `Array(realCount)` 抛 `NegativeArraySizeException`，整个虚拟模型构建失败、列表空白。修复：`realCount < 0` 时直接透传原始值、不构建模型。
3. **`adapter as Context` 强转抛 `ClassCastException`**（`jo5/y0` 不是 `Context`）→ 每次 `getCount` 静默失败。修复：`getGroupsXposed(ctx: Context?)` 参数改为可空，tmp 文件读取不依赖 Context。
4. **ListView 复用错位风险**：给收纳组头部独立的 `getItemViewType`，避免自定义 View 被塞进其它 scrap 堆导致后续崩溃。
5. **早期 FAB 方案被废弃**：改为用户要的内联收纳组（像折叠群聊），不再注入浮动按钮。

### 第二轮真机反馈修复：「点不开 / 添加到分组不生效」

用户反馈 `分组点不开`、`添加到分组也不生效`。排查出两个**确定性**根因（外加一个体验问题）：

1. **收纳组行被自己设成了「禁用」→ 点击回调永远不执行（点不开的真因）。**  
   `buildFolderView` 里给整行设了 `isEnabled = false`。Android 的 `View.onTouchEvent` 在 `DISABLED` 时会**提前 return**：它虽然仍返回 `clickable`（即继续消费触摸），但**永远不会调用 `performClick()`**，所以 `OnClickListener` 根本不会触发；同时因为事件被消费，ListView 也不会走 item 点击。表现就是「点一下毫无反应」。  
   **修复**：整行保持 `isEnabled = true` + `clickable`，由行自己的监听器处理点击/长按；「行不可被微信当会话点开」改由适配器的 `isEnabled(pos)=false` 负责（两者职责不同，之前混淆了）。
2. **「添加到分组」弹窗在分组列表加载之前就弹出了 → 永远只能「新建分组」（不生效的真因）。**  
   `GroupManageActivity.onCreate` 里先 `showAddToGroupDialog()`、后 `refresh()`，导致弹窗构造 `names` 时 `groups` 还是**空列表**，于是只列出一项「（新建分组）」，且 `which == groups.size`（0==0）恒成立，**无法选已有分组**。  
   **修复**：把 `refresh()` 提到处理 intent 之前；并给「已加入 X」「已新建 X 并加入」加 Toast 反馈。  
   （设备上的实证：用户建的分组 `1` 的 `members` 为空 `[]`，与「加不进去」完全吻合。）
3. **改完分组要重启微信才看得到（体验问题）。**  
   模块 App 写入分组后，微信侧列表不会自动重绘。  
   **修复**：新增 `GroupStore.groupsStamp()`（镜像文件 `lastModified*1e6 + length`）；Hook 在 `getCount` 时比对变更戳，变了就清缓存并 post 一次 `adapter.notifyDataSetChanged()`（只 post，不在布局流程里同步调用，避免 ListView 重入）。另起一个 1.2s 的轻量轮询（仅一次 `File` stat，开销可忽略），使列表空闲时也能及时刷新。


### 第三轮真机反馈修复：「点分组下面的会话，把分组展开了」/「那两个群点不开」

用户反馈：点击收纳组**下面**的普通会话，结果把某个收纳组展开了；后来升级成「那两个群直接点不开了」。这一轮挖到的根因最隐蔽，但结论最有普适性。

1. **`position` 完全不可信 —— 被点的那个 View 才是唯一事实。**（本轮最关键的发现）  
   `onItemClick` / `performLongPress` 收到的 `position` 并不等于被点行的位置。真机实测：`微信报 33 → 实际 15`、`微信报 20 → 实际 16`、`微信报 15 → 实际 17`。ListView 的 `mTouchPosition` 是**按下瞬间**抓的，中间只要刷新过一次列表（我们插入了收纳组行、或微信自带的「Windows 微信已登录」横幅增删 146px 导致整表位移），按下位置和抬手位置就指向两行不同的行。  
   **修复**：判断「点的是不是收纳组行」只看 **被点 View 的 `tag`**（我们给它放了 `FolderHolder`），并用 `ListView.getPositionForView(view)` 把真实的 position **改写回去**再交给微信。不改写的话微信会拿着错的 position 去 `getItem(pos - headerCount)`，拿到 `null` 就 `return`（日志 `null user at position = …`）——表现就是「点不开」。
2. **跨类型复用 `convertView` → 会话行显示成了收纳组行。**  
   ListView 按 `getItemViewType` 分桶回收；一旦这个 hook 没生效，`FolderHolder` 行会被当成会话行的 `convertView` 交给微信的 `getView`，绑定失败后又只能原样返回，于是那一行「变成」了展开/收纳行。  
   **修复**：双保险——`getView` 拿到 `tag is FolderHolder` 的 convertView 就丢弃（传 `null` 让微信重建），且真实行路径/兜底路径都拒绝返回收纳组行。
3. **Tinker 热修导致类加载器有两套 → 所有 Hook 挂在错的类上。**  
   微信 8.0.78 带 Tinker 补丁，运行时类来自 `DelegateLastClassLoader` 加载的 `tinker_classN.apk`，与 `lpparam.classLoader` 解析出的 `Class` **不是同一个对象**，于是「hook 成功但毫无效果」。引导改为从 `android.widget.AbsListView.setAdapter` 的 `arg[0]`（活的 adapter 实例）取它真正的 `classLoader`，再在它上面挂全部 Hook。
4. **引导时的类加载器必须校验，否则会被 `BootClassLoader` 污染。**  
   微信自己的对话框/其它列表用的是 framework 的 `ListView + ArrayAdapter`，那类 adapter 的 `classLoader` 是 `BootClassLoader`。不校验就把它记成「运行时加载器」，既装不上任何 Hook，又会占住 `hookedLoader` 让真正的加载器进不来。  
   **修复**：`canResolveWeChat(loader)` —— 能 `findClass` 出会话适配器才算数。注意签名里是 `jo5/y0` 这种斜杠写法，`Class.forName` 不认斜杠，必须走 `ReflectionUtil.findClass` 做归一化（这里是作者自己踩过的第二遍坑）。
5. **跨进程同步会丢数据：模块 App 启动时用陈旧副本覆盖镜像。**  
   `ensureDefaults()` 原本的顺序是 `importFromTmp()` → `mirrorGroupsToTmp()`，而 `importFromTmp` 只按 id 判断「本地有没有」，同 id 时保留本地旧值 —— 于是模块 App 一启动就把微信侧刚写进镜像的新成员覆盖掉了（实测一次丢了 2 个成员）。  
   **修复**：① `importFromTmp` 同 id 时**以镜像为准**（镜像由微信进程写，是会话列表真正在用的那份）；② 启动时**不再回写镜像**，只在用户真的在模块 App 里改分组时才写。

---

## 已知限制 / 注意

- 微信版本更新频繁，内部类名/字段位置可能变化，**必须为你的具体版本提供方法签名**（见上）。内置只给了类名。
- 本模块仅供学习与研究，使用前请自行评估是否违反微信 ToS 及当地法律。
- 内联收纳组依赖直接读微信适配器内部数据列表字段（`jo5/f.d`、`k4.field_username`）；这些字段可能随版本改名，签名或字段路径填错时对应功能会**静默跳过**（写在 Xposed 日志里），而不是让微信崩溃。所有 Hook 均已包 try/catch + 守卫。

---

## 代码组织

- `core/` —— 分组模型、存储（微信私有目录 + 跨进程镜像）、外观主题（72 个 emoji 图标 + 10 色配色）。
- `hook/` —— 全部 Hook 实现，按功能拆分：列表接管、点击/长按拦截、二级页面、返回键、菜单注入、分组操作、缓存刷新。
- `ui/` —— 模块 App（分组管理、签名配置）。
