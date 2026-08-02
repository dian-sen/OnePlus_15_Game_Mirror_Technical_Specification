package com.example.gamemirror;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.gamemirror.capture.PermissionActivity;
import com.example.gamemirror.config.ConfigManager;
import com.example.gamemirror.overlay.MirrorOverlayService;
import com.example.gamemirror.ui.AreaSelectionView;

/**
 * 主入口 Activity — 一加 15 GameMirror 控制面板
 *
 * 功能：
 * - 录屏权限请求
 * - 悬浮窗开关
 * - A 区域框选模式
 * - 快捷控制（镜像模式 / 透明度 / 停止）
 * - 状态信息面板
 */
public class MainActivity extends Activity {

    private static final int REQUEST_OVERLAY_PERMISSION = 1001;
    private static final int REQUEST_SCREEN_CAPTURE = 1002;

    private TextView statusText;
    private ConfigManager configManager;
    private AreaSelectionView selectionView;
    private boolean isSelectionMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configManager = new ConfigManager(this);
        setContentView(createLayout());
        checkBatteryOptimization();

        if (getIntent().getBooleanExtra("selection_mode", false)) {
            enterSelectionMode();
        }
    }

    private LinearLayout createLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        // 状态面板
        statusText = new TextView(this);
        statusText.setText("状态：就绪（一加15 / ColorOS）\n版本：1.0.2");
        statusText.setTextSize(14);
        statusText.setPadding(0, 0, 0, 24);
        layout.addView(statusText);

        // 录屏权限
        Button startButton = new Button(this);
        startButton.setText("启动录屏权限");
        startButton.setOnClickListener(v -> requestScreenCapture());
        layout.addView(startButton);

        // 悬浮窗
        Button overlayButton = new Button(this);
        overlayButton.setText("打开悬浮窗");
        overlayButton.setOnClickListener(v -> openOverlay());
        layout.addView(overlayButton);

        // 框选A区域
        Button selectButton = new Button(this);
        selectButton.setText("框选A区域");
        selectButton.setOnClickListener(v -> enterSelectionMode());
        layout.addView(selectButton);

        // 分隔
        TextView spacer = new TextView(this);
        spacer.setPadding(0, 16, 0, 8);
        spacer.setText("━ 快捷控制 ━");
        spacer.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        layout.addView(spacer);

        // 镜像模式切换
        Button mirrorButton = new Button(this);
        mirrorButton.setText("切换镜像模式");
        mirrorButton.setOnClickListener(v -> sendAction(MirrorOverlayService.ACTION_TOGGLE_MIRROR));
        layout.addView(mirrorButton);

        // 透明度调节
        LinearLayout alphaRow = new LinearLayout(this);
        alphaRow.setOrientation(LinearLayout.HORIZONTAL);

        Button alphaUpButton = new Button(this);
        alphaUpButton.setText("+透明度");
        alphaUpButton.setOnClickListener(v -> sendAction(MirrorOverlayService.ACTION_INCREASE_ALPHA));
        alphaRow.addView(alphaUpButton);

        Button alphaDownButton = new Button(this);
        alphaDownButton.setText("-透明度");
        alphaDownButton.setOnClickListener(v -> sendAction(MirrorOverlayService.ACTION_DECREASE_ALPHA));
        alphaRow.addView(alphaDownButton);

        layout.addView(alphaRow);

        // 停止服务
        Button stopButton = new Button(this);
        stopButton.setText("停止服务");
        stopButton.setOnClickListener(v -> sendAction(MirrorOverlayService.ACTION_STOP));
        layout.addView(stopButton);

        return layout;
    }

    /**
     * 请求屏幕录制权限（通过透明 PermissionActivity 触发 LSPosed 静默授权）
     */
    private void requestScreenCapture() {
        Intent intent = new Intent(this, PermissionActivity.class);
        startActivityForResult(intent, REQUEST_SCREEN_CAPTURE);
    }

    /**
     * 打开悬浮窗 Overlay 服务
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
     * 进入全屏框选模式
     */
    private void enterSelectionMode() {
        if (isSelectionMode) return;
        isSelectionMode = true;

        selectionView = new AreaSelectionView(this);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        selectionView.init(metrics.widthPixels, metrics.heightPixels, configManager,
                (x, y, w, h) -> {
                    exitSelectionMode();
                    Toast.makeText(MainActivity.this,
                            "A区域已设置: " + w + "x" + h, Toast.LENGTH_SHORT).show();
                    statusText.setText("状态：A区域 " + w + "x" + h + " @" + x + "," + y);
                });

        FrameLayout container = new FrameLayout(this);
        container.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(selectionView);
        setContentView(container);
    }

    private void exitSelectionMode() {
        isSelectionMode = false;
        selectionView = null;
        setContentView(createLayout());
    }

    /**
     * 发送 Action 指令到 MirrorOverlayService
     */
    private void sendAction(String action) {
        Intent intent = new Intent(this, MirrorOverlayService.class);
        intent.setAction(action);

        if (isServiceRunning(MirrorOverlayService.class)) {
            startService(intent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager != null) {
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 电池优化白名单引导
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

        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (resultCode == RESULT_OK) {
                openOverlay();
            }
            return;
        }

        if (requestCode == REQUEST_SCREEN_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent serviceIntent = new Intent(this, MirrorOverlayService.class);
            serviceIntent.putExtra("resultCode", data.getIntExtra("resultCode", -1));
            serviceIntent.putExtra("intent_clone", (Intent) data.getParcelableExtra("intent_clone"));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            statusText.setText("状态：录屏已授权，悬浮窗启动中");
            Toast.makeText(this, "录屏权限已获取，请切换到游戏", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (isSelectionMode) {
            exitSelectionMode();
            return;
        }
        super.onBackPressed();
    }
}