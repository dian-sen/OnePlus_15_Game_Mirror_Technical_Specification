package com.example.gamemirror.touch;

import android.util.Log;

/**
 * 触控映射重定向器
 * 将 B 悬浮窗的触控事件映射到屏幕 A 区域
 *
 * 坐标转换公式：
 *   xA = Ax + dx * (Aw / Bw)
 *   yA = Ay + dy * (Ah / Bh)
 *
 * 触控注入策略：
 * - 使用独立 Slot ID (1) 避免干扰游戏主摇杆操作
 * - 通过 Native JNI 直接操作 /dev/input/event*
 * - 目标延迟 ≤ 3ms
 */
public class TouchRedirector {

    private static final String TAG = "TouchRedirector";

    // A 区域（源区域）屏幕绝对坐标
    private int areaX = 0;
    private int areaY = 0;
    private int areaWidth = 400;
    private int areaHeight = 400;

    // Native 层引用
    private long nativeHandle = 0;
    private boolean nativeInitialized = false;

    // 独立 Slot ID（避免与游戏主操作冲突）
    private static final int MIRROR_SLOT_ID = 1;
    private static final int MIRROR_TRACKING_ID = 200;

    static {
        try {
            System.loadLibrary("touch_injector");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library: " + e.getMessage());
        }
    }

    public TouchRedirector() {
        nativeHandle = nativeInit();
        nativeInitialized = (nativeHandle != 0);
        if (nativeInitialized) {
            Log.i(TAG, "Touch redirector native layer initialized");
        } else {
            Log.w(TAG, "Native touch injection unavailable, will use InputManager fallback");
        }
    }

    /**
     * 将 B 区域触控重定向到 A 区域
     *
     * @param bx  B 悬浮窗内相对 X 坐标
     * @param by  B 悬浮窗内相对 Y 坐标
     * @param bw  B 悬浮窗当前宽度
     * @param bh  B 悬浮窗当前高度
     */
    public void redirectTouch(float bx, float by, int bw, int bh) {
        if (bw <= 0 || bh <= 0) return;

        // 坐标转换：B 相对坐标 → A 屏幕绝对坐标
        int xA = areaX + (int) (bx * ((float) areaWidth / bw));
        int yA = areaY + (int) (by * ((float) areaHeight / bh));

        Log.d(TAG, "Touch redirect: B(" + (int)bx + "," + (int)by + ") -> A(" + xA + "," + yA + ")");

        if (nativeInitialized) {
            injectTouchNative(xA, yA);
        } else {
            injectTouchFallback(xA, yA);
        }
    }

    /**
     * 通过 Native 层注入触控事件（推荐，延迟最低）
     */
    private void injectTouchNative(int xA, int yA) {
        nativeInjectTouch(nativeHandle, xA, yA, MIRROR_SLOT_ID, MIRROR_TRACKING_ID);
    }

    /**
     * 回退方案：通过 Android InputManager 注入
     */
    private void injectTouchFallback(int xA, int yA) {
        try {
            android.hardware.input.InputManager im =
                    (android.hardware.input.InputManager)
                            android.app.ActivityThread.currentApplication()
                                    .getSystemService(android.content.Context.INPUT_SERVICE);

            long downTime = android.os.SystemClock.uptimeMillis();

            android.view.MotionEvent down = android.view.MotionEvent.obtain(
                    downTime, downTime,
                    android.view.MotionEvent.ACTION_DOWN,
                    xA, yA, 0
            );
            im.injectInputEvent(down, android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            down.recycle();

            android.view.MotionEvent up = android.view.MotionEvent.obtain(
                    downTime, downTime + 50,
                    android.view.MotionEvent.ACTION_UP,
                    xA, yA, 0
            );
            im.injectInputEvent(up, android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            up.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Fallback touch injection failed: " + e.getMessage());
        }
    }

    /**
     * 更新 A 区域参数
     */
    public void setArea(int x, int y, int width, int height) {
        this.areaX = x;
        this.areaY = y;
        this.areaWidth = width;
        this.areaHeight = height;
    }

    public void release() {
        if (nativeHandle != 0) {
            nativeRelease(nativeHandle);
            nativeHandle = 0;
        }
        nativeInitialized = false;
    }

    // ========================================================================
    // Native 方法声明
    // ========================================================================

    /**
     * 初始化 Native 层，打开 /dev/input/event* 设备
     * @return native handle（0 表示失败）
     */
    private native long nativeInit();

    /**
     * 向 A 区域注入点击事件
     * @param handle     native handle
     * @param x          屏幕绝对 X 坐标
     * @param y          屏幕绝对 Y 坐标
     * @param slotId     独立 Slot ID
     * @param trackingId 触控跟踪 ID
     */
    private native void nativeInjectTouch(long handle, int x, int y, int slotId, int trackingId);

    /**
     * 释放 Native 资源
     */
    private native void nativeRelease(long handle);
}