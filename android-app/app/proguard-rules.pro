# Keep sensor/pipeline/offline classes
-keep class com.example.agent.sensors.** { *; }
-keep class com.example.agent.pipeline.** { *; }
-keep class com.example.agent.offline.** { *; }
-keep class com.example.agent.network.** { *; }
-keep class com.example.agent.utils.** { *; }

# Keep Java-WebSocket
-keep class org.java_websocket.** { *; }

# Keep OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Keep Room
-keep class androidx.room.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }

# USB serial / reflection
-keep class com.felhr.** { *; }
-dontwarn com.felhr.**

# Kotlin serialization / Gson models
-keep class com.example.agent.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, Exception

# Don't strip logging wrappers used in release for errors
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
