# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Chaquopy (Python runtime) needs its Java bridge classes intact,
# including anything reached only via reflection/JNI from CPython.
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# Kotlin serialization (kotlinx.serialization.json is used in the project)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}

# Keep classes with @Serializable
-keep,includedescriptorclasses class com.example.runstef.**$$serializer { *; }
-keepclassmembers class com.example.runstef.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.runstef.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# error-prone annotations are compile-time only (pulled in transitively by
# androidx.security.crypto / Tink); safe to silence per AGP's own suggestion.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
