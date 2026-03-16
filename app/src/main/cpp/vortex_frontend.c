/*
 * vortex_frontend.c — libretro frontend for Vortex Emulator
 *
 * Loads a libretro core (.so), initialises it, runs frames, and pushes
 * video/audio back to the Kotlin layer via JNI callbacks.
 *
 * Supports both software-rendered and hardware-rendered (OpenGL ES) cores.
 * Complex cores (PPSSPP, mupen64plus, melonDS, Flycast, Play!) use
 * RETRO_ENVIRONMENT_SET_HW_RENDER to request a GL context; this frontend
 * creates an EGL context + off-screen PBuffer, lets them draw into an FBO,
 * then reads back pixels for the Java Bitmap renderer.
 */

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <stdio.h>
#include <errno.h>

#include <EGL/egl.h>
#include <GLES3/gl3.h>

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
static retro_get_memory_data_t            core_get_memory_data;
static retro_get_memory_size_t            core_get_memory_size;

/* ── State ───────────────────────────────────────────────────────── */
static unsigned g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
static char     g_system_dir[512]  = {0};
static char     g_save_dir[512]    = {0};
static char     g_core_path[512]   = {0};

/* Frame buffer pushed to Java (software rendering path) */
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

/* Input state — 4 ports, 16 buttons each + analog */
#define MAX_PORTS 4
static int16_t g_input_state[MAX_PORTS][16];
static int16_t g_analog_state[MAX_PORTS][2][2]; /* [port][index][x/y] */
/* Touch/pointer for NDS cores */
static int16_t g_pointer_x = 0;
static int16_t g_pointer_y = 0;
static bool    g_pointer_pressed = false;

/* Frame skip for performance optimisation */
static int g_frameskip = 0;         /* 0 = off, 1 = skip every other, 2 = skip 2 of 3, etc. */
static int g_frame_counter = 0;

/* ═══════════════════════════════════════════════════════════════════
 *  CORE OPTIONS — key/value store for core configuration
 * ═══════════════════════════════════════════════════════════════════ */
#define MAX_CORE_OPTIONS 256
#define MAX_OPTION_KEY_LEN 128
#define MAX_OPTION_VAL_LEN 256

typedef struct {
    char key[MAX_OPTION_KEY_LEN];
    char value[MAX_OPTION_VAL_LEN];
} core_option_t;

static core_option_t g_core_options[MAX_CORE_OPTIONS];
static int           g_core_option_count = 0;
static bool          g_core_options_updated = false;

static const char *get_core_option(const char *key) {
    for (int i = 0; i < g_core_option_count; i++) {
        if (strcmp(g_core_options[i].key, key) == 0)
            return g_core_options[i].value;
    }
    return NULL;
}

static void set_core_option(const char *key, const char *value) {
    for (int i = 0; i < g_core_option_count; i++) {
        if (strcmp(g_core_options[i].key, key) == 0) {
            snprintf(g_core_options[i].value, MAX_OPTION_VAL_LEN, "%s", value);
            return;
        }
    }
    if (g_core_option_count < MAX_CORE_OPTIONS) {
        snprintf(g_core_options[g_core_option_count].key, MAX_OPTION_KEY_LEN, "%s", key);
        snprintf(g_core_options[g_core_option_count].value, MAX_OPTION_VAL_LEN, "%s", value);
        g_core_option_count++;
    }
}

/* ═══════════════════════════════════════════════════════════════════
 *  HARDWARE RENDERING — EGL context + FBO for GL-based cores
 * ═══════════════════════════════════════════════════════════════════ */
static bool g_hw_render_enabled  = false;
static struct retro_hw_render_callback g_hw_render = {0};

static EGLDisplay g_egl_display = EGL_NO_DISPLAY;
static EGLContext g_egl_context = EGL_NO_CONTEXT;
static EGLSurface g_egl_surface = EGL_NO_SURFACE;
static EGLConfig  g_egl_config;

/* FBO where the core renders, we then read back pixels */
static GLuint g_hw_fbo         = 0;
static GLuint g_hw_color_tex   = 0;
static GLuint g_hw_depth_rb    = 0;
static unsigned g_hw_fbo_width  = 0;
static unsigned g_hw_fbo_height = 0;

static bool init_egl(unsigned context_type, unsigned major, unsigned minor) {
    g_egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_egl_display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }
    if (!eglInitialize(g_egl_display, NULL, NULL)) {
        LOGE("eglInitialize failed: 0x%x", eglGetError());
        return false;
    }

    int gles_version = 2;
    if (context_type == RETRO_HW_CONTEXT_OPENGLES3 ||
        context_type == RETRO_HW_CONTEXT_OPENGLES_VERSION) {
        gles_version = 3;
    }

    EGLint config_attribs[] = {
        EGL_SURFACE_TYPE,    EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, gles_version >= 3 ? EGL_OPENGL_ES3_BIT : EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,   8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE,  8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };

    EGLint num_configs;
    if (!eglChooseConfig(g_egl_display, config_attribs, &g_egl_config, 1, &num_configs) ||
        num_configs == 0) {
        LOGE("eglChooseConfig failed: 0x%x", eglGetError());
        return false;
    }

    eglBindAPI(EGL_OPENGL_ES_API);

    EGLint context_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, gles_version,
        EGL_NONE
    };
    g_egl_context = eglCreateContext(g_egl_display, g_egl_config,
                                      EGL_NO_CONTEXT, context_attribs);
    if (g_egl_context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        return false;
    }

    EGLint pbuf_attribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    g_egl_surface = eglCreatePbufferSurface(g_egl_display, g_egl_config, pbuf_attribs);
    if (g_egl_surface == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface failed: 0x%x", eglGetError());
        return false;
    }

    if (!eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, g_egl_context)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }

    LOGI("EGL init OK: GLES%d.0, vendor=%s",
         gles_version, eglQueryString(g_egl_display, EGL_VENDOR));
    return true;
}

static void destroy_egl(void) {
    if (g_egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_egl_surface != EGL_NO_SURFACE) {
            eglDestroySurface(g_egl_display, g_egl_surface);
            g_egl_surface = EGL_NO_SURFACE;
        }
        if (g_egl_context != EGL_NO_CONTEXT) {
            eglDestroyContext(g_egl_display, g_egl_context);
            g_egl_context = EGL_NO_CONTEXT;
        }
        eglTerminate(g_egl_display);
        g_egl_display = EGL_NO_DISPLAY;
    }
}

static void ensure_hw_fbo(unsigned w, unsigned h) {
    if (g_hw_fbo && g_hw_fbo_width == w && g_hw_fbo_height == h)
        return;

    if (g_hw_fbo) {
        glDeleteFramebuffers(1, &g_hw_fbo);
        glDeleteTextures(1, &g_hw_color_tex);
        glDeleteRenderbuffers(1, &g_hw_depth_rb);
        g_hw_fbo = 0;
    }

    glGenFramebuffers(1, &g_hw_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g_hw_fbo);

    glGenTextures(1, &g_hw_color_tex);
    glBindTexture(GL_TEXTURE_2D, g_hw_color_tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_hw_color_tex, 0);

    glGenRenderbuffers(1, &g_hw_depth_rb);
    glBindRenderbuffer(GL_RENDERBUFFER, g_hw_depth_rb);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
                              GL_RENDERBUFFER, g_hw_depth_rb);

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("FBO incomplete: 0x%x", status);
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    g_hw_fbo_width = w;
    g_hw_fbo_height = h;
    LOGI("HW FBO created: %ux%u", w, h);
}

static uintptr_t hw_get_current_framebuffer(void) {
    return (uintptr_t)g_hw_fbo;
}

static retro_proc_address_t hw_get_proc_address(const char *sym) {
    return (retro_proc_address_t)eglGetProcAddress(sym);
}

/* Read back HW framebuffer to our CPU pixel buffer */
static void hw_readback_frame(unsigned w, unsigned h) {
    /* Clamp to actual FBO dimensions to avoid reading out-of-bounds */
    if (g_hw_fbo_width > 0 && w > g_hw_fbo_width) w = g_hw_fbo_width;
    if (g_hw_fbo_height > 0 && h > g_hw_fbo_height) h = g_hw_fbo_height;

    size_t needed = (size_t)w * h;
    if (g_frame_width != w || g_frame_height != h) {
        free(g_frame_buf);
        g_frame_buf = (uint32_t *)malloc(needed * 4);
        if (!g_frame_buf) return;
        g_frame_width = w;
        g_frame_height = h;
    }

    /* Ensure all core rendering is complete before readback */
    glFinish();

    glBindFramebuffer(GL_FRAMEBUFFER, g_hw_fbo);

    /* Reset GL pack state — cores (e.g. mupen64plus/GLideN64) may change
     * these during rendering, causing striped/corrupted readback. */
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glPixelStorei(GL_PACK_ROW_LENGTH, 0);

    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, g_frame_buf);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    /* GL gives bottom-left origin; flip vertically and convert RGBA→ARGB */
    uint32_t *tmp_row = (uint32_t *)malloc(w * 4);
    if (tmp_row) {
        for (unsigned y = 0; y < h / 2; y++) {
            uint32_t *row_top = g_frame_buf + y * w;
            uint32_t *row_bot = g_frame_buf + (h - 1 - y) * w;
            memcpy(tmp_row, row_top, w * 4);
            memcpy(row_top, row_bot, w * 4);
            memcpy(row_bot, tmp_row, w * 4);
        }
        free(tmp_row);
    }

    /* Convert RGBA (GL order) → ARGB (Android Bitmap order) */
    for (size_t i = 0; i < needed; i++) {
        uint32_t px = g_frame_buf[i];
        uint8_t r = (px      ) & 0xFF;
        uint8_t g = (px >> 8 ) & 0xFF;
        uint8_t b = (px >> 16) & 0xFF;
        uint8_t a = (px >> 24) & 0xFF;
        g_frame_buf[i] = ((uint32_t)a << 24) | ((uint32_t)r << 16) |
                          ((uint32_t)g << 8)  | b;
    }

    g_frame_ready = true;
}

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

static bool rumble_set_state(unsigned port, unsigned effect, uint16_t strength) {
    return true;
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

        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
            *(const char **)data = g_system_dir;
            return true;

        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            struct retro_log_callback *cb = (struct retro_log_callback *)data;
            cb->log = core_log;
            return true;
        }

        /* ── Core Options v1 (SET_VARIABLES — old format) ──────── */
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            const struct retro_variable *vars = (const struct retro_variable *)data;
            if (!vars) return true;
            while (vars->key) {
                if (!get_core_option(vars->key) && vars->value) {
                    const char *semi = strchr(vars->value, ';');
                    if (semi) {
                        semi++;
                        while (*semi == ' ') semi++;
                        char first_val[MAX_OPTION_VAL_LEN];
                        const char *pipe = strchr(semi, '|');
                        if (pipe) {
                            size_t len = pipe - semi;
                            if (len >= MAX_OPTION_VAL_LEN) len = MAX_OPTION_VAL_LEN - 1;
                            memcpy(first_val, semi, len);
                            first_val[len] = '\0';
                        } else {
                            snprintf(first_val, MAX_OPTION_VAL_LEN, "%s", semi);
                        }
                        set_core_option(vars->key, first_val);
                    }
                }
                vars++;
            }
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable *var = (struct retro_variable *)data;
            if (!var || !var->key) return false;
            const char *val = get_core_option(var->key);
            if (val) {
                var->value = val;
                return true;
            }
            return false;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *(bool *)data = g_core_options_updated;
            g_core_options_updated = false;
            return true;

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS: {
            const struct retro_core_option_definition *opts =
                (const struct retro_core_option_definition *)data;
            if (!opts) return true;
            while (opts->key) {
                if (!get_core_option(opts->key)) {
                    const char *def = opts->default_value;
                    if (!def && opts->values[0].value) def = opts->values[0].value;
                    if (def) set_core_option(opts->key, def);
                }
                opts++;
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
            const struct retro_core_options_intl *intl =
                (const struct retro_core_options_intl *)data;
            if (intl && intl->us) {
                const struct retro_core_option_definition *opts = intl->us;
                while (opts->key) {
                    if (!get_core_option(opts->key)) {
                        const char *def = opts->default_value;
                        if (!def && opts->values[0].value) def = opts->values[0].value;
                        if (def) set_core_option(opts->key, def);
                    }
                    opts++;
                }
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: {
            const struct retro_core_options_v2 *v2 =
                (const struct retro_core_options_v2 *)data;
            if (v2 && v2->definitions) {
                const struct retro_core_option_v2_definition *opts = v2->definitions;
                while (opts->key) {
                    if (!get_core_option(opts->key)) {
                        const char *def = opts->default_value;
                        if (!def && opts->values[0].value) def = opts->values[0].value;
                        if (def) set_core_option(opts->key, def);
                    }
                    opts++;
                }
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            const struct retro_core_options_v2_intl *intl =
                (const struct retro_core_options_v2_intl *)data;
            if (intl && intl->us && intl->us->definitions) {
                const struct retro_core_option_v2_definition *opts = intl->us->definitions;
                while (opts->key) {
                    if (!get_core_option(opts->key)) {
                        const char *def = opts->default_value;
                        if (!def && opts->values[0].value) def = opts->values[0].value;
                        if (def) set_core_option(opts->key, def);
                    }
                    opts++;
                }
            }
            return true;
        }

        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *(unsigned *)data = 2;
            return true;

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
            return true;

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
            return true;

        /* ── Hardware rendering ────────────────────────────────── */
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            struct retro_hw_render_callback *hw =
                (struct retro_hw_render_callback *)data;
            if (!hw) return false;

            LOGI("Core requests HW render: type=%u, version=%u.%u, depth=%d, stencil=%d",
                 hw->context_type, hw->version_major, hw->version_minor,
                 hw->depth, hw->stencil);

            if (hw->context_type != RETRO_HW_CONTEXT_OPENGLES2 &&
                hw->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
                hw->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION &&
                hw->context_type != RETRO_HW_CONTEXT_OPENGL &&
                hw->context_type != RETRO_HW_CONTEXT_OPENGL_CORE) {
                LOGW("Remapping context type %u to GLES3", hw->context_type);
            }

            memcpy(&g_hw_render, hw, sizeof(g_hw_render));
            hw->get_current_framebuffer = hw_get_current_framebuffer;
            hw->get_proc_address = hw_get_proc_address;
            g_hw_render.get_current_framebuffer = hw_get_current_framebuffer;
            g_hw_render.get_proc_address = hw_get_proc_address;
            g_hw_render_enabled = true;
            return true;
        }

        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
            *(unsigned *)data = RETRO_HW_CONTEXT_OPENGLES3;
            return true;

        case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
            return true;

        /* ── Geometry changes ──────────────────────────────────── */
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            const struct retro_game_geometry *geo =
                (const struct retro_game_geometry *)data;
            if (geo) {
                LOGI("Geometry changed: %ux%u (max %ux%u, aspect %.3f)",
                     geo->base_width, geo->base_height,
                     geo->max_width, geo->max_height, geo->aspect_ratio);
                if (g_hw_render_enabled && geo->max_width > 0 && geo->max_height > 0)
                    ensure_hw_fbo(geo->max_width, geo->max_height);
            }
            return true;
        }

        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            const struct retro_system_av_info *av =
                (const struct retro_system_av_info *)data;
            if (av) {
                g_fps = av->timing.fps;
                g_sample_rate = av->timing.sample_rate;
                if (g_hw_render_enabled) {
                    unsigned w = av->geometry.max_width > 0 ?
                        av->geometry.max_width : av->geometry.base_width;
                    unsigned h = av->geometry.max_height > 0 ?
                        av->geometry.max_height : av->geometry.base_height;
                    if (w > 0 && h > 0) ensure_hw_fbo(w, h);
                }
            }
            return true;
        }

        /* ── Input ─────────────────────────────────────────────── */
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
            return true;
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES: {
            uint64_t *caps = (uint64_t *)data;
            *caps = (1ULL << RETRO_DEVICE_JOYPAD) | (1ULL << RETRO_DEVICE_ANALOG) |
                    (1ULL << RETRO_DEVICE_POINTER);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS:
            *(unsigned *)data = MAX_PORTS;
            return true;

        /* ── Misc ──────────────────────────────────────────────── */
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool *)data = true;
            return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            return true;
        case RETRO_ENVIRONMENT_GET_LIBRETRO_PATH:
            *(const char **)data = g_core_path;
            return true;
        case RETRO_ENVIRONMENT_SET_ROTATION:
            return true;
        case RETRO_ENVIRONMENT_GET_OVERSCAN:
            *(bool *)data = false;
            return true;
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
            return true;
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
            return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
            return true;
        /* SET_SERIALIZATION_QUIRKS shares value 44 with SET_HW_SHARED_CONTEXT (handled above) */
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
            return true;
        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: {
            struct retro_rumble_interface *ri = (struct retro_rumble_interface *)data;
            ri->set_rumble_state = rumble_set_state;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_USERNAME:
            *(const char **)data = "VortexPlayer";
            return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *(unsigned *)data = RETRO_LANGUAGE_ENGLISH;
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            const struct retro_message *msg = (const struct retro_message *)data;
            if (msg) LOGI("Core message: %s", msg->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            const struct retro_message_ext *msg = (const struct retro_message_ext *)data;
            if (msg) LOGI("Core message (ext): %s", msg->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
            *(unsigned *)data = 1;
            return true;
        case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK:
            return true;
        case RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK:
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            *(int *)data = 3;
            return true;
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLE: {
            const struct retro_variable *var = (const struct retro_variable *)data;
            if (var && var->key && var->value) {
                set_core_option(var->key, var->value);
                g_core_options_updated = true;
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
            *(unsigned *)data = 0;
            return true;
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            return false;
        case RETRO_ENVIRONMENT_GET_LED_INTERFACE:
            return false;
        case RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER:
            return false;
        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
            return false;
        case RETRO_ENVIRONMENT_GET_THROTTLE_STATE:
            return false;

        default:
            LOGW("Unhandled environment cmd: %u", cmd);
            return false;
    }
}

static void video_refresh_cb(const void *data, unsigned width,
                              unsigned height, size_t pitch) {
    if (g_hw_render_enabled) {
        /* Hardware rendering: the core drew into our FBO. */
        if (data == (void *)RETRO_HW_FRAME_BUFFER_VALID || data == NULL) {
            if (width > 0 && height > 0)
                hw_readback_frame(width, height);
        }
        return;
    }

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
    if (port >= MAX_PORTS) return 0;

    if (device == RETRO_DEVICE_JOYPAD && id < 16)
        return g_input_state[port][id];

    if (device == RETRO_DEVICE_ANALOG && index < 2 && id < 2)
        return g_analog_state[port][index][id];

    if (device == RETRO_DEVICE_POINTER) {
        switch (id) {
            case RETRO_DEVICE_ID_POINTER_X:       return g_pointer_x;
            case RETRO_DEVICE_ID_POINTER_Y:       return g_pointer_y;
            case RETRO_DEVICE_ID_POINTER_PRESSED:  return g_pointer_pressed ? 1 : 0;
        }
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

/* ═══════════════════════════════════════════════════════════════════ */
/*                        JNI FUNCTIONS                              */
/* ═══════════════════════════════════════════════════════════════════ */

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

    /* Reset HW render state */
    if (g_hw_fbo) {
        glDeleteFramebuffers(1, &g_hw_fbo);
        glDeleteTextures(1, &g_hw_color_tex);
        glDeleteRenderbuffers(1, &g_hw_depth_rb);
        g_hw_fbo = 0;
        g_hw_fbo_width = 0;
        g_hw_fbo_height = 0;
    }
    destroy_egl();
    g_hw_render_enabled = false;
    memset(&g_hw_render, 0, sizeof(g_hw_render));

    /* Clear core options */
    g_core_option_count = 0;
    g_core_options_updated = false;

    const char *path = (*env)->GetStringUTFChars(env, corePath, NULL);
    const char *sdir = (*env)->GetStringUTFChars(env, systemDir, NULL);
    const char *svdir = (*env)->GetStringUTFChars(env, saveDir, NULL);

    snprintf(g_system_dir, sizeof(g_system_dir), "%s", sdir);
    snprintf(g_save_dir, sizeof(g_save_dir), "%s", svdir);
    snprintf(g_core_path, sizeof(g_core_path), "%s", path);

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
    LOAD_SYM_OPT(core_get_memory_data, "retro_get_memory_data");
    LOAD_SYM_OPT(core_get_memory_size, "retro_get_memory_size");

    /* Set callbacks BEFORE init — environment is called during init by many cores */
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

    /* Verify file is accessible before passing to core */
    {
        FILE *check = fopen(path, "rb");
        if (!check) {
            LOGE("ROM file not accessible: %s (%s)", path, strerror(errno));
            (*env)->ReleaseStringUTFChars(env, romPath, path);
            return JNI_FALSE;
        }
        fseek(check, 0, SEEK_END);
        long sz = ftell(check);
        fclose(check);
        LOGI("ROM file verified: %ld bytes", sz);
        if (sz < 64) {
            LOGE("ROM file too small (%ld bytes), likely corrupted", sz);
            (*env)->ReleaseStringUTFChars(env, romPath, path);
            return JNI_FALSE;
        }
    }

    struct retro_system_info sysinfo = {0};
    core_get_system_info(&sysinfo);
    LOGI("Core: %s v%s, need_fullpath=%d, valid_ext=%s",
         sysinfo.library_name ? sysinfo.library_name : "?",
         sysinfo.library_version ? sysinfo.library_version : "?",
         sysinfo.need_fullpath,
         sysinfo.valid_extensions ? sysinfo.valid_extensions : "?");

    struct retro_game_info game = {0};

    if (sysinfo.need_fullpath) {
        game.path = path;
        game.data = NULL;
        game.size = 0;
    } else {
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

    LOGI("Calling retro_load_game (path=%s, data=%p, size=%zu)",
         game.path, game.data, game.size);
    bool ok = core_load_game(&game);
    LOGI("retro_load_game returned %s", ok ? "true" : "false");

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

        /* If the core requested HW rendering, init EGL and the FBO now */
        if (g_hw_render_enabled) {
            LOGI("Initialising HW rendering context...");
            if (!init_egl(g_hw_render.context_type,
                          g_hw_render.version_major,
                          g_hw_render.version_minor)) {
                LOGE("EGL init failed — falling back to software");
                g_hw_render_enabled = false;
            } else {
                unsigned fbo_w = av.geometry.max_width > 0 ?
                    av.geometry.max_width : av.geometry.base_width;
                unsigned fbo_h = av.geometry.max_height > 0 ?
                    av.geometry.max_height : av.geometry.base_height;
                if (fbo_w == 0) fbo_w = 640;
                if (fbo_h == 0) fbo_h = 480;
                ensure_hw_fbo(fbo_w, fbo_h);

                /* Notify the core that the context is ready */
                if (g_hw_render.context_reset) {
                    g_hw_render.context_reset();
                    LOGI("HW context reset callback invoked");
                }

                /* Release EGL from this thread so the emulation thread can acquire it */
                eglMakeCurrent(g_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            }
        }
    } else {
        LOGE("retro_load_game failed for: %s", path);
    }

    (*env)->ReleaseStringUTFChars(env, romPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_runFrame(
    JNIEnv *env, jobject thiz)
{
    if (!core_run) return;

    /* Frame skip: run core logic but skip video readback on skipped frames */
    bool skip_render = false;
    if (g_frameskip > 0) {
        g_frame_counter++;
        if ((g_frame_counter % (g_frameskip + 1)) != 0)
            skip_render = true;
    }

    /* Make sure EGL context is current on this thread for HW-rendered cores */
    if (g_hw_render_enabled && g_egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, g_egl_context);
    }

    g_frame_ready = false;
    g_audio_write_pos = 0;
    core_run();

    if (skip_render) {
        g_frame_ready = false;
        return;
    }

    /* For HW rendering: if the core wrote to our FBO but didn't call
     * video_refresh with RETRO_HW_FRAME_BUFFER_VALID, do the readback. */
    if (g_hw_render_enabled && !g_frame_ready &&
        g_hw_fbo_width > 0 && g_hw_fbo_height > 0) {
        hw_readback_frame(g_hw_fbo_width, g_hw_fbo_height);
    }
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
{ return (jint)g_frame_width; }

JNIEXPORT jint JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getFrameHeight(
    JNIEnv *env, jobject thiz)
{ return (jint)g_frame_height; }

JNIEXPORT jshortArray JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getAudioBuffer(
    JNIEnv *env, jobject thiz)
{
    if (g_audio_write_pos == 0) return NULL;

    jsize samples = (jsize)(g_audio_write_pos * 2);
    jshortArray arr = (*env)->NewShortArray(env, samples);
    if (arr) {
        (*env)->SetShortArrayRegion(env, arr, 0, samples, g_audio_buf);
    }
    return arr;
}

JNIEXPORT jdouble JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getFps(
    JNIEnv *env, jobject thiz)
{ return g_fps; }

JNIEXPORT jdouble JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getSampleRate(
    JNIEnv *env, jobject thiz)
{ return g_sample_rate; }

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setInputState(
    JNIEnv *env, jobject thiz,
    jint port, jint buttonId, jint value)
{
    if (port >= 0 && port < MAX_PORTS && buttonId >= 0 && buttonId < 16)
        g_input_state[port][buttonId] = (int16_t)value;
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setAnalogState(
    JNIEnv *env, jobject thiz,
    jint port, jint index, jint axisId, jint value)
{
    if (port >= 0 && port < MAX_PORTS && index >= 0 && index < 2 && axisId >= 0 && axisId < 2)
        g_analog_state[port][index][axisId] = (int16_t)value;
}

/* ── Pointer/touch input for NDS etc. ────────────────────────────── */

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setPointerState(
    JNIEnv *env, jobject thiz,
    jint x, jint y, jboolean pressed)
{
    g_pointer_x = (int16_t)x;
    g_pointer_y = (int16_t)y;
    g_pointer_pressed = (bool)pressed;
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_resetGame(
    JNIEnv *env, jobject thiz)
{ if (core_reset) core_reset(); }

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_unloadGame(
    JNIEnv *env, jobject thiz)
{
    if (g_hw_render_enabled && g_hw_render.context_destroy) {
        g_hw_render.context_destroy();
    }
    if (core_unload_game) core_unload_game();
    if (core_deinit) core_deinit();
    if (g_core_handle) {
        dlclose(g_core_handle);
        g_core_handle = NULL;
    }

    /* Clean up HW render resources */
    if (g_hw_fbo) {
        glDeleteFramebuffers(1, &g_hw_fbo);
        glDeleteTextures(1, &g_hw_color_tex);
        glDeleteRenderbuffers(1, &g_hw_depth_rb);
        g_hw_fbo = 0;
        g_hw_fbo_width = 0;
        g_hw_fbo_height = 0;
    }
    destroy_egl();
    g_hw_render_enabled = false;

    free(g_frame_buf);
    g_frame_buf = NULL;
    g_frame_width = 0;
    g_frame_height = 0;
    g_frame_ready = false;
    g_audio_write_pos = 0;
    g_core_option_count = 0;
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

/* ── Query whether the core uses HW rendering ────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_vortex_emulator_emulation_VortexNative_isHardwareRendered(
    JNIEnv *env, jobject thiz)
{
    return g_hw_render_enabled ? JNI_TRUE : JNI_FALSE;
}

/* ── Set a core option from Java ─────────────────────────────────── */

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setCoreOption(
    JNIEnv *env, jobject thiz,
    jstring key, jstring value)
{
    const char *k = (*env)->GetStringUTFChars(env, key, NULL);
    const char *v = (*env)->GetStringUTFChars(env, value, NULL);
    set_core_option(k, v);
    g_core_options_updated = true;
    (*env)->ReleaseStringUTFChars(env, key, k);
    (*env)->ReleaseStringUTFChars(env, value, v);
}

/* ── SRAM save/load for battery saves ────────────────────────────── */

JNIEXPORT jboolean JNICALL
Java_com_vortex_emulator_emulation_VortexNative_saveSRAM(
    JNIEnv *env, jobject thiz, jstring path)
{
    if (!core_get_memory_data || !core_get_memory_size) return JNI_FALSE;

    void *sram = core_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = core_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!sram || size == 0) return JNI_FALSE;

    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE *f = fopen(p, "wb");
    bool ok = false;
    if (f) {
        ok = (fwrite(sram, 1, size, f) == size);
        fclose(f);
    }
    (*env)->ReleaseStringUTFChars(env, path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_vortex_emulator_emulation_VortexNative_loadSRAM(
    JNIEnv *env, jobject thiz, jstring path)
{
    if (!core_get_memory_data || !core_get_memory_size) return JNI_FALSE;

    void *sram = core_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = core_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!sram || size == 0) return JNI_FALSE;

    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE *f = fopen(p, "rb");
    bool ok = false;
    if (f) {
        ok = (fread(sram, 1, size, f) == size);
        fclose(f);
    }
    (*env)->ReleaseStringUTFChars(env, path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* ── Frame skip control ──────────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setFrameSkip(
    JNIEnv *env, jobject thiz, jint skip)
{
    g_frameskip = (int)skip;
    g_frame_counter = 0;
    LOGI("Frame skip set to %d", g_frameskip);
}
