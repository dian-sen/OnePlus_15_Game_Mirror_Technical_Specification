package com.example.gamemirror.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.example.gamemirror.config.ConfigManager;

/**
 * 全屏透明框选视图 — 用于选择 A 区域（源区域）
 *
 * 交互方式：
 * - 单指拖拽四角手柄：调整选框大小
 * - 单指拖拽选框内部：移动选框位置
 * - 双指缩放：整体缩放选框
 * - 双击：确认选择并退出
 */
public class AreaSelectionView extends View {

    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_TL = 1;
    private static final int HANDLE_TR = 2;
    private static final int HANDLE_BL = 3;
    private static final int HANDLE_BR = 4;
    private static final int HANDLE_BODY = 5;

    private static final int HANDLE_SIZE = 48;
    private static final int HANDLE_RADIUS = 16;
    private static final int MIN_SELECTION = 50;

    private final Paint rectPaint;
    private final Paint handlePaint;
    private final Paint dashPaint;
    private final Paint textPaint;

    private final Rect selectionRect;
    private int screenW, screenH;

    private int activeHandle = HANDLE_NONE;
    private float startX, startY;
    private int startLeft, startTop, startRight, startBottom;

    private float lastPinchDist = 0;
    private boolean isPinching = false;

    private long lastTapTime = 0;
    private static final long DOUBLE_TAP_INTERVAL = 300;

    private OnSelectionConfirmedListener listener;
    private ConfigManager configManager;

    public interface OnSelectionConfirmedListener {
        void onSelectionConfirmed(int x, int y, int w, int h);
    }

    public AreaSelectionView(Context context) {
        this(context, null);
    }

    public AreaSelectionView(Context context, AttributeSet attrs) {
        super(context, attrs);

        rectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rectPaint.setColor(Color.argb(80, 255, 50, 50));
        rectPaint.setStyle(Paint.Style.FILL);

        dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dashPaint.setColor(Color.RED);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(3);
        dashPaint.setPathEffect(new DashPathEffect(new float[]{12, 8}, 0));

        handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setShadowLayer(6, 0, 2, Color.BLACK);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36);
        textPaint.setShadowLayer(4, 1, 1, Color.BLACK);

        selectionRect = new Rect();
    }

    /**
     * 初始化框选视图
     */
    public void init(int screenWidth, int screenHeight, ConfigManager config, OnSelectionConfirmedListener l) {
        this.screenW = screenWidth;
        this.screenH = screenHeight;
        this.configManager = config;
        this.listener = l;

        int w = configManager.getAreaWidth();
        int h = configManager.getAreaHeight();
        int x = (screenW - w) / 2;
        int y = (screenH - h) / 2;
        selectionRect.set(x, y, x + w, y + h);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawColor(Color.argb(120, 0, 0, 0));

        canvas.drawRect(selectionRect, rectPaint);
        canvas.drawRect(selectionRect, dashPaint);

        drawHandle(canvas, selectionRect.left, selectionRect.top);
        drawHandle(canvas, selectionRect.right, selectionRect.top);
        drawHandle(canvas, selectionRect.left, selectionRect.bottom);
        drawHandle(canvas, selectionRect.right, selectionRect.bottom);

        int w = selectionRect.width();
        int h = selectionRect.height();
        String info = w + " x " + h + "  (双击确认)";
        float textW = textPaint.measureText(info);
        canvas.drawText(info,
                selectionRect.centerX() - textW / 2,
                selectionRect.centerY() + 12,
                textPaint);
    }

    private void drawHandle(Canvas canvas, int cx, int cy) {
        canvas.drawCircle(cx, cy, HANDLE_RADIUS, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                long now = System.currentTimeMillis();
                if (now - lastTapTime < DOUBLE_TAP_INTERVAL) {
                    confirmSelection();
                    return true;
                }
                lastTapTime = now;

                activeHandle = detectHandle(x, y);
                startX = x;
                startY = y;
                if (activeHandle != HANDLE_NONE) {
                    startLeft = selectionRect.left;
                    startTop = selectionRect.top;
                    startRight = selectionRect.right;
                    startBottom = selectionRect.bottom;
                }
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() == 2) {
                    isPinching = true;
                    lastPinchDist = pinchDistance(event);
                    startLeft = selectionRect.left;
                    startTop = selectionRect.top;
                    startRight = selectionRect.right;
                    startBottom = selectionRect.bottom;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isPinching && event.getPointerCount() == 2) {
                    float newDist = pinchDistance(event);
                    if (lastPinchDist > 0) {
                        float scale = newDist / lastPinchDist;
                        scaleSelection(scale);
                        lastPinchDist = newDist;
                    }
                    return true;
                }

                if (activeHandle == HANDLE_NONE) return false;

                float dx = x - startX;
                float dy = y - startY;
                applyHandleMove(activeHandle, dx, dy);
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                isPinching = false;
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeHandle = HANDLE_NONE;
                isPinching = false;
                lastPinchDist = 0;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private int detectHandle(float x, float y) {
        if (hitTest(x, y, selectionRect.left, selectionRect.top)) return HANDLE_TL;
        if (hitTest(x, y, selectionRect.right, selectionRect.top)) return HANDLE_TR;
        if (hitTest(x, y, selectionRect.left, selectionRect.bottom)) return HANDLE_BL;
        if (hitTest(x, y, selectionRect.right, selectionRect.bottom)) return HANDLE_BR;
        if (selectionRect.contains((int) x, (int) y)) return HANDLE_BODY;
        return HANDLE_NONE;
    }

    private boolean hitTest(float x, float y, int cx, int cy) {
        return Math.abs(x - cx) <= HANDLE_SIZE && Math.abs(y - cy) <= HANDLE_SIZE;
    }

    private void applyHandleMove(int handle, float dx, float dy) {
        int newL = startLeft, newT = startTop, newR = startRight, newB = startBottom;

        switch (handle) {
            case HANDLE_TL:
                newL = clampX(startLeft + (int) dx);
                newT = clampY(startTop + (int) dy);
                break;
            case HANDLE_TR:
                newR = clampX(startRight + (int) dx);
                newT = clampY(startTop + (int) dy);
                break;
            case HANDLE_BL:
                newL = clampX(startLeft + (int) dx);
                newB = clampY(startBottom + (int) dy);
                break;
            case HANDLE_BR:
                newR = clampX(startRight + (int) dx);
                newB = clampY(startBottom + (int) dy);
                break;
            case HANDLE_BODY:
                int bodyDx = (int) dx;
                int bodyDy = (int) dy;
                if (startLeft + bodyDx < 0) bodyDx = -startLeft;
                if (startTop + bodyDy < 0) bodyDy = -startTop;
                if (startRight + bodyDx > screenW) bodyDx = screenW - startRight;
                if (startBottom + bodyDy > screenH) bodyDy = screenH - startBottom;
                newL = startLeft + bodyDx;
                newT = startTop + bodyDy;
                newR = startRight + bodyDx;
                newB = startBottom + bodyDy;
                break;
        }

        if (newR - newL < MIN_SELECTION) {
            if (handle == HANDLE_TL || handle == HANDLE_BL) newL = newR - MIN_SELECTION;
            else newR = newL + MIN_SELECTION;
        }
        if (newB - newT < MIN_SELECTION) {
            if (handle == HANDLE_TL || handle == HANDLE_TR) newT = newB - MIN_SELECTION;
            else newB = newT + MIN_SELECTION;
        }

        selectionRect.set(newL, newT, newR, newB);
    }

    private void scaleSelection(float scale) {
        int cx = selectionRect.centerX();
        int cy = selectionRect.centerY();
        int hw = (int) ((startRight - startLeft) * scale / 2);
        int hh = (int) ((startBottom - startTop) * scale / 2);

        int newL = clampX(cx - hw);
        int newR = clampX(cx + hw);
        int newT = clampY(cy - hh);
        int newB = clampY(cy + hh);

        if (newR - newL >= MIN_SELECTION && newB - newT >= MIN_SELECTION) {
            selectionRect.set(newL, newT, newR, newB);
        }
        invalidate();
    }

    private float pinchDistance(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private int clampX(int x) {
        return Math.max(0, Math.min(x, screenW));
    }

    private int clampY(int y) {
        return Math.max(0, Math.min(y, screenH));
    }

    private void confirmSelection() {
        int x = selectionRect.left;
        int y = selectionRect.top;
        int w = selectionRect.width();
        int h = selectionRect.height();

        if (configManager != null) {
            configManager.setArea(x, y, w, h);
        }

        if (listener != null) {
            listener.onSelectionConfirmed(x, y, w, h);
        }
    }

    public Rect getSelection() {
        return new Rect(selectionRect);
    }
}