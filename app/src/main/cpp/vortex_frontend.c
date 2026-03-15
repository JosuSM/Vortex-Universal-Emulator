/*
 * vortex_frontend.c — libretro frontend for Vortex Emulator
 *
 * Loads a libretro core (.so), initialises it, runs frames, and pushes
 * video/audio back to the Kotlin layer via JNI callbacks.
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <stdio.h>
#include <errno.h>

#include "libretro.h"

#define TAG "VortexFrontend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ── Dynamic core handle ─────────────────────────────────────────── */
static void *g_core_handle    = NULL;

/* Core function pointers */
static retro_init_t                       core_init;
static retro_deinit_t                     core_deinit;
static retro_api_version_t                core_api_version;
static retro_get_system_info_t            core_get_system_info;
static retro_get_system_av_info_t         core_get_system_av_info;
static retro_set_environment_t            core_set_environment;
static retro_set_video_refresh_t          core_set_video_refresh;
static retro_set_audio_sample_t           core_set_audio_sample;
static retro_set_audio_sample_batch_t     core_set_audio_sample_batch;
static retro_set_input_poll_t             core_set_input_poll;
static retro_set_input_state_t            core_set_input_state;
static retro_load_game_t                  core_load_game;
static retro_unload_game_t                core_unload_game;
static retro_run_t                        core_run;
static retro_reset_t                      core_reset;
static retro_serialize_size_t             core_serialize_size;
static retro_serialize_t                  core_serialize;
static retro_unserialize_t                core_unserialize;
static retro_set_controller_port_device_t core_set_controller_port_device;

/* ── State ───────────────────────────────────────────────────────── */
static unsigned g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
static char     g_system_dir[512] = {0};
static char     g_save_dir[512]   = {0};

/* Frame buffer pushed to Java */
static uint32_t *g_frame_buf    = NULL;
static unsigned   g_frame_width  = 0;
static unsigned   g_frame_height = 0;
static bool       g_frame_ready  = false;

/* Audio ring buffer */
#define AUDIO_BUF_FRAMES 8192
static int16_t g_audio_buf[AUDIO_BUF_FRAMES * 2];
static size_t  g_audio_write_pos = 0;

/* AV info cached */
static double g_fps         = 60.0;
static double g_sample_rate = 44100.0;

/* Input state (16 buttons per port, max 2 ports) */
static int16_t g_input_state[2][16];
static int16_t g_analog_state[2][2][2]; /* [port][index][x/y] */

/* ── Libretro callbacks ──────────────────────────────────────────── */

static void core_log(enum retro_log_level level, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int prio = ANDROID_LOG_VERBOSE;
    switch (level) {
        case RETRO_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        case RETRO_LOG_INFO:  prio = ANDROID_LOG_INFO;  break;
        case RETRO_LOG_WARN:  prio = ANDROID_LOG_WARN;  break;
        case RETRO_LOG_ERROR: prio = ANDROID_LOG_ERROR;  break;
    }
    __android_log_vprint(prio, "LibretroCore", fmt, ap);
    va_end(ap);
}

static bool environment_cb(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            g_pixel_format = *(const unsigned *)data;
            LOGI("Pixel format set to %u", g_pixel_format);
            return true;

        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *(const char **)data = g_system_dir;
            return true;

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char **)data = g_save_dir;
            return true;

        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            struct retro_log_callback *cb = (struct retro_log_callback *)data;
            cb->log = core_log;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE:
            /* No core options set yet — return false */
            return false;

        case RETRO_ENVIRONMENT_SET_VARIABLES:
            return true;

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *(bool *)data = false;
            return true;

        default:
            return false;
    }
}

static void video_refresh_cb(const void *data, unsigned width,
                              unsigned height, size_t pitch) {
    if (!data) return;  /* Frame-duping — skip */

    /* Ensure our XRGB8888 conversion buffer is big enough */
    size_t needed = (size_t)width * height;
    if (g_frame_width != width || g_frame_height != height) {
        free(g_frame_buf);
        g_frame_buf = (uint32_t *)malloc(needed * 4);
        if (!g_frame_buf) {
            LOGE("Failed to allocate frame buffer %ux%u", width, height);
            return;
        }
        g_frame_width  = width;
        g_frame_height = height;
    }

    /* Convert to XRGB8888 regardless of source format */
    switch (g_pixel_format) {
        case RETRO_PIXEL_FORMAT_XRGB8888: {
            const uint32_t *src = (const uint32_t *)data;
            size_t src_pitch_px = pitch / 4;
            for (unsigned y = 0; y < height; y++) {
                memcpy(g_frame_buf + y * width, src + y * src_pitch_px, width * 4);
            }
            break;
        }
        case RETRO_PIXEL_FORMAT_RGB565: {
            const uint16_t *src = (const uint16_t *)data;
            size_t src_pitch_px = pitch / 2;
            for (unsigned y = 0; y < height; y++) {
                for (unsigned x = 0; x < width; x++) {
                    uint16_t px = src[y * src_pitch_px + x];
                    uint8_t r = (uint8_t)(((px >> 11) & 0x1F) * 255 / 31);
                    uint8_t g = (uint8_t)(((px >> 5)  & 0x3F) * 255 / 63);
                    uint8_t b = (uint8_t)(( px        & 0x1F) * 255 / 31);
                    g_frame_buf[y * width + x] = 0xFF000000u | ((uint32_t)r << 16) |
                                                  ((uint32_t)g << 8) | b;
                }
            }
            break;
        }
        case RETRO_PIXEL_FORMAT_0RGB1555: {
            const uint16_t *src = (const uint16_t *)data;
            size_t src_pitch_px = pitch / 2;
            for (unsigned y = 0; y < height; y++) {
                for (unsigned x = 0; x < width; x++) {
                    uint16_t px = src[y * src_pitch_px + x];
                    uint8_t r = (uint8_t)(((px >> 10) & 0x1F) * 255 / 31);
                    uint8_t g = (uint8_t)(((px >> 5)  & 0x1F) * 255 / 31);
                    uint8_t b = (uint8_t)(( px        & 0x1F) * 255 / 31);
                    g_frame_buf[y * width + x] = 0xFF000000u | ((uint32_t)r << 16) |
                                                  ((uint32_t)g << 8) | b;
                }
            }
            break;
        }
    }
    g_frame_ready = true;
}

static void audio_sample_cb(int16_t left, int16_t right) {
    if (g_audio_write_pos < AUDIO_BUF_FRAMES) {
        g_audio_buf[g_audio_write_pos * 2]     = left;
        g_audio_buf[g_audio_write_pos * 2 + 1] = right;
        g_audio_write_pos++;
    }
}

static size_t audio_sample_batch_cb(const int16_t *data, size_t frames) {
    size_t to_copy = frames;
    if (g_audio_write_pos + to_copy > AUDIO_BUF_FRAMES)
        to_copy = AUDIO_BUF_FRAMES - g_audio_write_pos;
    if (to_copy > 0) {
        memcpy(g_audio_buf + g_audio_write_pos * 2, data, to_copy * 4);
        g_audio_write_pos += to_copy;
    }
    return frames;
}

static void input_poll_cb(void) {
    /* Input state is set from Java side before each retro_run() */
}

static int16_t input_state_cb(unsigned port, unsigned device,
                               unsigned index, unsigned id) {
    if (port > 1) return 0;
    if (device == RETRO_DEVICE_JOYPAD && id < 16) {
        return g_input_state[port][id];
    }
    if (device == RETRO_DEVICE_ANALOG && index < 2 && id < 2) {
        return g_analog_state[port][index][id];
    }
    return 0;
}

/* ── Helper: load a symbol from the core .so ─────────────────────── */
#define LOAD_SYM(var, name) do { \
    *(void **)(&var) = dlsym(g_core_handle, name); \
    if (!var) { LOGE("Missing symbol: %s", name); return -1; } \
} while(0)

#define LOAD_SYM_OPT(var, name) do { \
    *(void **)(&var) = dlsym(g_core_handle, name); \
} while(0)

/* ═════════════════════════════════════════════════════════════════ */
/*                        JNI FUNCTIONS                            */
/* ═════════════════════════════════════════════════════════════════ */

JNIEXPORT jint JNICALL
Java_com_vortex_emulator_emulation_VortexNative_loadCore(
    JNIEnv *env, jobject thiz,
    jstring corePath, jstring systemDir, jstring saveDir)
{
    /* Unload previous core if any */
    if (g_core_handle) {
        if (core_deinit) core_deinit();
        dlclose(g_core_handle);
        g_core_handle = NULL;
    }

    const char *path = (*env)->GetStringUTFChars(env, corePath, NULL);
    const char *sdir = (*env)->GetStringUTFChars(env, systemDir, NULL);
    const char *svdir = (*env)->GetStringUTFChars(env, saveDir, NULL);

    snprintf(g_system_dir, sizeof(g_system_dir), "%s", sdir);
    snprintf(g_save_dir, sizeof(g_save_dir), "%s", svdir);

    LOGI("Loading core: %s", path);
    g_core_handle = dlopen(path, RTLD_LAZY);
    if (!g_core_handle) {
        LOGE("dlopen failed: %s", dlerror());
        (*env)->ReleaseStringUTFChars(env, corePath, path);
        (*env)->ReleaseStringUTFChars(env, systemDir, sdir);
        (*env)->ReleaseStringUTFChars(env, saveDir, svdir);
        return -1;
    }

    /* Load all required symbols */
    LOAD_SYM(core_init, "retro_init");
    LOAD_SYM(core_deinit, "retro_deinit");
    LOAD_SYM(core_api_version, "retro_api_version");
    LOAD_SYM(core_get_system_info, "retro_get_system_info");
    LOAD_SYM(core_get_system_av_info, "retro_get_system_av_info");
    LOAD_SYM(core_set_environment, "retro_set_environment");
    LOAD_SYM(core_set_video_refresh, "retro_set_video_refresh");
    LOAD_SYM(core_set_audio_sample, "retro_set_audio_sample");
    LOAD_SYM(core_set_audio_sample_batch, "retro_set_audio_sample_batch");
    LOAD_SYM(core_set_input_poll, "retro_set_input_poll");
    LOAD_SYM(core_set_input_state, "retro_set_input_state");
    LOAD_SYM(core_load_game, "retro_load_game");
    LOAD_SYM(core_unload_game, "retro_unload_game");
    LOAD_SYM(core_run, "retro_run");
    LOAD_SYM(core_reset, "retro_reset");

    /* Optional symbols */
    LOAD_SYM_OPT(core_serialize_size, "retro_serialize_size");
    LOAD_SYM_OPT(core_serialize, "retro_serialize");
    LOAD_SYM_OPT(core_unserialize, "retro_unserialize");
    LOAD_SYM_OPT(core_set_controller_port_device, "retro_set_controller_port_device");

    /* Set callbacks */
    core_set_environment(environment_cb);
    core_set_video_refresh(video_refresh_cb);
    core_set_audio_sample(audio_sample_cb);
    core_set_audio_sample_batch(audio_sample_batch_cb);
    core_set_input_poll(input_poll_cb);
    core_set_input_state(input_state_cb);

    /* Init the core */
    core_init();

    LOGI("Core loaded and initialised (API v%u)", core_api_version());

    (*env)->ReleaseStringUTFChars(env, corePath, path);
    (*env)->ReleaseStringUTFChars(env, systemDir, sdir);
    (*env)->ReleaseStringUTFChars(env, saveDir, svdir);
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_vortex_emulator_emulation_VortexNative_loadGame(
    JNIEnv *env, jobject thiz, jstring romPath)
{
    if (!core_load_game) return JNI_FALSE;

    const char *path = (*env)->GetStringUTFChars(env, romPath, NULL);
    LOGI("Loading ROM: %s", path);

    struct retro_system_info sysinfo = {0};
    core_get_system_info(&sysinfo);

    struct retro_game_info game = {0};

    if (sysinfo.need_fullpath) {
        /* Core will read the file itself */
        game.path = path;
        game.data = NULL;
        game.size = 0;
    } else {
        /* We need to load the file into memory */
        FILE *f = fopen(path, "rb");
        if (!f) {
            LOGE("Cannot open ROM file: %s (%s)", path, strerror(errno));
            (*env)->ReleaseStringUTFChars(env, romPath, path);
            return JNI_FALSE;
        }
        fseek(f, 0, SEEK_END);
        long fsize = ftell(f);
        fseek(f, 0, SEEK_SET);
        void *buf = malloc(fsize);
        if (!buf) {
            LOGE("Cannot allocate %ld bytes for ROM", fsize);
            fclose(f);
            (*env)->ReleaseStringUTFChars(env, romPath, path);
            return JNI_FALSE;
        }
        if (fread(buf, 1, fsize, f) != (size_t)fsize) {
            LOGE("Short read on ROM file");
            free(buf);
            fclose(f);
            (*env)->ReleaseStringUTFChars(env, romPath, path);
            return JNI_FALSE;
        }
        fclose(f);
        game.path = path;
        game.data = buf;
        game.size = (size_t)fsize;
    }

    bool ok = core_load_game(&game);

    if (!sysinfo.need_fullpath && game.data) {
        free((void *)game.data);
    }

    if (ok) {
        struct retro_system_av_info av = {0};
        core_get_system_av_info(&av);
        g_fps = av.timing.fps;
        g_sample_rate = av.timing.sample_rate;
        LOGI("Game loaded: %ux%u @ %.2f FPS, audio %.0f Hz",
             av.geometry.base_width, av.geometry.base_height,
             g_fps, g_sample_rate);
    } else {
        LOGE("retro_load_game failed");
    }

    (*env)->ReleaseStringUTFChars(env, romPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_runFrame(
    JNIEnv *env, jobject thiz)
{
    if (!core_run) return;
    g_frame_ready = false;
    g_audio_write_pos = 0;
    core_run();
}

JNIEXPORT jintArray JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getFrameBuffer(
    JNIEnv *env, jobject thiz)
{
    if (!g_frame_ready || !g_frame_buf || g_frame_width == 0) return NULL;

    size_t len = (size_t)g_frame_width * g_frame_height;
    jintArray arr = (*env)->NewIntArray(env, (jsize)len);
    if (arr) {
        (*env)->SetIntArrayRegion(env, arr, 0, (jsize)len, (const jint *)g_frame_buf);
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getFrameWidth(
    JNIEnv *env, jobject thiz)
{
    return (jint)g_frame_width;
}

JNIEXPORT jint JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getFrameHeight(
    JNIEnv *env, jobject thiz)
{
    return (jint)g_frame_height;
}

JNIEXPORT jshortArray JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getAudioBuffer(
    JNIEnv *env, jobject thiz)
{
    if (g_audio_write_pos == 0) return NULL;

    size_t samples = g_audio_write_pos * 2;
    jshortArray arr = (*env)->NewShortArray(env, (jsize)samples);
    if (arr) {
        (*env)->SetShortArrayRegion(env, arr, 0, (jsize)samples, g_audio_buf);
    }
    return arr;
}

JNIEXPORT jdouble JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getFps(
    JNIEnv *env, jobject thiz)
{
    return g_fps;
}

JNIEXPORT jdouble JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getSampleRate(
    JNIEnv *env, jobject thiz)
{
    return g_sample_rate;
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setInputState(
    JNIEnv *env, jobject thiz,
    jint port, jint buttonId, jint value)
{
    if (port >= 0 && port < 2 && buttonId >= 0 && buttonId < 16)
        g_input_state[port][buttonId] = (int16_t)value;
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setAnalogState(
    JNIEnv *env, jobject thiz,
    jint port, jint index, jint axisId, jint value)
{
    if (port >= 0 && port < 2 && index >= 0 && index < 2 && axisId >= 0 && axisId < 2)
        g_analog_state[port][index][axisId] = (int16_t)value;
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_resetGame(
    JNIEnv *env, jobject thiz)
{
    if (core_reset) core_reset();
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_unloadGame(
    JNIEnv *env, jobject thiz)
{
    if (core_unload_game) core_unload_game();
    if (core_deinit) core_deinit();
    if (g_core_handle) {
        dlclose(g_core_handle);
        g_core_handle = NULL;
    }
    free(g_frame_buf);
    g_frame_buf = NULL;
    g_frame_width = 0;
    g_frame_height = 0;
    g_frame_ready = false;
    g_audio_write_pos = 0;
}

JNIEXPORT jint JNICALL
Java_com_vortex_emulator_emulation_VortexNative_saveState(
    JNIEnv *env, jobject thiz, jstring path)
{
    if (!core_serialize_size || !core_serialize) return -1;
    size_t size = core_serialize_size();
    if (size == 0) return -1;

    void *buf = malloc(size);
    if (!buf) return -2;

    if (!core_serialize(buf, size)) {
        free(buf);
        return -3;
    }

    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE *f = fopen(p, "wb");
    int result = 0;
    if (f) {
        if (fwrite(buf, 1, size, f) != size) result = -4;
        fclose(f);
    } else {
        result = -5;
    }
    (*env)->ReleaseStringUTFChars(env, path, p);
    free(buf);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_vortex_emulator_emulation_VortexNative_loadState(
    JNIEnv *env, jobject thiz, jstring path)
{
    if (!core_unserialize) return -1;

    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE *f = fopen(p, "rb");
    if (!f) {
        (*env)->ReleaseStringUTFChars(env, path, p);
        return -2;
    }

    fseek(f, 0, SEEK_END);
    long fsize = ftell(f);
    fseek(f, 0, SEEK_SET);
    void *buf = malloc(fsize);
    if (!buf) { fclose(f); (*env)->ReleaseStringUTFChars(env, path, p); return -3; }

    if (fread(buf, 1, fsize, f) != (size_t)fsize) {
        free(buf); fclose(f);
        (*env)->ReleaseStringUTFChars(env, path, p);
        return -4;
    }
    fclose(f);
    (*env)->ReleaseStringUTFChars(env, path, p);

    bool ok = core_unserialize(buf, (size_t)fsize);
    free(buf);
    return ok ? 0 : -5;
}

/* ── In-memory save/load for rewind ──────────────────────────────── */

JNIEXPORT jbyteArray JNICALL
Java_com_vortex_emulator_emulation_VortexNative_saveStateToMemory(
    JNIEnv *env, jobject thiz)
{
    if (!core_serialize_size || !core_serialize) return NULL;
    size_t size = core_serialize_size();
    if (size == 0) return NULL;

    void *buf = malloc(size);
    if (!buf) return NULL;

    if (!core_serialize(buf, size)) {
        free(buf);
        return NULL;
    }

    jbyteArray arr = (*env)->NewByteArray(env, (jsize)size);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, (jsize)size, (const jbyte *)buf);
    }
    free(buf);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_vortex_emulator_emulation_VortexNative_loadStateFromMemory(
    JNIEnv *env, jobject thiz, jbyteArray stateData)
{
    if (!core_unserialize || !stateData) return JNI_FALSE;

    jsize len = (*env)->GetArrayLength(env, stateData);
    jbyte *buf = (*env)->GetByteArrayElements(env, stateData, NULL);
    if (!buf) return JNI_FALSE;

    bool ok = core_unserialize(buf, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, stateData, buf, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getSerializeSize(
    JNIEnv *env, jobject thiz)
{
    if (!core_serialize_size) return 0;
    return (jlong)core_serialize_size();
}
