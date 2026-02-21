# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.cellclaw.**$$serializer { *; }
-keepclassmembers class com.cellclaw.** { *** Companion; }
-keepclasseswithmembers class com.cellclaw.** { kotlinx.serialization.KSerializer serializer(...); }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
