-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider {
    public <init>();
}

-keep class org.sqlite.** {
    *;
}

-keep class javafx.** {
    *;
}

# jnativehook loads GlobalScreen$NativeHookThread.dispatchEvent via JNI; shrinking breaks release builds.
-keep class com.github.kwhat.jnativehook.** { *; }
-keepnames class com.github.kwhat.jnativehook.GlobalScreen$NativeHookThread {
    protected static void dispatchEvent(com.github.kwhat.jnativehook.NativeInputEvent);
}

# JNA builds native library proxies reflectively. Keep both JNA itself and the desktop
# DWM bridge so release packages can still style the Windows title bar.
-keep class com.sun.jna.** { *; }
-keep class com.phoebe.app.MainKt$WindowsWindowChrome** { *; }
-keep class com.phoebe.app.MainKt$WindowsWindowChrome$WinUser32** { *; }
-keep class com.phoebe.app.MainKt$WindowsWindowChrome$DwmApi** { *; }

# JNA Structure field order must survive shrinking for Windows Credential Manager writes.
-keep class com.phoebe.app.platform.WindowsCredential { *; }
-keep class com.phoebe.app.platform.WindowsFileTime { *; }
# Advapi32 is loaded by method name; ProGuard must not rename CredReadW/CredWriteW/etc.
-keep interface com.phoebe.app.platform.WindowsCredApi {
    boolean CredReadW(com.sun.jna.WString, int, int, com.sun.jna.ptr.PointerByReference);
    boolean CredWriteW(com.phoebe.app.platform.WindowsCredential, int);
    boolean CredDeleteW(com.sun.jna.WString, int, int);
    void CredFree(com.sun.jna.Pointer);
}

# Borderless Windows frame (shell snap / resize).
-keep class com.phoebe.app.platform.WindowsUndecoratedWindowSupport { *; }
-keep class com.phoebe.app.platform.BorderlessWindowProcedure { *; }
-keep class com.phoebe.app.platform.User32Ex { *; }
-keep class com.phoebe.app.platform.WindowsHwnd { *; }
-keep class com.phoebe.app.platform.WindowsWindowMetrics { *; }
-keep class com.phoebe.app.platform.WindowsFrameStateSync { *; }

-keep class com.sun.glass.** {
    *;
}

-keep class com.sun.javafx.** {
    *;
}

-keep class com.sun.media.** {
    *;
}

# Java Sound SPI decoders (MP3/Vorbis/FLAC). Flatpak playback relies on these instead of JavaFX Media.
-keep class javazoom.spi.** { *; }
-keep class javazoom.jl.** { *; }
-keep class org.jflac.** { *; }
-keep class tritonus.** { *; }

-keep class com.sun.prism.** {
    *;
}

# Desktop Chromecast uses a Java CastV2 sender library with protobuf/Jackson
# payloads and JmDNS service discovery.
-keep class su.litvak.chromecast.** { *; }
-keep class su.litvak.justdlna.** { *; }
-keep class javax.jmdns.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.fasterxml.jackson.** { *; }

# SkiaGpuCacheTrimmer reflects into Skiko internals (SkiaLayer.getRedrawer$skiko(),
# redrawer drawLock/contextHandler, ContextHandler.getContext). Keep those members so
# the trimmer still finds the GPU context in shrunk release builds.
-keepclassmembers class org.jetbrains.skiko.SkiaLayer {
    public org.jetbrains.skiko.redrawer.Redrawer getRedrawer$skiko();
}
-keepclassmembers class org.jetbrains.skiko.redrawer.** {
    java.lang.Object drawLock;
    org.jetbrains.skiko.context.* contextHandler;
}
-keepclassmembers class org.jetbrains.skiko.context.** {
    org.jetbrains.skia.DirectContext getContext();
}

# Navigation routes are persisted via rememberSerializable + kotlinx.serialization.
# Without these rules, release builds crash when opening routes such as Recently Added.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep @kotlinx.serialization.Serializable class com.phoebe.app.** { *; }
-keep,includedescriptorclasses class com.phoebe.app.**$$serializer { *; }

# Kotlin default-arg stubs + large home derivation break ProGuard stack maps (VerifyError on macOS release).
-keep class com.phoebe.app.ui.HomeUiStateKt {
    *;
}

# navigation3 NavDisplay + SceneInfo/NavigationEventInfo hierarchy break ProGuard stack maps (VerifyError on macOS release).
-keep class androidx.navigation3.** {
    *;
}
-keep class androidx.navigationevent.** {
    *;
}
-keep class org.jetbrains.androidx.navigationevent.** {
    *;
}
