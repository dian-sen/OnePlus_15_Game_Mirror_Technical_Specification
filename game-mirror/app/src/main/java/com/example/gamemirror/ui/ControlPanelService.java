package com.example.gamemirror.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

import com.example.gamemirror.MainActivity;
import com.example.gamemirror.overlay.MirrorOverlayService;

/**
 * 侧边栏 / 悬浮球控制服务
 * 提供游戏内快捷开关：一键开关悬浮窗、进入框选模式
 */
public class ControlPanelService extends Service {

    private static final String CHANNEL_ID = "game_mirror_control";
    private static final int NOTIFICATION_ID = 0xAF03;

    private WindowManager windowManager;
    private View controlPanel;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        createControlPanel();
    }

    private void createControlPanel() {
        // 悬浮球容器
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(8, 8, 8, 8);
        panel.setAlpha(0.7f);

        // 开关悬浮窗按钮
        Button toggleBtn = new Button(this);
        toggleBtn.setText("显示/隐藏");
        toggleBtn.setTextSize(10);
        toggleBtn.setOnClickListener(v -> toggleOverlay());
        panel.addView(toggleBtn);

        // 框选模式按钮
        Button selectBtn = new Button(this);
        selectBtn.setText("框选");
        selectBtn.setTextSize(10);
        selectBtn.setOnClickListener(v -> enterSelectionMode());
        panel.addView(selectBtn);

        this.controlPanel = panel;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = 0;
        params.y = 0;

        windowManager.addView(controlPanel, params);
    }

    private void toggleOverlay() {
        Intent intent = new Intent(this, MirrorOverlayService.class);
        // 简单切换：如果正在运行则停止，否则启动
        stopService(intent);
        startForegroundService(intent);
    }

    private void enterSelectionMode() {
        // TODO: 启动全屏透明框选界面
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("selection_mode", true);
        startActivity(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (controlPanel != null && controlPanel.isAttachedToWindow()) {
            windowManager.removeView(controlPanel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "GameMirror 控制面板",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("GameMirror")
                .setContentText("控制面板运行中")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}