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
