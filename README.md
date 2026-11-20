# ezXLRC

GitHub 仓库根目录保存完整 Android 工程源码。推送到 `main` 后，Actions 会执行源码检查、单元测试、Android Lint，构建并验证使用 `iqonli-1` 签名的 Release APK。

Android 原生歌词搜索与 LRC / XLRC 编辑工具。Kotlin + Material Components 2，采用 ezCD 的蓝色配色、卡片和简洁排版；图标沿用 ezCD 的矢量字形画法，将字样绘制为 **ezXL**。

## 功能

- 从 ESLyric/scripts 搜索 LRC 与 XLRC，支持单个来源和全部来源。当前上游清单包含 27 个搜索器，清单变化后应用会自动同步。
- 搜索结果显示来源、格式、歌曲信息和歌词预览，可直接载入编辑器。
- QQ 音乐 QRC、酷狗 KRC 在 Android 本地解码为 XLRC，保留逐字时间；KRC 双语翻译行一并保留。
- 打开、编辑、复制和保存本地歌词；导入支持 UTF-8、UTF-8 BOM、UTF-16 BOM 和 GB18030。
- 纯歌词转 LRC：按可设置的固定秒数生成时间码，默认 5 秒。
- LRC 转 XLRC：在当前歌词行与下一条时间码之间均匀分配逐字时间；最后一行默认按 4 秒处理。这是自动估时，不会还原真实演唱时间。
- XLRC 转普通 LRC、递进 LRC、单词 LRC；支持生成分词文本、导入自定义分词文件并转换。
- 应用 `[offset:...]` 到主时间码和逐字时间码。
- 酷我双语 LRC 修复：将独立时间码行相对歌词行整体上移或下移一行，连续时间码行作为一个区块移动；空行和元数据行不计入歌词行。
- 删除重复时间码组中的第 N 行；按每行开头的第一个时间码分组，时间格式不同但实际时间相同的行也会归为一组，N 从 1 开始。
- 时间标签支持 `mm:ss.xx` 和 `hh:mm:ss.xx`。

## ESLyric 歌词源与 JavaScript 运行时

应用内置 30 个歌词搜索器：27 个来自官方 [ESLyric/scripts](https://github.com/ESLyric/scripts)，固定在提交 `32244ac30ffb8f6fd9ff6d03a1dcce389b5c1600%；另外 3 个是从 Library 中的 `eslscripts.zip` 提取的酷我、网易云音乐 Ex 和 QQ 音乐 Ex 搜索器。旧压缩包中与官方脚本重复的四个旧版本由官方版本替代。

所有脚本都随源码和 APK 一起打包。应用启动时从 assets 读取脚本清单和脚本文件；没有脚本更新按钮，也不会在运行时访问 GitHub 下载脚本。在线搜索仍会向对应音乐服务发送歌词查询请求，离线时可以继续打开、编辑、转换和保存本地歌词。要更新搜索器，需要更新仓库内的脚本资源并重新构建 APK。

JavaScript 由嵌入式 QuickJS 执行，主界面、搜索结果、编辑器和修复工具均使用 Kotlin 与 Material Components 2 原生控件。项目不使用 WebView。Android 端通过 Kotlin 提供 HTTP 请求、AES-ECB、MD5、HTML/XML 解析和歌词数据桥接；HTML/XML 树由 Jsoup 解析，KRC/QRC/YRC 内容由应用内的 Kotlin 解码器处理。

旧压缩包中的 `parser_ext.so` 是 PE32+ Windows x64 DLL，不能用于 Android，因此没有打包。旧版 parser JS 不参与运行，相关格式由原生解码器处理。三个保留的旧搜索器位于 `app/src/main/assets/eslyric/library-searchers.zip`；清单记录了原始压缩包 SHA-256 和实际打包内容 SHA-256。

ESLyric/scripts 仓库没有仓库级 LICENSE。脚本原有作者与来源注释会保留，不能将它们视为本项目 MIT 代码。Library 中的 `netease_ex.js` 和 `qqmusic_ex.js` 标注 GPL-3.0；`kuwo.js` 没有附带明确许可证声明。第三方代码及许可情况见 `THIRD_PARTY_NOTICES.md` 和 `LICENSES/`。在公开发布包含这些搜索器的二进制包前，应确认相应作者的再分发许可。

## 构建

需要 JDK 17、Gradle 8.13、Android SDK 36。

```bash
gradle --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions 在推送到 `main` 或手动触发时检查 Android XML、内置 JavaScript 资源和脚本清单，运行 QuickJS 兼容层冒烟测试、Kotlin 单元测试和 Android Lint，随后构建并验证 `iqonli-1` 签名的 Release APK；构建过程不下载歌词脚本，APK 与诊断报告保留 3 天。

## 许可证

本项目自行编写的代码使用 MIT License，见 `LICENSE`。随 APK 打包的 ESLyric 搜索器及 Library 旧脚本按各自来源和许可证处理，不属于本项目 MIT 授权范围。

## Termux 本地构建与签名

本地编译需要 JDK 17、Gradle 8.13、Android SDK 36 和 Build Tools 36.0.0。首次运行 `termux-push-build.sh` 时，脚本会从手机 Download 目录导入 `iqonli-1-private-signing.zip`，将 JKS 和本地密码配置保存到 `$HOME/.config/ezxlrc/`，权限设为仅当前用户可读写。之后本地构建可直接运行：

```sh
bash termux-build.sh
```

本地构建会检查 APK 的签名证书 SHA-256 是否与 `iqonli-1` 一致。没有完整 Android SDK 时，直接使用下面的 GitHub Actions 一键构建。

## GitHub Actions 签名

Workflow 使用 `MCX_KEYSTORE_B64`、`MCX_STORE_PASSWORD`、`MCX_KEY_PASSWORD` 三个 GitHub Actions Secrets，alias 固定为 `iqonli-1`，并校验证书 SHA-256：

`4c281c59b8e1e8a9fb5b16020540128dcca608329255609ce9881fd9b75d12e1`

首次运行 Termux 推送脚本时，会从密钥备份包读取 JKS 和密码，通过 `gh secret set` 将它们加密写入 `iqonli/ezXLRC` 的 Actions Secrets。密钥文件和明文密码保存在 Termux 的 `$HOME/.config/ezxlrc/`，不会复制到工程目录或提交到 GitHub 仓库。脚本在推送前还会再次扫描并排除 JKS、P12、密码文件和签名配置。

## Termux 一键推送与构建

将 `ezXLRC-Termux-ready.zip` 和 `iqonli-1-private-signing.zip` 放进手机 Download 目录。解压工程后，在 Termux 中进入 `ezxlrc` 目录，运行：

```sh
bash termux-push-build.sh
```

脚本会自动安装缺少的 Termux 命令行依赖、引导 GitHub 登录、导入本地签名配置、设置仓库 Actions Secrets、提交并推送源码、等待构建完成，再将已签名 APK 和 SHA-256 文件下载到 Download 目录。无需手动打开 GitHub 设置 Secrets，也无需在命令行输入签名密码。

首次使用仍需要完成一次 GitHub 浏览器授权，并允许 Termux 访问手机存储。GitHub CLI 账号需要拥有 `iqonli/ezXLRC` 的仓库写入权限及 Actions Secrets 管理权限。

如果密钥包不在默认 Download 目录，可以把完整路径作为第一个参数：

```sh
bash termux-push-build.sh /sdcard/Download/iqonli-1-private-signing.zip
```
