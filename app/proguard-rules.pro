# =======================================================================
# Nimpata Bangla Dubbing - Anti-Tamper, Obfuscation & Security Rules
# Protects against APK Editor, MT Manager, and unauthorized decompilation
# =======================================================================

# 1. Scramble class and package hierarchy
-repackageclasses ''
-allowaccessmodification
-renamesourcefileattribute 'SourceFile'
-keepattributes SourceFile,LineNumberTable,InnerClasses,EnclosingMethod

# 2. Obfuscate internal business logic, algorithms, and security helpers
-keep class com.example.security.AntiTamperGuard {
    public static boolean verifyIntegrity(android.content.Context);
    public static java.lang.String getAdMobAppId();
    public static java.lang.String getRewardAdUnitId();
    public static java.lang.String getBanner1UnitId();
    public static java.lang.String getBanner2UnitId();
    public static java.lang.String getBanner3UnitId();
    public static java.lang.String getBanner4UnitId();
}

# 3. Google Mobile Ads (AdMob) ProGuard Rules
-keep public class com.google.android.gms.ads.** {
   public *;
}
-keep public class com.google.ads.** {
   public *;
}
-keepattributes *Annotation*
-dontwarn com.google.android.gms.ads.**

# 4. Jetpack Compose Rules
-keepclassmembers class * extends androidx.compose.runtime.State { *; }

# 5. Room Database Rules
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# 6. Media3 & ExoPlayer Rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 7. ONNX Runtime & ML Kit
-keep class ai.onnxruntime.** { *; }
-keep class com.google.mlkit.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn com.google.mlkit.**

# 8. Prevent tampering with Application & Activities
-keep public class com.example.DubbingApplication { public *; }
-keep public class com.example.MainActivity { public *; }
