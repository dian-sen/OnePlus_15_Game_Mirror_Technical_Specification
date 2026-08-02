package com.example.gamemirror.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

/**
 * 画面采集管理器
 * 基于 MediaProjection + VirtualDisplay + Surface
 * 实现无感后台录屏，支持 165Hz 高帧率
 *
 * 一加 15 适配：通过 LSPosed 模块免弹窗授权，ColorOS 下静默采集
 */
public class ScreenCaptureManager {

    private static final String TAG = "ScreenCapture";
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 2800;
    private static final float TARGET_FRAME_RATE = 165.0f;

    private final Context context;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private Surface inputSurface;

    private HandlerThread captureThread;
    private Handler captureHandler;

    private boolean isCapturing = false;

    // A 区域参数（源区域在屏幕上的绝对位置）
    private int areaX = 0;
    private int areaY = 0;
    private int areaWidth = 400;
    private int areaHeight = 400;

    public ScreenCaptureManager(Context context) {
        this.context = context;
        this.projectionManager = (MediaProjectionManager)
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    /**
     * 启动画面采集（需要先在 PermissionActivity 中获取 MediaProjection Intent）
     */
    public boolean startCapture(Intent mediaProjectionData, int resultCode, Surface outputSurface) {
        if (mediaProjectionData == null || resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "Invalid MediaProjection data");
            return false;
        }

        try {
            // 通过 LSPosed Hook 后此处 getMediaProjection 不会弹窗
            mediaProjection = projectionManager.getMediaProjection(resultCode, mediaProjectionData);

            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection (ensure LSPosed module is active)");
                return false;
            }

            captureThread = new HandlerThread("CaptureThread");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());

            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int density = metrics.densityDpi;

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "GameMirror-VD",
                    DEFAULT_WIDTH,
                    DEFAULT_HEIGHT,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    outputSurface,
                    null,   // VirtualDisplay.Callback
                    captureHandler
            );

            // 强制设置 165Hz 帧率（一加 15 高刷适配）
            if (outputSurface != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                outputSurface.setFrameRate(TARGET_FRAME_RATE,
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            }

            isCapturing = true;
            Log.i(TAG, "Screen capture started at " + TARGET_FRAME_RATE + "fps");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start screen capture: " + e.getMessage());
            stopCapture();
            return false;
        }
    }

    /**
     * 停止画面采集
     */
    public void stopCapture() {
        isCapturing = false;

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }

        Log.i(TAG, "Screen capture stopped");
    }

    /**
     * 更新 A 区域（源裁剪区域）参数
     */
    public void setCropArea(int x, int y, int width, int height) {
        this.areaX = x;
        this.areaY = y;
        this.areaWidth = width;
        this.areaHeight = height;

        if (virtualDisplay != null) {
            virtualDisplay.resize(width, height, 160);
            virtualDisplay.surfaceChanged(inputSurface, 0, width, height);
        }
    }

    public boolean isCapturing() {
        return isCapturing && mediaProjection != null;
    }

    public int getAreaX() { return areaX; }
    public int getAreaY() { return areaY; }
    public int getAreaWidth() { return areaWidth; }
    public int getAreaHeight() { return areaHeight; }
}