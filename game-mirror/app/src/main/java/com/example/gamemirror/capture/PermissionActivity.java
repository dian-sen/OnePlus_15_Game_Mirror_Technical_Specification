package com.example.gamemirror.capture;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

/**
 * 透明权限请求 Activity
 * 触发 MediaProjection 录屏权限 Intent，LSPosed 模块自动跳过弹窗静默授权
 */
public class PermissionActivity extends Activity {

    private static final String TAG = "PermissionActivity";
    private static final int REQUEST_CODE = 0xAF01;

    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);

        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(intent, REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "MediaProjection permission granted (via LSPosed)");
                Intent resultIntent = new Intent();
                resultIntent.putExtra("resultCode", resultCode);
                resultIntent.putExtra("intent_clone", data != null ? new Intent(data) : null);
                setResult(RESULT_OK, resultIntent);
            } else {
                Log.w(TAG, "MediaProjection permission denied");
                setResult(RESULT_CANCELED);
            }
            finish();
        }
    }
}