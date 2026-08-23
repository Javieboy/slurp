# Minification is disabled for release (see app/build.gradle.kts). These rules
# exist so that turning it on is merely difficult rather than silently broken.
-keep class com.yausername.** { *; }
-keepclassmembers class ** { @kotlinx.serialization.Serializable *; }
