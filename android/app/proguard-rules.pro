# Keep the Connect IQ SDK intact (it uses AIDL/reflection to talk to
# the Garmin Connect Mobile app).
-keep class com.garmin.android.connectiq.** { *; }
