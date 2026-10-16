# The release build does not currently run R8 (isMinifyEnabled = false),
# but these rules are kept ready for the day you switch it on.

# Keep the entry points that the framework instantiates by name.
-keep class com.compass.vault.CompassApp { *; }
-keep class com.compass.vault.MainActivity { *; }

# Coroutines internals that are looked up reflectively.
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
