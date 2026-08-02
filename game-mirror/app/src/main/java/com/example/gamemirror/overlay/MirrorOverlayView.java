package com.example.gamemirror.overlay;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.example.gamemirror.capture.GLRenderer;
import com.example.gamemirror.config.ConfigManager;
import com.example.gamemirror.touch.TouchRedirector;

/**
 * 悬浮窗 Overlay 容器 View — B 区域的渲染与触控事件拦截
 *
 * 包含：
 * - GLSurfaceView：GPU 渲染 A 区域裁剪画面
 * - 双指缩放 / 双击重置 / 边缘吸附
 * - 镜像模式切换（Action 指令触发）
 * - ConfigManager 持久化
 */
public class MirrorOverlayView extends FrameLayout {

    private final WindowManager windowManager;
    private final WindowManager.LayoutParams layoutParams;
    private final GLSurfaceView glSurfaceView;
    private final GLRenderer glRenderer;
    private final TouchRedirector touchRedirector;
    private final ConfigManager configManager;

    // 拖拽状态
    private float initialTouchX, initialTouchY;
    private int initialWindowX, initialWindowY;
    private boolean isDragging = false;
    private static final float DRAG_THRESHOLD = 10f;

    // 双指缩放状态
    private float lastPinchDist = 0;
    private boolean isPinching = false;
    private int pinchStartW, pinchStartH;

    // 双击检测
    private long lastTapTime = 0;
    private static final long DOUBLE_TAP_INTERVAL = 300;

    // 短按延迟重定向（区分悬浮窗操作与游戏触控）
    private final Handler touchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingRedirect = null;
    private static final long TOUCH_REDIRECT_DELAY_MS = 120;
    private float pendingRedirectX, pendingRedirectY;

    // 边缘吸附
    private static final int EDGE_SNAP_THRESHOLD = 30;
    private static final int SNAP_MARGIN = 8;

    // B 区域默认尺寸
    private static final int DEFAULT_WIDTH = 300;
    private static final int DEFAULT_HEIGHT = 300;
    private static final int MIN_SIZE = 80;
    private static final int MAX_SIZE = 600;

    private int viewWidth;
    private int viewHeight;

    public MirrorOverlayView(Context context, WindowManager wm, TouchRedirector redirector,
                             ConfigManager config) {
        super(context);
        this.windowManager = wm;
        this.touchRedirector = redirector;
        this.configManager = config;

        viewWidth = config.getOverlayWidth();
        viewHeight = config.getOverlayHeight();

        glSurfaceView = new GLSurfaceView(context);
        glSurfaceView.setEGLContextClientVersion(2);
        glRenderer = new GLRenderer();
        glSurfaceView.setRenderer(glRenderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        addView(glSurfaceView, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        glRenderer.setMirrorMode(config.getMirrorMode());
        glRenderer.setTargetFrameRate(config.getFrameRate());

        setupTouchListener();

        layoutParams = new WindowManager.LayoutParams(
                viewWidth,
                viewHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = config.getOverlayX();
        layoutParams.y = config.getOverlayY();
        layoutParams.alpha = config.getAlpha();
    }

    /**
     * 设置触控事件监听
     * - 单击（无移动）→ 触控重定向到 A 区域
     * - 单指拖拽 → 移动悬浮窗
     * - 双指缩放 → 调整悬浮窗大小
     * - 双击 → 重置到默认尺寸
     */
    private void setupTouchListener() {
        setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    initialWindowX = layoutParams.x;
                    initialWindowY = layoutParams.y;
                    isDragging = false;

                    long now = System.currentTimeMillis();
                    if (now - lastTapTime < DOUBLE_TAP_INTERVAL) {
                        if (pendingRedirect != null) {
                            touchHandler.removeCallbacks(pendingRedirect);
                            pendingRedirect = null;
                        }
                        resetToDefault();
                        return true;
                    }
                    lastTapTime = now;
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() == 2) {
                        isPinching = true;
                        pinchStartW = viewWidth;
                        pinchStartH = viewHeight;
                        lastPinchDist = pinchDistance(event);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (isPinching && event.getPointerCount() == 2) {
                        float newDist = pinchDistance(event);
                        if (lastPinchDist > 0) {
                            float scale = newDist / lastPinchDist;
                            int newW = (int) (pinchStartW * scale);
                            int newH = (int) (pinchStartH * scale);
                            newW = Math.max(MIN_SIZE, Math.min(MAX_SIZE, newW));
                            newH = Math.max(MIN_SIZE, Math.min(MAX_SIZE, newH));
                            setSize(newW, newH);
                            lastPinchDist = newDist;
                        }
                        return true;
                    }

                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                        isDragging = true;
                        layoutParams.x = initialWindowX + (int) dx;
                        layoutParams.y = initialWindowY + (int) dy;
                        windowManager.updateViewLayout(this, layoutParams);
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    isPinching = false;
                    lastPinchDist = 0;
                    return true;

                case MotionEvent.ACTION_UP:
                    if (isPinching) {
                        isPinching = false;
                        lastPinchDist = 0;
                        configManager.setOverlaySize(viewWidth, viewHeight);
                        configManager.setOverlayPosition(layoutParams.x, layoutParams.y);
                        return true;
                    }

                    if (!isDragging) {
                        final float bx = event.getX();
                        final float by = event.getY();
                        pendingRedirectX = bx;
                        pendingRedirectY = by;

                        if (pendingRedirect != null) {
                            touchHandler.removeCallbacks(pendingRedirect);
                        }

                        pendingRedirect = () -> {
                            touchRedirector.redirectTouch(pendingRedirectX, pendingRedirectY,
                                    viewWidth, viewHeight);
                            pendingRedirect = null;
                        };
                        touchHandler.postDelayed(pendingRedirect, TOUCH_REDIRECT_DELAY_MS);
                    } else {
                        snapToEdge();
                        configManager.setOverlayPosition(layoutParams.x, layoutParams.y);
                    }
                    return true;
            }
            return true;
        });
    }

    /**
     * 边缘吸附：靠近屏幕边缘时自动吸附
     */
    private void snapToEdge() {
        int screenW = configManager.getScreenWidth();
        if (screenW <= 0) return;

        int x = layoutParams.x;

        if (x < EDGE_SNAP_THRESHOLD) {
            layoutParams.x = SNAP_MARGIN;
        } else if (x + viewWidth > screenW - EDGE_SNAP_THRESHOLD) {
            layoutParams.x = screenW - viewWidth - SNAP_MARGIN;
        }

        if (layoutParams.x < 0) layoutParams.x = SNAP_MARGIN;
        if (layoutParams.x + viewWidth > screenW) {
            layoutParams.x = screenW - viewWidth - SNAP_MARGIN;
        }

        windowManager.updateViewLayout(this, layoutParams);
    }

    /**
     * 双击重置到默认尺寸
     */
    private void resetToDefault() {
        setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        configManager.setOverlaySize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * 循环切换镜像模式
     */
    public void cycleMirrorMode() {
        configManager.cycleMirrorMode();
        glRenderer.setMirrorMode(configManager.getMirrorMode());
    }

    public WindowManager.LayoutParams getLayoutParams() {
        return layoutParams;
    }

    public GLSurfaceView getGLSurfaceView() {
        return glSurfaceView;
    }

    public GLRenderer getGLRenderer() {
        return glRenderer;
    }

    /**
     * 更新 B 区域尺寸
     */
    public void setSize(int width, int height) {
        this.viewWidth = width;
        this.viewHeight = height;
        layoutParams.width = width;
        layoutParams.height = height;
        if (isAttachedToWindow()) {
            windowManager.updateViewLayout(this, layoutParams);
        }
    }

    /**
     * 设置透明度
     */
    public void setMirrorAlpha(float alpha) {
        alpha = Math.max(0.1f, Math.min(1.0f, alpha));
        layoutParams.alpha = alpha;
        if (isAttachedToWindow()) {
            windowManager.updateViewLayout(this, layoutParams);
        }
        configManager.setAlpha(alpha);
    }

    public float getMirrorAlpha() {
        return layoutParams.alpha;
    }

    private float pinchDistance(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}