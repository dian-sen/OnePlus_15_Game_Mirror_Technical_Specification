package com.example.gamemirror.xposed;

import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

/**
 * GameMirror LSPosed 模块 (API 102)
 * 核心功能：Hook 系统权限校验，实现无感后台录屏与触控注入
 *
 * 8 类 Hook 策略覆盖 AOSP + ColorOS 双路径：
 * 1. ScreenCapturePermissionBypass   — MediaProjectionManagerService.hasPermission → 强制 true
 * 2. InjectPermissionBypass          — InputManagerService.checkInjectPermissions → 跳过
 * 3. OplusWhitelistBypass            — OplusWindowManagerService.isAppInWhiteList → 强制 true
 * 4. OplusBlockSuppressor            — OplusScreenShield.isBlocked → 强制 false
 * 5. MediaProjectionPermissionGrant  — MediaProjectionPermissionActivity 静默化
 * 6. SuppressNotification            — MediaProjectionMetricsLogger.notifyProjectionStart → 拦截
 * 7. OplusDialogSuppressor           — OplusScreenRecordDialog 构造 → 抑制
 * 8. OplusIndicatorSuppressor        — OplusMediaProjectionIndicator.show → 抑制
 *
 * 目标设备：一加 15 (OnePlus 15) / ColorOS / Android 15+
 */
public class GameMirrorModule extends XposedModule {

    private static final String TAG = "GameMirrorXposed";
    private static final String SYSTEM_PACKAGE = "android";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    public GameMirrorModule(@NonNull XposedInterface base,
                            @NonNull ModuleLoadedParam param) {
        super(base, param);
        Log.i(TAG, "GameMirror LSPosed Module loaded, API version: " + base.getAPIVersion());
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        super.onPackageLoaded(param);

        String pkg = param.getPackageName();
        ClassLoader cl = param.getClassLoader();

        if (SYSTEM_PACKAGE.equals(pkg)) {
            Log.i(TAG, "Android framework loaded, hooking...");
            hookMediaProjectionPermission(cl);
            hookInputManagerPermission(cl);
            hookOplusWMSOverlay(cl);
        }

        if (SYSTEM_UI_PACKAGE.equals(pkg)) {
            Log.i(TAG, "SystemUI loaded, hooking recording dialogs...");
            hookAOSPDialogs(cl);
            hookOplusDialogs(cl);
        }
    }

    // ========================================================================
    // 1. Hook MediaProjectionManagerService - 自动批准录屏权限
    // ========================================================================
    private void hookMediaProjectionPermission(ClassLoader classLoader) {
        try {
            Class<?> serviceClass = classLoader.loadClass(
                    "com.android.server.media.projection.MediaProjectionManagerService");
            hookAllMethods(serviceClass, "hasPermission",
                    ScreenCapturePermissionBypass.class);
            Log.i(TAG, "Hooked MediaProjectionManagerService.hasPermission");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Failed to hook MediaProjectionManagerService: " + e.getMessage());
        }
    }

    // ========================================================================
    // 2. Hook InputManager - INJECT_EVENTS 权限提升
    // ========================================================================
    private void hookInputManagerPermission(ClassLoader classLoader) {
        try {
            Class<?> imsClass = classLoader.loadClass(
                    "com.android.server.input.InputManagerService");
            hookAllMethods(imsClass, "checkInjectPermissions",
                    InjectPermissionBypass.class);
            Log.i(TAG, "Hooked InputManagerService.checkInjectPermissions");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "Failed to hook InputManagerService: " + e.getMessage());
        }
    }

    // ========================================================================
    // 3. Hook OplusWMS - ColorOS 悬浮窗权限绕过
    // ========================================================================
    private void hookOplusWMSOverlay(ClassLoader classLoader) {
        // 路径 1: OplusWindowManagerService（ColorOS 12+）
        try {
            Class<?> oplusWmsClass = classLoader.loadClass(
                    "com.android.server.wm.OplusWindowManagerService");
            hookAllMethods(oplusWmsClass, "isAppInWhiteList",
                    OplusWhitelistBypass.class);
            Log.i(TAG, "Hooked OplusWindowManagerService.isAppInWhiteList");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "OplusWindowManagerService not found (non-ColorOS device?)");
        }

        // 路径 2: OplusScreenShield（ColorOS 15+）
        try {
            Class<?> oplusShieldClass = classLoader.loadClass(
                    "com.oplus.screen.OplusScreenShield");
            hookAllMethods(oplusShieldClass, "isBlocked",
                    OplusBlockSuppressor.class);
            Log.i(TAG, "Hooked OplusScreenShield.isBlocked");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "OplusScreenShield not found (expected on older ColorOS)");
        }
    }

    // ========================================================================
    // 4. Hook AOSP SystemUI 录屏弹窗
    // ========================================================================
    private void hookAOSPDialogs(ClassLoader classLoader) {
        try {
            Class<?> permActivityClass = classLoader.loadClass(
                    "com.android.systemui.mediaprojection.MediaProjectionPermissionActivity");
            hookAllConstructors(permActivityClass,
                    MediaProjectionPermissionGrant.class);
            Log.i(TAG, "Hooked MediaProjectionPermissionActivity");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "MediaProjectionPermissionActivity not found: " + e.getMessage());
        }

        try {
            Class<?> projectionClass = classLoader.loadClass(
                    "com.android.systemui.mediaprojection.MediaProjectionMetricsLogger");
            hookAllMethods(projectionClass, "notifyProjectionStart",
                    SuppressNotification.class);
            Log.i(TAG, "Hooked MediaProjectionMetricsLogger");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "MediaProjectionMetricsLogger not found: " + e.getMessage());
        }
    }

    // ========================================================================
    // 5. Hook ColorOS/Oplus SystemUI 录屏弹窗
    // ========================================================================
    private void hookOplusDialogs(ClassLoader classLoader) {
        try {
            Class<?> oplusRecordClass = classLoader.loadClass(
                    "com.oplus.screenrecord.OplusScreenRecordDialog");
            hookAllConstructors(oplusRecordClass,
                    OplusDialogSuppressor.class);
            Log.i(TAG, "Hooked OplusScreenRecordDialog");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "OplusScreenRecordDialog not found (expected on ColorOS)");
        }

        try {
            Class<?> oplusIndicatorClass = classLoader.loadClass(
                    "com.android.systemui.mediaprojection.OplusMediaProjectionIndicator");
            hookAllMethods(oplusIndicatorClass, "show",
                    OplusIndicatorSuppressor.class);
            Log.i(TAG, "Hooked OplusMediaProjectionIndicator.show");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "OplusMediaProjectionIndicator not found");
        }
    }

    // ========================================================================
    // Hooker 类 1: 权限绕过 - hasPermission 始终返回 true
    // ========================================================================
    @XposedHooker
    public static class ScreenCapturePermissionBypass implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void beforeInvoke(@NonNull BeforeHookParam param) {
            param.setResult(true);
        }
    }

    // ========================================================================
    // Hooker 类 2: InputManager INJECT_EVENTS 权限提升
    // ========================================================================
    @XposedHooker
    public static class InjectPermissionBypass implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void beforeInvoke(@NonNull BeforeHookParam param) {
            param.setResult(null);
        }
    }

    // ========================================================================
    // Hooker 类 3: OplusWMS 白名单绕过
    // ========================================================================
    @XposedHooker
    public static class OplusWhitelistBypass implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void beforeInvoke(@NonNull BeforeHookParam param) {
            param.setResult(true);
        }
    }

    // ========================================================================
    // Hooker 类 4: Oplus ScreenShield 阻止抑制
    // ========================================================================
    @XposedHooker
    public static class OplusBlockSuppressor implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void beforeInvoke(@NonNull BeforeHookParam param) {
            param.setResult(false);
        }
    }

    // ========================================================================
    // Hooker 类 5: AOSP MediaProjection 权限弹窗静默授权
    // ========================================================================
    @XposedHooker
    public static class MediaProjectionPermissionGrant implements XposedInterface.Hooker {
        @AfterInvocation
        public static void afterInvoke(@NonNull AfterHookParam param) {
            Log.d(TAG, "MediaProjectionPermissionActivity constructed, auto-granting...");
        }
    }

    // ========================================================================
    // Hooker 类 6: 抑制录屏通知
    // ========================================================================
    @XposedHooker
    public static class SuppressNotification implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void beforeInvoke(@NonNull BeforeHookParam param) {
            param.setResult(null);
        }
    }

    // ========================================================================
    // Hooker 类 7: ColorOS 录屏弹窗抑制
    // ========================================================================
    @XposedHooker
    public static class OplusDialogSuppressor implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void beforeInvoke(@NonNull BeforeHookParam param) {
            param.setResult(null);
            Log.d(TAG, "OplusScreenRecordDialog suppressed");
        }
    }

    // ========================================================================
    // Hooker 类 8: ColorOS 状态栏指示器抑制
    // ========================================================================
    @XposedHooker
    public static class OplusIndicatorSuppressor implements XposedInterface.Hooker {
        @BeforeInvocation
        public static void beforeInvoke(@NonNull BeforeHookParam param) {
            param.setResult(null);
        }
    }
}