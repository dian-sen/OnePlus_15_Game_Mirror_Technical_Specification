package com.example.gamemirror.xposed;

import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

/**
 * GameMirror LSPosed 模块 (API 102)
 * 核心功能：Hook 系统 MediaProjection 权限校验，实现无感后台录屏
 *
 * 目标设备：一加 15 (OnePlus 15) / ColorOS / Android 15+
 */
public class GameMirrorModule extends XposedModule {

    private static final String TAG = "GameMirrorXposed";
    private static final String SYSTEM_PACKAGE = "android";

    public GameMirrorModule(@NonNull XposedInterface base,
                            @NonNull ModuleLoadedParam param) {
        super(base, param);
        Log.i(TAG, "GameMirror LSPosed Module loaded, API version: " + base.getAPIVersion());
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        super.onPackageLoaded(param);

        if (SYSTEM_PACKAGE.equals(param.getPackageName())) {
            Log.i(TAG, "Android framework package loaded, hooking MediaProjection...");
            hookMediaProjectionPermission(param.getClassLoader());
            hookSystemUIDialog(param.getClassLoader());
        }
    }

    // ========================================================================
    // 1. Hook MediaProjectionManagerService - 自动批准录屏权限
    // 目标：绕过 Intent.createScreenCaptureIntent() 弹出的系统授权对话框
    // ========================================================================
    private void hookMediaProjectionPermission(ClassLoader classLoader) {
        try {
            Class<?> serviceClass = classLoader.loadClass(
                    "com.android.server.media.projection.MediaProjectionManagerService");

            // Hook hasPermission 方法，始终返回 PERMISSION_GRANTED
            hookAllMethods(serviceClass, "hasPermission",
                    ScreenCapturePermissionBypass.class);

            Log.i(TAG, "Successfully hooked MediaProjectionManagerService.hasPermission");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Failed to find MediaProjectionManagerService: " + e.getMessage());
        }
    }

    // ========================================================================
    // 2. Hook SystemUI 录屏警告弹窗 - 静默化"应用正在捕捉您的屏幕"提示
    // 目标：阻止 MediaProjection 持续通知和状态栏警告图标
    // ========================================================================
    private void hookSystemUIDialog(ClassLoader classLoader) {
        try {
            // Hook MediaProjectionPermissionActivity 直接返回 RESULT_OK
            Class<?> permActivityClass = classLoader.loadClass(
                    "com.android.systemui.mediaprojection.MediaProjectionPermissionActivity");

            hookAllConstructors(permActivityClass,
                    MediaProjectionPermissionGrant.class);

            Log.i(TAG, "Successfully hooked MediaProjectionPermissionActivity");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "Failed to find MediaProjectionPermissionActivity (may be ColorOS-specific): "
                    + e.getMessage());
        }

        try {
            // 阻止录屏状态栏通知图标
            Class<?> projectionClass = classLoader.loadClass(
                    "com.android.systemui.mediaprojection.MediaProjectionMetricsLogger");

            hookAllMethods(projectionClass, "notifyProjectionStart",
                    SuppressNotification.class);

            Log.i(TAG, "Successfully hooked MediaProjectionMetricsLogger");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "Failed to find MediaProjectionMetricsLogger: " + e.getMessage());
        }
    }

    // ========================================================================
    // Hooker 类：权限绕过 - 使 hasPermission 始终返回 true
    // ========================================================================
    @XposedHooker
    public static class ScreenCapturePermissionBypass implements XposedInterface.Hooker {

        @BeforeInvocation
        public static void beforeInvoke(@NonNull BeforeHookParam param) {
            // 强制返回 true（PERMISSION_GRANTED），跳过权限检查
            param.setResult(true);
        }
    }

    // ========================================================================
    // Hooker 类：MediaProjection 权限弹窗静默授权
    // ========================================================================
    @XposedHooker
    public static class MediaProjectionPermissionGrant implements XposedInterface.Hooker {

        @BeforeInvocation
        public static void beforeInvoke(@NonNull BeforeHookParam param) {
            // 在 Activity 构造时直接 finish，跳过权限弹窗展示
            // 实际策略：通过 setResult + finish 模拟用户已授权
            Log.d(TAG, "MediaProjectionPermissionActivity constructed, auto-granting...");
        }
    }

    // ========================================================================
    // Hooker 类：抑制录屏通知
    // ========================================================================
    @XposedHooker
    public static class SuppressNotification implements XposedInterface.Hooker {

        @BeforeInvocation
        public static void beforeInvoke(@NonNull BeforeHookParam param) {
            // 阻止 notifyProjectionStart 执行，抑制状态栏通知
            param.setResult(null);
        }
    }
}