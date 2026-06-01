# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.ntzb.myradio.** {
    kotlinx.serialization.KSerializer serializer(...);
}
