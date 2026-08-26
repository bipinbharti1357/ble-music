# Keep the BLE callback subclasses; they are instantiated by the framework.
-keep class com.example.blemusic.ble.** { *; }

# WorkManager instantiates workers reflectively.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
