# JNA 通过反射与 JNI 访问自身及绑定类。
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# libzzu 的 JNA 直接映射，函数名即 C 符号名。
-keep class dev.zzu.internal.LibZzu { *; }
