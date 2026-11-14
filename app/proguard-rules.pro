# kotlinx.serialization: keep generated serializers of our models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class net.paramnezia.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class net.paramnezia.**$$serializer { *; }


# deploy/ssh: ML-KEM (post-quantum SSH key exchange) uses javax.crypto.KEM, which only desktop Java 24+
# has. On Android MlKem768.available is false and these classes are never loaded.
-dontwarn javax.crypto.KEM
-dontwarn javax.crypto.KEM$*
-dontwarn java.security.spec.NamedParameterSpec
