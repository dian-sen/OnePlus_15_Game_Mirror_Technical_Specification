# OnePlus 15 Game Mirror — 游戏画面提取与触控映射模块

一加 15 (ColorOS) 类红魔游戏空间画面提取功能，基于 LSPosed + Android Native 实现无感、低延迟、高帧率的屏幕局部实时映射与触控交互。

---

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 需求分析](#2-需求分析)
- [3. 系统架构](#3-系统架构)
- [4. 技术实现](#4-技术实现)
- [5. 项目结构](#5-项目结构)
- [6. 构建与部署](#6-构建与部署)
- [7. 版本历史](#7-版本历史)
- [8. 测试与验收](#8-测试与验收)
- [9. 后续优化方向](#9-后续优化方向)

---

## 1. 项目概述

### 1.1 项目背景

在竞速（如《QQ飞车》）、射击及 MOBA 类手游中，关键 UI 元素（小地图、技能 CD、车辆 ECU 状态等）通常固定在屏幕边缘，玩家操作时频繁转移视角容易分散注意力。

红魔系统内置的"画面提取"与"触控映射"功能，允许玩家截取屏幕 **A 区域（源区域）**，实时放大/镜像悬浮显示在 **B 区域（目标悬浮窗）**，并在点击 B 区域时自动将触控事件重定向映射至 A 区域。

本项目为 **一加 15 (ColorOS)** 设备开发系统级辅助模块，实现无感、低延迟、高帧率的屏幕局部实时映射与触控交互。

### 1.2 目标设备与环境

| 项目 | 规格 |
|------|------|
| 硬件 | 一加 15 (OnePlus 15) |
| 操作系统 | Android 15/16 (ColorOS) |
| 运行环境 | Magisk / KernelSU / APatch + LSPosed (API 102+) |
| 开发语言 | Java 17, C++17, OpenGL ES 2.0 |
| 构建工具 | Gradle 8.9 + AGP 8.7.3 + CMake 3.22.1 |

### 1.3 核心性能指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 图像映射延迟 | ≤ 6.06ms | 单帧内，对应 165Hz 帧间隔 |
| 触控重定向延迟 | ≤ 3ms | uinput 虚拟设备直写 |
| 刷新率 | ≤ 165Hz | 自适应帧率同步 |
| CPU 占用 | < 3% | GPU 硬件渲染，零 CPU 拷贝 |
| 内存占用 | < 50MB | 显存共享，避免 Bitmap 拷贝 |

---

## 2. 需求分析

### 2.1 功能需求

| 模块 | 需求项 | 实现状态 |
|------|--------|----------|
| **画面提取** | A/B 区域自定义选区 | 已实现 |
| **画面提取** | 静默录屏（LSPosed 免弹窗） | 已实现 |
| **画面提取** | 165Hz GPU 硬件渲染 | 已实现 |
| **画面提取** | uMirror 镜像翻转（水平/垂直/双向） | 已实现 |
| **画面提取** | 自适应帧率 + FPS 统计 | 已实现 |
| **触控映射** | 点击重定向 (B → A) | 已实现 |
| **触控映射** | 滑动重定向 (B → A) | 已实现 |
| **触控映射** | 多点触控隔离（独立 Slot ID） | 已实现 |
| **触控映射** | uinput 虚拟设备注入 | 已实现 |
| **系统交互** | 侧边栏/悬浮球控制 | 已实现 |
| **系统交互** | 快捷 Action 指令（镜像/透明度/停止） | 已实现 |
| **配置管理** | SharedPreferences 持久化 | 已实现 |

### 2.2 非功能需求

1. **高性能零 GC**：全程 GPU 显存共享 + C++ Native 处理，禁用 Bitmap 拷贝
2. **防封号隐蔽性**：不修改游戏 APK，仅基于系统原生 Input Subsystem 与 MediaProjection
3. **ColorOS 适配**：OplusWMS 白名单绕过、OplusScreenShield 屏蔽抑制、双路径弹窗抑制

---

## 3. 系统架构

三层架构：**LSPosed 系统注入层** → **画面抓取渲染层** → **触控映射重定向层**

```
┌──────────────────────────────────────────────────────────────────┐
│                    LSPosed Framework (API 102)                   │
│                                                                  │
│  GameMirrorModule ──(Hook)──► SystemUI / MediaProjection        │
│  · 自动批准 MediaProjection 录屏权限                               │
│  · 绕过"应用正在捕捉您的屏幕"警告弹窗                                 │
│  · OplusWMS 悬浮窗白名单绕过                                      │
│  · InputManager INJECT_EVENTS 权限提升                            │
│  · ColorOS 双路径弹窗抑制 (AOSP + Oplus)                          │
└──────────────────────────────┬───────────────────────────────────┘
                               │ (Granted Permission)
                               ▼
┌──────────────────────────────────────────────────────────────────┐
│               Graphics Layer (Screen Mirror)                     │
│                                                                  │
│  MediaProjection Service ──► VirtualDisplay (165FPS Surface)     │
│  OpenGL ES Shaders ──► GPU UV 裁剪 + uMirror 翻转                │
│  WindowManager ──► TYPE_APPLICATION_OVERLAY (B 区域渲染)          │
│  ConfigManager ──► 持久化配置 (A/B 区域/透明度/帧率)               │
└──────────────────────────────┬───────────────────────────────────┘
                               │ (Touch Listener)
                               ▼
┌──────────────────────────────────────────────────────────────────┐
│               Touch Layer (B → A Redirect)                       │
│                                                                  │
│  MirrorOverlayView (B) 捕获 MotionEvent                          │
│  TouchRedirector 坐标转换: (xA,yA) = (Ax+dx·Aw/Bw, Ay+dy·Ah/Bh)  │
│  TouchInjector (C++) ──► uinput 虚拟设备 (vendor=0x1A15)         │
│                       ──► /dev/input/event* 物理设备 (回退)        │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. 技术实现

### 4.1 LSPosed 模块 (API 102)

8 类 Hook 策略覆盖 AOSP + ColorOS 双路径：

| # | Hook 类 | 目标 | 策略 |
|---|---------|------|------|
| 1 | `ScreenCapturePermissionBypass` | `MediaProjectionManagerService.hasPermission` | 强制返回 `true` |
| 2 | `InjectPermissionBypass` | `InputManagerService.checkInjectPermissions` | 跳过权限检查 |
| 3 | `OplusWhitelistBypass` | `OplusWindowManagerService.isAppInWhiteList` | 强制返回 `true` |
| 4 | `OplusBlockSuppressor` | `OplusScreenShield.isBlocked` | 强制返回 `false` |
| 5 | `MediaProjectionPermissionGrant` | `MediaProjectionPermissionActivity` 构造 | 静默化 |
| 6 | `SuppressNotification` | `MediaProjectionMetricsLogger.notifyProjectionStart` | 拦截通知 |
| 7 | `OplusDialogSuppressor` | `OplusScreenRecordDialog` 构造 | 抑制 ColorOS 弹窗 |
| 8 | `OplusIndicatorSuppressor` | `OplusMediaProjectionIndicator.show` | 抑制状态栏指示器 |

```java
// 核心 Hook 入口
@Override
public void onPackageLoaded(@NonNull PackageLoadedParam param) {
    if ("android".equals(param.getPackageName())) {
        hookMediaProjectionPermission(cl);
        hookInputManagerPermission(cl);
        hookOplusWMSOverlay(cl);
    }
    if ("com.android.systemui".equals(param.getPackageName())) {
        hookAOSPDialogs(cl);
        hookOplusDialogs(cl);
    }
}
```

### 4.2 画面采集与 OpenGL ES 渲染

采用 `VirtualDisplay + Surface` 方案，GPU 显存内裁剪渲染，零 CPU 拷贝。

```glsl
// Fragment Shader — GPU 侧 UV 裁剪 + 镜像翻转
#extension GL_OES_EGL_image_external : require
precision mediump float;

varying vec2 vTexCoord;
uniform samplerExternalOES sTexture;
uniform vec4 uCropRect;       // A 区域归一化 UV [left, top, width, height]
uniform int uMirror;           // 0=无 1=水平 2=垂直 3=双向

void main() {
    vec2 uv = vTexCoord;
    if (uMirror == 1) uv.x = 1.0 - uv.x;
    else if (uMirror == 2) uv.y = 1.0 - uv.y;
    else if (uMirror == 3) { uv.x = 1.0 - uv.x; uv.y = 1.0 - uv.y; }
    vec2 croppedUV = uCropRect.xy + uv * uCropRect.zw;
    gl_FragColor = texture2D(sTexture, croppedUV);
}
```

### 4.3 触控映射重定向 (B → A)

**坐标转换公式**：

```
xA = Ax + dx × (Aw ÷ Bw)
yA = Ay + dy × (Ah ÷ Bh)
```

**双策略触控注入**：

| 策略 | 设备 | 延迟 | 可靠性 |
|------|------|------|--------|
| uinput 虚拟设备 | `/dev/uinput` → 虚拟触控设备 (vendor=0x1A15) | ≤ 2ms | 高（系统原生识别） |
| 物理设备直写 | `/dev/input/event*` → 触摸屏硬件 | ≤ 3ms | 中（需 root） |

```cpp
// C++ Native 触控注入 — uinput 虚拟设备
bool TouchInjector::createUinputDevice() {
    uinputFd_ = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    // 设置能力位: EV_KEY, EV_ABS, BTN_TOUCH, ABS_MT_*
    ioctl(uinputFd_, UI_SET_EVBIT, EV_KEY);
    ioctl(uinputFd_, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    // 设备身份: vendor=0x1A15 (一加15)
    usetup.id.vendor = 0x1A15;
    ioctl(uinputFd_, UI_DEV_CREATE);
    return true;
}
```

### 4.4 配置持久化

`ConfigManager` 基于 `SharedPreferences` 持久化所有配置项：

- A 区域坐标/尺寸
- B 悬浮窗位置/尺寸
- 透明度 (0.1~1.0)
- 镜像模式 (0=无, 1=水平, 2=垂直, 3=双向)
- 目标帧率 (30~240Hz)
- 屏幕尺寸

### 4.5 Action 指令系统

通过 `Intent.setAction()` 发送指令到 `MirrorOverlayService`：

| Action | 常量 | 功能 |
|--------|------|------|
| `TOGGLE_MIRROR` | `ACTION_TOGGLE_MIRROR` | 循环切换镜像模式 |
| `INCREASE_ALPHA` | `ACTION_INCREASE_ALPHA` | 透明度 +0.05 |
| `DECREASE_ALPHA` | `ACTION_DECREASE_ALPHA` | 透明度 -0.05 |
| `STOP` | `ACTION_STOP` | 停止服务 |

---

## 5. 项目结构

```
game-mirror/
├── app/                                    # 主应用模块
│   ├── build.gradle.kts                    # 应用构建配置
│   ├── proguard-rules.pro                  # 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml             # 应用清单
│       ├── cpp/                            # C++ Native 层
│       │   ├── CMakeLists.txt
│       │   ├── touch_injector.h            # 触控注入器头文件
│       │   └── touch_injector.cpp          # uinput + 物理设备双策略实现
│       ├── java/com/example/gamemirror/
│       │   ├── MainActivity.java           # 主入口 + 控制面板
│       │   ├── capture/                    # 画面采集
│       │   │   ├── GLRenderer.java         # OpenGL ES 渲染器 (uMirror + FPS)
│       │   │   ├── PermissionActivity.java # 透明权限请求
│       │   │   └── ScreenCaptureManager.java # MediaProjection 管理
│       │   ├── config/
│       │   │   └── ConfigManager.java      # SharedPreferences 持久化
│       │   ├── overlay/                    # 悬浮窗
│       │   │   ├── MirrorOverlayService.java # 前台服务 + Action 指令
│       │   │   └── MirrorOverlayView.java  # 悬浮窗 View (缩放/吸附)
│       │   ├── touch/
│       │   │   └── TouchRedirector.java    # 触控重定向 (点击 + 滑动)
│       │   └── ui/
│       │       ├── AreaSelectionView.java  # 全屏框选 UI
│       │       └── ControlPanelService.java # 侧边栏服务
│       └── res/                            # 资源文件
│           ├── drawable/
│           │   └── ic_launcher_foreground.xml
│           ├── mipmap-hdpi/
│           │   └── ic_launcher.xml
│           └── values/
│               ├── strings.xml
│               └── styles.xml
├── xposed/                                 # LSPosed 模块
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/gamemirror/xposed/
│       │   └── GameMirrorModule.java       # 8 类 Hook 策略
│       └── res/values/
│           └── arrays.xml                  # Hook 作用域配置
├── gradle/
│   ├── libs.versions.toml                  # 版本目录
│   └── wrapper/
│       └── gradle-wrapper.properties
├── build.gradle.kts                        # 根构建配置
├── gradle.properties
└── settings.gradle.kts
```

---

## 6. 构建与部署

### 6.1 前置条件

- Android Studio Hedgehog+ 或 AGP 8.7.3 CLI
- Android SDK 35 + NDK 27+
- CMake 3.22.1+
- 已 root 的一加 15 设备 + LSPosed 框架

### 6.2 构建

```bash
# 生成 Gradle Wrapper（首次）
gradle wrapper --gradle-version 8.9

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease
```

### 6.3 部署

1. 安装 `app/build/outputs/apk/release/app-release.apk`
2. 在 LSPosed 管理器中启用 `GameMirror` 模块，勾选 **系统框架 (android)** 和 **SystemUI** 作用域
3. 重启设备使 LSPosed Hook 生效
4. 打开 GameMirror 应用，按引导授予悬浮窗权限和电池优化白名单
5. 点击"启动录屏权限" → "框选A区域" → "打开悬浮窗"

---

## 7. 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| **v1.0.2** | 2026-08 | 新增 ConfigManager、AreaSelectionView、uinput 虚拟设备、uMirror 翻转、自适应帧率、FPS 统计、滑动重定向、8 类 LSPosed Hook、Action 指令系统 |
| **v1.0.1** | 2026-08 | 修复 isTouchDevice 缓冲区溢出、权限流程断裂、GLRenderer uMVPMatrix 缺失、切换逻辑错误、图标缺失 |
| **v1.0.0** | 2026-08 | 初始版本：基础 MediaProjection 采集、OpenGL 渲染、触控注入、LSPosed 权限绕过 |

---

## 8. 测试与验收

| 测试项 | 指标要求 | 测试方法 |
|--------|----------|----------|
| 图像延迟 | ≤ 6.06ms（适配 165Hz 帧间隔） | 240fps/480fps 高速相机拍摄 A/B 区域变化计算帧差 |
| 触控响应 | 点击 B 区域 ≤ 3ms 内 A 区域触发 | 查看 `/dev/input/event` 时间戳与游戏响应帧 |
| 多点触控 | 左手摇杆 + 右手 B 区域不掉步 | 游戏内实测双手同时操作 |
| 系统资源 | CPU < 3%, RAM < 50MB | `dumpsys cpuinfo` + Android Profiler |
| 镜像模式 | 水平/垂直/双向翻转正确 | 画面内容方向验证 |
| 配置持久化 | 重启后恢复上次配置 | SharedPreferences 读写验证 |

---

## 9. 后续优化方向

### 9.1 工程化

| 优先级 | 方向 | 说明 |
|--------|------|------|
| **高** | 添加 Gradle Wrapper | 当前缺少 `gradlew` 脚本和 `gradle-wrapper.jar`，无法直接构建 |
| **高** | 添加 CI/CD | GitHub Actions 自动化构建 APK |
| **中** | 单元测试 | JNI 层、坐标转换算法的单元测试 |
| **中** | 代码覆盖率 | JaCoCo 集成 |

### 9.2 功能增强

| 优先级 | 方向 | 说明 |
|--------|------|------|
| **高** | 多 A 区域支持 | 同时提取多个不连续区域（如小地图 + 技能栏） |
| **高** | 录制回放 | 录制触控操作序列，支持回放宏 |
| **中** | 手势识别 | 自定义手势 → 映射到特定操作（如画圈 = 释放技能） |
| **中** | 网络投屏 | 将 B 区域画面通过 RTMP/WebRTC 推流到 PC |
| **低** | 语音控制 | 语音指令触发 Action（如"切换镜像"） |
| **低** | 游戏专属预设 | 热门游戏一键加载预设配置 |

### 9.3 性能优化

| 优先级 | 方向 | 说明 |
|--------|------|------|
| **高** | `setFrameRate` 兼容性 | `Surface.setFrameRate` 在部分设备上可能无效，需要回退方案 |
| **中** | Vulkan 渲染后端 | 替代 OpenGL ES，降低驱动开销 |
| **中** | Frame Pacing | 使用 Android Frame Pacing API 精确控制帧间隔 |
| **低** | EGL 异步纹理 | 减少 `updateTexImage` 阻塞 |

### 9.4 稳定性

| 优先级 | 方向 | 说明 |
|--------|------|------|
| **高** | 错误恢复 | `MediaProjection.stop()` 后的自动重连 |
| **中** | 热重载 | LSPosed 模块更新后无需重启 |
| **中** | 崩溃上报 | 集成 Bugly/Crashlytics 收集 Native 崩溃堆栈 |
| **低** | 低电量保护 | 电量 < 20% 时自动降低帧率到 60Hz |

### 9.5 兼容性

| 优先级 | 方向 | 说明 |
|--------|------|------|
| **中** | 多设备适配 | 扩展到其他 ColorOS 设备（一加 12/13, OPPO Find 系列） |
| **中** | Shizuku 方案 | 替代 root 依赖，降低使用门槛 |
| **低** | 无障碍服务方案 | 完全免 root 的触控方案（性能降级） |

---

> **License**: MIT
> **Target**: OnePlus 15 / ColorOS 15+ / Android 15+