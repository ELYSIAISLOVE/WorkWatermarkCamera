# ProGuard/R8 rules for Watermark Camera

# Keep Room entities
-keep class com.watermark.camera.data.local.** { *; }

# Keep Hilt components
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }

# Keep CameraX classes
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep EXIF data
-keep class androidx.exifinterface.** { *; }

# Keep Kotlin metadata
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault,Signature,Exceptions,*Annotation*,InnerClasses,EnclosingMethod

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Remove logging in release
-assumenosideeffects class android.util.Log { *; }
-assumenosideeffects class timber.log.Timber { *; }
