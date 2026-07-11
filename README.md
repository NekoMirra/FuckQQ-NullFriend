# 去TM的单向好友

**FuckQQ-NullFriend** — 检测 QQ 好友列表中「消失」的联系人（单向/被删感知）的 LSPosed 模块。

> 列表里没了？至少让你知道是谁、大概什么时候没的。  
> 本项目**重新实现**检测逻辑，不依赖已失效的旧版「历史好友」模块实现。

## 功能

- 启动后（可延迟）读取当前登录号好友列表，建立本地基线
- 与上次快照对比，**只记录减少项**
- 在 **QQ 内嵌页面**查看历史（模块无桌面图标）
- 长按记录尝试打开对应聊天页（查看本地会话，若仍存在）
- 可选系统通知（**默认关闭**）
- 可选定时刷新（默认关闭）
- **多账号**：按 QQ 号隔离快照与历史

## 要求

| 项 | 说明 |
|----|------|
| 系统 | Android 8.0+ |
| 框架 | [LSPosed](https://github.com/LSPosed/LSPosed)（或兼容实现） |
| 作用域 | **仅** `com.tencent.mobileqq`（官方 QQ） |
| QQ 版本 | 以当前官方 **9.x NT** 为主；小版本升级可能需更新适配 |

在 LSPosed 中启用本模块时，请**只勾选 QQ**，不要勾选其他应用。

## 安装

1. 安装 LSPosed，确保对 QQ 生效  
2. 安装本模块 APK  
3. 在 LSPosed 中启用模块，作用域仅勾选 **QQ**（`com.tencent.mobileqq`）  
4. **强制停止 QQ** 后重新打开（必须）  
5. 打开入口（任选其一）：
   - **QQ 主界面右下角**橙色按钮 **「单向好友」**（推荐）
   - 桌面图标 **「去TM的单向好友」** → 点「打开 QQ 并唤起面板」
   - QQ 设置/关于类页面上的悬浮入口  
6. 在面板中点 **立即刷新** 建立基线（首次**不会**报删除）

若完全看不到入口：确认 LSPosed 作用域已勾选 QQ、模块已启用、已强停 QQ；用 `adb logcat -s FuckQQNullFriend` 查看是否有 `Loading in com.tencent.mobileqq`。


## 工作原理（简述）

1. 混合获取好友：优先 QQ 内部接口，失败则尝试本地联系人数据  
2. 按账号保存快照；再次获取后做集合差：`removed = old − new`  
3. 减少项写入本地历史；可选通知  
4. 取数失败时**不覆盖**旧快照，避免误报「全员被删」

## 隐私

- 好友数据与历史**仅存本机** QQ 私有目录下的模块数据库  
- **不上传**、无统计、无广告  
- 无法从列表区分「对方删你」还是「你删对方」，界面会如实说明  

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

## 文档

- 设计: [docs/superpowers/specs/2026-07-12-qq-friend-deletion-detector-design.md](docs/superpowers/specs/2026-07-12-qq-friend-deletion-detector-design.md)
- 实现计划: [docs/superpowers/plans/2026-07-12-fuckqq-nullfriend.md](docs/superpowers/plans/2026-07-12-fuckqq-nullfriend.md)

## 免责声明

- 仅供个人学习与研究，请遵守当地法律法规与腾讯用户协议  
- Hook 官方客户端存在封号、不稳定与兼容性风险，后果自负  
- QQ 升级可能导致功能失效，需自行或社区更新适配层  

## License

MIT — 见 [LICENSE](LICENSE)

## 致谢

思路参考了开源 QQ 模块生态（如 QAuxiliary / QNotified 的「列表对比」方向），但本仓库为独立重写，不包含其已失效钩子的直接搬用。
