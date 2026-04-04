# ---- Lazysodium ----
-keep class com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.goterl.lazysodium.**

# ---- JNA ----
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# ---- Tink ----
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ---- JavaMail ----
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.**

# ---- Kotlinx Serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ru.cheburmail.app.**$$serializer { *; }
-keepclassmembers class ru.cheburmail.app.** { *** Companion; }
-keepclasseswithmembers class ru.cheburmail.app.** { kotlinx.serialization.KSerializer serializer(...); }

# ---- WorkManager ----
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# ---- Google Play Services (Code Scanner) ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ---- ZXing ----
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---- Enums ----
-keepclassmembers enum * { *; }
