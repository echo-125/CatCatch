# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# 保留 Room Entity 类（字段名映射列名）
-keep @androidx.room.Entity class com.catcatch.data.local.** { *; }
# 保留领域模型（序列化/反序列化需要）
-keep class com.catcatch.domain.model.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# OkHttp 已自带 consumer proguard rules，仅保留 dontwarn
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin Coroutines
-keep class kotlinx.coroutines.** { *; }

# FFmpeg-kit
-keep class com.arthenica.** { *; }
-dontwarn com.arthenica.**

# 移除 Release 构建中的 debug/verbose 日志，防止泄露敏感信息
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
