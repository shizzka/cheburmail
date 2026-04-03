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

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.**
