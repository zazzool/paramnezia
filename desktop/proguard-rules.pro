# ProGuard for the Windows app (compose.desktop release build). The goal is size and start-up: drop
# the unused parts of Compose, Material and the Kotlin libraries. Everything that is reached by
# reflection or native code is kept whole: our own code (small), JNA (DPAPI state file, registry,
# title bar), JmDNS (Wi-Fi sharing), JSch (server management, loads algorithms by class name) and
# kotlinx.serialization. CI starts the packaged release app (.github/scripts/windows-service-check.ps1),
# so a class removed by mistake fails the build, not a user.
-keep class net.paramnezia.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class javax.jmdns.** { *; }
-keep class com.jcraft.jsch.** { *; }
# QR codes (zxing looks enums up by name).
-keep class com.google.zxing.** { *; }
# Enum.valueOf / values() work through reflection on the enum's own members: keep them for every enum.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }

# Loaded through java.util.ServiceLoader (META-INF/services), so nothing references them in code:
# without the Swing main dispatcher, Compose cannot start at all.
-keep class kotlinx.coroutines.swing.** { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler

# Optional dependencies of JSch, JmDNS and JNA that are not shipped.
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.**
-dontwarn com.sun.jna.**
-dontwarn javax.jmdns.**
-dontwarn kotlinx.coroutines.debug.**
-dontnote **
# Shrink only: obfuscation and optimisation save little here and make field reports unreadable.
-dontobfuscate
