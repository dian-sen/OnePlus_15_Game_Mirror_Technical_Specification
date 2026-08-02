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