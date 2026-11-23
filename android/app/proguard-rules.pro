# kotlinx.serialization keeps its own rules through its consumer proguard file.
-keepattributes *Annotation*, InnerClasses, Signature

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
