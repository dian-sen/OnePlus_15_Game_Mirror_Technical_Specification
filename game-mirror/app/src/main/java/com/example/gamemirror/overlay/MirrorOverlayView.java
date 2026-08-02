package com.example.gamemirror.overlay;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.example.gamemirror.capture.GLRenderer;
import com.example.gamemirror.touch.TouchRedirector;

/**
 * 悬浮窗 Overlay 容器 View
 * 负责 B 区域的渲染与触控事件拦截
 *
 * 包含：
 * - GLSurfaceView：GPU 渲染 A 区域裁剪画面
 * - 拖拽/缩放/透明度控制
 * - 触控事件拦截并转发至 TouchRedirector
 */
public class MirrorOverlayView extends FrameLayout {

    private final WindowManager windowManager;
    private final WindowManager.LayoutParams layoutParams;
    private final GLSurfaceView glSurfaceView;
    private final GLRenderer glRenderer;
    private final TouchRedirector touchRedirector;

    // 拖拽状态
    private float initialTouchX;
    private float initialTouchY;
    private int initialWindowX;
    private int initialWindowY;
    private boolean isDragging = false;
    private static final float DRAG_THRESHOLD = 10f;

    // B 区域当前尺寸
    private int viewWidth = 300;
    private int viewHeight = 300;

    public MirrorOverlayView(Context context, WindowManager wm, TouchRedirector redirector) {
        super(context);
        this.windowManager = wm;
        this.touchRedirector = redirector;

        // 创建 GLSurfaceView 用于 GPU 渲染
        glSurfaceView = new GLSurfaceView(context);
        glSurfaceView.setEGLContextClientVersion(2);
        glRenderer = new GLRenderer();
        glSurfaceView.setRenderer(glRenderer);
        // 165Hz 渲染模式
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        addView(glSurfaceView, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 设置触控监听
        setupTouchListener();

        // 配置 WindowManager 参数
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
        layoutParams.x = 100;
        layoutParams.y = 200;
        layoutParams.alpha = 0.85f;

        // 一加 15 165Hz 高刷适配
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            layoutParams.preferredDisplayModeId = find165HzModeId();
        }
    }

    /**
     * 设置触控事件监听
     * 拦截 B 区域所有触控，判断拖拽/点击：
     * - 短点击 + 无移动 → 触控重定向到 A 区域
     * - 长按拖动 → 移动悬浮窗位置
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
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                        isDragging = true;
                        layoutParams.x = initialWindowX + (int) dx;
                        layoutParams.y = initialWindowY + (int) dy;
                        windowManager.updateViewLayout(this, layoutParams);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        // 非拖拽 → 触控重定向到 A 区域
                        float bx = event.getX();
                        float by = event.getY();
                        touchRedirector.redirectTouch(bx, by, viewWidth, viewHeight);
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    // 多点触控：双指缩放
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    return true;
            }
            return true;
        });
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
        layoutParams.alpha = Math.max(0.1f, Math.min(1.0f, alpha));
        if (isAttachedToWindow()) {
            windowManager.updateViewLayout(this, layoutParams);
        }
    }

    /**
     * 一加 15 165Hz 模式查找
     */
    private int find165HzModeId() {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            getContext().getSystemService(Context.DISPLAY_SERVICE);
            android.view.Display display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            android.view.Display.Mode[] modes = display.getSupportedModes();

            for (android.view.Display.Mode mode : modes) {
                if (mode.getRefreshRate() >= 165.0f) {
                    return mode.getModeId();
                }
            }
            // 回退到最高刷新率
            for (android.view.Display.Mode mode : modes) {
                if (mode.getRefreshRate() >= 120.0f) {
                    return mode.getModeId();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }
}