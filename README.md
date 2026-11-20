# ezXLRC

Android 原生歌词搜索与 LRC / XLRC 处理工具。Kotlin + Material Components 2，界面采用 ezCD 的蓝色配色和简洁卡片布局；图标沿用 ezCD 的矢量字形绘制方式，将字样改为 ezXL。

## 功能

- 从 ESLyric/scripts 歌词源搜索 LRC 与 XLRC，支持单个来源和全部来源。
- 搜索结果显示来源、格式、歌曲信息和歌词预览，可直接载入编辑器。
- KRC、QRC 结果在本地解码为 XLRC；普通 LRC 保留原格式。
- 打开、编辑、复制和保存本地歌词。
- 纯歌词转 LRC：按可设置的固定秒数生成时间码，默认 5 秒。
- LRC 转 XLRC：在当前歌词行与下一条时间码之间均匀分配逐字时间；最后一行默认按 4 秒处理。这是自动估时，不会还原真实演唱时间。
- XLRC 转普通 LRC、递进 LRC、单词 LRC；支持导出分词文本和导入自定义分词。
- 应用 [offset:...] 到主时间码和逐字时间码。
- 酷我双语 LRC 修复：将独立时间码行相对歌词行整体上移或下移一行，连续时间码行按一个区块移动。
- 删除重复时间码组中的第 N 行；按每行开头的第一个时间码分组，N 从 1 开始。

## ESLyric 歌词源

歌词搜索脚本来自 https://github.com/ESLyric/scripts 。https://github.com/ESLyric/release 是 foobar2000 插件发行仓库，不是歌词搜索脚本仓库，因此本项目不从该仓库获取插件或界面资源。

首次启动和手动更新时，应用读取 ESLyric/scripts 的 archive/version.json，只下载清单中 sources 对应的 searcher/*.js，并使用 ETag 缓存。不会下载 parser/、parser_ext.so、插件二进制或上游依赖包。搜索脚本在应用私有目录缓存；离线时可继续使用已缓存脚本。

Android 端自行实现 ESLyric 搜索器所需的请求桥接和兼容接口，包括 querystring、CryptoES 的 MD5/AES-ECB、Himalaya HTML 解析、MXML XML 读取，以及 MiniLyrics 的二进制请求。QQ 音乐脚本的歌曲字段错位和 UTF-8 Base64 参数在下载后做定点修正。

ESLyric/scripts 没有仓库级 LICENSE。本项目不把上游脚本复制进 MIT 源码包，也不声称这些脚本由本项目授权；应用运行时按上游清单下载，脚本版权及适用条款仍归各自作者。旧版 ESLyric 压缩包只用于核对历史接口，没有打包进应用。

QRC 解码逻辑参考 https://github.com/WXRIW/QQMusicDecoder ，本项目使用 Kotlin/JCE 独立实现。第三方说明见 THIRD_PARTY_NOTICES.md。

## 构建

需要 JDK 17、Gradle 8.13、Android SDK 36。

    gradle --no-daemon testDebugUnitTest lintDebug assembleDebug

GitHub Actions 在推送到 main 或手动触发时执行单元测试、Android Lint 和 Debug APK 构建。APK 作为 workflow artifact 保留 3 天。

## 许可证

本项目自行编写的代码使用 MIT License，见 LICENSE。运行时下载的第三方歌词源脚本不包含在本项目的 MIT 授权范围内。
