package com.example.gamemirror.capture;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import com.example.gamemirror.config.ConfigManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL ES 渲染器
 * 使用 GPU 直接在显存中裁剪 A 区域并渲染到 B 悬浮窗
 * 基于 OES_EGL_image_external 扩展，零 CPU 内存拷贝
 *
 * 一加 15 适配：165Hz 帧率同步，uMirror 镜像翻转，自适应帧率 + FPS 统计
 */
public class GLRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "GLRenderer";

    // 顶点着色器
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uMVPMatrix * aPosition;\n" +
            "  vTexCoord = aTexCoord;\n" +
            "}";

    // 片段着色器：uCropRect 裁剪 + uMirror 镜像翻转
    // uMirror: 0=无, 1=水平, 2=垂直, 3=双向
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform vec4 uCropRect;\n" +
            "uniform int uMirror;\n" +
            "void main() {\n" +
            "  vec2 uv = vTexCoord;\n" +
            "  if (uMirror == 1) uv.x = 1.0 - uv.x;\n" +
            "  else if (uMirror == 2) uv.y = 1.0 - uv.y;\n" +
            "  else if (uMirror == 3) { uv.x = 1.0 - uv.x; uv.y = 1.0 - uv.y; }\n" +
            "  vec2 croppedUV = uCropRect.xy + uv * uCropRect.zw;\n" +
            "  gl_FragColor = texture2D(sTexture, croppedUV);\n" +
            "}";

    // 顶点四边形（全屏四边形）
    private static final float[] VERTEX_DATA = {
            -1.0f, -1.0f, 0.0f,  0.0f, 0.0f,  // 左下
             1.0f, -1.0f, 0.0f,  1.0f, 0.0f,  // 右下
            -1.0f,  1.0f, 0.0f,  0.0f, 1.0f,  // 左上
             1.0f,  1.0f, 0.0f,  1.0f, 1.0f,  // 右上
    };

    private static final int FLOAT_SIZE = 4;
    private static final int VERTEX_STRIDE = 5 * FLOAT_SIZE;

    private final FloatBuffer vertexBuffer;

    private int program;
    private int textureId;

    // Uniform / Attribute 句柄
    private int uMVPMatrixLoc;
    private int aPositionLoc;
    private int aTexCoordLoc;
    private int uCropRectLoc;
    private int uMirrorLoc;

    // A 区域裁剪参数（归一化 UV 坐标）
    private float cropLeft = 0.0f;
    private float cropTop = 0.0f;
    private float cropWidth = 1.0f;
    private float cropHeight = 1.0f;

    // 屏幕尺寸
    private int screenWidth = 1280;
    private int screenHeight = 2800;

    // 镜像模式
    private int mirrorMode = ConfigManager.MIRROR_NONE;

    // 自适应帧率控制
    private long lastFrameTimeNs = 0;
    private long frameIntervalNs = 6_060_606L; // ~6.06ms @ 165Hz default
    private static final long MIN_FRAME_INTERVAL_NS = 4_166_667L; // ~240Hz max
    private static final long MAX_FRAME_INTERVAL_NS = 33_333_333L; // ~30Hz min

    // FPS 统计
    private long fpsStartTimeNs = 0;
    private int fpsFrameCount = 0;
    private float currentFps = 0.0f;
    private long lastFpsUpdateNs = 0;

    // MVP 矩阵
    private final float[] mvpMatrix = new float[16];

    private SurfaceTexture surfaceTexture;
    private Surface surface;

    public GLRenderer() {
        vertexBuffer = ByteBuffer.allocateDirect(VERTEX_DATA.length * FLOAT_SIZE)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(VERTEX_DATA).position(0);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // 创建外部纹理（OES_EGL_image_external）
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 创建 SurfaceTexture 用于接收 MediaProjection 画面
        surfaceTexture = new SurfaceTexture(textureId);
        surface = new Surface(surfaceTexture);

        // 编译着色器
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        GLES20.glUseProgram(program);

        uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        uCropRectLoc = GLES20.glGetUniformLocation(program, "uCropRect");
        uMirrorLoc = GLES20.glGetUniformLocation(program, "uMirror");

        Matrix.setIdentityM(mvpMatrix, 0);
        fpsStartTimeNs = System.nanoTime();
        fpsFrameCount = 0;

        Log.i(TAG, "OpenGL ES renderer initialized, textureId=" + textureId);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        Log.i(TAG, "Surface changed: " + width + "x" + height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // 自适应帧率控制
        long now = System.nanoTime();
        if (lastFrameTimeNs > 0) {
            long elapsed = now - lastFrameTimeNs;
            if (elapsed < frameIntervalNs) {
                return;
            }
        }
        lastFrameTimeNs = now;

        // FPS 统计
        fpsFrameCount++;
        long fpsElapsed = now - lastFpsUpdateNs;
        if (fpsElapsed >= 1_000_000_000L) { // 每秒更新一次
            currentFps = fpsFrameCount * 1_000_000_000.0f / fpsElapsed;
            fpsFrameCount = 0;
            lastFpsUpdateNs = now;
        }

        // 更新纹理
        if (surfaceTexture != null) {
            surfaceTexture.updateTexImage();
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        // 设置 MVP 矩阵
        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0);

        // 设置裁剪区域
        GLES20.glUniform4f(uCropRectLoc, cropLeft, cropTop, cropWidth, cropHeight);

        // 设置镜像模式
        GLES20.glUniform1i(uMirrorLoc, mirrorMode);

        // 设置顶点属性
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aPositionLoc);

        vertexBuffer.position(3);
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoordLoc);

        // 绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * 更新 A 区域裁剪参数（归一化 UV 坐标）
     */
    public void updateCropRect(int x, int y, int w, int h) {
        this.cropLeft = (float) x / screenWidth;
        this.cropTop = (float) y / screenHeight;
        this.cropWidth = (float) w / screenWidth;
        this.cropHeight = (float) h / screenHeight;
    }

    public void setScreenSize(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    /**
     * 设置镜像模式
     * @param mode ConfigManager.MIRROR_NONE / HORIZONTAL / VERTICAL / BOTH
     */
    public void setMirrorMode(int mode) {
        this.mirrorMode = mode;
    }

    /**
     * 设置目标帧率（自适应调节帧间隔）
     * @param fps 目标帧率，如 60, 120, 165
     */
    public void setTargetFrameRate(float fps) {
        if (fps <= 0) return;
        long interval = (long) (1_000_000_000L / fps);
        this.frameIntervalNs = Math.max(MIN_FRAME_INTERVAL_NS,
                Math.min(MAX_FRAME_INTERVAL_NS, interval));
        Log.i(TAG, "Target frame rate: " + fps + "fps (interval=" + frameIntervalNs + "ns)");
    }

    /**
     * 获取当前实际 FPS
     */
    public float getCurrentFps() {
        return currentFps;
    }

    /**
     * 获取用于 MediaProjection 的 Surface
     */
    public Surface getInputSurface() {
        return surface;
    }

    /**
     * 释放 GL 资源（纹理、Shader Program）
     * 应在不再需要渲染时调用，防止 GPU 资源泄漏
     */
    public void release() {
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        if (textureId != 0) {
            int[] textures = {textureId};
            GLES20.glDeleteTextures(1, textures, 0);
            textureId = 0;
        }
        Log.i(TAG, "GL resources released");
    }

    private int buildProgram(String vertexSrc, String fragmentSrc) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);

        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vertexShader);
        GLES20.glAttachShader(prog, fragmentShader);
        GLES20.glLinkProgram(prog);

        int[] linked = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.e(TAG, "Shader link failed: " + GLES20.glGetProgramInfoLog(prog));
            GLES20.glDeleteProgram(prog);
            return 0;
        }

        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        return prog;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}