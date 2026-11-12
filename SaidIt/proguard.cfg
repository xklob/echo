# Native binding names and reflection fields must survive minification.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepattributes *Annotation*,Signature
# JNA's desktop AWT bridge is unreachable on Android.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
