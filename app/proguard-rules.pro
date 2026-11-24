# Keep Shizuku UserService and AIDL interfaces
-keep class com.self.zai.shell.** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Keep AccessibilityService & foreground service entrypoints
-keep class com.self.zai.phone.ZaiAccessibilityService { *; }
-keep class com.self.zai.service.ZaiAgentService { *; }

# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.self.zai.model.**$$serializer { *; }
-keepclassmembers class com.self.zai.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.self.zai.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
