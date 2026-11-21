-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.noctalia.music.data.**$$serializer { *; }
-keepclassmembers class com.noctalia.music.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.noctalia.music.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
