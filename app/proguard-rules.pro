# Standard Android Keep Rules
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*,*TypeAnnotations*

# Shizuku API
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.manager.** { *; }

# Libsu
-keep class com.github.topjohnwu.libsu.** { *; }

# Fastboot Java
-keep class io.github.rohitverma882.fastboot.** { *; }

# Keep AIDL interfaces
-keep interface * extends android.os.IInterface { *; }

# Strip verbose, debug and info logging from release builds so shell output,
# package lists and device details don't end up in logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
