# App-specific R8 keep rules.
# kotlinx.serialization, AndroidX/Compose, ML Kit and LiteRT ship their own consumer rules.

# LiteRT / TensorFlow Lite uses JNI callbacks into these classes.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**

# Keep @Serializable model classes' generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.brushwork.paint.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}