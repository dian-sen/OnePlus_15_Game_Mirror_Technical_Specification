# OnePlus_15_Game_Mirror_Technical_Specification
一加15类红魔游戏空间画面提取功能

一加 15 游戏画面提取与触控映射模块技术方案书
1. 项目概述
1.1 项目背景
在竞速（如《QQ飞车》）、射击及 MOBA 类手游中，关键 UI 元素（如车辆 ECU 状态、小地图、技能 CD 等）通常固定在屏幕边缘角落，玩家在操作时视角频繁转移容易分散注意力。
红魔系统内置的“画面提取”与“触控映射”功能，允许玩家截取屏幕特定 A 区域（源区域），实时放大/镜像悬浮显示在方便观察的 B 区域（目标悬浮窗），并在点击 B 区域时，自动将触控事件重定向映射至 A 区域。
本项目旨在为一加 15（ColorOS 体系）设备开发一套基于 LSPosed (API 102 Framework) + Android Native/Surface/Input Subsystem 的系统级辅助模块，实现无感、低延迟、高帧率的屏幕局部实时映射与触控交互。
1.2 目标设备与运行环境
• 硬件设备：一加 15 (OnePlus 15)
• 操作系统：Android 15 / 16 (ColorOS)
• 运行环境：Magisk / KernelSU / APatch + LSPosed (API 102+)
• 核心性能指标：图像映射延迟 ≤ 6.06ms（单帧内，对应 165Hz 帧间隔）；触控重定向延迟 ≤ 3ms；支持最高 165Hz 刷新率同步；平均 CPU 占用率<3%。
2. 需求分析
2.1 功能需求 (FR)
模块	需求项	详细说明
画面提取	A/B 区域自定义选区	A 区域（源）：在屏幕上自由框选提取画面（可调节 x, y, w, h）。
B 区域（悬浮窗）：可任意拖拽、缩放、调节透明度的 Overlay 窗口。
画面提取	静默录屏/抓图	Hook 拦截 Android 原生 MediaProjection 警告弹窗，实现无感后台抓图。
画面提取	电竞级极高帧率渲染	实时将 A 区域图像映射至 B 区域，支持最高 165 FPS 的 GPU 硬件级渲染。
触控映射	点击重定向 (B → A)	用户点击/滑动 B 悬浮窗时，系统拦截该事件，计算相对坐标并映射注入到屏幕 A 区域。
触控映射	多点触控隔离	触控注入分配独立 Pointer/Slot ID，避免干扰游戏主操作（如左手方向摇杆）。
系统交互	侧边栏控制	提供快捷悬浮球/侧边栏，支持在游戏中一键开关悬浮窗及进入框选模式。
2.2 非功能需求 (NFR)
1. 高性能与零 GC：禁用频繁的 Bitmap 拷贝与 Java 层内存分配，全程采用 GPU 显存共享与 C++ Native 处理。
2. 防封号与隐蔽性：不修改游戏 APK 内存/代码，仅基于 Android 系统原生 Input Subsystem 与 MediaProjection 机制运行。
3. 系统整体架构
系统分为 LSPosed 系统注入层、画面抓取与渲染层、触控事件映射重定向层 三层架构：
+-------------------------------------------------------------------------------+
|                             LSPosed Framework                                 |
|  [MainModule] --(Hook)-->SystemUI / MediaProjectionManagerService           |
|                  * 自动批准 MediaProjection 录屏权限                            |
|                  * 绕过 "应用正在捕捉您的屏幕" 警告弹窗                           |
+------------------------------------+------------------------------------------+
                                     | (Granted Permission)
                                     v
+-------------------------------------------------------------------------------+
|                       Graphics Layer (Screen Mirror)                          |
|  1. MediaProjection Service --->VirtualDisplay (165FPS Surface)              |
|  2. OpenGL ES Shaders (GPU 侧基于 UV 坐标毫秒级裁剪 A 区域)                       |
|  3. WindowManager ->TYPE_APPLICATION_OVERLAY (B 区域 165Hz 实时渲染)           |
+------------------------------------+------------------------------------------+
                                     | (Touch Listener Event)
                                     v
+-------------------------------------------------------------------------------+
|                      Touch Layer (B ->A Redirect)                            |
|  1. Overlay View (B) 捕获 MotionEvent (dx, dy)                                |
|  2. 坐标转换公式计算: (xA, yA) = (Ax + dx*Aw/Bw, Ay + dy*Ah/Bh)               |
|  3. Root/Native Service 直接向 /dev/input/event* 写入 Linux Input Touch 事件   |
+-------------------------------------------------------------------------------+

4. 详细技术实现方案
4.1 LSPosed 模块实现 (API 102 Standard)
通过 Hook 系统框架服务，跳过 MediaProjection 的系统授权弹窗，实现无感后台录屏。
package com.example.gamemirror.xposed;

import androidx.annotation.NonNull;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public class GameMirrorModule extends XposedModule {

    public GameMirrorModule(@NonNull XposedInterface base, @NonNull XposedModuleInterface.ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        super.onPackageLoaded(param);

        // 目标：Hook 系统 Framework，免弹窗授权 MediaProjection
        if ("android".equals(param.getPackageName())) {
            hookMediaProjectionPermission(param.getClassLoader());
        }
    }

    private void hookMediaProjectionPermission(ClassLoader classLoader) {
        try {
            Class<?> helperClass = classLoader.loadClass("com.android.server.media.projection.MediaProjectionManagerService");
            // API 102 自动同意 Screen Capture 授权逻辑
            log("Successfully hooked MediaProjectionManagerService");
        } catch (ClassNotFoundException e) {
            log("Failed to find MediaProjectionManagerService: " + e.getMessage());
        }
    }
}

4.2 画面采集与 OpenGL ES 165Hz 渲染
采用 VirtualDisplay + Surface 方案，直接在 GPU 显存内进行裁剪与渲染，避免 CPU 内存拷贝（GC 零开销）。
#extension GL_OES_EGL_image_external : require
precision mediump float;

varying vec2 vTextureCoord;
uniform samplerExternalOES sTexture;

// A 区域归一化 UV 坐标参数 [left, top, width, height]
uniform vec4 uCropRect; 

void main() {
    // 将 B 区域整张纹理的 UV 坐标映射回 A 区域的局部坐标
    vec2 croppedUV = uCropRect.xy + vTextureCoord * uCropRect.zw;
    gl_FragColor = texture2D(sTexture, croppedUV);
}

4.3 触控映射重定向 (B → A)
1. 坐标转换算法
假设 A 区域屏幕绝对位置为 (Ax, Ay, Aw, Ah)，B 悬浮窗当前宽高为 (Bw, Bh)，用户点击 B 悬浮窗内的相对坐标为 (dx, dy)。
映射到屏幕 A 区域的真实触控坐标公式如下：
xA = Ax + dx * (Aw / Bw)
yA = Ay + dy * (Ah / Bh)
2. Native 触控注入逻辑 (Linux Input Event)
利用 C++ 直接操作 /dev/input/event* 设备，实现硬件级模拟触控：
#include<linux/input.h>
#include<fcntl.h>
#include<unistd.h>

void sendTouchEvent(int fd, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    write(fd, &ev, sizeof(ev));
}

// 模拟向 A 区域注入点击事件
void injectTouchA(int inputFd, int xA, int yA) {
    // 1. 分配独立 Slot (避免干扰游戏方向盘摇杆)
    sendTouchEvent(inputFd, EV_ABS, ABS_MT_SLOT, 1);
    sendTouchEvent(inputFd, EV_ABS, ABS_MT_TRACKING_ID, 200);
    sendTouchEvent(inputFd, EV_KEY, BTN_TOUCH, 1);
    
    // 2. 写入 A 点映射坐标
    sendTouchEvent(inputFd, EV_ABS, ABS_MT_POSITION_X, xA);
    sendTouchEvent(inputFd, EV_ABS, ABS_MT_POSITION_Y, yA);
    sendTouchEvent(inputFd, EV_SYN, SYN_REPORT, 0);

    // 3. 抬起 (Up)
    sendTouchEvent(inputFd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    sendTouchEvent(inputFd, EV_KEY, BTN_TOUCH, 0);
    sendTouchEvent(inputFd, EV_SYN, SYN_REPORT, 0);
}

5. 一加 15 (ColorOS) 165Hz 专项优化与适配
1. 165Hz 屏幕刷新率解锁：一加 15 硬件支持高刷新率，但 ColorOS 对后台悬浮窗有严格限制。必须在 WindowManager.LayoutParams 中显式配置 preferredDisplayModeId，并在 Surface 渲染侧强制绑定：
 surface.setFrameRate(165.0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
2. 后台高电耗与保活设置：一加系统对后台进程管理较为严格，需引导用户开启“允许后台高电耗”并将应用添加到电池优化白名单中。
3. 触控穿透防护：B 悬浮窗设置 WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL，在 onTouchListener 中消费所有点击事件（返回 true），防止触控穿透至 B 悬浮窗正下方原本的游戏 UI。
6. 测试与验收标准
测试项	指标要求	测试方法
图像延迟	≤ 6.06ms（适配 165Hz 帧间隔）	使用 240fps / 480fps 高速相机拍摄屏幕 A/B 区域变化计算帧差。
触控响应	点击 B 区域 ≤ 3ms 内 A 区域触发动作	查看 /dev/input/event 时间戳与游戏响应帧。
多点触控测试	左手按住方向摇杆时，右手点击 B 区域不掉步	游戏内实测双手同时操作稳定性。
系统资源占用	CPU<3%, RAM<50MB	通过 dumpsys cpuinfo 与 Android Profiler 监控。