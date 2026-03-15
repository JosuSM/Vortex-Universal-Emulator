-keepclassmembers class * extends com.vortex.emulator.core.EmulationCore {
    native <methods>;
}
-keep class com.vortex.emulator.core.** { *; }
-keep class com.vortex.emulator.gpu.** { *; }

-dontwarn javax.annotation.**
