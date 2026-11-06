# GIS内检机器人 App（网页套壳）

一个"点开就是控制画面"的 Android App：启动 → 直接全屏打开 `http://172.20.10.10:5000`。
没有地址栏、没有标签页，也不需要输入网址。

**使用场景**：手机开热点，平板和机器人同时连这个热点。
机器人还没开机时网页当然打不开，所以 App 的重心是**等待 + 自动重试**，而不是启动页好看。

---

## 一、没有 Android Studio，怎么拿到 APK

工程里带了一条 GitHub Actions 流水线（`.github/workflows/build-apk.yml`），
把代码传到 GitHub，云端自动编译出 APK，你只要下载安装。**全程不用装任何开发环境。**

### 路线 A：网页上传，拿到 APK（推荐，5 分钟一次）

1. 打开 https://github.com ，注册/登录，右上角 `+` → **New repository**
   - 名字建议 `gis-robot-app`（ASCII 名，URL 干净）
   - 选 **Private** 或 **Public** 都行（Private 每月也有 2000 分钟免费额度，这个项目一次编译 2～3 分钟）
   - **不要**勾 `Add a README`（工程自带）→ **Create repository**
2. 进入仓库 → **Add file → Upload files**
   把 `D:\huang\Project\app开发\app代码\` 里的内容**整个拖进去**（要包含 `app`、`gradle` 这些子目录，保持目录结构）
   → 页面底部 **Commit changes**
   > 拖拽有时会漏掉以 `.` 开头的隐藏目录，所以第 3 步单独建。
3. 仓库页 → **Add file → Create new file**
   文件名直接输入：`.github/workflows/build-apk.yml`（输入 `/` 会自动建目录）
   把本工程里 `.github/workflows/build-apk.yml` 的内容整段粘进去 → **Commit changes**
4. 点仓库上方的 **Actions** 标签 → 等 2～5 分钟，看到绿色 ✓
5. 点进这次运行 → 页面底部 **Artifacts** → 下载 `gis-robot-app-apk.zip` → 解压得到 **`app-debug.apk`**
6. 把这个 apk 传到平板（微信/QQ/数据线/网盘都行）→ 点击安装
   - 首次要允许"安装未知来源应用"
   - 国产 ROM 可能还要在"安全中心 → 安装外部来源应用"里给文件管理器放行
   - 若平板上已装过**另一个签名**的旧版，需先卸载再装

### 路线 B：拿一个手机能直接点开的下载链接（更省事）

代码传好之后：仓库 → **Releases** → **Draft a new release** → Tag 填 `v1.0` → **Publish release**。

流水线会在打 tag 时自动编译，并把 APK 挂到这个 Release 页面上。
以后在平板上打开这个页面，直接点 `app-debug.apk` 就能下载安装，不用再走 Artifacts。

### 路线 C：有 git 的话

```bash
git init && git add -A && git commit -m "GIS inspection robot app"
git remote add origin https://github.com/<你的账号>/gis-robot-app.git
git push -u origin main
```

> 把 `gis-robot-app` 换成你实际建的仓库名即可。

### 上传后自检（30 秒）

仓库首页根目录应该能看到这些（不要求顺序，要求**存在**）：

```
仓库首页
├── settings.gradle.kts        ← 有
├── build.gradle.kts           ← 有
├── gradle.properties          ← 有
├── app/                       ← 有，点进去还有 build.gradle.kts 和 src/
└── .github/workflows/build-apk.yml   ← 有，没有的话 Actions 不会自动跑
```

- 少 `settings.gradle.kts` 或 `app/`：说明拖拽时漏了，重新 `Upload files` 补上。
- 首页只有一个 `app开发` 文件夹：说明整个文件夹被拖进去了。**也能编译**（流水线会自动找工程根目录），但下次重传建议传"文件夹里的内容"。
- 看不到 `.github`：点开 Actions 标签是空的，就是没建这个文件，回到路线 A 第 3 步。

### 关于"每次编译签名不一样"

签名不一致会导致新 APK 装不上旧版本（报"应用未安装"）。
流水线里已经用 `actions/cache` 把 debug keystore 缓存住了，所以正常情况可以**覆盖升级**。
万一缓存被清掉（90 天没跑过流水线），卸载旧版再装新版即可 —— App 里除了地址没有别的数据，卸载不心疼。

---

## 二、打开 App 之后看到什么

```
点桌面图标
   ↓
黑底 + 转圈 +「正在连接 http://172.20.10.10:5000」
（下面还写着"机器人刚开机可能要等 60~120 秒"）
   ↓
├─ 连上了 ──→ 自动进入控制画面，遮罩消失，弹一句"已连接机器人"
│
└─ 12 秒还没响应 ──→ 显示失败原因 +「已尝试 N 次」
                     并且每 3 秒自动重试一次（无限重试，直到连上）
                     ↑ 机器人开机过程中你可以把平板放着不管，
                       它自己会连上，不需要一直点刷新
```

**手动刷新有两个入口**（这就是你说的"浏览器上那个刷新"）：

- 遮罩上的大按钮「刷新」
- **音量下键**（页面正常显示时也能用，不占屏幕空间）

> 为什么不做一个常驻的悬浮刷新按钮：它会压住页面右下角的控制按钮，
> 而误触一个盖在"升降/云台"上的浮动按钮是有风险的。所以刷新只在"连不上"的时候出现。

### 其他操作

| 操作 | 作用 |
|---|---|
| 音量上键 | 打开地址设置（换 IP、换端口，保存后立即刷新，不用重新打包） |
| 音量下键 | 刷新页面 |
| 返回键 | 页面能后退就后退；不能则需**连按两次**才退出（防误触退出导致失控） |
| 退出 App | 请先在页面里点"停止" |

> 这两个键是被 App 截获的，所以 App 内音量键不调音量（控制类 App 一般不需要）。
> 页面正在播视频时也能按，因为截获点在 `dispatchKeyEvent`。

### 画面放大模式

页面加载完成后，屏幕左侧会出现一个**半透明 ⊕ 圆钮**（35% 透明度，可拖到任意位置，位置会记住）。

点它 → **左侧整块变成可缩放画布**：视频铺满左侧（默认按"铺满"显示，会裁掉一点边），
右侧云台/风机/升降/停止那块**完全不动、照常可点**。

| 手势 | 结果 |
|---|---|
| 双指捏合 | **连续**缩放，1× ～ 12×（不是固定档位） |
| 单指拖动 | 平移画面 |
| 双击 | 复位到"铺满左侧" |
| 点 `×`（画布右上角） | 退出放大模式，回到原页面 |
| 外接键盘 `Esc` | 同上 |

左下角常驻显示当前倍率和识别到的画面元素，例如 `1.8×  ·  img 1280×720`。

**实现原理**：App 在页面加载完成后往 WebView 注入 `assets/magnifier.js`。
它往页面**追加**一个覆盖层（`pointer-events:none`，所以右侧照常响应点击）+ 一块 `canvas`，
自己 `drawImage` 视频元素并做缩放。**不改页面原有 DOM、不改布局、不重排**，
所以页面控件位置一根毫毛都不会动。

画布范围 = 屏幕左边 → 视频右边缘，整屏高度。也就是放大时左侧那块（含顶部标题、底部那排绿色按钮）
会被放大画面盖住，点 `×` 立刻恢复。

**自动识别画面元素**是启发式的（取面积最大的 `video`/`img`）。如果左下角显示
`未找到画面元素` 或者画面内容是错的，把左下角那行文字截图发我，我按你的页面结构调整。

**倍率是真的在放大像素**：源流是 1080p，放到 4 倍以上只是插值变大，不会多出细节。

**打激光点要先退出放大模式**：放大模式下这块区域被画布接管，页面上的点选/打点是用不了的。

**不想要这个按钮**：音量上键 → 取消勾选「显示画面放大按钮」。

### 拍照 / 录像保存到相册

**这个问题跟浏览器不一样，必须由 App 自己接管。**

Chrome 里页面点"拍照"会直接把文件下载到相册，但 WebView 是**没有下载行为**的：
`Content-Disposition` 之类的响应会被静默丢掉，页面用 `blob:` 生成文件更连回调都没有。

工程里的处理（`Downloads.kt` + `assets/downloads.js`）：

| 页面的做法 | App 怎么接 |
|---|---|
| 服务器直接返回文件（`http://…/photo`） | `DownloadListener` 接住 → 后台线程下载 |
| 页面用 `blob:` 生成（MediaRecorder 录像就是这种） | 注入 JS 把它读出来，**分块**（256KB）通过 JS 桥推给 App |
| `canvas.toDataURL()` 的 `data:` URL | 同上，一次推完 |
| 用户直接点 `<a download href="blob:…">` | JS 捕获阶段拦截，`preventDefault` 后走上面那条 |

文件落到（保存成功会弹 Toast「已保存到相册：xxx」）：

| 类型 | 位置 |
|---|---|
| 图片 | `Pictures/GIS内检机器人/` |
| 视频 | `Movies/GIS内检机器人/` |
| 其它 | `Download/GIS内检机器人/` |

> 为什么照片和视频分两个目录：MediaStore 对每个集合的顶层目录有白名单——
> 图片只允许 `DCIM`/`Pictures`，视频只允许 `DCIM`/`Movies`。
> 按系统惯例分别放 `Pictures` 和 `Movies`，代价是图库的“相册/文件夹”视图里会看到两个文件夹。
> （如果希望照片和视频躺在同一个文件夹，可把两处都改成 `DCIM/…`——`DCIM` 是两边都合法的唯一顶层目录。）
> 要改目录就改 `Downloads.kt` 里的 `ALBUM` 常量以及 `saveModern` / `saveLegacy` 两个分支。

Android 10+ 走 `MediaStore`（不需要任何权限，图库直接能看到）；
Android 9 及以下写公共目录并触发媒体扫描，第一次会弹存储权限。

**排查用**：`adb logcat -s RobotWeb` 能看到 `开始下载: …`、`分块保存开始`、`已保存: content://…` 这些日志。
如果点了拍照没反应，先看日志里有没有这些行——没有的话说明页面用的方式不在上表里，告诉我它怎么触发的。

---

## 三、版本管理（旧版怎么留、新版怎么发）

GitHub 上的版本靠两样东西保存，**都不会过期**：

| 手段 | 保存什么 | 在哪看 |
|---|---|---|
| **commit 历史** | 每一版代码 | Code 页面 → 提交记录；切到某个 tag 就能看那版代码 |
| **tag + Release** | 一版代码 + 那一版的 APK | Releases 页面，每个版本一页 |

> 注意：Actions 里的 **Artifacts 只保留 90 天**，所以「正式版本」一定要发 Release，
> APK 才会永久挂在版本页上。

### 发布一个正式版本（3 步）

1. 改 `app/build.gradle.kts`：
   - `versionCode` **+1**（必须比上一版大，否则平板上覆盖安装会被系统拒绝）
   - `versionName` 改成和 tag 一致，例如 `2.0`
2. 把代码提交到 main（网页上传或改文件都行），等 Actions 变绿
3. 仓库 → **Releases** → **Draft a new release**
   - **Choose a tag** → 输入 `v2.0` → **Create new tag on publish**
   - **Target** 选 `main`
   - 标题写 `v2.0 画面放大模式`，说明里写这版改了什么
   - **Publish release**

发布后 Actions 会再跑一次（因为打了 tag），跑完 APK 就挂在这个 Release 页面下面，
平板上打开该页面直接点 `app-debug.apk` 下载安装。

### 回到旧版本

- 想下**旧 APK**：Releases → 点进 `v1.0` → 下载里面的 apk
- 想下**旧代码**：Code 页面左上角的分支选择器 → 切到 tag `v1.0` →
  右上 **Code** 按钮 → **Download ZIP**
- Release 页面还自带 "Source code (zip)"，等于代码存档

### ⚠️ 两个版本不能同时装在平板上

同签名 + 同 `applicationId` 的 App 只能存在一个，装 v2 会覆盖 v1。
如果要在平板上**新旧并存**对比，必须把 `applicationId` 改掉（例如
`com.robotctrl.web.v2`），这样系统会当成两个不同的 App。需要的话跟我说，我来加。

---

## 四、必须处理的 5 个坑（工程里都已处理）

### 1. 明文 http：Android 9+ 默认直接拦掉

页面是 `http://`（不是 https），而 targetSdk ≥ 28 的应用默认**禁止明文 http**，
拦截时 WebView 里报 `net::ERR_CLEARTEXT_NOT_PERMITTED`，表现就是打不开、还不明显。

处理：`AndroidManifest.xml` 里 `android:usesCleartextTraffic="true"`。

> 代码里还留着"只对内网放行自签名证书"的逻辑（`onReceivedSslError`）。
> 现在用 http 用不上它，但哪天机器人换成 https 自签证书，不用改代码就自动生效。

### 2. 手机热点没有"互联网" → 请求被丢到移动数据

平板连着热点，但热点没有外网时，Android 会判定这个 Wi-Fi"不可用"，
把 App 的请求切到移动数据，于是局域网里的机器人永远连不上。
（浏览器里手动打开又是好的，特别迷惑。）

处理：`registerNetworkCallback(WIFI)` + `bindProcessToNetwork()`，把**本进程**的默认网络强制绑到 Wi-Fi。
只影响这个 App，不动系统设置。连上后会立刻重新加载一次。

### 3. 视频流不自动播放

`mediaPlaybackRequiresUserGesture = false`，否则 `<video>` 必须用户点一下才开始播。

### 4. 连接可能"既不成功也不失败"（挂住）

机器人没开机时，TCP 连接可能挂很久才超时，期间页面一片空白，用户不知道在等什么。
处理：自己加 **12 秒超时兜底** → 立刻显示失败界面 + 开始自动重试。

### 5. 息屏 / 切后台

`FLAG_KEEP_SCREEN_ON` 保证操作中不息屏；并且**故意不调用** `webView.onPause()`，
否则 JS 定时器和视频解码会被冻结，回到前台时界面显示的机器人状态是过期的 —— 拿这个去操作云台很危险。

---

## 五、工程结构

```
app开发/
├── .github/workflows/build-apk.yml   # 云编译：推送即出 APK，打 tag 自动发 Release
├── settings.gradle.kts / build.gradle.kts / gradle.properties
└── app/
    ├── build.gradle.kts              # minSdk 24 (Android 7.0) / targetSdk 34 / 零第三方依赖
    └── src/main/
        ├── AndroidManifest.xml       # 横屏、权限、cleartext、全屏主题
        ├── java/com/robotctrl/web/MainActivity.kt   # 主逻辑（约 620 行，逐段注释）
        ├── java/com/robotctrl/web/Downloads.kt      # 拍照/录像保存到相册
        ├── assets/magnifier.js                      # 画面放大模式（注入到页面里的一段 JS）
        ├── assets/downloads.js                      # 下载接管（注入到页面里的一段 JS）
        └── res/
            ├── layout/activity_main.xml   # WebView + 连接遮罩（转圈 + 刷新按钮）
            ├── layout/dialog_settings.xml # 改地址对话框
            ├── values/strings.xml         # 所有文案都在这，改字不用碰代码
            ├── values/themes.xml          # 全屏无标题栏主题
            └── drawable/ic_app.xml        # 占位图标
```

刻意做成**零第三方依赖**（不用 AppCompat / AndroidX），少一个出错的地方，APK 也小。

### 常见的改动位置

| 想改什么 | 改哪 |
|---|---|
| 默认网址 | `MainActivity.kt` 里的 `DEFAULT_URL` |
| App 名字（桌面显示） | `res/values/strings.xml` 的 `app_name` |
| 桌面图标 | 换掉 `res/drawable/ic_app.xml` |
| 重试间隔 / 超时时间 | `MainActivity.kt` 顶部的 `RETRY_DELAY_MS` / `LOAD_TIMEOUT_MS` |
| 横屏还是竖屏 | `AndroidManifest.xml` 里的 `screenOrientation` |
| 版本号 | `app/build.gradle.kts` 里的 `versionCode`（每次发新版 +1）和 `versionName` |
| 提示文字 | `res/values/strings.xml` |

---

## 六、以后可以加（按优先级）

| 优先级 | 功能 | 说明 |
|---|---|---|
| P0 | **机器人侧心跳看门狗** | App/网页每 500ms 发心跳，2 秒收不到就硬件停车。App 被杀时"退出时发停止指令"是发不出去的 |
| P1 | 证书指纹钉死 | 把"内网就放行"升级成"只认这一张证书" |
| P1 | 自动记住上次连接 | 连上后记住，下次启动先试上次的地址 |
| P2 | 悬浮急停按钮 | App 层一个永远能点的"停"，代价是遮住页面一角 |
| P3 | 多机器人切换 | 地址列表 + 二维码扫码自动填 IP |
| P3 | kiosk 模式 | Device Owner + Lock Task，把平板锁成专用遥控器 |

---

## 七、装的时候遇到问题

| 现象 | 原因 / 处理 |
|---|---|
| 安装时报"应用未安装" | 手机上已有旧版且签名不同。先卸载旧版再装（签名的坑见第一节） |
| 打开就白屏，没任何提示 | 理论上不会：连不上会显示失败原因。若真白屏，`adb logcat -s RobotWeb` 看日志 |
| 一直显示"已尝试 N 次" | 机器人没开机 / 平板没连上手机热点 / 机器人 IP 变了（按音量上键改） |
| 连上了但中间画面是黑的 | 视频流没出来。按音量下键刷新；仍不行就是网页侧问题（可在电脑 Chrome 里打开同一地址对比） |
| App 名/图标不对 | 改 `strings.xml` 的 `app_name` 和 `drawable/ic_app.xml`，重新编译 |

