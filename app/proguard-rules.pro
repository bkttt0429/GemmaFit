# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.

# Gemma 4 model related
-keep class com.gemmafit.jni.** { *; }
-keep class com.gemmafit.functions.** { *; }

# Keep MediaPipe
-keep class com.google.mediapipe.** { *; }
