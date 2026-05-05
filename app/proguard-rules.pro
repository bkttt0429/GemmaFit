# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.

# Gemma 4 model related
-keep class com.gemmafit.jni.** { *; }
-keep class com.gemmafit.functions.** { *; }
-keep class com.gemmafit.video.** { *; }
-keep class com.gemmafit.memory.** { *; }

# Keep MediaPipe
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
