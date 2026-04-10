# ─── VULCAN PROGUARD RULES ───────────────────────────────────────────────────
# Configured for release build minification.
# Keeps all runtime-critical classes that use reflection.

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Lazy { *; }
-dontwarn kotlin.**

# ── Vulcan Domain Models (serialized to/from JSON) ───────────────────────────
-keep class com.vulcan.app.data.model.** { *; }
-keep class com.vulcan.app.data.database.entities.** { *; }
-keepclassmembers class com.vulcan.app.data.model.** { *; }

# ── Gson serialization ────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ── Kotlinx Serialization ────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.vulcan.app.**$$serializer { *; }
-keepclassmembers class com.vulcan.app.** {
    *** Companion;
}

# ── Hilt DI ───────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel

# ── Room Database ─────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-dontwarn androidx.room.paging.**

# ── OkHttp + Retrofit ────────────────────────────────────────────────────────
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }

# ── BouncyCastle (Ed25519) ────────────────────────────────────────────────────
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── NanoHTTPD ─────────────────────────────────────────────────────────────────
-keep class fi.iki.elonen.** { *; }

# ── MinIO S3 Client ───────────────────────────────────────────────────────────
-keep class io.minio.** { *; }
-dontwarn io.minio.**

# ── WorkManager ───────────────────────────────────────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── BroadcastReceivers (boot receiver must survive minification) ──────────────
-keep class com.vulcan.app.service.BootReceiver { *; }
-keep class com.vulcan.app.service.VulcanAlarmReceiver { *; }

# ── Biometric ────────────────────────────────────────────────────────────────
-keep class androidx.biometric.** { *; }

# ── Compose ───────────────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── General Android ──────────────────────────────────────────────────────────
-keepattributes SourceFile, LineNumberTable
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*

# Keep line numbers for crash reporting
-renamesourcefileattribute SourceFile
