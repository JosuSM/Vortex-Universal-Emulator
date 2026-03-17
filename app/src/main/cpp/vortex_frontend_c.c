/*
 * vortex_frontend_c.c — Pure C libretro frontend for VortexEmulator (Android).
 *
 * Alternative to the C++ frontend, designed for better compatibility with
 * C-based cores (mupen64plus/GlideN64, PPSSPP, etc.).
 *
 * Key differences from the C++ frontend:
 *   - Pure C99/C11 — no C++ runtime, RAII, or STL overhead
 *   - Simple flat-array core option storage (no unordered_map)
 *   - pthread_mutex for thread safety (no std::mutex)
 *   - Direct texture upload (no PBO double-buffering)
 *   - Cleaner GL state management around retro_run
 *   - EnableFBEmulation=True by default for GlideN64 compatibility
 */

#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdio.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES3/gl31.h>

#include "libretro.h"

/* ── Logging ───────────────────────────────────────────────────── */
#define LOGT "VortexCFrontend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOGT, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOGT, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOGT, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOGT, __VA_ARGS__)

/* ── Constants ─────────────────────────────────────────────────── */
#define MAX_PORTS        4
#define MAX_BUTTONS      16
#define AUDIO_BUF_FRAMES 16384
#define MAX_CORE_OPTIONS 512
#define OPT_KEY_LEN      256
#define OPT_VAL_LEN      256
#define PATH_MAX_LEN     1024

/* ── Core option storage ───────────────────────────────────────── */
typedef struct {
    char key[OPT_KEY_LEN];
    char value[OPT_VAL_LEN];
    int  used;
} CoreOption;

static CoreOption       s_core_options[MAX_CORE_OPTIONS];
static int              s_core_option_count = 0;
static pthread_mutex_t  s_options_mutex = PTHREAD_MUTEX_INITIALIZER;
static atomic_bool      s_options_updated = ATOMIC_VAR_INIT(0);

/* ── Core option helpers ───────────────────────────────────────── */
static CoreOption* find_option(const char* key) {
    for (int i = 0; i < s_core_option_count; i++) {
        if (s_core_options[i].used && strcmp(s_core_options[i].key, key) == 0)
            return &s_core_options[i];
    }
    return NULL;
}

static void set_option(const char* key, const char* value) {
    pthread_mutex_lock(&s_options_mutex);
    CoreOption* opt = find_option(key);
    if (opt) {
        strncpy(opt->value, value, OPT_VAL_LEN - 1);
        opt->value[OPT_VAL_LEN - 1] = '\0';
    } else if (s_core_option_count < MAX_CORE_OPTIONS) {
        CoreOption* newopt = &s_core_options[s_core_option_count++];
        strncpy(newopt->key, key, OPT_KEY_LEN - 1);
        newopt->key[OPT_KEY_LEN - 1] = '\0';
        strncpy(newopt->value, value, OPT_VAL_LEN - 1);
        newopt->value[OPT_VAL_LEN - 1] = '\0';
        newopt->used = 1;
    }
    pthread_mutex_unlock(&s_options_mutex);
}

static int get_option(const char* key, const char** out_value) {
    pthread_mutex_lock(&s_options_mutex);
    CoreOption* opt = find_option(key);
    if (opt) {
        *out_value = opt->value;
        pthread_mutex_unlock(&s_options_mutex);
        return 1;
    }
    pthread_mutex_unlock(&s_options_mutex);
    return 0;
}

static void clear_options(void) {
    pthread_mutex_lock(&s_options_mutex);
    s_core_option_count = 0;
    memset(s_core_options, 0, sizeof(s_core_options));
    pthread_mutex_unlock(&s_options_mutex);
}

/* ── Cached JavaVM ─────────────────────────────────────────────── */
static JavaVM* s_jvm = NULL;

/* ── Thread attachment for cores (e.g. PPSSPP) ─────────────────── */
static void* jni_attach_thread(void) {
    JNIEnv* env = NULL;
    if (s_jvm && (*s_jvm)->AttachCurrentThread(s_jvm, &env, NULL) == JNI_OK)
        return env;
    LOGE("AttachCurrentThread failed");
    return NULL;
}

static void jni_detach_thread(void) {
    if (s_jvm) (*s_jvm)->DetachCurrentThread(s_jvm);
}

/* ── Libretro core function pointers ───────────────────────────── */
typedef struct {
    void* handle;

    void (*init)(void);
    void (*deinit)(void);
    unsigned (*api_version)(void);
    void (*get_system_info)(struct retro_system_info*);
    void (*get_system_av_info)(struct retro_system_av_info*);
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    bool (*load_game)(const struct retro_game_info*);
    void (*unload_game)(void);
    void (*run)(void);
    void (*reset)(void);

    /* Optional */
    size_t (*serialize_size)(void);
    bool (*serialize)(void*, size_t);
    bool (*unserialize)(const void*, size_t);
    void (*set_controller_port)(unsigned, unsigned);
    void* (*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
} CoreFuncs;

/* ── EGL state ─────────────────────────────────────────────────── */
static EGLDisplay s_egl_display = EGL_NO_DISPLAY;
static EGLContext s_egl_context = EGL_NO_CONTEXT;
static EGLSurface s_egl_surface = EGL_NO_SURFACE;
static EGLConfig  s_egl_config  = NULL;

static void egl_make_current(void) {
    if (s_egl_display != EGL_NO_DISPLAY && s_egl_context != EGL_NO_CONTEXT) {
        EGLSurface s = (s_egl_surface != EGL_NO_SURFACE) ? s_egl_surface : EGL_NO_SURFACE;
        eglMakeCurrent(s_egl_display, s, s, s_egl_context);
    }
}

static void egl_swap(void) {
    if (s_egl_display != EGL_NO_DISPLAY && s_egl_surface != EGL_NO_SURFACE)
        eglSwapBuffers(s_egl_display, s_egl_surface);
}

static int egl_valid(void) {
    return s_egl_display != EGL_NO_DISPLAY && s_egl_context != EGL_NO_CONTEXT;
}

static void egl_destroy(void) {
    if (s_egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(s_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (s_egl_surface != EGL_NO_SURFACE) {
            eglDestroySurface(s_egl_display, s_egl_surface);
            s_egl_surface = EGL_NO_SURFACE;
        }
        if (s_egl_context != EGL_NO_CONTEXT) {
            eglDestroyContext(s_egl_display, s_egl_context);
            s_egl_context = EGL_NO_CONTEXT;
        }
        eglTerminate(s_egl_display);
        s_egl_display = EGL_NO_DISPLAY;
    }
}

/* ── Blit resources ────────────────────────────────────────────── */
static GLuint s_blit_vao     = 0;
static GLuint s_blit_vbo     = 0;
static GLuint s_blit_texture = 0;
static GLuint s_blit_program = 0;

/* HW rendering */
static GLuint s_hw_fbo       = 0;
static GLuint s_hw_tex_color = 0;
static GLuint s_hw_rb_depth  = 0;
static GLuint s_hw_blit_prog = 0;
static unsigned s_hw_fbo_w   = 0;
static unsigned s_hw_fbo_h   = 0;

/* ── Global state ──────────────────────────────────────────────── */
static CoreFuncs             s_core;
static ANativeWindow*        s_window       = NULL;
static int                   s_surface_w    = 0;
static int                   s_surface_h    = 0;
static int                   s_hw_render    = 0;
static struct retro_hw_render_callback s_hw_cb;
static struct retro_system_av_info     s_av_info;
static double                s_fps          = 60.0;
static double                s_sample_rate  = 44100.0;
static unsigned              s_pixel_fmt    = 0; /* RETRO_PIXEL_FORMAT_0RGB1555 */

/* Frame buffer (software rendering) */
static uint32_t*             s_frame_buf    = NULL;
static size_t                s_frame_buf_sz = 0;
static unsigned              s_frame_w      = 0;
static unsigned              s_frame_h      = 0;
static atomic_bool           s_frame_ready  = ATOMIC_VAR_INIT(0);
static pthread_mutex_t       s_video_mutex  = PTHREAD_MUTEX_INITIALIZER;

/* Audio buffer */
static int16_t               s_audio_buf[AUDIO_BUF_FRAMES * 2];
static size_t                s_audio_write_pos = 0;
static pthread_mutex_t       s_audio_mutex  = PTHREAD_MUTEX_INITIALIZER;

/* Input state */
static int16_t               s_joypad[MAX_PORTS][MAX_BUTTONS];
static int16_t               s_analog[MAX_PORTS][2][2];
static int16_t               s_pointer_x    = 0;
static int16_t               s_pointer_y    = 0;
static int                   s_pointer_pressed = 0;
static pthread_mutex_t       s_input_mutex  = PTHREAD_MUTEX_INITIALIZER;

/* Frame skip */
static atomic_int            s_frame_skip   = ATOMIC_VAR_INIT(0);
static int                   s_frame_skip_counter = 0;
static atomic_int            s_input_poll_mode = ATOMIC_VAR_INIT(0); /* 0=normal, 1=early, 2=late */

/* HW context */
static atomic_bool           s_hw_ctx_reset_pending = ATOMIC_VAR_INIT(0);
static atomic_bool           s_hw_frame_presented   = ATOMIC_VAR_INIT(0);
static int                   s_debug_frame_counter  = 0;

/* Paths */
static char s_system_dir[PATH_MAX_LEN];
static char s_save_dir[PATH_MAX_LEN];

/* ── Shader sources ────────────────────────────────────────────── */
static const char* VERT_SRC =
    "#version 300 es\n"
    "layout(location=0) in vec2 aPos;\n"
    "layout(location=1) in vec2 aUV;\n"
    "out vec2 vUV;\n"
    "void main() {\n"
    "    gl_Position = vec4(aPos, 0.0, 1.0);\n"
    "    vUV = aUV;\n"
    "}\n";

static const char* FRAG_SW_SRC =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec2 vUV;\n"
    "out vec4 fragColor;\n"
    "uniform sampler2D uTex;\n"
    "void main() {\n"
    "    fragColor = texture(uTex, vUV);\n"
    "}\n";

static const char* FRAG_HW_SRC =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec2 vUV;\n"
    "out vec4 fragColor;\n"
    "uniform sampler2D uTex;\n"
    "uniform float uFlipY;\n"
    "void main() {\n"
    "    vec2 uv = vUV;\n"
    "    if (uFlipY > 0.5) uv.y = 1.0 - uv.y;\n"
    "    fragColor = texture(uTex, uv);\n"
    "}\n";

/* ── Compile shader helper ─────────────────────────────────────── */
static GLuint compile_shader(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        LOGE("Shader compile error: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static GLuint link_program(const char* vsrc, const char* fsrc) {
    GLuint vs = compile_shader(GL_VERTEX_SHADER, vsrc);
    GLuint fs = compile_shader(GL_FRAGMENT_SHADER, fsrc);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return 0;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetProgramInfoLog(prog, sizeof(log), NULL, log);
        LOGE("Program link error: %s", log);
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

/* ── Blit initialization ───────────────────────────────────────── */
static void init_blit(void) {
    /* Fullscreen quad: pos(x,y) + uv(s,t) */
    static const float quad[] = {
        -1.f, -1.f,  0.f, 0.f,
         1.f, -1.f,  1.f, 0.f,
        -1.f,  1.f,  0.f, 1.f,
         1.f,  1.f,  1.f, 1.f,
    };

    glGenVertexArrays(1, &s_blit_vao);
    glGenBuffers(1, &s_blit_vbo);
    glBindVertexArray(s_blit_vao);
    glBindBuffer(GL_ARRAY_BUFFER, s_blit_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));
    glEnableVertexAttribArray(1);
    glBindVertexArray(0);

    /* Software blit texture */
    glGenTextures(1, &s_blit_texture);
    glBindTexture(GL_TEXTURE_2D, s_blit_texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    /* Software blit program */
    s_blit_program = link_program(VERT_SRC, FRAG_SW_SRC);

    /* HW blit program */
    s_hw_blit_prog = link_program(VERT_SRC, FRAG_HW_SRC);

    LOGI("Blit init: vao=%u vbo=%u tex=%u sw_prog=%u hw_prog=%u",
         s_blit_vao, s_blit_vbo, s_blit_texture, s_blit_program, s_hw_blit_prog);
}

static void destroy_blit(void) {
    if (s_blit_vao) { glDeleteVertexArrays(1, &s_blit_vao); s_blit_vao = 0; }
    if (s_blit_vbo) { glDeleteBuffers(1, &s_blit_vbo); s_blit_vbo = 0; }
    if (s_blit_texture) { glDeleteTextures(1, &s_blit_texture); s_blit_texture = 0; }
    if (s_blit_program) { glDeleteProgram(s_blit_program); s_blit_program = 0; }
    if (s_hw_blit_prog) { glDeleteProgram(s_hw_blit_prog); s_hw_blit_prog = 0; }
}

/* ── EGL initialization ────────────────────────────────────────── */
static int init_egl(ANativeWindow* window) {
    egl_destroy();

    s_egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (s_egl_display == EGL_NO_DISPLAY) { LOGE("eglGetDisplay failed"); return 0; }
    if (!eglInitialize(s_egl_display, NULL, NULL)) { LOGE("eglInitialize failed"); egl_destroy(); return 0; }

    const EGLint cfg_attrs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
        EGL_RED_SIZE,   8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE,  8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    EGLint numConfigs = 0;
    eglChooseConfig(s_egl_display, cfg_attrs, &s_egl_config, 1, &numConfigs);
    if (numConfigs == 0) { LOGE("No EGL config"); egl_destroy(); return 0; }

    const EGLint ctx_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    s_egl_context = eglCreateContext(s_egl_display, s_egl_config, EGL_NO_CONTEXT, ctx_attrs);
    if (s_egl_context == EGL_NO_CONTEXT) { LOGE("eglCreateContext failed"); egl_destroy(); return 0; }

    s_egl_surface = eglCreateWindowSurface(s_egl_display, s_egl_config, window, NULL);
    if (s_egl_surface == EGL_NO_SURFACE) { LOGE("eglCreateWindowSurface failed"); egl_destroy(); return 0; }

    egl_make_current();
    init_blit();
    LOGI("EGL initialized: GLES %s, renderer %s", glGetString(GL_VERSION), glGetString(GL_RENDERER));
    return 1;
}

/* ── HW FBO creation ───────────────────────────────────────────── */
static void create_hw_fbo(unsigned w, unsigned h) {
    if (w == 0 || h == 0) {
        if (s_surface_w > 0 && s_surface_h > 0) {
            w = (unsigned)s_surface_w;
            h = (unsigned)s_surface_h;
            LOGI("create_hw_fbo: core reported 0x0, using surface %ux%u", w, h);
        } else {
            LOGW("create_hw_fbo: no valid dimensions");
            return;
        }
    }

    /* Clean up old */
    if (s_hw_fbo)       { glDeleteFramebuffers(1, &s_hw_fbo); s_hw_fbo = 0; }
    if (s_hw_tex_color) { glDeleteTextures(1, &s_hw_tex_color); s_hw_tex_color = 0; }
    if (s_hw_rb_depth)  { glDeleteRenderbuffers(1, &s_hw_rb_depth); s_hw_rb_depth = 0; }

    glGenFramebuffers(1, &s_hw_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, s_hw_fbo);

    /* Texture-backed color attachment */
    glGenTextures(1, &s_hw_tex_color);
    glBindTexture(GL_TEXTURE_2D, s_hw_tex_color);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)w, (GLsizei)h,
                 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s_hw_tex_color, 0);

    /* Depth/stencil renderbuffer */
    if (s_hw_cb.depth || s_hw_cb.stencil) {
        glGenRenderbuffers(1, &s_hw_rb_depth);
        glBindRenderbuffer(GL_RENDERBUFFER, s_hw_rb_depth);
        GLenum fmt = GL_DEPTH24_STENCIL8;
        if (s_hw_cb.depth && !s_hw_cb.stencil) fmt = GL_DEPTH_COMPONENT24;
        glRenderbufferStorage(GL_RENDERBUFFER, fmt, (GLsizei)w, (GLsizei)h);
        if (s_hw_cb.stencil)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, s_hw_rb_depth);
        else
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, s_hw_rb_depth);
    }

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE)
        LOGE("HW FBO incomplete: 0x%x", status);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    s_hw_fbo_w = w;
    s_hw_fbo_h = h;
    LOGI("Created HW FBO %u (%ux%u) tex=%u depth=%u", s_hw_fbo, w, h, s_hw_tex_color, s_hw_rb_depth);
}

/* ── HW render callbacks ───────────────────────────────────────── */
static uintptr_t hw_get_current_framebuffer(void) {
    return (uintptr_t)s_hw_fbo;
}

static retro_proc_address_t hw_get_proc_address(const char* sym) {
    return (retro_proc_address_t)eglGetProcAddress(sym);
}

/* ── Compute letterbox viewport ────────────────────────────────── */
static void compute_viewport(unsigned src_w, unsigned src_h,
                             int* out_x, int* out_y, int* out_w, int* out_h) {
    float src_aspect = (float)src_w / (float)src_h;
    float dst_aspect = (s_surface_h > 0) ? (float)s_surface_w / (float)s_surface_h : src_aspect;
    *out_x = 0; *out_y = 0;
    *out_w = s_surface_w; *out_h = s_surface_h;
    if (src_aspect > dst_aspect) {
        *out_h = (int)(s_surface_w / src_aspect);
        *out_y = (s_surface_h - *out_h) / 2;
    } else {
        *out_w = (int)(s_surface_h * src_aspect);
        *out_x = (s_surface_w - *out_w) / 2;
    }
}

/* ── Forward declarations ──────────────────────────────────────── */
static void blit_hw_frame(void);

/* ── Blit software frame ────────────────────────────────────────── */
static void blit_sw_frame(void) {
    unsigned w, h;
    pthread_mutex_lock(&s_video_mutex);
    if (!atomic_load(&s_frame_ready)) { pthread_mutex_unlock(&s_video_mutex); return; }
    w = s_frame_w;
    h = s_frame_h;
    if (w == 0 || h == 0 || !s_frame_buf) { pthread_mutex_unlock(&s_video_mutex); return; }

    glBindTexture(GL_TEXTURE_2D, s_blit_texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei)w, (GLsizei)h,
                 0, GL_RGBA, GL_UNSIGNED_BYTE, s_frame_buf);
    atomic_store(&s_frame_ready, 0);
    pthread_mutex_unlock(&s_video_mutex);

    /* Draw with letterbox */
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glViewport(0, 0, s_surface_w, s_surface_h);
    glClear(GL_COLOR_BUFFER_BIT);

    int vx, vy, vw, vh;
    compute_viewport(w, h, &vx, &vy, &vw, &vh);
    glViewport(vx, vy, vw, vh);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);

    glUseProgram(s_blit_program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, s_blit_texture);
    glBindVertexArray(s_blit_vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    atomic_store(&s_frame_ready, 0);
}

/* ── Blit HW frame ─────────────────────────────────────────────── */
static void blit_hw_frame(void) {
    if (!s_hw_tex_color || !s_hw_blit_prog) {
        if (s_debug_frame_counter < 5)
            LOGW("blit_hw_frame: missing resources (tex=%u prog=%u)", s_hw_tex_color, s_hw_blit_prog);
        return;
    }

    unsigned w = s_frame_w;
    unsigned h = s_frame_h;
    if (w == 0 || h == 0) { w = s_hw_fbo_w; h = s_hw_fbo_h; }
    if (w == 0 || h == 0) return;

    /* Check which FBO the core left bound */
    GLint core_fbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &core_fbo);
    int core_used_ours = (s_hw_fbo != 0 && (GLuint)core_fbo == s_hw_fbo);

    if (s_debug_frame_counter < 10) {
        LOGI("blit_hw: frame=%ux%u fbo=%u tex=%u core_left=%d surface=%dx%d flip=%d ours=%d",
             w, h, s_hw_fbo, s_hw_tex_color, core_fbo,
             s_surface_w, s_surface_h, s_hw_cb.bottom_left_origin, core_used_ours);
    }
    s_debug_frame_counter++;

    if (!core_used_ours) {
        /* Core rendered to FBO 0 (or its own FBO). Copy to our texture. */
        glFinish();

        if (s_debug_frame_counter <= 5) {
            uint8_t px[4] = {0};
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            glReadPixels(10, 10, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            LOGI("blit_hw diag: FBO0(10,10) R%u G%u B%u A%u", px[0], px[1], px[2], px[3]);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, s_hw_fbo);
            glReadPixels((GLint)(w/2), (GLint)(h/2), 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            LOGI("blit_hw diag: OurFBO(%u,%u) R%u G%u B%u A%u", w/2, h/2, px[0], px[1], px[2], px[3]);
        }

        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, s_hw_tex_color);
        if (w != s_hw_fbo_w || h != s_hw_fbo_h) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)w, (GLsizei)h,
                         0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
            s_hw_fbo_w = w;
            s_hw_fbo_h = h;
        }
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, (GLsizei)w, (GLsizei)h);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /* Draw FBO texture to screen */
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glViewport(0, 0, s_surface_w, s_surface_h);
    glClear(GL_COLOR_BUFFER_BIT);

    int vx, vy, vw, vh;
    compute_viewport(w, h, &vx, &vy, &vw, &vh);
    glViewport(vx, vy, vw, vh);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);

    glUseProgram(s_hw_blit_prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, s_hw_tex_color);
    glUniform1f(glGetUniformLocation(s_hw_blit_prog, "uFlipY"),
                s_hw_cb.bottom_left_origin ? 1.0f : 0.0f);
    glBindVertexArray(s_blit_vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    atomic_store(&s_frame_ready, 0);
}

/* ── Libretro callbacks ────────────────────────────────────────── */
static void core_log(enum retro_log_level level, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int prio;
    switch (level) {
        case RETRO_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        case RETRO_LOG_INFO:  prio = ANDROID_LOG_INFO;  break;
        case RETRO_LOG_WARN:  prio = ANDROID_LOG_WARN;  break;
        case RETRO_LOG_ERROR: prio = ANDROID_LOG_ERROR;  break;
        default:              prio = ANDROID_LOG_VERBOSE; break;
    }
    __android_log_vprint(prio, "RetroCore", fmt, ap);
    va_end(ap);
}

static void core_video_refresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    static int vr_count = 0;
    if (vr_count < 20) {
        LOGI("video_refresh[%d]: data=%p w=%u h=%u pitch=%zu hw=%d",
             vr_count, data, width, height, pitch, s_hw_render);
        vr_count++;
    }

    if (s_hw_render) {
        if (width > 0 && height > 0) {
            pthread_mutex_lock(&s_video_mutex);
            s_frame_w = width;
            s_frame_h = height;
            pthread_mutex_unlock(&s_video_mutex);
        }
        /* Blit + swap INSIDE callback (works with blocking cores) */
        if (egl_valid() && s_egl_surface != EGL_NO_SURFACE) {
            blit_hw_frame();
            egl_swap();
            atomic_store(&s_hw_frame_presented, 1);
        }
        atomic_store(&s_frame_ready, 1);
        return;
    }

    if (!data) return; /* duped frame */

    pthread_mutex_lock(&s_video_mutex);
    s_frame_w = width;
    s_frame_h = height;

    size_t needed = (size_t)width * height;
    if (needed > s_frame_buf_sz) {
        free(s_frame_buf);
        s_frame_buf = (uint32_t*)malloc(needed * sizeof(uint32_t));
        s_frame_buf_sz = needed;
    }

    const uint8_t* src = (const uint8_t*)data;
    for (unsigned y = 0; y < height; y++) {
        const uint8_t* row = src + y * pitch;
        uint32_t* dst = s_frame_buf + y * width;

        switch (s_pixel_fmt) {
            case 2: /* RETRO_PIXEL_FORMAT_XRGB8888 */
                memcpy(dst, row, width * 4);
                break;
            case 1: { /* RETRO_PIXEL_FORMAT_RGB565 */
                for (unsigned x = 0; x < width; x++) {
                    uint16_t px;
                    memcpy(&px, row + x * 2, 2);
                    uint8_t r = (uint8_t)((px >> 11) << 3);
                    uint8_t g = (uint8_t)(((px >> 5) & 0x3F) << 2);
                    uint8_t b = (uint8_t)((px & 0x1F) << 3);
                    dst[x] = 0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
                }
                break;
            }
            default: { /* RETRO_PIXEL_FORMAT_0RGB1555 */
                for (unsigned x = 0; x < width; x++) {
                    uint16_t px;
                    memcpy(&px, row + x * 2, 2);
                    uint8_t r = (uint8_t)(((px >> 10) & 0x1F) << 3);
                    uint8_t g = (uint8_t)(((px >> 5) & 0x1F) << 3);
                    uint8_t b = (uint8_t)((px & 0x1F) << 3);
                    dst[x] = 0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
                }
                break;
            }
        }
    }
    atomic_store(&s_frame_ready, 1);
    pthread_mutex_unlock(&s_video_mutex);
}

static void core_audio_sample(int16_t left, int16_t right) {
    pthread_mutex_lock(&s_audio_mutex);
    if (s_audio_write_pos + 2 <= AUDIO_BUF_FRAMES * 2) {
        s_audio_buf[s_audio_write_pos++] = left;
        s_audio_buf[s_audio_write_pos++] = right;
    }
    pthread_mutex_unlock(&s_audio_mutex);
}

static size_t core_audio_sample_batch(const int16_t* data, size_t frames) {
    pthread_mutex_lock(&s_audio_mutex);
    size_t samples = frames * 2;
    size_t cap = AUDIO_BUF_FRAMES * 2;
    size_t space = cap - s_audio_write_pos;
    size_t to_copy = (samples < space) ? samples : space;
    memcpy(s_audio_buf + s_audio_write_pos, data, to_copy * sizeof(int16_t));
    s_audio_write_pos += to_copy;
    pthread_mutex_unlock(&s_audio_mutex);
    return frames;
}

static void core_input_poll(void) { /* no-op, state is pushed */ }

static int16_t core_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    pthread_mutex_lock(&s_input_mutex);
    int16_t val = 0;
    if (port < MAX_PORTS) {
        switch (device & RETRO_DEVICE_MASK) {
            case RETRO_DEVICE_JOYPAD:
                if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
                    for (int i = 0; i < MAX_BUTTONS; i++)
                        if (s_joypad[port][i]) val |= (1 << i);
                } else if (id < MAX_BUTTONS) {
                    val = s_joypad[port][id];
                }
                break;
            case RETRO_DEVICE_ANALOG:
                if (index < 2 && id < 2) val = s_analog[port][index][id];
                break;
            case RETRO_DEVICE_POINTER:
                if (port == 0) {
                    switch (id) {
                        case RETRO_DEVICE_ID_POINTER_X: val = s_pointer_x; break;
                        case RETRO_DEVICE_ID_POINTER_Y: val = s_pointer_y; break;
                        case RETRO_DEVICE_ID_POINTER_PRESSED: val = s_pointer_pressed ? 1 : 0; break;
                    }
                }
                break;
        }
    }
    pthread_mutex_unlock(&s_input_mutex);
    return val;
}

/* ── Performance interface ─────────────────────────────────────── */
#include <time.h>

static retro_time_t perf_get_time_usec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (retro_time_t)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

static retro_perf_tick_t perf_get_counter(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (retro_perf_tick_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static retro_perf_tick_t perf_get_features(void) { return 0; }
static void perf_log(void) {}
static void perf_register(struct retro_perf_counter* c) { (void)c; }
static void perf_start(struct retro_perf_counter* c) {
    if (c) c->start = perf_get_counter();
}
static void perf_stop(struct retro_perf_counter* c) {
    if (c) c->total += perf_get_counter() - c->start;
}

/* ── Environment callback ──────────────────────────────────────── */
static int env_inner(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            struct retro_log_callback* cb = (struct retro_log_callback*)data;
            cb->log = core_log;
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            *(const char**)data = s_system_dir;
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            *(const char**)data = s_save_dir;
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: {
            *(const char**)data = s_system_dir;
            return 1;
        }
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            s_pixel_fmt = *(const unsigned*)data;
            LOGI("Pixel format: %u", s_pixel_fmt);
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            *(bool*)data = true;
            return 1;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            struct retro_hw_render_callback* hw = (struct retro_hw_render_callback*)data;
            LOGI("HW render requested: type=%u version=%u.%u depth=%d stencil=%d bottom_left=%d",
                 hw->context_type, hw->version_major, hw->version_minor,
                 hw->depth, hw->stencil, hw->bottom_left_origin);
            if (hw->context_type == RETRO_HW_CONTEXT_VULKAN) {
                LOGW("Vulkan not supported");
                return 0;
            }
            s_hw_render = 1;
            s_hw_cb = *hw;
            s_hw_cb.get_current_framebuffer = hw_get_current_framebuffer;
            s_hw_cb.get_proc_address = hw_get_proc_address;
            *hw = s_hw_cb;
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable* var = (struct retro_variable*)data;
            if (!var->key) return 0;
            const char* val = NULL;
            if (get_option(var->key, &val)) {
                var->value = val;
                static int log_count = 0;
                if (log_count < 50) {
                    LOGD("GET_VARIABLE: %s = %s", var->key, var->value);
                    log_count++;
                }
                return 1;
            }
            static int miss_count = 0;
            if (miss_count < 30) {
                LOGD("GET_VARIABLE miss: %s", var->key);
                miss_count++;
            }
            return 0;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            const struct retro_variable* vars = (const struct retro_variable*)data;
            while (vars && vars->key) {
                const char* existing = NULL;
                if (!get_option(vars->key, &existing)) {
                    /* Parse default: "Description; val1|val2|..." */
                    const char* semi = strchr(vars->value, ';');
                    if (semi) {
                        const char* p = semi + 1;
                        while (*p == ' ') p++;
                        const char* pipe = strchr(p, '|');
                        char def[OPT_VAL_LEN];
                        size_t len = pipe ? (size_t)(pipe - p) : strlen(p);
                        if (len >= OPT_VAL_LEN) len = OPT_VAL_LEN - 1;
                        memcpy(def, p, len);
                        def[len] = '\0';
                        set_option(vars->key, def);
                    }
                }
                vars++;
            }
            return 1;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
            if (cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS && data) {
                const struct retro_core_option_definition* defs = (const struct retro_core_option_definition*)data;
                while (defs && defs->key) {
                    const char* existing = NULL;
                    if (!get_option(defs->key, &existing)) {
                        if (defs->default_value)
                            set_option(defs->key, defs->default_value);
                        else if (defs->values[0].value)
                            set_option(defs->key, defs->values[0].value);
                    }
                    defs++;
                }
            } else if (cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL && data) {
                const struct retro_core_options_intl* intl = (const struct retro_core_options_intl*)data;
                if (intl->us) {
                    const struct retro_core_option_definition* defs = intl->us;
                    while (defs->key) {
                        const char* existing = NULL;
                        if (!get_option(defs->key, &existing)) {
                            if (defs->default_value)
                                set_option(defs->key, defs->default_value);
                            else if (defs->values[0].value)
                                set_option(defs->key, defs->values[0].value);
                        }
                        defs++;
                    }
                }
            }
            return 1;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            if (cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2 && data) {
                const struct retro_core_options_v2* v2 = (const struct retro_core_options_v2*)data;
                if (v2->definitions) {
                    const struct retro_core_option_v2_definition* defs = v2->definitions;
                    while (defs->key) {
                        const char* existing = NULL;
                        if (!get_option(defs->key, &existing)) {
                            if (defs->default_value)
                                set_option(defs->key, defs->default_value);
                            else if (defs->values[0].value)
                                set_option(defs->key, defs->values[0].value);
                        }
                        defs++;
                    }
                }
            } else if (cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL && data) {
                const struct retro_core_options_v2_intl* intl = (const struct retro_core_options_v2_intl*)data;
                if (intl->us && intl->us->definitions) {
                    const struct retro_core_option_v2_definition* defs = intl->us->definitions;
                    while (defs->key) {
                        const char* existing = NULL;
                        if (!get_option(defs->key, &existing)) {
                            if (defs->default_value)
                                set_option(defs->key, defs->default_value);
                            else if (defs->values[0].value)
                                set_option(defs->key, defs->values[0].value);
                        }
                        defs++;
                    }
                }
            }
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: {
            *(unsigned*)data = 2;
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            _Bool expected = 1;
            _Bool updated = atomic_compare_exchange_strong(&s_options_updated, &expected, 0);
            *(bool*)data = updated;
            return 1;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
            return 1;
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            const struct retro_game_geometry* geo = (const struct retro_game_geometry*)data;
            if (geo) {
                s_av_info.geometry.base_width = geo->base_width;
                s_av_info.geometry.base_height = geo->base_height;
                if (geo->max_width > 0 && geo->max_height > 0) {
                    s_av_info.geometry.max_width = geo->max_width;
                    s_av_info.geometry.max_height = geo->max_height;
                }
                if (geo->aspect_ratio > 0.0f)
                    s_av_info.geometry.aspect_ratio = geo->aspect_ratio;
                LOGI("Geometry updated: %ux%u (max %ux%u)", geo->base_width, geo->base_height,
                     s_av_info.geometry.max_width, s_av_info.geometry.max_height);
                /* Create HW FBO if we now have valid dimensions and didn't before */
                if (s_hw_render && !s_hw_fbo && s_av_info.geometry.max_width > 0 && egl_valid())
                    create_hw_fbo(s_av_info.geometry.max_width, s_av_info.geometry.max_height);
            }
            return 1;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            const struct retro_system_av_info* info = (const struct retro_system_av_info*)data;
            if (info) {
                s_av_info = *info;
                s_fps = info->timing.fps;
                s_sample_rate = info->timing.sample_rate;
                if (s_hw_render && egl_valid())
                    create_hw_fbo(info->geometry.max_width, info->geometry.max_height);
            }
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_PERF_INTERFACE: {
            struct retro_perf_callback* cb = (struct retro_perf_callback*)data;
            cb->get_time_usec    = perf_get_time_usec;
            cb->get_cpu_features = perf_get_features;
            cb->get_perf_counter = perf_get_counter;
            cb->perf_register    = perf_register;
            cb->perf_start       = perf_start;
            cb->perf_stop        = perf_stop;
            cb->perf_log         = perf_log;
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: {
            *(unsigned*)data = RETRO_HW_CONTEXT_OPENGLES3;
            return 1;
        }
        case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
            LOGI("Core requests shared HW context");
            return 1;
        case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES: {
            *(uint64_t*)data = (1 << RETRO_DEVICE_JOYPAD) |
                               (1 << RETRO_DEVICE_ANALOG) |
                               (1 << RETRO_DEVICE_POINTER);
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_LANGUAGE: {
            *(unsigned*)data = RETRO_LANGUAGE_ENGLISH;
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: {
            *(int*)data = 3; /* both on */
            return 1;
        }
        /* RETRO_ENVIRONMENT_GET_FASTFORWARDING may not be in all libretro.h versions */
        case 64 | RETRO_ENVIRONMENT_EXPERIMENTAL: {
            *(bool*)data = false;
            return 1;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLE: {
            if (data) {
                const struct retro_variable* var = (const struct retro_variable*)data;
                if (var->key && var->value) {
                    set_option(var->key, var->value);
                    atomic_store(&s_options_updated, 1);
                }
            }
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_USERNAME: {
            *(const char**)data = "VortexPlayer";
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return 1;
        case RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS: {
            *(unsigned*)data = MAX_PORTS;
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_OVERSCAN: {
            *(bool*)data = false;
            return 1;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            if (data) {
                const struct retro_message* msg = (const struct retro_message*)data;
                LOGI("Core message: %s", msg->msg);
            }
            return 1;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            if (data) {
                const struct retro_message_ext* msg = (const struct retro_message_ext*)data;
                LOGI("Core message (ext): %s", msg->msg);
            }
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION: {
            *(unsigned*)data = 1;
            return 1;
        }
        case RETRO_ENVIRONMENT_SET_ROTATION:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
            return 1;
        case RETRO_ENVIRONMENT_SHUT_DOWN: {
            LOGI("Core requested shutdown");
            return 1;
        }
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
            return 1;
        case RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER:
        case RETRO_ENVIRONMENT_GET_LIBRETRO_PATH:
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
        case RETRO_ENVIRONMENT_GET_LED_INTERFACE:
        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE:
        case RETRO_ENVIRONMENT_GET_THROTTLE_STATE:
        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
        case RETRO_ENVIRONMENT_GET_GAME_INFO_EXT:
        case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK:
        case RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK:
            return 0;
        default:
            return 0;
    }
}

static bool core_environment(unsigned cmd, void* data) {
    if (cmd & RETRO_ENVIRONMENT_PRIVATE) return false;
    if (env_inner(cmd, data)) return true;
    if (cmd & RETRO_ENVIRONMENT_EXPERIMENTAL) {
        unsigned base = cmd & ~RETRO_ENVIRONMENT_EXPERIMENTAL;
        if (env_inner(base, data)) return true;
        LOGD("Unhandled env: %u (base %u)", cmd, base);
    } else {
        LOGD("Unhandled env: %u", cmd);
    }
    return false;
}

/* ── Symbol loading ────────────────────────────────────────────── */
#define LOAD_SYM(field, name) do { \
    s_core.field = dlsym(s_core.handle, name); \
    if (!s_core.field) { LOGE("Missing: %s — %s", name, dlerror()); return 0; } \
} while(0)

#define LOAD_SYM_OPT(field, name) do { \
    s_core.field = dlsym(s_core.handle, name); \
    if (!s_core.field) LOGD("Optional not found: %s", name); \
} while(0)

static int load_core_symbols(void) {
    LOAD_SYM(init, "retro_init");
    LOAD_SYM(deinit, "retro_deinit");
    LOAD_SYM(api_version, "retro_api_version");
    LOAD_SYM(get_system_info, "retro_get_system_info");
    LOAD_SYM(get_system_av_info, "retro_get_system_av_info");
    LOAD_SYM(set_environment, "retro_set_environment");
    LOAD_SYM(set_video_refresh, "retro_set_video_refresh");
    LOAD_SYM(set_audio_sample, "retro_set_audio_sample");
    LOAD_SYM(set_audio_sample_batch, "retro_set_audio_sample_batch");
    LOAD_SYM(set_input_poll, "retro_set_input_poll");
    LOAD_SYM(set_input_state, "retro_set_input_state");
    LOAD_SYM(load_game, "retro_load_game");
    LOAD_SYM(unload_game, "retro_unload_game");
    LOAD_SYM(run, "retro_run");
    LOAD_SYM(reset, "retro_reset");

    LOAD_SYM_OPT(serialize_size, "retro_serialize_size");
    LOAD_SYM_OPT(serialize, "retro_serialize");
    LOAD_SYM_OPT(unserialize, "retro_unserialize");
    LOAD_SYM_OPT(set_controller_port, "retro_set_controller_port_device");
    LOAD_SYM_OPT(get_memory_data, "retro_get_memory_data");
    LOAD_SYM_OPT(get_memory_size, "retro_get_memory_size");
    return 1;
}

#undef LOAD_SYM
#undef LOAD_SYM_OPT

/* ── Cleanup ───────────────────────────────────────────────────── */
static void cleanup_all(void) {
    destroy_blit();
    if (s_hw_fbo)       { glDeleteFramebuffers(1, &s_hw_fbo); s_hw_fbo = 0; }
    if (s_hw_tex_color) { glDeleteTextures(1, &s_hw_tex_color); s_hw_tex_color = 0; }
    if (s_hw_rb_depth)  { glDeleteRenderbuffers(1, &s_hw_rb_depth); s_hw_rb_depth = 0; }
    egl_destroy();
    if (s_window) { ANativeWindow_release(s_window); s_window = NULL; }
    memset(&s_core, 0, sizeof(s_core));
    s_hw_render = 0;
    memset(&s_hw_cb, 0, sizeof(s_hw_cb));
    s_hw_fbo_w = s_hw_fbo_h = 0;
    free(s_frame_buf); s_frame_buf = NULL; s_frame_buf_sz = 0;
    s_frame_w = s_frame_h = 0;
    atomic_store(&s_frame_ready, 0);
    s_audio_write_pos = 0;
    s_pixel_fmt = 0;
    clear_options();
    atomic_store(&s_options_updated, 0);
    memset(s_joypad, 0, sizeof(s_joypad));
    memset(s_analog, 0, sizeof(s_analog));
    s_debug_frame_counter = 0;
}

/* ══════════════════════════════════════════════════════════════════
 *  JNI EXPORTS
 * ══════════════════════════════════════════════════════════════════ */

#define JNI_PREFIX(name) Java_com_vortex_emulator_emulation_VortexNativeC_##name

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    s_jvm = vm;
    LOGI("C Frontend JNI_OnLoad: JavaVM cached (%p)", (void*)vm);
    return JNI_VERSION_1_6;
}

/* ── loadCore ──────────────────────────────────────────────────── */
JNIEXPORT jint JNICALL
JNI_PREFIX(loadCore)(JNIEnv* env, jobject thiz,
                     jstring jCorePath, jstring jSystemDir, jstring jSaveDir)
{
    (void)thiz;
    const char* corePath  = (*env)->GetStringUTFChars(env, jCorePath, NULL);
    const char* systemDir = (*env)->GetStringUTFChars(env, jSystemDir, NULL);
    const char* saveDir   = (*env)->GetStringUTFChars(env, jSaveDir, NULL);

    strncpy(s_system_dir, systemDir, PATH_MAX_LEN - 1);
    strncpy(s_save_dir, saveDir, PATH_MAX_LEN - 1);

    LOGI("Loading core (C frontend): %s", corePath);

    /* Clean up previous */
    if (s_core.deinit) s_core.deinit();
    memset(&s_core, 0, sizeof(s_core));
    s_hw_render = 0;
    memset(&s_hw_cb, 0, sizeof(s_hw_cb));
    s_debug_frame_counter = 0;

    void* handle = dlopen(corePath, RTLD_LAZY);

    (*env)->ReleaseStringUTFChars(env, jCorePath, corePath);
    (*env)->ReleaseStringUTFChars(env, jSystemDir, systemDir);
    (*env)->ReleaseStringUTFChars(env, jSaveDir, saveDir);

    if (!handle) {
        LOGE("dlopen failed: %s", dlerror());
        return -1;
    }
    s_core.handle = handle;

    /* Call core's JNI_OnLoad if present (needed by PPSSPP etc.) */
    if (s_jvm) {
        typedef jint (*JniOnLoad_fn)(JavaVM*, void*);
        JniOnLoad_fn core_jni = (JniOnLoad_fn)dlsym(handle, "JNI_OnLoad");
        if (core_jni) {
            jint ver = core_jni(s_jvm, NULL);
            LOGI("Called core JNI_OnLoad -> 0x%x", ver);
        }

        /* PPSSPP: set gJvm */
        void** jvm_ptr = (void**)dlsym(handle, "gJvm");
        if (jvm_ptr) {
            *jvm_ptr = (void*)s_jvm;
            LOGI("Set gJvm in core to %p", *jvm_ptr);
        }

        /* PPSSPP: set g_attach/g_detach */
        void* (**attach_ptr)(void) = (void* (**)(void))dlsym(handle, "g_attach");
        void (**detach_ptr)(void) = (void (**)(void))dlsym(handle, "g_detach");
        if (attach_ptr && detach_ptr) {
            *attach_ptr = jni_attach_thread;
            *detach_ptr = jni_detach_thread;
            LOGI("Set g_attach/g_detach in core");
        }
    }

    if (!load_core_symbols()) {
        LOGE("Failed to load core symbols");
        dlclose(handle);
        memset(&s_core, 0, sizeof(s_core));
        return -2;
    }

    unsigned api = s_core.api_version();
    LOGI("Core API version: %u", api);

    s_core.set_environment(core_environment);
    s_core.init();
    s_core.set_video_refresh(core_video_refresh);
    s_core.set_audio_sample(core_audio_sample);
    s_core.set_audio_sample_batch(core_audio_sample_batch);
    s_core.set_input_poll(core_input_poll);
    s_core.set_input_state(core_input_state);

    struct retro_system_info sysInfo;
    memset(&sysInfo, 0, sizeof(sysInfo));
    s_core.get_system_info(&sysInfo);
    LOGI("Core: %s (%s)", sysInfo.library_name, sysInfo.library_version);
    LOGI("HW render: %s", s_hw_render ? "yes" : "no");

    /* Pre-set critical options for GlideN64 compatibility.
     * EnableFBEmulation=True is THE key difference — without it,
     * GlideN64 produces no pixels on mobile GPUs. */
    set_option("mupen64plus-EnableFBEmulation", "True");
    set_option("mupen64plus-EnableShadersStorage", "False");
    LOGI("Pre-set mupen64plus options: EnableFBEmulation=True, EnableShadersStorage=False");

    return 0;
}

/* ── loadGame ──────────────────────────────────────────────────── */
JNIEXPORT jboolean JNICALL
JNI_PREFIX(loadGame)(JNIEnv* env, jobject thiz, jstring jRomPath)
{
    (void)thiz;
    if (!s_core.load_game) return JNI_FALSE;

    const char* romPath = (*env)->GetStringUTFChars(env, jRomPath, NULL);
    LOGI("Loading game: %s", romPath);

    struct retro_game_info gameInfo;
    memset(&gameInfo, 0, sizeof(gameInfo));
    gameInfo.path = romPath;

    struct retro_system_info sysInfo;
    memset(&sysInfo, 0, sizeof(sysInfo));
    s_core.get_system_info(&sysInfo);

    /* If core doesn't use file paths, load ROM into memory */
    uint8_t* rom_data = NULL;
    size_t rom_size = 0;
    if (!sysInfo.need_fullpath) {
        FILE* f = fopen(romPath, "rb");
        if (f) {
            fseek(f, 0, SEEK_END);
            long sz = ftell(f);
            fseek(f, 0, SEEK_SET);
            if (sz > 0) {
                rom_data = (uint8_t*)malloc((size_t)sz);
                if (rom_data) {
                    rom_size = fread(rom_data, 1, (size_t)sz, f);
                    if (rom_size == (size_t)sz) {
                        gameInfo.data = rom_data;
                        gameInfo.size = rom_size;
                    }
                }
            }
            fclose(f);
        }
    }

    bool ok = s_core.load_game(&gameInfo);
    (*env)->ReleaseStringUTFChars(env, jRomPath, romPath);
    free(rom_data);

    if (!ok) {
        LOGE("retro_load_game failed");
        return JNI_FALSE;
    }

    s_core.get_system_av_info(&s_av_info);
    s_fps = s_av_info.timing.fps;
    s_sample_rate = s_av_info.timing.sample_rate;
    LOGI("AV: %ux%u @ %.2f fps, audio %.0f Hz",
         s_av_info.geometry.base_width, s_av_info.geometry.base_height,
         s_fps, s_sample_rate);

    /* Tell the core a standard joypad is connected on each port */
    if (s_core.set_controller_port) {
        for (unsigned port = 0; port < MAX_PORTS; port++) {
            s_core.set_controller_port(port, RETRO_DEVICE_JOYPAD);
        }
        LOGI("Set controller port device: JOYPAD on ports 0-%d", MAX_PORTS - 1);
    }

    if (s_hw_render) {
        atomic_store(&s_hw_ctx_reset_pending, 1);
        LOGI("HW render active — context_reset deferred to emulation thread");
    }

    return JNI_TRUE;
}

/* ── runFrame ──────────────────────────────────────────────────── */
JNIEXPORT void JNICALL
JNI_PREFIX(runFrame)(JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    if (!s_core.run) return;

    /* Frame skip */
    int skip = atomic_load(&s_frame_skip);
    if (skip > 0) {
        s_frame_skip_counter++;
        if (s_frame_skip_counter <= skip) {
            s_audio_write_pos = 0;
            s_core.run();
            return;
        }
        s_frame_skip_counter = 0;
    }

    /* Reset audio */
    pthread_mutex_lock(&s_audio_mutex);
    s_audio_write_pos = 0;
    pthread_mutex_unlock(&s_audio_mutex);

    /* Make EGL current */
    if (egl_valid())
        egl_make_current();

    /* Handle deferred context_reset */
    _Bool ctx_expected = 1;
    if (atomic_compare_exchange_strong(&s_hw_ctx_reset_pending, &ctx_expected, 0)) {
        if (s_hw_render && egl_valid()) {
            if (!s_hw_fbo)
                create_hw_fbo(s_av_info.geometry.max_width, s_av_info.geometry.max_height);

            /* Flush pending GL errors */
            while (glGetError() != GL_NO_ERROR) {}

            if (s_hw_cb.context_reset) {
                LOGI("context_reset (C frontend): fbo=%u %ux%u GL=%s renderer=%s",
                     s_hw_fbo, s_hw_fbo_w, s_hw_fbo_h,
                     glGetString(GL_VERSION), glGetString(GL_RENDERER));
                s_hw_cb.context_reset();

                /* Check GL errors after init */
                GLenum err;
                int errs = 0;
                while ((err = glGetError()) != GL_NO_ERROR) {
                    LOGE("GL error after context_reset: 0x%x", err);
                    if (++errs > 20) break;
                }
                LOGI("context_reset done (%d GL errors)", errs);
            }
        }
    }

    /* Reset frame presented flag */
    atomic_store(&s_hw_frame_presented, 0);

    /* Bind FBO and set viewport before retro_run — RetroArch does this.
     * GlideN64 expects the target FBO to be bound when retro_run starts. */
    if (s_hw_render && s_hw_fbo) {
        glBindFramebuffer(GL_FRAMEBUFFER, s_hw_fbo);
        glViewport(0, 0, (GLsizei)s_hw_fbo_w, (GLsizei)s_hw_fbo_h);
    }

    static int run_log = 0;
    if (run_log < 5)
        LOGI("runFrame (C): hw=%d fbo=%u", s_hw_render, s_hw_fbo);

    /* Early input polling (Gemini fix) */
    if (atomic_load(&s_input_poll_mode) == 1)
        core_input_poll();

    s_core.run();

    if (run_log < 5) {
        LOGI("runFrame done: hw_presented=%d", (int)atomic_load(&s_hw_frame_presented));
        run_log++;
    }

    /* Present frame */
    if (egl_valid() && s_egl_surface != EGL_NO_SURFACE) {
        if (s_hw_render) {
            if (!atomic_load(&s_hw_frame_presented)) {
                blit_hw_frame();
                egl_swap();
            }
        } else {
            blit_sw_frame();
            egl_swap();
        }
    }
}

/* ── getFrameBuffer ────────────────────────────────────────────── */
JNIEXPORT jintArray JNICALL
JNI_PREFIX(getFrameBuffer)(JNIEnv* env, jobject thiz)
{
    (void)thiz;
    pthread_mutex_lock(&s_video_mutex);
    if (!s_frame_buf || s_frame_w == 0 || s_frame_h == 0) {
        pthread_mutex_unlock(&s_video_mutex);
        return NULL;
    }
    size_t count = (size_t)s_frame_w * s_frame_h;
    jintArray arr = (*env)->NewIntArray(env, (jsize)count);
    if (arr)
        (*env)->SetIntArrayRegion(env, arr, 0, (jsize)count, (const jint*)s_frame_buf);
    pthread_mutex_unlock(&s_video_mutex);
    return arr;
}

/* ── getFrameWidth / getFrameHeight ────────────────────────────── */
JNIEXPORT jint JNICALL
JNI_PREFIX(getFrameWidth)(JNIEnv* env, jobject thiz)
{ (void)env; (void)thiz; return (jint)s_frame_w; }

JNIEXPORT jint JNICALL
JNI_PREFIX(getFrameHeight)(JNIEnv* env, jobject thiz)
{ (void)env; (void)thiz; return (jint)s_frame_h; }

/* ── getAudioBuffer ────────────────────────────────────────────── */
JNIEXPORT jshortArray JNICALL
JNI_PREFIX(getAudioBuffer)(JNIEnv* env, jobject thiz)
{
    (void)thiz;
    pthread_mutex_lock(&s_audio_mutex);
    if (s_audio_write_pos == 0) {
        pthread_mutex_unlock(&s_audio_mutex);
        return NULL;
    }
    jshortArray arr = (*env)->NewShortArray(env, (jsize)s_audio_write_pos);
    if (arr)
        (*env)->SetShortArrayRegion(env, arr, 0, (jsize)s_audio_write_pos, s_audio_buf);
    pthread_mutex_unlock(&s_audio_mutex);
    return arr;
}

/* ── getFps / getSampleRate ────────────────────────────────────── */
JNIEXPORT jdouble JNICALL
JNI_PREFIX(getFps)(JNIEnv* env, jobject thiz)
{ (void)env; (void)thiz; return s_fps; }

JNIEXPORT jdouble JNICALL
JNI_PREFIX(getSampleRate)(JNIEnv* env, jobject thiz)
{ (void)env; (void)thiz; return s_sample_rate; }

/* ── Input ─────────────────────────────────────────────────────── */
JNIEXPORT void JNICALL
JNI_PREFIX(setInputState)(JNIEnv* env, jobject thiz, jint port, jint buttonId, jint value)
{
    (void)env; (void)thiz;
    pthread_mutex_lock(&s_input_mutex);
    if (port >= 0 && port < MAX_PORTS && buttonId >= 0 && buttonId < MAX_BUTTONS)
        s_joypad[port][buttonId] = (int16_t)value;
    pthread_mutex_unlock(&s_input_mutex);
}

JNIEXPORT void JNICALL
JNI_PREFIX(setAnalogState)(JNIEnv* env, jobject thiz,
                           jint port, jint index, jint axisId, jint value)
{
    (void)env; (void)thiz;
    pthread_mutex_lock(&s_input_mutex);
    if (port >= 0 && port < MAX_PORTS && index >= 0 && index < 2 && axisId >= 0 && axisId < 2)
        s_analog[port][index][axisId] = (int16_t)value;
    pthread_mutex_unlock(&s_input_mutex);
}

JNIEXPORT void JNICALL
JNI_PREFIX(setPointerState)(JNIEnv* env, jobject thiz, jint x, jint y, jboolean pressed)
{
    (void)env; (void)thiz;
    pthread_mutex_lock(&s_input_mutex);
    s_pointer_x = (int16_t)x;
    s_pointer_y = (int16_t)y;
    s_pointer_pressed = (pressed == JNI_TRUE);
    pthread_mutex_unlock(&s_input_mutex);
}

/* ── resetGame / unloadGame ────────────────────────────────────── */
JNIEXPORT void JNICALL
JNI_PREFIX(resetGame)(JNIEnv* env, jobject thiz)
{ (void)env; (void)thiz; if (s_core.reset) s_core.reset(); }

JNIEXPORT void JNICALL
JNI_PREFIX(unloadGame)(JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    LOGI("Unloading game and core (C frontend)");
    if (s_core.unload_game) s_core.unload_game();
    if (s_core.deinit) s_core.deinit();
    if (s_core.handle) { dlclose(s_core.handle); s_core.handle = NULL; }
    cleanup_all();
}

/* ── Save state (file) ─────────────────────────────────────────── */
JNIEXPORT jint JNICALL
JNI_PREFIX(saveState)(JNIEnv* env, jobject thiz, jstring jPath)
{
    (void)thiz;
    if (!s_core.serialize_size || !s_core.serialize) return -1;
    size_t sz = s_core.serialize_size();
    if (sz == 0) return -2;

    uint8_t* buf = (uint8_t*)malloc(sz);
    if (!buf) return -6;
    if (!s_core.serialize(buf, sz)) { free(buf); return -3; }

    const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
    FILE* f = fopen(path, "wb");
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    if (!f) { free(buf); return -4; }

    size_t written = fwrite(buf, 1, sz, f);
    fclose(f);
    free(buf);
    return (written == sz) ? 0 : -5;
}

/* ── Load state (file) ─────────────────────────────────────────── */
JNIEXPORT jint JNICALL
JNI_PREFIX(loadState)(JNIEnv* env, jobject thiz, jstring jPath)
{
    (void)thiz;
    if (!s_core.unserialize) return -1;

    const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
    FILE* f = fopen(path, "rb");
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    if (!f) return -2;

    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (sz <= 0) { fclose(f); return -3; }

    uint8_t* buf = (uint8_t*)malloc((size_t)sz);
    if (!buf) { fclose(f); return -6; }
    size_t rd = fread(buf, 1, (size_t)sz, f);
    fclose(f);
    if (rd != (size_t)sz) { free(buf); return -4; }

    int ok = s_core.unserialize(buf, (size_t)sz) ? 0 : -5;
    free(buf);
    return ok;
}

/* ── Save/load state (memory) ──────────────────────────────────── */
JNIEXPORT jbyteArray JNICALL
JNI_PREFIX(saveStateToMemory)(JNIEnv* env, jobject thiz)
{
    (void)thiz;
    if (!s_core.serialize_size || !s_core.serialize) return NULL;
    size_t sz = s_core.serialize_size();
    if (sz == 0) return NULL;

    uint8_t* buf = (uint8_t*)malloc(sz);
    if (!buf) return NULL;
    if (!s_core.serialize(buf, sz)) { free(buf); return NULL; }

    jbyteArray arr = (*env)->NewByteArray(env, (jsize)sz);
    if (arr)
        (*env)->SetByteArrayRegion(env, arr, 0, (jsize)sz, (const jbyte*)buf);
    free(buf);
    return arr;
}

JNIEXPORT jboolean JNICALL
JNI_PREFIX(loadStateFromMemory)(JNIEnv* env, jobject thiz, jbyteArray jData)
{
    (void)thiz;
    if (!s_core.unserialize || !jData) return JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, jData);
    if (len <= 0) return JNI_FALSE;

    jbyte* data = (*env)->GetByteArrayElements(env, jData, NULL);
    if (!data) return JNI_FALSE;

    bool ok = s_core.unserialize(data, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, jData, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
JNI_PREFIX(getSerializeSize)(JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    if (!s_core.serialize_size) return 0;
    return (jlong)s_core.serialize_size();
}

/* ── isHardwareRendered ────────────────────────────────────────── */
JNIEXPORT jboolean JNICALL
JNI_PREFIX(isHardwareRendered)(JNIEnv* env, jobject thiz)
{ (void)env; (void)thiz; return s_hw_render ? JNI_TRUE : JNI_FALSE; }

/* ── setCoreOption ─────────────────────────────────────────────── */
JNIEXPORT void JNICALL
JNI_PREFIX(setCoreOption)(JNIEnv* env, jobject thiz, jstring jKey, jstring jValue)
{
    (void)thiz;
    const char* key = (*env)->GetStringUTFChars(env, jKey, NULL);
    const char* val = (*env)->GetStringUTFChars(env, jValue, NULL);
    set_option(key, val);
    atomic_store(&s_options_updated, 1);

    /* Handle frontend-level options */
    if (strcmp(key, "input_poll_type_behavior") == 0) {
        if (strcmp(val, "early") == 0) atomic_store(&s_input_poll_mode, 1);
        else if (strcmp(val, "late") == 0) atomic_store(&s_input_poll_mode, 2);
        else atomic_store(&s_input_poll_mode, 0);
        LOGI("Input poll mode set to: %s", val);
    }

    (*env)->ReleaseStringUTFChars(env, jKey, key);
    (*env)->ReleaseStringUTFChars(env, jValue, val);
}

/* ── SRAM ──────────────────────────────────────────────────────── */
JNIEXPORT jboolean JNICALL
JNI_PREFIX(saveSRAM)(JNIEnv* env, jobject thiz, jstring jPath)
{
    (void)thiz;
    if (!s_core.get_memory_data || !s_core.get_memory_size) return JNI_FALSE;
    void* data = s_core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = s_core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return JNI_FALSE;

    const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
    FILE* f = fopen(path, "wb");
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    if (!f) return JNI_FALSE;

    size_t written = fwrite(data, 1, size, f);
    fclose(f);
    return (written == size) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
JNI_PREFIX(loadSRAM)(JNIEnv* env, jobject thiz, jstring jPath)
{
    (void)thiz;
    if (!s_core.get_memory_data || !s_core.get_memory_size) return JNI_FALSE;
    void* data = s_core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = s_core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return JNI_FALSE;

    const char* path = (*env)->GetStringUTFChars(env, jPath, NULL);
    FILE* f = fopen(path, "rb");
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    if (!f) return JNI_FALSE;

    size_t rd = fread(data, 1, size, f);
    fclose(f);
    return (rd == size) ? JNI_TRUE : JNI_FALSE;
}

/* ── setFrameSkip ──────────────────────────────────────────────── */
JNIEXPORT void JNICALL
JNI_PREFIX(setFrameSkip)(JNIEnv* env, jobject thiz, jint skip)
{
    (void)env; (void)thiz;
    atomic_store(&s_frame_skip, skip);
    s_frame_skip_counter = 0;
}

/* ── setSurface ────────────────────────────────────────────────── */
JNIEXPORT void JNICALL
JNI_PREFIX(setSurface)(JNIEnv* env, jobject thiz, jobject jSurface)
{
    (void)thiz;
    /* Release previous */
    if (s_window) {
        destroy_blit();
        egl_destroy();
        s_hw_fbo = 0; s_hw_tex_color = 0; s_hw_rb_depth = 0;
        s_hw_fbo_w = 0; s_hw_fbo_h = 0; s_hw_blit_prog = 0;
        ANativeWindow_release(s_window);
        s_window = NULL;
    }
    if (!jSurface) { LOGI("Surface cleared"); return; }

    s_window = ANativeWindow_fromSurface(env, jSurface);
    if (!s_window) { LOGE("ANativeWindow_fromSurface failed"); return; }

    s_surface_w = ANativeWindow_getWidth(s_window);
    s_surface_h = ANativeWindow_getHeight(s_window);
    LOGI("Surface set: %dx%d", s_surface_w, s_surface_h);

    if (!init_egl(s_window)) {
        LOGE("EGL init failed");
        ANativeWindow_release(s_window);
        s_window = NULL;
        return;
    }

    if (s_hw_render) {
        create_hw_fbo(s_av_info.geometry.max_width, s_av_info.geometry.max_height);
        atomic_store(&s_hw_ctx_reset_pending, 1);
        LOGI("context_reset deferred (fbo=%u %ux%u)", s_hw_fbo, s_hw_fbo_w, s_hw_fbo_h);
    }

    /* Release EGL from UI thread */
    eglMakeCurrent(s_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    LOGI("EGL released from UI thread");
}

/* ── surfaceChanged ────────────────────────────────────────────── */
JNIEXPORT void JNICALL
JNI_PREFIX(surfaceChanged)(JNIEnv* env, jobject thiz, jint width, jint height)
{
    (void)env; (void)thiz;
    s_surface_w = width;
    s_surface_h = height;
    LOGI("Surface changed: %dx%d", width, height);
}

/* ── setRenderBackend ──────────────────────────────────────────── */
JNIEXPORT void JNICALL
JNI_PREFIX(setRenderBackend)(JNIEnv* env, jobject thiz, jint backend)
{
    (void)env; (void)thiz;
    /* C frontend uses OpenGL ES only — log the request but stay on GL */
    if (backend != 0) {
        LOGW("C frontend: Vulkan display backend requested (%d) but not supported, using OpenGL ES", backend);
    }
}
