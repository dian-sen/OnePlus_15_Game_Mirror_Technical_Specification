# GameMirror ProGuard Rules
# 保持 JNI 方法不被混淆
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保持 LSPosed Hooker 类
-keep class com.example.gamemirror.xposed.** { *; }

# 保持 Native 触控注入桥接类
-keep class com.example.gamemirror.touch.TouchRedirector {
    native <methods>;
}

# 保持 Service
-keep class com.example.gamemirror.overlay.MirrorOverlayService { *; }
-keep class com.example.gamemirror.ui.ControlPanelService { *; }

# 保持 ConfigManager
-keep class com.example.gamemirror.config.ConfigManager { *; }

# 保持 AreaSelectionView 回调接口
-keep class com.example.gamemirror.ui.AreaSelectionView$OnSelectionConfirmedListener { *; }

# 保持 GLRenderer
-keep class com.example.gamemirror.capture.GLRenderer { *; }

# 保持 Xposed 模块接口
-keep class * extends io.github.libxposed.api.XposedModule { *; }
-keep class * implements io.github.libxposed.api.XposedInterface$Hooker { *; }