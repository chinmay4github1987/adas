# LiteRT / TFLite: keep JNI-bound classes when minify is enabled.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
