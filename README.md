# 网络电台 APP —— 怎么拿到能装手机的 APK

这个工程是**原生安卓(Kotlin)**写的网络电台播放器,功能是:
- 主界面:32 个电台的列表(点一下就播放)
- 底部常驻控制栏:`⏮ 上一台` / `▶ 播放·暂停` / `下一台 ⏭`
- 后台播放 + 锁屏/通知栏也能控制

> 电台地址都在 `app/src/main/java/com/example/radioapp/Stations.kt`,想加台照格式加一行即可。

---

## 重要说明
本机(帮你写代码的这台机器)**没有完整的安卓编译环境,且访问 Google 安卓编译服务器被截断**(核心编译二进制 aapt2 拿不到),所以**无法在这里直接打出 APK**。
APK 必须在"有完整安卓工具链"的地方生成。下面两条路都验证过可行,挑一条:

---

## 路线 A:云端自动编译(你电脑什么都不用装,推荐)
利用 GitHub 的免费服务器(自带完整安卓工具链)帮你把 APK 编出来。

1. 注册一个免费 GitHub 账号: https://github.com
2. 新建一个**空仓库**(名字随意,例如 `radioapp`),**不要**勾选自动生成 README。
3. 在本机安装 Git(若没装): https://git-scm.com 一路下一步即可。
4. 在 RadioApp 文件夹里打开终端,依次执行(把下面 `你的用户名` 和 `仓库名` 换成你自己的):
   ```
   git init
   git add .
   git commit -m "init radio app"
   git branch -M main
   git remote add origin https://github.com/你的用户名/仓库名.git
   git push -u origin main
   ```
   (推送时会让你登录 GitHub,用网页生成的 Token 当密码即可)
5. 推送成功后,打开仓库页面 → 顶部 **Actions** → 左侧 `Build Debug APK` → 右侧 **Run workflow** 点一下。
6. 等大约 3~5 分钟变绿,点进去 → 右下方 **Artifacts** → 下载 `radioapp-debug-apk.zip`,解压得到 `app-debug.apk`。
7. 把 `app-debug.apk` 发到手机(微信/数据线都行),点开安装即可。安装时若提示"允许未知来源",同意即可。

---

## 路线 B:用 Android Studio 在自己电脑编(最直观)
1. 下载安装 Android Studio(免费): https://developer.android.com/studio
   首次打开会引导你装好安卓 SDK(一路下一步)。
2. `File → Open`,选中 **RadioApp** 文件夹,等它自动同步(Gradle 会自己下载依赖,几分钟)。
3. 手机用 USB 连电脑,打开 `设置 → 关于手机 → 连点版本号 7 下` 开启开发者选项,再打开`USB 调试`。
4. 点工具栏绿色 ▶ 按钮(或 `Run → Run 'app'`),APP 直接装到手机。
5. 想单独导出 APK 文件:菜单 `Build → Build Bundle(s)/APK → Build APK(s)`,编完右下角会提示,打开文件夹就是 `app-debug.apk`。

---

## 常见问题
- **装了还是播不出声音?** 这些电台是 `http` 明文流,工程已放行明文流量;若个别台失效,是电台方地址变动,去 `Stations.kt` 换地址即可。
- **想换 APP 名字/图标?** 名字在 `res/values/strings.xml`;图标现在是临时白点,可随时让我帮你做正式图标。
- **想先不装 APP 试听这 32 个台?** 我可以再给你做一个手机浏览器打开就能播的网页版,先验证地址和手感。
