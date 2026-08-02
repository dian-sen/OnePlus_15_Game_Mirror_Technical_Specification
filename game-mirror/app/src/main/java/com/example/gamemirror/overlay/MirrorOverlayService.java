package com.example.gamemirror.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.example.gamemirror.MainActivity;
import com.example.gamemirror.capture.ScreenCaptureManager;
import com.example.gamemirror.config.ConfigManager;
import com.example.gamemirror.touch.TouchRedirector;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 悬浮窗 Overlay 服务 — 管理 B 区域悬浮窗的生命周期，协调画面采集与触控映射
 *
 * 支持 Action 指令：
 * - TOGGLE_MIRROR : 循环切换镜像模式
 * - INCREASE_ALPHA : 透明度 +0.05
 * - DECREASE_ALPHA : 透明度 -0.05
 * - STOP : 停止服务
 *
 * 一加 15 ColorOS 适配：前台服务 + ConfigManager 持久化
 */
public class MirrorOverlayService extends Service {

    private static final String TAG = "MirrorOverlay";
    private static final String CHANNEL_ID = "game_mirror_overlay";
    private static final int NOTIFICATION_ID = 0xAF02;

    public static final String ACTION_TOGGLE_MIRROR = "com.example.gamemirror.action.TOGGLE_MIRROR";
    public static final String ACTION_INCREASE_ALPHA = "com.example.gamemirror.action.INCREASE_ALPHA";
    public static final String ACTION_DECREASE_ALPHA = "com.example.gamemirror.action.DECREASE_ALPHA";
    public static final String ACTION_STOP = "com.example.gamemirror.action.STOP";

    private static final float ALPHA_STEP = 0.05f;

    private WindowManager windowManager;
    private MirrorOverlayView overlayView;
    private ScreenCaptureManager captureManager;
    private TouchRedirector touchRedirector;
    private ConfigManager configManager;

    private final CountDownLatch initLatch = new CountDownLatch(1);

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "MirrorOverlayService creating...");

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        configManager = new ConfigManager(this);
        touchRedirector = new TouchRedirector(this);
        captureManager = new ScreenCaptureManager(this);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        configManager.setScreenSize(metrics.widthPixels, metrics.heightPixels);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        overlayView = new MirrorOverlayView(this, windowManager, touchRedirector, configManager);
        windowManager.addView(overlayView, overlayView.getLayoutParams());

        touchRedirector.setArea(
                configManager.getAreaX(), configManager.getAreaY(),
                configManager.getAreaWidth(), configManager.getAreaHeight());

        initLatch.countDown();

        Log.i(TAG, "MirrorOverlayService started, overlay added (uinput="
                + touchRedirector.isUinputMode() + ")");
    }

    /**
     * 启动画面采集
     */
    public void startScreenCapture(Intent data, int resultCode) {
        if (captureManager == null || overlayView == null) {
            Log.e(TAG, "Service not fully initialized");
            return;
        }

        captureManager.startCapture(data, resultCode,
                overlayView.getGLRenderer().getInputSurface());

        overlayView.getGLRenderer().updateCropRect(
                configManager.getAreaX(), configManager.getAreaY(),
                configManager.getAreaWidth(), configManager.getAreaHeight());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if (action != null) {
                try {
                    if (!initLatch.await(500, TimeUnit.MILLISECONDS)) {
                        Log.e(TAG, "Service initialization timed out, ignoring action: " + action);
                        return START_STICKY;
                    }
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted waiting for init, ignoring action: " + action);
                    return START_STICKY;
                }

                switch (action) {
                    case ACTION_TOGGLE_MIRROR:
                        overlayView.cycleMirrorMode();
                        Log.i(TAG, "Mirror mode cycled to: " + configManager.getMirrorMode());
                        break;

                    case ACTION_INCREASE_ALPHA:
                        overlayView.setMirrorAlpha(overlayView.getMirrorAlpha() + ALPHA_STEP);
                        Log.i(TAG, "Alpha increased to: " + overlayView.getMirrorAlpha());
                        break;

                    case ACTION_DECREASE_ALPHA:
                        overlayView.setMirrorAlpha(overlayView.getMirrorAlpha() - ALPHA_STEP);
                        Log.i(TAG, "Alpha decreased to: " + overlayView.getMirrorAlpha());
                        break;

                    case ACTION_STOP:
                        stopSelf();
                        return START_NOT_STICKY;
                }
            }

            // 检查是否有 MediaProjection 数据
            if (intent.hasExtra("intent_clone")) {
                int resultCode = intent.getIntExtra("resultCode", -1);
                Intent data = (Intent) intent.getParcelableExtra("intent_clone");
                if (data != null) {
                    startScreenCapture(data, resultCode);
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayView != null) {
            if (overlayView.isAttachedToWindow()) {
                windowManager.removeView(overlayView);
            }
            overlayView.getGLRenderer().release();
        }
        if (captureManager != null) {
            captureManager.stopCapture();
        }
        if (touchRedirector != null) {
            touchRedirector.release();
        }
        Log.i(TAG, "MirrorOverlayService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "游戏镜像悬浮窗",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("一加15 画面提取与触控映射服务");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("GameMirror")
                .setContentText("画面提取与触控映射运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}