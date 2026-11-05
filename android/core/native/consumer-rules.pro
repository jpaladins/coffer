# JNA and the UniFFI bindings are reached reflectively.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class app.coffer.ffi.** { *; }
-dontwarn java.awt.**
