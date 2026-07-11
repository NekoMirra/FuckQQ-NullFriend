# 去TM的单向好友 (FuckQQ-NullFriend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an LSPosed module that snapshots QQ friend lists, detects removals, shows history inside QQ UI, and optionally notifies — product name「去TM的单向好友」, repo `FuckQQ-NullFriend`.

**Architecture:** Single-purpose Kotlin LSPosed module scoped only to `com.tencent.mobileqq`. Domain layer (diff/store) is pure and unit-tested; adapter layer (DexKit/API/DB) is isolated for QQ upgrades; UI injects into QQ settings with no standalone launcher.

**Tech Stack:** Kotlin, AGP, LSPosed API (libxposed / classic bridge), DexKit, SQLite, JUnit4 unit tests on JVM for domain.

## Global Constraints

- Product display name: **去TM的单向好友**
- ApplicationId / package: `com.fuckqq.nullfriend`
- LSPosed scope **static**: only `com.tencent.mobileqq`
- No TIM / no desktop launcher activity for settings
- Hybrid friend fetch: API first, DB fallback; fail must not overwrite snapshot
- First successful fetch = baseline only (no false deletions)
- System notification default **off**
- Multi-account isolation by `ownerUin`
- Record **removals only**
- Data local-only, no upload
- Spec: `docs/superpowers/specs/2026-07-12-qq-friend-deletion-detector-design.md`

---

## File Structure

```
FuckQQ-NullFriend/   (workspace: D:\AI\QQfriend)
├── README.md
├── LICENSE
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/...
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/xposed_init
│       ├── java/com/fuckqq/nullfriend/
│       │   ├── XposedEntry.kt
│       │   ├── ModuleMain.kt
│       │   ├── domain/
│       │   │   ├── FriendEntry.kt
│       │   │   ├── DiffEngine.kt
│       │   │   └── Models.kt
│       │   ├── data/
│       │   │   ├── DetectorDatabase.kt
│       │   │   ├── SnapshotStore.kt
│       │   │   ├── HistoryStore.kt
│       │   │   └── Prefs.kt
│       │   ├── service/
│       │   │   ├── DetectionService.kt
│       │   │   ├── Notifier.kt
│       │   │   └── ChatLauncher.kt
│       │   ├── provider/
│       │   │   ├── FriendListProvider.kt
│       │   │   ├── ApiFriendSource.kt
│       │   │   └── DbFriendSource.kt
│       │   ├── hook/
│       │   │   ├── StartupHook.kt
│       │   │   └── SettingsInjectHook.kt
│       │   ├── ui/
│       │   │   └── DetectorActivity.kt
│       │   └── util/
│       │       ├── Log.kt
│       │       └── UinUtil.kt
│       └── res/...
├── app/src/test/java/.../DiffEngineTest.kt
└── docs/superpowers/...
```

---

### Task 1: Repo + Gradle LSPosed skeleton

**Files:**
- Create: `README.md`, `LICENSE` (MIT), root Gradle files, `app/` module, `AndroidManifest.xml`, `xposed_init`, `XposedEntry.kt`, resources (app_name 去TM的单向好友)
- Create: GitHub public repo `FuckQQ-NullFriend`

- [ ] **Step 1:** `git init`, create public repo, push skeleton after files exist
- [ ] **Step 2:** Android library/application module with `xposedapi` + compileOnly libxposed/Xposed API
- [ ] **Step 3:** Static scope metadata for `com.tencent.mobileqq` only
- [ ] **Step 4:** Empty loadPackage logs module init for QQ only
- [ ] **Step 5:** Commit `chore: bootstrap LSPosed module skeleton`

### Task 2: Domain + DiffEngine (TDD)

**Files:**
- Create: `domain/FriendEntry.kt`, `domain/DiffEngine.kt`, `domain/Models.kt`
- Test: `app/src/test/java/com/fuckqq/nullfriend/domain/DiffEngineTest.kt`

**Interfaces:**
- Produces: `DiffEngine.diff(previous: Set<FriendEntry>, current: Set<FriendEntry>): DiffResult` where removed = previous uins − current uins

- [ ] **Step 1:** Write DiffEngineTest (empty, partial remove, no add tracking, name from previous)
- [ ] **Step 2:** Implement DiffEngine until tests pass
- [ ] **Step 3:** Commit `feat: add DiffEngine for friend removal detection`

### Task 3: SQLite stores + Prefs

**Files:**
- Create: `data/DetectorDatabase.kt`, `SnapshotStore.kt`, `HistoryStore.kt`, `Prefs.kt`

**Interfaces:**
- `SnapshotStore.get/put(ownerUin, snapshot)`
- `HistoryStore.insertRemovals(...)`, `list(ownerUin)`, `clear(ownerUin)`
- Prefs: notifyEnabled=false, intervalMinutes=0, startupDelaySec=5, verboseLog=false

- [ ] **Step 1:** Implement DB schema per spec §6
- [ ] **Step 2:** Unit-test serialization roundtrip if possible on JVM (or instrument later)
- [ ] **Step 3:** Commit `feat: add snapshot and history storage`

### Task 4: DetectionService orchestration

**Files:**
- Create: `service/DetectionService.kt`
- Depends on: FriendListProvider interface (mockable), stores, DiffEngine, Notifier

**Logic:**
- Mutex single-flight
- Fail → no snapshot overwrite
- No baseline → put baseline only
- Else diff removals → history + optional notify → put snapshot

- [ ] **Step 1:** Unit-test DetectionService with fake provider
- [ ] **Step 2:** Implement service
- [ ] **Step 3:** Commit `feat: add DetectionService orchestration`

### Task 5: FriendListProvider (API + DB stubs + real hooks)

**Files:**
- Create: `provider/*`, adapter hooks using DexKit placeholders with clear TODO markers only where QQ version-specific signatures must be filled on device

- [ ] **Step 1:** Define `FriendListProvider` interface + composite API→DB
- [ ] **Step 2:** Implement ownerUin resolution + best-effort NT contact hooks
- [ ] **Step 3:** Commit `feat: add hybrid FriendListProvider`

### Task 6: UI inject + DetectorActivity

**Files:**
- Create: `ui/DetectorActivity.kt`, layouts, `hook/SettingsInjectHook.kt`, `ChatLauncher.kt`, `Notifier.kt`

- [ ] **Step 1:** Activity for history, refresh, toggles, account switch, long-press chat
- [ ] **Step 2:** Hook QQ settings entry
- [ ] **Step 3:** StartupHook delayed detect
- [ ] **Step 4:** Commit `feat: embed detector UI in QQ`

### Task 7: README polish + release checklist

- [ ] **Step 1:** Document install (LSPosed, scope=QQ only), disclaimer, privacy
- [ ] **Step 2:** Tag-ready build instructions
- [ ] **Step 3:** Commit `docs: finalize README and usage`

---

## Spec coverage checklist

| Spec item | Task |
|-----------|------|
| Static scope QQ only | 1 |
| Diff removals only | 2 |
| Baseline / no overwrite on fail | 4 |
| Multi-account store | 3 |
| Hybrid provider | 5 |
| In-QQ UI + long-press chat | 6 |
| Optional notify default off | 3, 4, 6 |
| Startup + manual + timer | 4, 6 |

## Execution note

User requested immediate implementation after rename + public GitHub repo. Prefer **inline execution** of tasks in order with commits and push.
