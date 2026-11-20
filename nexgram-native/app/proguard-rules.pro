# NexGram Proguard Rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @org.telegram.** *;
}
