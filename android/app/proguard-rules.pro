# UniFFI generated bindings — keep all JNA/FFI machinery
-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * implements uniffi.** { *; }

# Keep Rust-generated native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep pulldown-cmark / panicking infrastructure (if stripped)
-dontwarn org.slf4j.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# Ignore missing compile-time annotations used by Google Tink / Crypto
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Ignore desktop Java AWT classes referenced by JNA
-dontwarn java.awt.**

# Keep JNA / UniFFI native binding classes intact
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
