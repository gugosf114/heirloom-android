# Compose / Kotlin reflection used by Coil + lifecycle
-keepattributes *Annotation*, InnerClasses
-keep class kotlin.Metadata { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
