# 微信分组 (wxgroup)

> 微信会话分组 LSPosed 模块 —— 在会话列表里做「收纳组」（无联网、无账号、完全离线）

在微信会话列表顶部插入**可点开的收纳组行**，点进去就是一个二级页面，只显示这个组的成员会话；返回键回到上一级，效果类似微信自带的「折叠的群聊」。

**当前版本 v1.0.1** → [下载 Release](https://github.com/tangjin2580/wx.group/releases/latest)

## 特性

- ✅ **完全离线**：无账号、无联网（仅「检查更新」按需访问 GitHub）、无任何服务端校验，分组数据只存在本机。
- ✅ **libxposed API 102**：基于 `io.github.libxposed:api:102.0.0`（LSPosed 新版 API），旧代码风格通过 `xp/` 薄封装层保留。
- ✅ **Jetpack Compose + Material 3 设置界面**（v1.0.0 重做）：「海盐薄荷」主题，底部三 Tab（首页 / 分组 / 设置），深浅色跟随系统，与微信内渲染共用同一套取色。
- ✅ **签名零配置起步**：内置微信 8.0.79 的实测 Hook 签名，安装即用；签名页提供「版本匹配状态行 / 一键填充内置 / 在线获取最新」，适配新微信无需重新打包。
- ✅ **实时生效**：模块 App 里改分组 / 偏好 / 主题，经镜像通道 + Provider 通知，微信侧秒级自动刷新，无需重启微信。
- ✅ **防御式 Hook**：每个 Hook 都包 try/catch + 日志，单条签名失效只会静默跳过该功能，不会崩微信。

---

## 安装与激活

1. 从 [Releases](https://github.com/tangjin2580/wx.group/releases/latest) 下载 `app-release.apk` 安装。
2. 打开 **LSPosed Manager → 模块**，勾选启用 **微信分组**，作用域勾选 **微信 (com.tencent.mm)**。
3. **（一次性，需 root）预创建跨进程镜像文件**——模块 App 进程在 SELinux 下无法在 `/data/local/tmp` 新建文件，只能覆盖已存在的世界可写文件：
   ```bash
   adb shell su -c 'for f in groups settings; do p=/data/local/tmp/wxgroup_reborn_$f.json; touch $p && chmod 666 $p; done'
   ```
   > 也可以在模块 App 里看到「模块状态：未激活」时，按提示用 root 创建（旧版设置页内置一键创建入口）。
4. 强停微信后重新打开，会话列表顶部就会出现收纳组。

### 模块 App（Compose 界面）

| Tab | 内容 |
| --- | --- |
| **首页** | 状态总览（模块通道 / 微信版本 / 签名匹配）、快捷操作（新建分组 / 签名配置 / 外观主题 / 检查更新）、折叠的使用说明 |
| **分组** | 分组完整管理：新建、右滑重命名、左滑删除、置顶 / 免打扰、长按拖动排序；「管理成员」复用旧版成员管理页 |
| **设置** | iOS 风格分组列表：外观与主题、显示偏好 4 开关、微信签名配置 + 强杀微信、关于 |

### 在微信里使用（全部操作都在微信界面内完成）

- 会话列表顶部出现**分组收纳行**（折叠态，点击展开/收起）。
- 收进分组：**长按聊天 → 「添加到分组」** → 选已有分组或当场新建。
- 移出分组：**长按会话 → 「移出分组「X」」**。
- 调整顺序 / 重命名 / 删除分组：**长按收纳组行** → 对应菜单项。
- 分组顺序规则：置顶优先 → 手动 `order` → 创建时间 → 名字。
- 分组归属互斥（文件夹语义）：把已在 A 组的会话加进 B 组会自动从 A 摘掉。

---

## 签名配置（按微信版本）

微信各版本会重排内部类名/方法，Hook 签名抽成一份 JSON 运行时读取。**内置 `app/src/main/assets/wechat_signatures.json` 已含微信 8.0.79（versionCode 3182）的全部实测签名**，多数用户无需任何操作——签名页状态行会显示：

```
已装微信 v8.0.79 · 内置签名 v8.0.79 · 已匹配，无需改动
```

版本不匹配时有三条路：

1. **在线获取最新**（推荐）：App 内置四级源逐个回退——① 私有 WebDAV 网盘镜像（Cloudflare 加速，国内直连最稳，凭据内置）→ ② jsDelivr CDN → ③ GitHub API contents → ④ raw.githubusercontent.com。适配新微信只需更新仓库文件，不用发新版 App。
2. **填充内置**：一键填入当前 APK 内置的签名。
3. **手动逆向**：用 jadx 之类工具按「方法名 + params + ret」三要素填写。`methodName` 为空或含 `TODO` 的条目会被自动跳过，不会崩微信。

> ⚠️ 内置签名库必须放在 **assets** 而不是 res/raw：AGP 对 release 包默认做资源路径优化（aapt2 optimize 重命名资源路径），按 `res/raw/...` 原路径读会拿到空（debug 包无此优化，容易漏测）。assets 路径恒定，微信进程里的模块 ClassLoader 与 App 进程都能稳定读到。

### 微信 8.0.79 关键混淆名对照（实测）

| 角色 | 8.0.78 | 8.0.79 |
| --- | --- | --- |
| 会话适配器（BaseAdapter + ListView 链） | `jo5/y0` | **`iq5/a1`** |
| 数据容器字段 | `adapter.q` | **`adapter.r`**（`a`=行数、`b`=SparseArray、`c`=HashMap、`d`=List<行>） |
| 行元素 | `jo5/z`（`z.d` = k4） | **`iq5/a0`**（`a0.d` = k4） |
| talker 方法 | `k4.i1()` | **`k4.f1()`**（代码里 `f1`/`i1` 双候选） |
| 长按菜单宿主 | `conversation/s3` | **`conversation/x3`** |
| 菜单容器 / 菜单项 | `kj5/i4` / `kj5/j4` | **`gl5/h4` / `gl5/i4`** |
| 菜单点击回调 | `q3.onMMMenuItemSelected` | `gl5/u4` 接口实现（运行时自动翻找） |

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
├── xp/                                 # libxposed 102 薄封装（hook/Helper/XC_MethodHook 兼容层）
├── core/
│   ├── GroupProvider.kt                # ContentProvider（fallback，多数机型被 AppsFilter 拦截）
│   ├── GroupStore.kt                   # 分组/设置/签名读写 + 跨进程镜像通道
│   ├── GroupModels.kt                  # WxGroup / WxSettings 数据类 + JSON 序列化
│   ├── GroupTheme.kt                   # 8 套主题 + 72 个单字图标 + 单组独立配色
│   ├── Updater.kt                      # 检查更新（GitHub Releases latest，零网络库）
│   ├── WeChatKiller.kt                 # 强杀微信（su 提权 force-stop，两套 UI 共用）
│   └── WeChatSignatures.kt             # 方法描述符 / 签名解析
├── util/
│   └── ReflectionUtil.kt               # 反射工具（候选名回退 / 子串搜索，版本兼容）
└── ui/
    ├── MainComposeActivity.kt          # 启动入口（Compose 版设置界面）
    ├── AppRoot.kt                      # NavigationBar 三 Tab + LargeTitle 折叠 + 页面切换
    ├── theme/Theme.kt                  # 「海盐薄荷」色彩令牌 + WxRebornTheme（深浅色）
    ├── state/AppState.kt               # AppViewModel：GroupStore 的 UI 门面（全真实数据）
    ├── components/SettingsComponents.kt# SettingsGroup/Item/SwitchItem、IconBox/GlyphBox
    ├── screens/                        # HomeScreen / GroupListScreen / SettingsScreen
    ├── SettingsActivity.kt             # 旧版 XML 设置页（保留，含导出/导入/通道修复）
    ├── GroupManageActivity.kt          # 分组管理 + 批量成员管理
    ├── SortGroupsActivity.kt           # 分组排序页（☰ 拖拽把手）
    ├── ThemeActivity.kt                # 外观与主题（8 套主题 + 单组配色）
    └── SignatureActivity.kt            # 签名配置（状态行 / 填充内置 / 在线获取 / 手动编辑）

app/src/main/assets/wechat_signatures.json   # 内置 Hook 签名（8.0.79 实测，随版本发布）
app/src/main/resources/META-INF/xposed/java_init.list  # libxposed 102 入口声明
```

### 跨进程数据通道（关键实现细节）

Xposed Hook 跑在**微信进程**里，分组数据由**模块 App 进程**创建编辑，两个进程不能直接共享内存。Android 11+（HyperOS）上常规方案**全部被 `AppsFilter` 包可见性与 SELinux 拦截**：

| 方案 | 结果 |
| --- | --- |
| `ContentProvider`（exported + forceQueryable） | `Can't resolve content provider` —— 厂商 PMS 忽略 forceQueryable |
| `createPackageContext(IGNORE_SECURITY)` | `Application package not found` —— PackageManager 级拦截 |
| `XSharedPreferences` | 读出来为空 —— 同样被 AppsFilter/SELinux 挡住 |
| 模块私有文件直接读 | `ENOENT` —— 微信进程看不到模块的数据目录 |
| `/sdcard/...` 直接读 | `EACCES` —— FUSE 不允许 |

**唯一可行的通道：`/data/local/tmp/` 下已存在的世界可写（666）文件。**

- 微信进程**能读** `/data/local/tmp/`；模块 App 进程**不能新建**文件（EACCES），但**可以覆盖已存在的世界可写文件**。
- 因此首次运行用 `su` 预创建 `wxgroup_reborn_{groups,settings}.json` 并 `chmod 666`；之后 App 每次改动都覆盖写（`Os.chmod` 免 fork），微信侧 `getGroupsXposed()` 直接读文件，并用「stamp（mtime+size）比对 + 1.2s 轻量轮询」自动重绘列表。
- `GroupProvider` 保留为 fallback；写路径带「tmp+rename 失败即退回覆盖写」兜底（`/data/local/tmp` 建不了 tmp 文件）。
- 静态签名另有「内置 assets 资源」兜底：LSPosed 把模块类与资源载入微信进程，模块 ClassLoader 直接读 `assets/wechat_signatures.json`，完全不经过 IPC，保证 Hook 一定挂得上；App 内保存的自定义签名 revision 更高时优先生效。

### 内联收纳组核心算法（折叠群聊式）

`buildModel(realCount)` 产出虚拟行表：① 分组按（置顶 → order → 创建时间）排序，每组先放一个 `TYPE_HEADER` 行；② 展开的组依次插入其成员（`placed` 去重）；③ 其余真实会话按原顺序追加。`getView/getItem` 按虚拟 position 查表，真实行交还微信原生渲染前把 `args[0]` 还原成真实 index；收纳组头部有独立 viewType，避免 ListView 复用错位。点击判定只信**被点 View 的 tag**（微信上报的 position 不可信），并用 `getPositionForView` 改写回真实位置。

---

## 构建

| 组件 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK | platform 35 + build-tools 35.0.0 |
| Gradle | 8.13（仓库带 wrapper） |
| AGP / Kotlin | 8.13.2 / 2.0.21（Compose 编译器插件随 Kotlin 版本） |
| Compose BOM | 2024.12.01 + material-icons-extended 1.7.5 |

```bash
./gradlew :app:assembleDebug     # debug 包
./gradlew :app:assembleRelease   # release 包（本地有 keystore.properties 时自动签名）
```

正式签名：优先读 gitignored 的 `keystore.properties`（storeFile/storePassword/keyAlias/keyPassword），否则回落环境变量 `KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD`（CI 经 GitHub Secrets 注入）。都没有时 release 出未签名包，构建不失败。

**CI**：推 `v*` tag → GitHub Actions 解码 keystore → 构建签名 release → 自动发 GitHub Release。

---

## 更新日志

### v1.2.1（2026-10-23）

- **修「在线获取签名 404」**：签名库 v1.0.1 迁 assets 后在线源仍指向旧 res/raw 路径。
- 在线源升级为四级回退，并新增**私有 WebDAV 网盘镜像**（r2pan，Cloudflare 加速国内直连）为第一优先源；jsDelivr / GitHub API / raw 依次兜底，失败时汇总各源原因。

### v1.2.0（2026-10-23）

- **applicationId 更名**：`io.github.wxgroup.reborn` → **`io.github.wxgroup`**（与显示名一起彻底去掉 reborn）。
  - Provider authority 同步改为 `io.github.wxgroup.groups`；代码包名（namespace）保留不动，内部实现零改动。
  - **升级路径**：直接安装新包（与旧包共存）→ 首次启动自动从 `/data/local/tmp` 镜像恢复分组与设置（签名走内置）→ 在 LSPosed 重新启用新包并勾选微信作用域 → 强停微信 → 卸载旧包 `io.github.wxgroup.reborn`。

### v1.1.0（2026-10-23）

- 应用更名：**微信分组·重生 → 微信分组**（applicationId 与数据保持不变，覆盖安装即改名，分组数据不丢）。
- README 全面重写到当前架构与用法。

### v1.0.1（2026-10-23）

- **重大修复：内置签名库移至 assets**。release 包经 aapt2 资源路径优化后 `res/raw/` 路径失效，hook 端从 v1.0.0 起一直读不到内置签名（靠 Provider 兜底）；assets 路径恒定，双进程稳定可读。
- 签名页一键获取：版本匹配状态行、「填充内置签名」、「在线获取最新」（GitHub raw，适配新微信无需发版）。
- manifest 增加 `<queries>` 解决 Android 11+ 包可见性（读取已装微信版本号）。

### v1.0.0（2026-10-23）

- **Jetpack Compose + Material 3 设置界面全面重做**：三 Tab（首页状态总览 / 分组管理 / iOS 风格设置列表）、「海盐薄荷」主题、Large Title 折叠、深浅色跟随。
- 设置界面全量接入 GroupStore 真实数据源：分组增删改序、偏好开关、主题选择全部落盘并实时同步微信进程。
- 分组页：长按拖动排序、右滑重命名、左滑删除、未读红角标、置顶/免打扰快捷开关。
- 新增检查更新（GitHub Releases latest + 语义化版本比较 + 更新弹窗）；正式签名发布流程（本地 keystore / CI Secrets 双通道）。
- 主题系统：8 套主题 + 单组独立配色 + 深浅色，取色与微信进程内渲染共用一套令牌。

### v0.1.x（2026-10-17 ~ 10-20，详见旧版 README 历史）

- libxposed API 82 → 102 迁移；适配微信 8.0.79 混淆重排（签名 revision 7）。
- 内联折叠收纳组（接管 BaseAdapter 虚拟行模型）；微信内建分组 / 批量成员管理 / 分组备份导入导出。
- 渲染热路径优化（反射缓存、未读快照、读路径零写入、原子写）；跨进程同步数据竞争修复；同步通道自检与一键 root 修复。

---

## 已知限制

- 微信版本更新频繁，内部类名/字段可能变化；版本不匹配时到签名页「在线获取最新」或手动逆向填写，填错的条目会静默跳过（见 Xposed 日志），不会崩微信。
- 模块 App 的「模块状态」用的是同步镜像通道就绪与否作为代理指标——LSPosed 是否真正激活需在 LSPosed Manager 中确认。
- 本模块仅供学习与研究，使用前请自行评估是否违反微信 ToS 及当地法律。
