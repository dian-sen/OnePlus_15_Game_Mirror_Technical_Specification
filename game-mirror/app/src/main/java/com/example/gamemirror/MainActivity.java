package com.example.gamemirror;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.gamemirror.capture.PermissionActivity;
import com.example.gamemirror.overlay.MirrorOverlayService;

/**
 * 主入口 Activity
 * 一加 15 GameMirror 控制面板
 */
public class MainActivity extends Activity {

    private static final int REQUEST_OVERLAY_PERMISSION = 1001;

    private TextView statusText;
    private Button startButton;
    private Button overlayButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createLayout());
        checkBatteryOptimization();
    }

    private LinearLayout createLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        statusText = new TextView(this);
        statusText.setText("状态：就绪（一加15 / ColorOS）");
        statusText.setTextSize(16);
        statusText.setPadding(0, 0, 0, 32);
        layout.addView(statusText);

        startButton = new Button(this);
        startButton.setText("启动录屏权限");
        startButton.setOnClickListener(v -> requestScreenCapture());
        layout.addView(startButton);

        overlayButton = new Button(this);
        overlayButton.setText("打开悬浮窗");
        overlayButton.setOnClickListener(v -> openOverlay());
        layout.addView(overlayButton);

        return layout;
    }

    /**
     * 请求屏幕录制权限（通过透明 Activity 触发 MediaProjection Intent）
     */
    private void requestScreenCapture() {
        Intent intent = new Intent(this, PermissionActivity.class);
        startActivity(intent);
    }

    /**
     * 打开悬浮窗 Overlay
     */
    private void openOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            return;
        }

        Intent serviceIntent = new Intent(this, MirrorOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        statusText.setText("状态：悬浮窗已启动");
        Toast.makeText(this, "悬浮窗已启动，请切换到游戏", Toast.LENGTH_SHORT).show();
    }

    /**
     * 一加 ColorOS 电池优化白名单引导
     */
    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "请将应用添加到电池优化白名单以保证后台运行",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION && resultCode == RESULT_OK) {
            openOverlay();
        }
    }
}