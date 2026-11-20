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

## ESLyric 歌词源

歌词搜索脚本来自 <https://github.com/ESLyric/scripts>。<https://github.com/ESLyric/release> 是 foobar2000 插件发行仓库，不是歌词搜索脚本仓库；ezXLRC 不从该仓库获取插件或界面资源。

首次启动和手动更新时，应用读取 `ESLyric/scripts` 的 `archive/version.json`，只下载清单 `sources` 对应的 `searcher/*.js`，并使用 ETag 缓存。不会下载 `parser/`、`parser_ext.so`、插件二进制或上游依赖包。搜索脚本保存在应用私有目录；首次启动需要联网，脚本缓存后可离线打开、编辑和转换歌词，在线搜索仍需要网络。

Android 端自行实现搜索器所需的请求桥接和兼容接口，包括 querystring、CryptoES 所需的 MD5/AES-ECB、Himalaya HTML 解析、MXML XML 读取，以及 MiniLyrics 的二进制请求。QQ 音乐脚本下载后会定点修正歌曲字段错位和 UTF-8 Base64 参数。

`ESLyric/scripts` 没有仓库级 LICENSE。本项目不把上游脚本复制进 MIT 源码包，也不声称这些脚本由本项目授权；应用运行时按上游清单下载，脚本版权及适用条款仍归各自作者。用户提供的旧版 ESLyric 压缩包只用于核对历史接口，没有打包进应用。

QRC 解码逻辑参考 <https://github.com/WXRIW/QQMusicDecoder>，本项目使用 Kotlin/JCE 独立实现。第三方说明见 `THIRD_PARTY_NOTICES.md`。

## 构建

需要 JDK 17、Gradle 8.13、Android SDK 36。

```bash
gradle --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions 在推送到 `main` 或手动触发时检查 Android XML、JavaScript 运行时和上游搜索器依赖，运行桥接冒烟测试、Kotlin 单元测试和 Android Lint，随后构建并验证 `iqonli-1` 签名的 Release APK；APK 与诊断报告保留 3 天。

## 许可证

本项目自行编写的代码使用 MIT License，见 `LICENSE`。运行时下载的第三方歌词源脚本不包含在本项目的 MIT 授权范围内。

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
