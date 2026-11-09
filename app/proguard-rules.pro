# Shrunk, not obfuscated: class and method names stay readable in the diagnostic log's stack traces.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses,Signature

# kotlinx.serialization: keep the generated serializers of our @Serializable classes.
-keepclassmembers @kotlinx.serialization.Serializable class dev.jdcm.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.jdcm.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.jdcm.**$$serializer { *; }

# Navigation's type-safe routes are looked up by their serializers.
-keep @kotlinx.serialization.Serializable class dev.jdcm.ui.nav.** { *; }
