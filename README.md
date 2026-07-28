# 去TM的单向好友

FuckQQ-NullFriend 是个 LSPosed 模块，干一件事：盯住你的 QQ 好友列表，谁从列表里消失了就记下来，让你知道是谁、大概什么时候没的。

QQ 自己不告诉你谁把你删了。这模块用笨办法，把每次拉到的好友列表存一份，下次再拉，比一比谁没了。就这么简单，也很粗暴。

> 重新实现检测逻辑，没搬老版「历史好友」模块那些已经失效的钩子。

## 功能

- 启动后读一次当前登录号的好友列表，存成本地基线
- 之后每次刷新都拿新列表跟基线比，只记减少的人
- 入口塞在 QQ 联系人列表最底下，跟 QNotified 的历史好友一个位置，不搞全局悬浮窗
- 面板里能搜、能筛未读、能导出文本
- 长按记录试着打开那个聊天页，看看本地会话还在不在
- 系统通知默认关着，想要自己开
- 定时检查也默认关，得先手动刷一次建好基线才能设，间隔有 30 分、1 小时、3 小时、半天
- 多账号各管各的，按 QQ 号隔离
- 界面是 ark-ui 那套，近黑底配信号青，方方正正

## 工作原理

模块跑在 QQ 进程里。每次取好友列表，存快照；下次取完跟旧的做集合差，差出来的人写进历史。取数失败不覆盖旧快照，免得误报成「全员被删」。

```mermaid
flowchart TD
    A[QQ 启动] --> B[模块注入 QQ 进程]
    B --> C{首次刷新?}
    C -->|是| D[拉取好友列表]
    D --> E[存为基线快照]
    E --> F[基线就绪]
    C -->|否| G[拉取最新好友列表]
    G --> H[与上次快照对比]
    H --> I{取数成功?}
    I -->|失败| J[保留旧快照\n不误报]
    I -->|成功| K[removed = 旧 - 新]
    K --> L[消失的人写入历史]
    L --> M[更新快照]
    M --> N{开了通知?}
    N -->|是| O[系统通知提醒]
    N -->|否| P[静默记录]
    O --> Q[联系人列表底部入口\n显示未读角标]
    P --> Q
    F --> R[定时检查循环\n按设定间隔重复 G]
    R --> G
```

## 要求

- Android 8.0 以上
- LSPosed 或兼容框架
- 作用域只勾 `com.tencent.mobileqq`，别勾别的
- QQ 以官方 9.x NT 为主，小版本升级可能要改适配

在 LSPosed 里启用模块时只勾 QQ，别勾其他应用。

## 安装

1. 装好 LSPosed，确认对 QQ 生效
2. 装本模块 APK
3. LSPosed 里启用模块，作用域只勾 QQ
4. 强制停止 QQ 再重开，这步不能省
5. 打开入口，二选一：
   - QQ 联系人页，列表最底下那个「单向好友」（推荐）
   - 桌面图标「去TM的单向好友」，点「打开 QQ 并唤起面板」
6. 面板里点立即刷新建基线，第一次不会报删除

入口看不到的话，先确认作用域勾了 QQ、模块启用了、QQ 强停过了。还不行就 `adb logcat -s FuckQQNullFriend` 看有没有 `Loading in com.tencent.mobileqq` 这行。

## 隐私

- 好友数据和历史只存本机 QQ 私有目录下的模块数据库
- 不上传，无统计，无广告
- 没法区分对方删你和你删对方，列表只认减少，界面也照实说

## 构建

```bash
# JDK 17+，Android SDK
./gradlew :app:assembleDebug
# 输出: app/build/outputs/apk/debug/
```

Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

正式签名构建：`./gradlew :app:assembleRelease`，签名配置走 `local.properties`（已 gitignore），keystore 自己生成。

## 更新日志

- v0.3.1：定时按钮标签改成「定时：关闭」这种带前缀的，一眼能认出来；桌面启动器底部加了作者和仓库跳转
- v0.3.0：整个 UI 重做成 ark-ui 风格，去掉了全局悬浮窗，入口挪到联系人列表底部（照 QNotified 抄的）；原生 ProgressBar、Spinner、AlertDialog 全换成自定义组件；定时检查加了「半天」选项，加了基线限制；补了应用图标
- v0.2.1：初版进程内面板加悬浮窗入口

## 文档

- 设计: [docs/superpowers/specs/2026-07-12-qq-friend-deletion-detector-design.md](docs/superpowers/specs/2026-07-12-qq-friend-deletion-detector-design.md)
- 实现计划: [docs/superpowers/plans/2026-07-12-fuckqq-nullfriend.md](docs/superpowers/plans/2026-07-12-fuckqq-nullfriend.md)

## 免责声明

- 仅供个人学习研究，遵守当地法律和腾讯用户协议
- Hook 官方客户端有封号、不稳定、不兼容的风险，自己担着
- QQ 升级可能让功能失效，得自己或等社区更新适配

## License

MIT，见 [LICENSE](LICENSE)。

## 致谢

思路参考了开源 QQ 模块生态，QAuxiliary 和 QNotified 那套「列表对比」的方向。本仓库是独立重写，没搬它们已经失效的钩子。
