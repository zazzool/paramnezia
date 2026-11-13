# kotlinx.serialization: keep generated serializers of our models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class net.paramnezia.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class net.paramnezia.**$$serializer { *; }

# JSch loads its algorithms by class name.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.**
-dontwarn com.sun.jna.**
-dontwarn javax.naming.**
