# StudyBuddy Android 外壳

把网页版打包成一个能装在手机上的 APK。**不需要本地装 Android SDK**——构建全在 GitHub 上跑。

## 它是怎么工作的

一个极小的原生壳（就 `MainActivity.java` 一个文件），把仓库根目录的 `index.html` 装进 APK 的 assets，
再用官方的 `WebViewAssetLoader` 把它映射成虚拟域名 `https://appassets.androidplatform.net/assets/`。

这么绕一圈而不是直接读 `file://` 的原因：

- 是 **https 安全上下文**，`localStorage` 才正常工作（你的笔记全在 localStorage 里）；
- 走 AssetLoader，不需要开 `allowFileAccess`，也不依赖任何服务器；
- 因此也**不需要 TWA 那套 `assetlinks.json` 域名校验**，不用碰托管平台的隐藏目录。

结果：**完全离线可用**，飞机上也能记笔记。

## WebView 里的三个坑（已经在壳里补掉了）

网页版有三个能力浏览器默认给、WebView 默认不给。不处理的话，界面看着正常、点下去没反应：

| 能力 | 网页里的用法 | WebView 的默认行为 | 壳里的处理 |
| --- | --- | --- | --- |
| `confirm()` 确认框 | 8 处在用（清空记录、跳过专注、切换预设…） | 直接返回 `false`，等于全部静默取消 | `onJsConfirm` / `onJsAlert` 弹原生对话框 |
| 导出文件 | 统一走 `download()` 里的 Blob + `<a download>` | 不认这种下载，没反应 | 注入脚本接管 `download()`，改走系统「另存为」 |
| 导入文件 | `<input type="file">` | 不实现 `onShowFileChooser` 就没反应 | `onShowFileChooser` 起系统文件选择器 |

> 注入脚本是在页面加载完成后覆盖 `window.download` 的，**不改 `index.html` 一个字节**，
> 所以浏览器版和 APK 版共用同一份网页代码。

## 怎么构建

推一个提交到 `main` 分支，GitHub 就会自动构建（改动只涉及 `index.html`、`icons/`、`android/` 或本工作流时才会触发）。
也可以到仓库的 **Actions** 页面点 **Run workflow** 手动触发。

- **在手机上下载（推荐）**：进仓库的 **Releases** 页面，找 `apk-latest`，点里面的 `.apk` 文件直接下载。
  手动触发的那次构建会自动更新这条 Release。
- **在电脑上下载**：进 Actions 里那次运行的页面，最下方 **Artifacts** → `StudyBuddy-apk`。

## 怎么装到手机

下载到的 `.apk` 点开安装，系统会提示「允许安装未知来源应用」，同意即可
（这是 debug 签名的包，自用完全没问题）。

## ⚠️ 数据要手动搬一次

**APK 里的存储和桌面浏览器是两套完全独立的空间。** 装上打开会是一个空应用——你的笔记不会自己跟过去。

搬法：

1. 在桌面浏览器打开 StudyBuddy → 菜单里 **导出备份**（会下载一个 `studybuddy-日期.json`）
2. 把这个 JSON 传到手机（微信传文件 / 数据线 / 网盘都行）
3. 手机 App 里点 **导入**，选中那个文件
4. 看到「导入成功」就完成了

> 别把这个 JSON 提交进仓库——里面是你的全部笔记。仓库如果是公开的，等于公开你的笔记。

## 改代码时要注意的

- **网页只维护一份**：仓库根目录的 `index.html`。构建时会自动复制进 `android/app/src/main/assets/`，
  不用手动同步（那份副本也没提交进 git）。
- **APK 版本号自动跟随** `index.html` 标题里的 `v4.1.0`，不用手改。
- **版本组合别单独动**：`AGP 8.5.2` + `Gradle 8.7` + `JDK 17` + `compileSdk 34`，
  换一个就要一起换，否则构建直接失败。
- 要上架应用商店，才需要补 `release` 签名（keystore 放 GitHub Secrets，用 base64 存）——自用不需要。

## 出问题了看哪里

构建失败时，Actions 里那一步的日志会直接指出原因，常见两类：

- **Gradle / AGP 版本不匹配** → 报错里会说 minimum supported Gradle version，按上面那组版本对齐；
- **Android SDK 组件缺失** → 报错里会说 failed to find target with hash string，看看 `compileSdk` 是不是被改过。
