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
 * - uinput 虚拟设备（首选，vendor=0x1A15 一加15 标识）
 * - /dev/input/event* 物理设备直写（回退）
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
    private boolean isUinput = false;

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
            isUinput = nativeIsUinput(nativeHandle);
            Log.i(TAG, "Touch redirector initialized ("
                    + (isUinput ? "uinput" : "physical") + ")");
        } else {
            Log.w(TAG, "Native touch injection unavailable, will use InputManager fallback");
        }
    }

    /**
     * 将 B 区域触控重定向到 A 区域（单击）
     */
    public void redirectTouch(float bx, float by, int bw, int bh) {
        if (bw <= 0 || bh <= 0) return;

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
     * 将 B 区域滑动操作重定向到 A 区域
     *
     * @param fromBX   B 悬浮窗内起始 X
     * @param fromBY   B 悬浮窗内起始 Y
     * @param toBX     B 悬浮窗内目标 X
     * @param toBY     B 悬浮窗内目标 Y
     * @param bw       B 悬浮窗当前宽度
     * @param bh       B 悬浮窗当前高度
     * @param steps    滑动步数
     */
    public void redirectSwipe(float fromBX, float fromBY, float toBX, float toBY,
                              int bw, int bh, int steps) {
        if (bw <= 0 || bh <= 0) return;

        int fromXA = areaX + (int) (fromBX * ((float) areaWidth / bw));
        int fromYA = areaY + (int) (fromBY * ((float) areaHeight / bh));
        int toXA = areaX + (int) (toBX * ((float) areaWidth / bw));
        int toYA = areaY + (int) (toBY * ((float) areaHeight / bh));

        Log.d(TAG, "Swipe redirect: B(" + (int)fromBX + "," + (int)fromBY
                + ")->(" + (int)toBX + "," + (int)toBY
                + ") to A(" + fromXA + "," + fromYA + ")->(" + toXA + "," + toYA + ")");

        if (nativeInitialized) {
            nativeInjectSwipe(nativeHandle, fromXA, fromYA, toXA, toYA,
                    steps, MIRROR_SLOT_ID, MIRROR_TRACKING_ID);
        } else {
            injectSwipeFallback(fromXA, fromYA, toXA, toYA, steps);
        }
    }

    /**
     * 通过 Native 层注入点击事件
     */
    private void injectTouchNative(int xA, int yA) {
        nativeInjectTouch(nativeHandle, xA, yA, MIRROR_SLOT_ID, MIRROR_TRACKING_ID);
    }

    /**
     * 回退方案：通过 Android InputManager 注入点击
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
                    xA, yA, 0);
            im.injectInputEvent(down, android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            down.recycle();

            android.view.MotionEvent up = android.view.MotionEvent.obtain(
                    downTime, downTime + 50,
                    android.view.MotionEvent.ACTION_UP,
                    xA, yA, 0);
            im.injectInputEvent(up, android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            up.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Fallback touch injection failed: " + e.getMessage());
        }
    }

    /**
     * 回退方案：通过 Android InputManager 注入滑动
     */
    private void injectSwipeFallback(int fromX, int fromY, int toX, int toY, int steps) {
        try {
            android.hardware.input.InputManager im =
                    (android.hardware.input.InputManager)
                            android.app.ActivityThread.currentApplication()
                                    .getSystemService(android.content.Context.INPUT_SERVICE);

            long downTime = android.os.SystemClock.uptimeMillis();

            // Down
            android.view.MotionEvent down = android.view.MotionEvent.obtain(
                    downTime, downTime,
                    android.view.MotionEvent.ACTION_DOWN,
                    fromX, fromY, 0);
            im.injectInputEvent(down, android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            down.recycle();

            // Move steps
            float stepX = (float)(toX - fromX) / steps;
            float stepY = (float)(toY - fromY) / steps;
            for (int i = 1; i <= steps; i++) {
                int curX = fromX + (int)(stepX * i);
                int curY = fromY + (int)(stepY * i);
                android.view.MotionEvent move = android.view.MotionEvent.obtain(
                        downTime, downTime + (long)(i * 10),
                        android.view.MotionEvent.ACTION_MOVE,
                        curX, curY, 0);
                im.injectInputEvent(move, android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                move.recycle();
                if (i < steps) {
                    try { Thread.sleep(2); } catch (InterruptedException ignored) {}
                }
            }

            // Up
            android.view.MotionEvent up = android.view.MotionEvent.obtain(
                    downTime, downTime + (long)(steps * 10) + 50,
                    android.view.MotionEvent.ACTION_UP,
                    toX, toY, 0);
            im.injectInputEvent(up, android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            up.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Fallback swipe injection failed: " + e.getMessage());
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

    /**
     * 是否使用 uinput 虚拟设备（比物理设备更可靠）
     */
    public boolean isUinputMode() {
        return isUinput;
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

    private native long nativeInit();

    private native void nativeInjectTouch(long handle, int x, int y, int slotId, int trackingId);

    private native void nativeInjectSwipe(long handle, int fromX, int fromY, int toX, int toY,
                                          int steps, int slotId, int trackingId);

    private native boolean nativeIsUinput(long handle);

    private native void nativeRelease(long handle);
}