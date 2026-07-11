# 去TM的单向好友 — LSPosed 模块设计

**日期**: 2026-07-12  
**产品名**: 去TM的单向好友  
**仓库名**: FuckQQ-NullFriend  
**项目路径**: `D:\AI\QQfriend`  
**状态**: 设计已确认，进入实现  
**方案**: 精简单模块 + 混合取数（API 优先，DB 回退）

---

## 1. 背景与目标

### 1.1 问题

用户需要在官方 QQ Android 客户端上检测「好友列表中消失」的联系人（通常理解为被删或双边关系变化导致列表减少），并在本地查看发现时间与历史记录；长按可尝试打开对应聊天页查看本地会话记录。

### 1.2 为何重做

开源生态中 QNotified / QAuxiliary 曾提供「被删好友通知 / 历史好友」类功能，但在较新 QQ 9.x 上经常失效。本项目**不依赖、不 fork 其已失效实现**，仅借鉴「列表快照对比 + 进程内 Hook」思路，按当前 NT 架构与用户需求重新设计。

### 1.3 成功标准

在用户指定的真机 + 当前官方 QQ 正式版 9.x 上：

1. 冷启动或手动刷新可拉取当前账号好友列表并建立基线（首跑不误报）。
2. 列表减少项写入历史，可在 QQ 内嵌页查看。
3. 长按记录尽量打开 QQ 私聊页；失败有明确提示。
4. 系统通知默认关闭，开启后对减少项有可配置提醒。
5. 多账号数据按 uin 隔离，UI 可切换查看。
6. LSPosed 作用域**静态限定为 QQ**（`com.tencent.mobileqq`）。

---

## 2. 范围与约束

### 2.1 LSPosed 作用域（静态）

| 项 | 约定 |
|----|------|
| **作用应用** | 仅 `com.tencent.mobileqq`（官方 QQ） |
| **作用域配置** | 模块清单 / LSPosed 元数据中**静态写明**仅作用于 QQ |
| **用户勾选** | 在 LSPosed 中只应勾选 QQ |
| **首版不做** | TIM、其他马甲包 |

实现时：`xposed_init` / scope 列表仅含 `com.tencent.mobileqq`。

### 2.2 已确认需求

| 项 | 选择 |
|----|------|
| QQ 版本 | 主打最新官方 9.x NT；旧版不保证 |
| 检测时机 | 启动检测 + 手动「立即刷新」+ 可选定时 |
| UI | 全部嵌进 QQ；模块无独立 Launcher |
| 系统通知 | 可配置，**默认关** |
| 多账号 | 每 uin 独立快照 + 独立历史；UI 可切换 |
| 记录内容 | **仅**好友减少 |
| 取数 | **混合**：内部 API 优先，失败回退本地联系人数据 |

### 2.3 非目标（首版）

- 区分「对方删你」与「你删对方」（文案如实说明）
- 群成员 / 特别关心 / 黑名单监控
- 好友列表上传、云同步
- 导出 CSV/JSON（可二期）
- TIM / 多包名

---

## 3. 总体架构

### 3.1 模块定位

- **类型**: LSPosed 模块（Kotlin）
- **包名**: `com.fuckqq.nullfriend`
- **展示名**: 去TM的单向好友
- **运行位置**: 仅在 QQ 进程内 Hook 与展示
- **无独立 UI 进程 / 无桌面图标**

### 3.2 分层

```
QQ UI 注入层 → 应用服务层 (DetectionService / Notifier / ChatLauncher)
  → 领域层 (DiffEngine) → 数据层 (Snapshot / History / Prefs)
  → 适配层 (DexKit / API / DB)
```

### 3.3 设计原则

1. 适配与业务分离  
2. 按 uin 隔离  
3. 首跑只建基线  
4. 失败不覆盖快照  
5. 隐私本地化，无上报  

---

## 4. 数据流与检测时机

触发：启动延迟（默认 5s）/ 手动刷新 / 可选定时（默认关；30/60/180 分）。

流程：解析 ownerUin → Provider(API→DB) → 失败则保留旧快照 → 无基线则只写基线 → 有基线则 diff removed → 写 history + 可选通知 → 覆盖 snapshot。

检测仅针对当前登录号；UI 可切换查看其他号历史。

`detected_at` 为模块发现时间。同一时间仅一个检测任务（Mutex）。

---

## 5. 好友列表获取

统一模型：`FriendEntry { uin, name, nick?, source }`。

路径 A：内部 API / DexKit。路径 B：本地联系人数据回退。顺序 API→DB→Failure。

真机交付：至少一条路径稳定。

---

## 6. 存储与 Diff

路径：`filesDir/qqfriend_detector/detector.db`（实现可用 `nullfriend` 子目录）。

表：accounts / snapshots / deletion_history（见实现）。

Diff：`removed = previous.uins - current.uins`。不记 added。

去重：单次 diff 内一人一条；再次消失新开历史行。首版不标「已恢复」。

---

## 7. QQ 内嵌 UI

入口：QQ 设置项「去TM的单向好友」。主页：账号切换、状态、立即刷新、通知开关、定时、被删列表、长按打开聊天、清空历史、关于。

ChatLauncher：内部路由 → Intent → 失败 Toast + 复制 QQ 号。

配置默认：notify=false, interval=0, startup_delay=5, verbose=false。

---

## 8. 系统通知

默认关；开启后 removed 非空发一条摘要；点击进内嵌列表页。

---

## 9. 错误处理与隐私

失败可见；不静默成功；不覆盖基线；verbose 时 uin 打码；无网络上报。

文案：列表消失可能是对方删除、自己删除或其他关系变化，模块无法区分。

---

## 10. 测试策略

单元：DiffEngine、DetectionService（fake provider）、存储。  
真机：基线、人为减少、长按、通知、切号、杀进程。

---

## 11. 技术栈

Kotlin · minSdk 26 · LSPosed · scope 仅 com.tencent.mobileqq · DexKit · SQLite · Gradle

---

## 12. 风险

QQ 升级失效 → 双路径 + 适配层。全量误报 → 首跑基线 + 失败不覆盖。无法区分谁删谁 → 诚实文案。

---

## 13. 参考

- cinit/QAuxiliary、ferredoxin/QNotified（思路参考，不 fork 失效逻辑）

---

## 14. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-07-12 | 初稿；产品名「去TM的单向好友」；仓库 FuckQQ-NullFriend；包名 com.fuckqq.nullfriend |
