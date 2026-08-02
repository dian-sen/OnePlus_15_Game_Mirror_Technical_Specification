package com.example.gamemirror.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 持久化配置管理器
 * 管理 A/B 区域参数、透明度、镜像模式、帧率等配置
 */
public class ConfigManager {

    private static final String PREFS_NAME = "game_mirror_config";
    private static final String KEY_AREA_X = "area_x";
    private static final String KEY_AREA_Y = "area_y";
    private static final String KEY_AREA_W = "area_w";
    private static final String KEY_AREA_H = "area_h";
    private static final String KEY_OVERLAY_X = "overlay_x";
    private static final String KEY_OVERLAY_Y = "overlay_y";
    private static final String KEY_OVERLAY_W = "overlay_w";
    private static final String KEY_OVERLAY_H = "overlay_h";
    private static final String KEY_ALPHA = "alpha";
    private static final String KEY_MIRROR_MODE = "mirror_mode";
    private static final String KEY_FRAME_RATE = "frame_rate";
    private static final String KEY_SCREEN_W = "screen_w";
    private static final String KEY_SCREEN_H = "screen_h";

    // 镜像模式
    public static final int MIRROR_NONE = 0;
    public static final int MIRROR_HORIZONTAL = 1;
    public static final int MIRROR_VERTICAL = 2;
    public static final int MIRROR_BOTH = 3;

    private final SharedPreferences prefs;

    private int areaX, areaY, areaW, areaH;
    private int overlayX, overlayY, overlayW, overlayH;
    private float alpha;
    private int mirrorMode;
    private float frameRate;
    private int screenW, screenH;

    public ConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        areaX = prefs.getInt(KEY_AREA_X, 0);
        areaY = prefs.getInt(KEY_AREA_Y, 0);
        areaW = prefs.getInt(KEY_AREA_W, 400);
        areaH = prefs.getInt(KEY_AREA_H, 400);
        overlayX = prefs.getInt(KEY_OVERLAY_X, 100);
        overlayY = prefs.getInt(KEY_OVERLAY_Y, 200);
        overlayW = prefs.getInt(KEY_OVERLAY_W, 300);
        overlayH = prefs.getInt(KEY_OVERLAY_H, 300);
        alpha = prefs.getFloat(KEY_ALPHA, 0.85f);
        mirrorMode = prefs.getInt(KEY_MIRROR_MODE, MIRROR_NONE);
        frameRate = prefs.getFloat(KEY_FRAME_RATE, 165.0f);
        screenW = prefs.getInt(KEY_SCREEN_W, 1280);
        screenH = prefs.getInt(KEY_SCREEN_H, 2800);
    }

    // ========================================================================
    // A 区域（源裁剪区域）
    // ========================================================================

    public int getAreaX() { return areaX; }
    public int getAreaY() { return areaY; }
    public int getAreaWidth() { return areaW; }
    public int getAreaHeight() { return areaH; }

    public void setArea(int x, int y, int w, int h) {
        areaX = Math.max(0, x);
        areaY = Math.max(0, y);
        areaW = Math.max(1, w);
        areaH = Math.max(1, h);
        prefs.edit()
                .putInt(KEY_AREA_X, areaX)
                .putInt(KEY_AREA_Y, areaY)
                .putInt(KEY_AREA_W, areaW)
                .putInt(KEY_AREA_H, areaH)
                .apply();
    }

    // ========================================================================
    // B 区域（悬浮窗位置/尺寸）
    // ========================================================================

    public int getOverlayX() { return overlayX; }
    public int getOverlayY() { return overlayY; }
    public int getOverlayWidth() { return overlayW; }
    public int getOverlayHeight() { return overlayH; }

    public void setOverlayPosition(int x, int y) {
        overlayX = x;
        overlayY = y;
        prefs.edit()
                .putInt(KEY_OVERLAY_X, x)
                .putInt(KEY_OVERLAY_Y, y)
                .apply();
    }

    public void setOverlaySize(int w, int h) {
        overlayW = Math.max(50, w);
        overlayH = Math.max(50, h);
        prefs.edit()
                .putInt(KEY_OVERLAY_W, overlayW)
                .putInt(KEY_OVERLAY_H, overlayH)
                .apply();
    }

    // ========================================================================
    // 透明度
    // ========================================================================

    public float getAlpha() { return alpha; }

    public void setAlpha(float alpha) {
        this.alpha = Math.max(0.1f, Math.min(1.0f, alpha));
        prefs.edit().putFloat(KEY_ALPHA, this.alpha).apply();
    }

    // ========================================================================
    // 镜像模式
    // ========================================================================

    public int getMirrorMode() { return mirrorMode; }

    public void setMirrorMode(int mode) {
        this.mirrorMode = mode;
        prefs.edit().putInt(KEY_MIRROR_MODE, mode).apply();
    }

    public void cycleMirrorMode() {
        setMirrorMode((mirrorMode + 1) % 4);
    }

    // ========================================================================
    // 帧率
    // ========================================================================

    public float getFrameRate() { return frameRate; }

    public void setFrameRate(float rate) {
        this.frameRate = rate;
        prefs.edit().putFloat(KEY_FRAME_RATE, rate).apply();
    }

    // ========================================================================
    // 屏幕尺寸
    // ========================================================================

    public int getScreenWidth() { return screenW; }
    public int getScreenHeight() { return screenH; }

    public void setScreenSize(int w, int h) {
        this.screenW = w;
        this.screenH = h;
        prefs.edit()
                .putInt(KEY_SCREEN_W, w)
                .putInt(KEY_SCREEN_H, h)
                .apply();
    }
}