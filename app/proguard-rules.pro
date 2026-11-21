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
-keep class com.noctalia.music.ui.NoctaliaViewModel { public <init>(...); }
-keep class com.noctalia.music.widget.BrookWidgetProvider { *; }
-keep class com.noctalia.music.player.NoctaliaPlaybackService { *; }
