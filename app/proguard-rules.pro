# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Disable obfuscation and shrinking, but keep optimization (needed for log stripping)
#-dontobfuscate
#-dontshrink

# Strip Log.v and Log.d
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

-assumenosideeffects class com.oakamil.olive.Log {
    public static void v(...);
    public static void d(...);
}

# Preserve standard Android entry points
-keep class com.oakamil.olive.MainActivity { *; }
-keep class com.oakamil.olive.OliveService { *; }

# Preserve JNI methods so Rust can call them and vice versa
-keepclasseswithmembers class com.oakamil.olive.OliveEngine {
    native <methods>;
}

# Preserve UVCCamera library classes and members accessed via reflection and JNI
-keep class com.jiangdg.uvc.** { *; }
