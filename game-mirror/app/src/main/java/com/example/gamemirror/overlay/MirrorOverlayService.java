package com.example.gamemirror.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.WindowManager;

import com.example.gamemirror.MainActivity;
import com.example.gamemirror.capture.ScreenCaptureManager;
import com.example.gamemirror.touch.TouchRedirector;

/**
 * 悬浮窗 Overlay 服务
 * 管理 B 区域悬浮窗的生命周期，协调画面采集与触控映射
 *
 * 一加 15 ColorOS 适配：
 * - 前台服务保证后台存活
 * - 165Hz Surface 帧率绑定
 */
public class MirrorOverlayService extends Service {

    private static final String TAG = "MirrorOverlay";
    private static final String CHANNEL_ID = "game_mirror_overlay";
    private static final int NOTIFICATION_ID = 0xAF02;

    private WindowManager windowManager;
    private MirrorOverlayView overlayView;
    private ScreenCaptureManager captureManager;
    private TouchRedirector touchRedirector;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "MirrorOverlayService creating...");

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        touchRedirector = new TouchRedirector();
        captureManager = new ScreenCaptureManager(this);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        // 创建并添加悬浮窗
        overlayView = new MirrorOverlayView(this, windowManager, touchRedirector);
        windowManager.addView(overlayView, overlayView.getLayoutParams());

        Log.i(TAG, "MirrorOverlayService started, overlay added");
    }

    /**
     * 启动画面采集（由外部调用，传入已获取的 MediaProjection 数据）
     */
    public void startScreenCapture(Intent data, int resultCode) {
        if (captureManager == null || overlayView == null) {
            Log.e(TAG, "Service not fully initialized");
            return;
        }

        // 使用 OpenGL Surface 作为 VirtualDisplay 输出
        captureManager.startCapture(data, resultCode,
                overlayView.getGLRenderer().getInputSurface());

        // 设置渲染器裁剪区域
        overlayView.getGLRenderer().updateCropRect(
                captureManager.getAreaX(),
                captureManager.getAreaY(),
                captureManager.getAreaWidth(),
                captureManager.getAreaHeight()
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 检查是否有 MediaProjection 数据
        if (intent != null && intent.hasExtra("data")) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = intent.getParcelableExtra("data");
            if (data != null) {
                startScreenCapture(data, resultCode);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayView != null && overlayView.isAttachedToWindow()) {
            windowManager.removeView(overlayView);
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