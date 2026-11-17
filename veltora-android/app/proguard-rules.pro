# kotlinx.serialization — keep @Serializable models used by Supabase
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.veltora.app.**$$serializer { *; }
-keepclassmembers class com.veltora.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.veltora.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**

# Supabase-kt / Ktor (release build for Google Play)
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**
-keep class io.ktor.** { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn java.lang.management.**
-dontwarn com.google.errorprone.annotations.**
