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
# v1.9 (A7a, R9): crash reports keep line numbers ("at a.b.c(SourceFile:120)"); the file names
# themselves are replaced by "SourceFile". With the release's mapping.txt
# (app/build/outputs/mapping/release/mapping.txt, kept with each release) R8's retrace turns the
# obfuscated names and lines of a shared report back into the source's.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
