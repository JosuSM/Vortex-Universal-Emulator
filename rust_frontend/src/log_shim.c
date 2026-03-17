// Minimal C shim for the libretro log callback.
// Rust cannot define C-variadic functions on stable, so we
// bridge through this tiny C file.
#include <stdarg.h>
#include <android/log.h>

void vortex_retro_log(unsigned level, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int prio;
    switch (level) {
        case 0: prio = ANDROID_LOG_DEBUG; break;
        case 1: prio = ANDROID_LOG_INFO;  break;
        case 2: prio = ANDROID_LOG_WARN;  break;
        case 3: prio = ANDROID_LOG_ERROR; break;
        default: prio = ANDROID_LOG_INFO; break;
    }
    __android_log_vprint(prio, "VortexCore", fmt, ap);
    va_end(ap);
}
