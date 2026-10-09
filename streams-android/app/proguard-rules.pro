# kotlinx.serialization — keep @Serializable models used by Supabase
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.streams.app.**$$serializer { *; }
-keepclassmembers class com.streams.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.streams.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
