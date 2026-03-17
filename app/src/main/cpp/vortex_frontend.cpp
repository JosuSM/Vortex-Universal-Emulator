/*
 * vortex_frontend.cpp — C++ libretro frontend for VortexEmulator (Android).
 *
 * Improvements over the original C version:
 *   - RAII wrappers for EGL, GL, and dlopen resources (no leak paths)
 *   - std::mutex for thread-safe shared state (frame buffer, audio buffer)
 *   - PBO double-buffering for software texture uploads (no GPU stalls)
 *   - glFenceSync after HW-rendered frames (eliminates GPU race conditions)
 *   - Proper JNI string release on all error paths
 *   - <cstdarg> included for va_list in core_log
 *   - C++ namespacing and constexpr instead of #define for constants
 */

#include <jni.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstring>
#include <cstdarg>
#include <cstdio>
#include <cmath>
#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <algorithm>
#include <exception>
#include <csignal>

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES3/gl31.h>

#include "libretro.h"
#include "vortex_vulkan.h"

/* ── Logging ───────────────────────────────────────────────────── */
#define LOGT "VortexFrontend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOGT, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOGT, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOGT, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOGT, __VA_ARGS__)

/* ── Constants ─────────────────────────────────────────────────── */
static constexpr int MAX_PORTS        = 4;
static constexpr int MAX_BUTTONS      = 16;
static constexpr int AUDIO_BUF_FRAMES = 16384;  // ~0.37s at 44100Hz

/* ── Cached JavaVM (set in JNI_OnLoad) ─────────────────────────── */
static JavaVM* g_jvm = nullptr;

/* ── Thread attachment for cores (e.g. PPSSPP) ─────────────────── */
static void* jni_attach_current_thread() {
    JNIEnv* env = nullptr;
    if (g_jvm && g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK)
        return env;
    LOGE("AttachCurrentThread failed");
    return nullptr;
}

static void jni_detach_current_thread() {
    if (g_jvm) g_jvm->DetachCurrentThread();
}

/* ── RAII helper: dlopen handle ────────────────────────────────── */
struct DlHandle {
    void* handle = nullptr;
    DlHandle() = default;
    explicit DlHandle(void* h) : handle(h) {}
    ~DlHandle() { if (handle) dlclose(handle); }
    DlHandle(DlHandle&& o) noexcept : handle(o.handle) { o.handle = nullptr; }
    DlHandle& operator=(DlHandle&& o) noexcept {
        if (this != &o) { if (handle) dlclose(handle); handle = o.handle; o.handle = nullptr; }
        return *this;
    }
    DlHandle(const DlHandle&) = delete;
    DlHandle& operator=(const DlHandle&) = delete;
    explicit operator bool() const { return handle != nullptr; }
};

/* ── Core function pointers ────────────────────────────────────── */
struct CoreSymbols {
    retro_init_t                     init                    = nullptr;
    retro_deinit_t                   deinit                  = nullptr;
    retro_api_version_t              api_version             = nullptr;
    retro_get_system_info_t          get_system_info         = nullptr;
    retro_get_system_av_info_t       get_system_av_info      = nullptr;
    retro_set_environment_t          set_environment         = nullptr;
    retro_set_video_refresh_t        set_video_refresh       = nullptr;
    retro_set_audio_sample_t         set_audio_sample        = nullptr;
    retro_set_audio_sample_batch_t   set_audio_sample_batch  = nullptr;
    retro_set_input_poll_t           set_input_poll          = nullptr;
    retro_set_input_state_t          set_input_state         = nullptr;
    retro_load_game_t                load_game               = nullptr;
    retro_unload_game_t              unload_game             = nullptr;
    retro_run_t                      run                     = nullptr;
    retro_reset_t                    reset                   = nullptr;
    retro_serialize_size_t           serialize_size           = nullptr;
    retro_serialize_t                serialize               = nullptr;
    retro_unserialize_t              unserialize             = nullptr;
    retro_set_controller_port_device_t set_controller_port   = nullptr;
    retro_get_memory_data_t          get_memory_data         = nullptr;
    retro_get_memory_size_t          get_memory_size         = nullptr;
};

/* ── EGL state (RAII‐protected) ────────────────────────────────── */
struct EglState {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context  = EGL_NO_CONTEXT;
    EGLSurface surface  = EGL_NO_SURFACE;
    EGLConfig  config   = nullptr;

    bool valid() const { return display != EGL_NO_DISPLAY && context != EGL_NO_CONTEXT; }

    void makeCurrent() {
        if (valid()) {
            EGLSurface s = (surface != EGL_NO_SURFACE) ? surface : EGL_NO_SURFACE;
            eglMakeCurrent(display, s, s, context);
        }
    }

    void swapBuffers() {
        if (display != EGL_NO_DISPLAY && surface != EGL_NO_SURFACE)
            eglSwapBuffers(display, surface);
    }

    void destroySurface() {
        if (display != EGL_NO_DISPLAY && surface != EGL_NO_SURFACE) {
            eglDestroySurface(display, surface);
            surface = EGL_NO_SURFACE;
        }
    }

    void destroy() {
        if (display != EGL_NO_DISPLAY) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            destroySurface();
            if (context != EGL_NO_CONTEXT) {
                eglDestroyContext(display, context);
                context = EGL_NO_CONTEXT;
            }
            eglTerminate(display);
            display = EGL_NO_DISPLAY;
        }
    }

    ~EglState() { destroy(); }
    EglState() = default;
    EglState(EglState&& o) noexcept
        : display(o.display), context(o.context), surface(o.surface), config(o.config)
    { o.display = EGL_NO_DISPLAY; o.context = EGL_NO_CONTEXT; o.surface = EGL_NO_SURFACE; }
    EglState& operator=(EglState&& o) noexcept {
        if (this != &o) { destroy(); display = o.display; context = o.context; surface = o.surface; config = o.config;
            o.display = EGL_NO_DISPLAY; o.context = EGL_NO_CONTEXT; o.surface = EGL_NO_SURFACE; }
        return *this;
    }
    EglState(const EglState&) = delete;
    EglState& operator=(const EglState&) = delete;
};

/* ── GL blit state ─────────────────────────────────────────────── */
struct BlitState {
    GLuint program  = 0;
    GLuint vao      = 0;
    GLuint vbo      = 0;
    GLuint texture  = 0;
    GLuint pbo[2]   = {0, 0};
    int    pboIndex = 0;
    size_t pboSize  = 0;

    void destroy() {
        if (program)  { glDeleteProgram(program); program = 0; }
        if (vao)      { glDeleteVertexArrays(1, &vao); vao = 0; }
        if (vbo)      { glDeleteBuffers(1, &vbo); vbo = 0; }
        if (texture)  { glDeleteTextures(1, &texture); texture = 0; }
        if (pbo[0])   { glDeleteBuffers(2, pbo); pbo[0] = pbo[1] = 0; }
        pboSize = 0;
    }
};

/* ── Global frontend state ─────────────────────────────────────── */
static DlHandle              g_core_handle;
static CoreSymbols           g_core;
static EglState              g_egl;
static BlitState             g_blit;
static ANativeWindow*        g_window         = nullptr;

// Directories
static std::string           g_system_dir;
static std::string           g_save_dir;

// Video state
static std::mutex            g_video_mutex;
static std::vector<uint32_t> g_frame_buf;
static unsigned              g_frame_w        = 0;
static unsigned              g_frame_h        = 0;
static std::atomic<bool>     g_frame_ready{false};
static unsigned              g_pixel_fmt      = RETRO_PIXEL_FORMAT_0RGB1555;
static int                   g_surface_w      = 0;
static int                   g_surface_h      = 0;

// Hardware rendering
static bool                  g_hw_render      = false;
static retro_hw_render_callback g_hw_cb       = {};
static GLuint                g_hw_fbo         = 0;
static GLuint                g_hw_tex_color   = 0;  // texture-backed color (RetroArch approach)
static GLuint                g_hw_rb_depth    = 0;
static unsigned              g_hw_fbo_w       = 0;  // actual FBO dimensions
static unsigned              g_hw_fbo_h       = 0;
static GLuint                g_hw_blit_program = 0; // shader for HW frame blit
static std::atomic<bool>     g_hw_context_reset_pending{false};
static std::atomic<bool>     g_hw_frame_presented{false}; // set in video_refresh when swap done
static std::atomic<bool>     g_hw_context_failed{false};  // set if context_reset threw

// Audio state
static std::mutex            g_audio_mutex;
static std::vector<int16_t>  g_audio_buf(AUDIO_BUF_FRAMES * 2);
static size_t                g_audio_write_pos = 0;
static double                g_sample_rate    = 44100.0;

// Input state
static std::mutex            g_input_mutex;
static int16_t               g_joypad[MAX_PORTS][MAX_BUTTONS] = {};
static int16_t               g_analog[MAX_PORTS][2][2] = {};  // [port][index][axis]
static int16_t               g_pointer_x      = 0;
static int16_t               g_pointer_y      = 0;
static bool                  g_pointer_pressed = false;

// AV info
static retro_system_av_info  g_av_info        = {};
static double                g_fps            = 60.0;

// Core options
static std::mutex            g_options_mutex;
static std::unordered_map<std::string, std::string> g_core_options;
static std::atomic<bool>     g_options_updated{false};

// Frame skip
static std::atomic<int>      g_frame_skip{0};
static int                   g_frame_skip_counter = 0;
static int                   g_debug_frame_counter = 0;  // for diagnostic logging
static std::atomic<int>      g_input_poll_mode{0}; // 0=normal, 1=early, 2=late

/* ── Display backend: GL (default) or Vulkan ───────────────────── */
enum DisplayBackend { DISPLAY_GL = 0, DISPLAY_VULKAN = 1 };
static std::atomic<int>      g_display_backend{DISPLAY_GL};
static VulkanRenderer        g_vulkan;
static bool                  g_egl_offscreen = false; // true when EGL is PBuffer (Vulkan display + HW render)
static std::vector<uint32_t> g_hw_readback_buf;       // HW frame readback for Vulkan display

/* ── Shader sources ────────────────────────────────────────────── */
static const char* VERT_SRC = R"(#version 300 es
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUV;
out vec2 vUV;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUV = aUV;
})";

static const char* FRAG_SRC = R"(#version 300 es
precision mediump float;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uTex;
void main() {
    // Pixel data is stored as 0xFFRRGGBB (uint32 LE = bytes [BB,GG,RR,FF]).
    // GL reads bytes as RGBA → r=BB, g=GG, b=RR, a=FF. Swizzle to fix.
    fragColor = texture(uTex, vUV).bgra;
})";

// HW blit shader: reads FBO texture directly (already RGBA), with Y-flip control
static const char* FRAG_HW_SRC = R"(#version 300 es
precision mediump float;
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uTex;
uniform float uFlipY;
void main() {
    vec2 uv = vUV;
    if (uFlipY > 0.5) uv.y = 1.0 - uv.y;
    fragColor = texture(uTex, uv);
})";

/* ── GL helpers ────────────────────────────────────────────────── */
static GLuint compileShader(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        LOGE("Shader compile error: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static GLuint createBlitProgram() {
    GLuint vs = compileShader(GL_VERTEX_SHADER, VERT_SRC);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, FRAG_SRC);
    if (!vs || !fs) { glDeleteShader(vs); glDeleteShader(fs); return 0; }
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
        glGetProgramInfoLog(prog, sizeof(log), nullptr, log);
        LOGE("Program link error: %s", log);
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

static void initBlit() {
    g_blit.destroy();
    g_blit.program = createBlitProgram();
    if (!g_blit.program) { LOGE("Failed to create blit program"); return; }

    // Full-screen quad: position (xy) + texcoord (uv)
    static const float quad[] = {
        -1.f, -1.f,  0.f, 1.f,
         1.f, -1.f,  1.f, 1.f,
        -1.f,  1.f,  0.f, 0.f,
         1.f,  1.f,  1.f, 0.f,
    };
    glGenVertexArrays(1, &g_blit.vao);
    glGenBuffers(1, &g_blit.vbo);
    glBindVertexArray(g_blit.vao);
    glBindBuffer(GL_ARRAY_BUFFER, g_blit.vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), nullptr);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));
    glEnableVertexAttribArray(1);
    glBindVertexArray(0);

    glGenTextures(1, &g_blit.texture);
    glBindTexture(GL_TEXTURE_2D, g_blit.texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // Create PBOs for double-buffered texture uploads
    glGenBuffers(2, g_blit.pbo);
    g_blit.pboIndex = 0;
    g_blit.pboSize = 0;

    // HW blit program: reads FBO texture directly (no bgra swizzle), Y-flip control
    if (g_hw_blit_program) { glDeleteProgram(g_hw_blit_program); g_hw_blit_program = 0; }
    {
        GLuint hw_vs = compileShader(GL_VERTEX_SHADER, VERT_SRC);
        GLuint hw_fs = compileShader(GL_FRAGMENT_SHADER, FRAG_HW_SRC);
        if (hw_vs && hw_fs) {
            g_hw_blit_program = glCreateProgram();
            glAttachShader(g_hw_blit_program, hw_vs);
            glAttachShader(g_hw_blit_program, hw_fs);
            glLinkProgram(g_hw_blit_program);
            GLint ok = 0;
            glGetProgramiv(g_hw_blit_program, GL_LINK_STATUS, &ok);
            if (!ok) {
                char log[512];
                glGetProgramInfoLog(g_hw_blit_program, sizeof(log), nullptr, log);
                LOGE("HW blit program link error: %s", log);
                glDeleteProgram(g_hw_blit_program);
                g_hw_blit_program = 0;
            }
        }
        if (hw_vs) glDeleteShader(hw_vs);
        if (hw_fs) glDeleteShader(hw_fs);
    }
}

static void ensurePBOSize(size_t bytes) {
    if (g_blit.pboSize >= bytes) return;
    for (int i = 0; i < 2; i++) {
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, g_blit.pbo[i]);
        glBufferData(GL_PIXEL_UNPACK_BUFFER, static_cast<GLsizeiptr>(bytes), nullptr, GL_STREAM_DRAW);
    }
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    g_blit.pboSize = bytes;
}

/* ── EGL initialization ────────────────────────────────────────── */
static bool initEGL(ANativeWindow* window) {
    g_egl.destroy();

    g_egl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_egl.display == EGL_NO_DISPLAY) { LOGE("eglGetDisplay failed"); return false; }
    if (!eglInitialize(g_egl.display, nullptr, nullptr)) { LOGE("eglInitialize failed"); return false; }

    const EGLint configAttribs[] = {
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
    eglChooseConfig(g_egl.display, configAttribs, &g_egl.config, 1, &numConfigs);
    if (numConfigs == 0) { LOGE("No EGL config found"); g_egl.destroy(); return false; }

    const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    g_egl.context = eglCreateContext(g_egl.display, g_egl.config, EGL_NO_CONTEXT, ctxAttribs);
    if (g_egl.context == EGL_NO_CONTEXT) { LOGE("eglCreateContext failed"); g_egl.destroy(); return false; }

    g_egl.surface = eglCreateWindowSurface(g_egl.display, g_egl.config, window, nullptr);
    if (g_egl.surface == EGL_NO_SURFACE) { LOGE("eglCreateWindowSurface failed"); g_egl.destroy(); return false; }

    g_egl.makeCurrent();
    initBlit();
    LOGI("EGL initialized: GLES %s", glGetString(GL_VERSION));
    return true;
}

/* ── Offscreen EGL (PBuffer) for Vulkan display + HW render ───── */
static bool initEGLOffscreen() {
    g_egl.destroy();
    g_egl_offscreen = false;

    g_egl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_egl.display == EGL_NO_DISPLAY) { LOGE("eglGetDisplay failed (offscreen)"); return false; }
    if (!eglInitialize(g_egl.display, nullptr, nullptr)) { LOGE("eglInitialize failed (offscreen)"); return false; }

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE,    EGL_PBUFFER_BIT,
        EGL_RED_SIZE,   8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE,  8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    EGLint numConfigs = 0;
    eglChooseConfig(g_egl.display, configAttribs, &g_egl.config, 1, &numConfigs);
    if (numConfigs == 0) { LOGE("No PBuffer EGL config found"); g_egl.destroy(); return false; }

    const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    g_egl.context = eglCreateContext(g_egl.display, g_egl.config, EGL_NO_CONTEXT, ctxAttribs);
    if (g_egl.context == EGL_NO_CONTEXT) { LOGE("eglCreateContext failed (offscreen)"); g_egl.destroy(); return false; }

    // 1×1 PBuffer — we render to our FBO, not the default framebuffer
    const EGLint pbufAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    g_egl.surface = eglCreatePbufferSurface(g_egl.display, g_egl.config, pbufAttribs);
    if (g_egl.surface == EGL_NO_SURFACE) { LOGE("eglCreatePbufferSurface failed"); g_egl.destroy(); return false; }

    g_egl.makeCurrent();
    initBlit();
    g_egl_offscreen = true;
    LOGI("Offscreen EGL initialized: GLES %s (PBuffer for Vulkan display + HW render)", glGetString(GL_VERSION));
    return true;
}

/* ── Hardware rendering FBO ────────────────────────────────────── */
static void createHWFBO(unsigned w, unsigned h) {
    // Abstraction layer: always provide a real FBO to the core.
    // If the core reports 0x0 (e.g. PPSSPP), fall back to surface dimensions.
    if (w == 0 || h == 0) {
        if (g_surface_w > 0 && g_surface_h > 0) {
            w = static_cast<unsigned>(g_surface_w);
            h = static_cast<unsigned>(g_surface_h);
            LOGI("createHWFBO: core reported 0x0, using surface dims %ux%u", w, h);
        } else {
            LOGW("createHWFBO: no valid dimensions and no surface, skipping");
            return;
        }
    }

    if (g_hw_fbo) { glDeleteFramebuffers(1, &g_hw_fbo); g_hw_fbo = 0; }
    if (g_hw_tex_color) { glDeleteTextures(1, &g_hw_tex_color); g_hw_tex_color = 0; }
    if (g_hw_rb_depth) { glDeleteRenderbuffers(1, &g_hw_rb_depth); g_hw_rb_depth = 0; }

    glGenFramebuffers(1, &g_hw_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, g_hw_fbo);

    // RetroArch approach: texture-backed color attachment for shader-based blit
    glGenTextures(1, &g_hw_tex_color);
    glBindTexture(GL_TEXTURE_2D, g_hw_tex_color);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, static_cast<GLsizei>(w), static_cast<GLsizei>(h),
                 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_hw_tex_color, 0);

    if (g_hw_cb.depth || g_hw_cb.stencil) {
        glGenRenderbuffers(1, &g_hw_rb_depth);
        glBindRenderbuffer(GL_RENDERBUFFER, g_hw_rb_depth);
        GLenum depthFmt = GL_DEPTH24_STENCIL8;
        if (g_hw_cb.depth && !g_hw_cb.stencil) depthFmt = GL_DEPTH_COMPONENT24;
        glRenderbufferStorage(GL_RENDERBUFFER, depthFmt, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        if (g_hw_cb.stencil)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, g_hw_rb_depth);
        else
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, g_hw_rb_depth);
    }

    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        LOGE("HW FBO incomplete: 0x%x", glCheckFramebufferStatus(GL_FRAMEBUFFER));

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    g_hw_fbo_w = w;
    g_hw_fbo_h = h;
    LOGI("Created HW FBO %u (%ux%u)", g_hw_fbo, w, h);
}

/* ── HW render callbacks ───────────────────────────────────────── */
static uintptr_t hw_get_current_framebuffer() {
    // Return our texture-backed FBO so the core renders into it.
    // We blit this texture to the screen in video_refresh with proper scaling.
    static int gcfb_log = 0;
    if (gcfb_log < 10) {
        LOGI("hw_get_current_framebuffer() -> %u", g_hw_fbo);
        gcfb_log++;
    }
    return static_cast<uintptr_t>(g_hw_fbo);
}

static retro_proc_address_t hw_get_proc_address(const char* sym) {
    return reinterpret_cast<retro_proc_address_t>(eglGetProcAddress(sym));
}

/* ── Performance interface ─────────────────────────────────────── */
#include <time.h>

static retro_time_t perf_get_time_usec() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<retro_time_t>(ts.tv_sec) * 1000000 + ts.tv_nsec / 1000;
}

static retro_perf_tick_t perf_get_perf_counter() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<retro_perf_tick_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

static uint64_t perf_get_cpu_features() {
    return 0; // No SIMD feature detection for now
}

static void perf_log() { /* no-op */ }

static void perf_register(struct retro_perf_counter* counter) {
    if (counter) counter->registered = true;
}

static void perf_start(struct retro_perf_counter* counter) {
    if (counter) {
        counter->start = perf_get_perf_counter();
        counter->call_cnt++;
    }
}

static void perf_stop(struct retro_perf_counter* counter) {
    if (counter) {
        counter->total += perf_get_perf_counter() - counter->start;
    }
}

/* ── Libretro callbacks ────────────────────────────────────────── */
static void blitHardwareFrame(); // forward declaration
static void core_video_refresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    // Diagnostic: log every call for the first 20 frames
    static int vr_call_count = 0;
    if (vr_call_count < 20) {
        LOGI("video_refresh[%d]: data=%p w=%u h=%u pitch=%zu hw=%d",
             vr_call_count, data, width, height, pitch, (int)g_hw_render);
        vr_call_count++;
    }

    if (g_hw_render) {
        // HW-rendered core: the core rendered into our texture-backed FBO.
        if (width > 0 && height > 0) {
            std::lock_guard<std::mutex> lock(g_video_mutex);
            g_frame_w = width;
            g_frame_h = height;
        }

        if (g_display_backend.load(std::memory_order_relaxed) == DISPLAY_VULKAN) {
            // Vulkan display: readback from FBO → present via Vulkan
            if (g_vulkan.isReady()) {
                unsigned rw = g_hw_fbo_w;
                unsigned rh = g_hw_fbo_h;
                if (rw == 0 || rh == 0) { rw = g_frame_w; rh = g_frame_h; }
                if (rw > 0 && rh > 0) {
                    g_hw_readback_buf.resize(static_cast<size_t>(rw) * rh);
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, g_hw_fbo);
                    glReadPixels(0, 0, static_cast<GLsizei>(rw), static_cast<GLsizei>(rh),
                                 GL_RGBA, GL_UNSIGNED_BYTE, g_hw_readback_buf.data());
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
                    g_vulkan.presentFrame(g_hw_readback_buf.data(), rw, rh,
                                          false, g_hw_cb.bottom_left_origin);
                    g_hw_frame_presented.store(true, std::memory_order_release);
                }
            }
        } else {
            // GL display: blit FBO texture to window surface and swap
            if (g_egl.valid() && g_egl.surface != EGL_NO_SURFACE) {
                blitHardwareFrame();
                g_egl.swapBuffers();
                g_hw_frame_presented.store(true, std::memory_order_release);
            }
        }
        g_frame_ready.store(true, std::memory_order_release);
        return;
    }

    if (!data) return; // duped frame

    // Software-rendered frame: convert pixel data to XRGB8888
    std::lock_guard<std::mutex> lock(g_video_mutex);
    g_frame_w = width;
    g_frame_h = height;
    g_frame_buf.resize(width * height);

    const uint8_t* src = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < height; y++) {
        const uint8_t* row = src + y * pitch;
        uint32_t* dst = g_frame_buf.data() + y * width;

        switch (g_pixel_fmt) {
            case RETRO_PIXEL_FORMAT_XRGB8888:
                std::memcpy(dst, row, width * 4);
                break;
            case RETRO_PIXEL_FORMAT_RGB565:
                for (unsigned x = 0; x < width; x++) {
                    uint16_t px;
                    std::memcpy(&px, row + x * 2, 2);
                    uint8_t r = static_cast<uint8_t>((px >> 11) << 3);
                    uint8_t g = static_cast<uint8_t>(((px >> 5) & 0x3F) << 2);
                    uint8_t b = static_cast<uint8_t>((px & 0x1F) << 3);
                    dst[x] = 0xFF000000u | (static_cast<uint32_t>(r) << 16) |
                             (static_cast<uint32_t>(g) << 8) | b;
                }
                break;
            case RETRO_PIXEL_FORMAT_0RGB1555:
            default:
                for (unsigned x = 0; x < width; x++) {
                    uint16_t px;
                    std::memcpy(&px, row + x * 2, 2);
                    uint8_t r = static_cast<uint8_t>(((px >> 10) & 0x1F) << 3);
                    uint8_t g = static_cast<uint8_t>(((px >> 5) & 0x1F) << 3);
                    uint8_t b = static_cast<uint8_t>((px & 0x1F) << 3);
                    dst[x] = 0xFF000000u | (static_cast<uint32_t>(r) << 16) |
                             (static_cast<uint32_t>(g) << 8) | b;
                }
                break;
        }
    }
    g_frame_ready.store(true, std::memory_order_release);
}

static void core_audio_sample(int16_t left, int16_t right) {
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    size_t cap = g_audio_buf.size();
    if (g_audio_write_pos + 2 <= cap) {
        g_audio_buf[g_audio_write_pos++] = left;
        g_audio_buf[g_audio_write_pos++] = right;
    }
}

static size_t core_audio_sample_batch(const int16_t* data, size_t frames) {
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    size_t samples = frames * 2;
    size_t cap = g_audio_buf.size();
    size_t avail = (g_audio_write_pos < cap) ? cap - g_audio_write_pos : 0;
    size_t to_copy = std::min(samples, avail);
    if (to_copy > 0) {
        std::memcpy(g_audio_buf.data() + g_audio_write_pos, data, to_copy * sizeof(int16_t));
        g_audio_write_pos += to_copy;
    }
    return frames;
}

static void core_input_poll() {
    // Input is set asynchronously from Java — nothing to poll here
}

static int16_t core_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    std::lock_guard<std::mutex> lock(g_input_mutex);
    if (port >= MAX_PORTS) return 0;

    switch (device & RETRO_DEVICE_MASK) {
        case RETRO_DEVICE_JOYPAD:
            if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
                int16_t mask = 0;
                for (int i = 0; i < MAX_BUTTONS; i++)
                    if (g_joypad[port][i]) mask |= (1 << i);
                return mask;
            }
            if (id < MAX_BUTTONS) return g_joypad[port][id];
            break;
        case RETRO_DEVICE_ANALOG:
            if (index < 2 && id < 2) return g_analog[port][index][id];
            break;
        case RETRO_DEVICE_POINTER:
            switch (id) {
                case RETRO_DEVICE_ID_POINTER_X:       return g_pointer_x;
                case RETRO_DEVICE_ID_POINTER_Y:       return g_pointer_y;
                case RETRO_DEVICE_ID_POINTER_PRESSED:  return g_pointer_pressed ? 1 : 0;
            }
            break;
    }
    return 0;
}

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

/* ── Environment callback ──────────────────────────────────────── */
static bool core_environment_inner(unsigned cmd, void* data);

static bool core_environment(unsigned cmd, void* data) {
    // Skip private/internal commands silently
    if (cmd & RETRO_ENVIRONMENT_PRIVATE) return false;

    if (core_environment_inner(cmd, data))
        return true;

    // Some cores send known commands with the EXPERIMENTAL flag as a
    // compatibility probe.  Strip the flag and retry.
    if (cmd & RETRO_ENVIRONMENT_EXPERIMENTAL) {
        unsigned base = cmd & ~RETRO_ENVIRONMENT_EXPERIMENTAL;
        if (core_environment_inner(base, data))
            return true;
        LOGD("Unhandled env cmd: %u (base %u)", cmd, base);
    } else {
        LOGD("Unhandled env cmd: %u", cmd);
    }
    return false;
}

static bool core_environment_inner(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            auto* cb = static_cast<retro_log_callback*>(data);
            cb->log = core_log;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            *static_cast<const char**>(data) = g_system_dir.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            *static_cast<const char**>(data) = g_save_dir.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: {
            *static_cast<const char**>(data) = g_system_dir.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            g_pixel_fmt = *static_cast<const unsigned*>(data);
            LOGI("Pixel format set to %u", g_pixel_fmt);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            *static_cast<bool*>(data) = true;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            auto* hw = static_cast<retro_hw_render_callback*>(data);
            LOGI("HW render requested: context_type=%u, version=%u.%u, depth=%d stencil=%d bottom_left=%d cache_context=%d",
                 hw->context_type, hw->version_major, hw->version_minor,
                 hw->depth, hw->stencil, hw->bottom_left_origin, hw->cache_context);
            if (hw->context_type == RETRO_HW_CONTEXT_VULKAN) {
                LOGW("Vulkan not supported, rejecting HW render");
                return false;
            }
            g_hw_render = true;
            g_hw_cb = *hw;
            g_hw_cb.get_current_framebuffer = hw_get_current_framebuffer;
            g_hw_cb.get_proc_address = hw_get_proc_address;
            *hw = g_hw_cb;  // write back patched callbacks
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* var = static_cast<retro_variable*>(data);
            if (!var->key) return false;
            std::lock_guard<std::mutex> lock(g_options_mutex);
            auto it = g_core_options.find(var->key);
            if (it != g_core_options.end()) {
                var->value = it->second.c_str();
                static int get_var_log = 0;
                if (get_var_log < 50) {
                    LOGD("GET_VARIABLE: %s = %s", var->key, var->value);
                    get_var_log++;
                }
                return true;
            }
            static int miss_var_log = 0;
            if (miss_var_log < 30) {
                LOGD("GET_VARIABLE miss: %s", var->key);
                miss_var_log++;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            auto* vars = static_cast<const retro_variable*>(data);
            std::lock_guard<std::mutex> lock(g_options_mutex);
            while (vars && vars->key) {
                // Parse default from "Description; val1|val2|..." — first value is default
                if (g_core_options.find(vars->key) == g_core_options.end()) {
                    const char* semi = std::strchr(vars->value, ';');
                    if (semi) {
                        const char* p = semi + 1;
                        while (*p == ' ') p++;
                        const char* pipe = std::strchr(p, '|');
                        std::string def(p, pipe ? pipe : p + std::strlen(p));
                        g_core_options[vars->key] = def;
                    }
                }
                vars++;
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
            // For v1 options, parse and set defaults
            if (cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS && data) {
                auto* defs = static_cast<const retro_core_option_definition*>(data);
                std::lock_guard<std::mutex> lock(g_options_mutex);
                while (defs && defs->key) {
                    if (g_core_options.find(defs->key) == g_core_options.end()) {
                        if (defs->default_value)
                            g_core_options[defs->key] = defs->default_value;
                        else if (defs->values[0].value)
                            g_core_options[defs->key] = defs->values[0].value;
                    }
                    defs++;
                }
            } else if (cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL && data) {
                auto* intl = static_cast<const retro_core_options_intl*>(data);
                if (intl->us) {
                    auto* defs = intl->us;
                    std::lock_guard<std::mutex> lock(g_options_mutex);
                    while (defs->key) {
                        if (g_core_options.find(defs->key) == g_core_options.end()) {
                            if (defs->default_value)
                                g_core_options[defs->key] = defs->default_value;
                            else if (defs->values[0].value)
                                g_core_options[defs->key] = defs->values[0].value;
                        }
                        defs++;
                    }
                }
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            if (cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2 && data) {
                auto* v2 = static_cast<const retro_core_options_v2*>(data);
                if (v2->definitions) {
                    auto* defs = v2->definitions;
                    std::lock_guard<std::mutex> lock(g_options_mutex);
                    while (defs->key) {
                        if (g_core_options.find(defs->key) == g_core_options.end()) {
                            if (defs->default_value)
                                g_core_options[defs->key] = defs->default_value;
                            else if (defs->values[0].value)
                                g_core_options[defs->key] = defs->values[0].value;
                        }
                        defs++;
                    }
                }
            } else if (cmd == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL && data) {
                auto* intl = static_cast<const retro_core_options_v2_intl*>(data);
                if (intl->us && intl->us->definitions) {
                    auto* defs = intl->us->definitions;
                    std::lock_guard<std::mutex> lock(g_options_mutex);
                    while (defs->key) {
                        if (g_core_options.find(defs->key) == g_core_options.end()) {
                            if (defs->default_value)
                                g_core_options[defs->key] = defs->default_value;
                            else if (defs->values[0].value)
                                g_core_options[defs->key] = defs->values[0].value;
                        }
                        defs++;
                    }
                }
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: {
            *static_cast<unsigned*>(data) = 2;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            bool updated = g_options_updated.exchange(false, std::memory_order_acq_rel);
            *static_cast<bool*>(data) = updated;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY: {
            return true;  // acknowledge but ignore visibility toggling
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK: {
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME: {
            return true;
        }
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS: {
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO: {
            return true;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            auto* geom = static_cast<const retro_game_geometry*>(data);
            g_av_info.geometry.base_width = geom->base_width;
            g_av_info.geometry.base_height = geom->base_height;
            if (geom->max_width > 0 && geom->max_height > 0) {
                g_av_info.geometry.max_width = geom->max_width;
                g_av_info.geometry.max_height = geom->max_height;
            }
            if (geom->aspect_ratio > 0.0f)
                g_av_info.geometry.aspect_ratio = geom->aspect_ratio;
            // Create HW FBO if we now have valid dimensions and didn't before
            if (g_hw_render && !g_hw_fbo && g_av_info.geometry.max_width > 0 && g_egl.valid())
                createHWFBO(g_av_info.geometry.max_width, g_av_info.geometry.max_height);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            auto* info = static_cast<const retro_system_av_info*>(data);
            g_av_info = *info;
            g_fps = info->timing.fps;
            g_sample_rate = info->timing.sample_rate;
            if (g_hw_render && g_egl.valid())
                createHWFBO(info->geometry.max_width, info->geometry.max_height);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: {
            return false;  // not implemented yet
        }
        case RETRO_ENVIRONMENT_GET_PERF_INTERFACE: {
            auto* cb = static_cast<retro_perf_callback*>(data);
            cb->get_time_usec   = perf_get_time_usec;
            cb->get_cpu_features = reinterpret_cast<retro_perf_get_counter_t>(perf_get_cpu_features);
            cb->get_perf_counter = perf_get_perf_counter;
            cb->perf_register   = perf_register;
            cb->perf_start      = perf_start;
            cb->perf_stop       = perf_stop;
            cb->perf_log        = perf_log;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT: {
            LOGI("Core requests shared HW context");
            return true;
        }
        case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES: {
            *static_cast<uint64_t*>(data) = (1 << RETRO_DEVICE_JOYPAD) |
                                            (1 << RETRO_DEVICE_ANALOG) |
                                            (1 << RETRO_DEVICE_POINTER);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LANGUAGE: {
            *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_USERNAME: {
            *static_cast<const char**>(data) = "VortexPlayer";
            return true;
        }
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: {
            return true;
        }
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: {
            *static_cast<unsigned*>(data) = RETRO_HW_CONTEXT_OPENGLES3;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS: {
            return true;
        }
        case RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS: {
            *static_cast<unsigned*>(data) = MAX_PORTS;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS: {
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO: {
            return true;
        }
        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE: {
            return false;
        }
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS: {
            return true;
        }
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: {
            int result = 0;
            result |= 1;  // enable video
            result |= 2;  // enable audio
            *static_cast<int*>(data) = result;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
        case RETRO_ENVIRONMENT_GET_GAME_INFO_EXT: {
            return false;
        }
        case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK:
        case RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK: {
            return false;
        }
        case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE: {
            return false;
        }
        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION: {
            *static_cast<unsigned*>(data) = 1;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE:
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            if (cmd == RETRO_ENVIRONMENT_SET_MESSAGE && data) {
                auto* msg = static_cast<const retro_message*>(data);
                LOGI("Core message: %s", msg->msg);
            } else if (data) {
                auto* msg = static_cast<const retro_message_ext*>(data);
                LOGI("Core message (ext): %s", msg->msg);
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL: {
            return true;
        }
        case RETRO_ENVIRONMENT_SET_ROTATION: {
            return true;
        }
        case RETRO_ENVIRONMENT_GET_OVERSCAN: {
            *static_cast<bool*>(data) = false;
            return true;
        }
        case RETRO_ENVIRONMENT_SHUT_DOWN: {
            LOGI("Core requested shutdown");
            return true;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLE: {
            if (data) {
                auto* var = static_cast<const retro_variable*>(data);
                if (var->key && var->value) {
                    std::lock_guard<std::mutex> lock(g_options_mutex);
                    g_core_options[var->key] = var->value;
                    g_options_updated.store(true, std::memory_order_release);
                }
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_THROTTLE_STATE: {
            return false;
        }
        case RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER: {
            return false;  // we don't support software framebuffer pass-through
        }
        case RETRO_ENVIRONMENT_GET_LIBRETRO_PATH: {
            return false;
        }
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
        case RETRO_ENVIRONMENT_GET_LED_INTERFACE: {
            return false;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
            return false;
        }
        default:
            return false;
    }
}

/* ── Blit software frame to screen ─────────────────────────────── */
static void blitSoftwareFrame() {
    unsigned w, h;
    {
        std::lock_guard<std::mutex> lock(g_video_mutex);
        if (!g_frame_ready.load(std::memory_order_acquire)) return;
        w = g_frame_w;
        h = g_frame_h;
        if (w == 0 || h == 0 || g_frame_buf.empty()) return;

        // Direct texture upload — fast enough for retro resolutions
        glBindTexture(GL_TEXTURE_2D, g_blit.texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, static_cast<GLsizei>(w), static_cast<GLsizei>(h),
                     0, GL_RGBA, GL_UNSIGNED_BYTE, g_frame_buf.data());

        g_frame_ready.store(false, std::memory_order_release);
    }

    // Draw with aspect-ratio-preserving viewport
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glViewport(0, 0, g_surface_w, g_surface_h);
    glClear(GL_COLOR_BUFFER_BIT);

    // Compute letterboxed viewport
    float srcAspect = static_cast<float>(w) / static_cast<float>(h);
    float dstAspect = (g_surface_h > 0) ? static_cast<float>(g_surface_w) / static_cast<float>(g_surface_h) : srcAspect;
    int vpX = 0, vpY = 0, vpW = g_surface_w, vpH = g_surface_h;
    if (srcAspect > dstAspect) {
        // Wider than surface: pillarbox (black top/bottom)
        vpH = static_cast<int>(g_surface_w / srcAspect);
        vpY = (g_surface_h - vpH) / 2;
    } else {
        // Taller than surface: letterbox (black left/right)
        vpW = static_cast<int>(g_surface_h * srcAspect);
        vpX = (g_surface_w - vpW) / 2;
    }
    glViewport(vpX, vpY, vpW, vpH);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);

    glUseProgram(g_blit.program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, g_blit.texture);
    glBindVertexArray(g_blit.vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
}

/* ── Blit hardware-rendered FBO to screen (RetroArch approach) ── */
static void blitHardwareFrame() {
    if (!g_hw_tex_color || !g_hw_blit_program) {
        if (g_debug_frame_counter < 5)
            LOGW("blitHardwareFrame: missing resources (tex=%u prog=%u)",
                 g_hw_tex_color, g_hw_blit_program);
        return;
    }

    // Use video_refresh dimensions; fall back to FBO size if core reported 0x0
    unsigned w = g_frame_w;
    unsigned h = g_frame_h;
    if (w == 0 || h == 0) {
        w = g_hw_fbo_w;
        h = g_hw_fbo_h;
    }
    if (w == 0 || h == 0) return;

    // Check which FBO the core left bound.
    GLint coreFbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &coreFbo);
    bool coreUsedOurFbo = (g_hw_fbo != 0 && static_cast<GLuint>(coreFbo) == g_hw_fbo);

    if (g_debug_frame_counter < 10) {
        LOGI("blitHW: frame=%ux%u fbo=%u tex=%u fbo_dims=%ux%u core_left_fbo=%d surface=%dx%d flip=%d usedOurFbo=%d",
             g_frame_w, g_frame_h, g_hw_fbo, g_hw_tex_color, g_hw_fbo_w, g_hw_fbo_h,
             coreFbo, g_surface_w, g_surface_h, g_hw_cb.bottom_left_origin,
             (int)coreUsedOurFbo);
    }
    g_debug_frame_counter++;

    if (!coreUsedOurFbo) {
        // Core left FBO 0 bound, but might have rendered to our FBO first.
        // Check both: our FBO texture content AND FBO 0 content.
        glFinish();

        if (g_debug_frame_counter < 5) {
            // Check multiple locations for content
            uint8_t pixel[4] = {0};
            // Check FBO 0 at various points
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            glReadPixels(10, 10, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            LOGI("blitHW diag: FBO0(10,10)=R%u G%u B%u A%u", pixel[0], pixel[1], pixel[2], pixel[3]);
            glReadPixels(320, 240, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            LOGI("blitHW diag: FBO0(320,240)=R%u G%u B%u A%u", pixel[0], pixel[1], pixel[2], pixel[3]);
            // Check our FBO 
            glBindFramebuffer(GL_READ_FRAMEBUFFER, g_hw_fbo);
            glReadPixels(10, 10, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            LOGI("blitHW diag: OurFBO(10,10)=R%u G%u B%u A%u", pixel[0], pixel[1], pixel[2], pixel[3]);
            glReadPixels(static_cast<GLint>(w/2), static_cast<GLint>(h/2), 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            LOGI("blitHW diag: OurFBO(%u,%u)=R%u G%u B%u A%u", w/2, h/2, pixel[0], pixel[1], pixel[2], pixel[3]);

            // Check GL errors
            GLenum err;
            while ((err = glGetError()) != GL_NO_ERROR)
                LOGW("blitHW diag GL error: 0x%x", err);
        }

        // Try to copy from FBO 0 into our texture
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, g_hw_tex_color);
        if (w != g_hw_fbo_w || h != g_hw_fbo_h) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8,
                         static_cast<GLsizei>(w), static_cast<GLsizei>(h),
                         0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
            g_hw_fbo_w = w;
            g_hw_fbo_h = h;
        }
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0,
                            static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    // Draw FBO texture to default FB using shader (Mali-compatible, no glBlitFramebuffer)
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glViewport(0, 0, g_surface_w, g_surface_h);
    glClear(GL_COLOR_BUFFER_BIT);

    // Compute letterboxed viewport for aspect ratio
    float srcAspect = static_cast<float>(w) / static_cast<float>(h);
    float dstAspect = (g_surface_h > 0) ? static_cast<float>(g_surface_w) / static_cast<float>(g_surface_h) : srcAspect;
    int vpX = 0, vpY = 0, vpW = g_surface_w, vpH = g_surface_h;
    if (srcAspect > dstAspect) {
        vpH = static_cast<int>(g_surface_w / srcAspect);
        vpY = (g_surface_h - vpH) / 2;
    } else {
        vpW = static_cast<int>(g_surface_h * srcAspect);
        vpX = (g_surface_w - vpW) / 2;
    }
    glViewport(vpX, vpY, vpW, vpH);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);

    glUseProgram(g_hw_blit_program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, g_hw_tex_color);
    // Flip Y for bottom-left-origin cores (e.g. N64/mupen64plus)
    glUniform1f(glGetUniformLocation(g_hw_blit_program, "uFlipY"),
                g_hw_cb.bottom_left_origin ? 1.0f : 0.0f);
    glBindVertexArray(g_blit.vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    g_frame_ready.store(false, std::memory_order_release);
}

/* ── Symbol loader macros ───────────────────────────────────────── */
#define LOAD_SYM(sym) do {                                                     \
    g_core.sym = reinterpret_cast<decltype(g_core.sym)>(                       \
        dlsym(g_core_handle.handle, "retro_" #sym));                           \
    if (!g_core.sym) {                                                         \
        LOGE("Missing required symbol: retro_" #sym " — %s", dlerror());      \
        return false;                                                          \
    }                                                                          \
} while(0)

#define LOAD_SYM_OPT(sym) do {                                                 \
    g_core.sym = reinterpret_cast<decltype(g_core.sym)>(                       \
        dlsym(g_core_handle.handle, "retro_" #sym));                           \
    if (!g_core.sym)                                                           \
        LOGD("Optional symbol not found: retro_" #sym);                        \
} while(0)

/* Some symbols have different field names than their actual export names */
#define LOAD_SYM_NAMED(field, name) do {                                       \
    g_core.field = reinterpret_cast<decltype(g_core.field)>(                   \
        dlsym(g_core_handle.handle, name));                                    \
    if (!g_core.field)                                                         \
        LOGD("Optional symbol not found: " name);                              \
} while(0)

static bool loadCoreSymbols() {
    /* Required symbols — every libretro core MUST export these */
    LOAD_SYM(init);
    LOAD_SYM(deinit);
    LOAD_SYM(api_version);
    LOAD_SYM(get_system_info);
    LOAD_SYM(get_system_av_info);
    LOAD_SYM(set_environment);
    LOAD_SYM(set_video_refresh);
    LOAD_SYM(set_audio_sample);
    LOAD_SYM(set_audio_sample_batch);
    LOAD_SYM(set_input_poll);
    LOAD_SYM(set_input_state);
    LOAD_SYM(load_game);
    LOAD_SYM(unload_game);
    LOAD_SYM(run);
    LOAD_SYM(reset);

    /* Optional symbols — not all cores implement these */
    LOAD_SYM_OPT(serialize_size);
    LOAD_SYM_OPT(serialize);
    LOAD_SYM_OPT(unserialize);
    LOAD_SYM_NAMED(set_controller_port, "retro_set_controller_port_device");
    LOAD_SYM_OPT(get_memory_data);
    LOAD_SYM_OPT(get_memory_size);
    return true;
}
#undef LOAD_SYM
#undef LOAD_SYM_OPT
#undef LOAD_SYM_NAMED

/* ── Cleanup everything ────────────────────────────────────────── */
static void cleanupAll() {
    g_blit.destroy();
    if (g_hw_fbo)       { glDeleteFramebuffers(1, &g_hw_fbo); g_hw_fbo = 0; }
    if (g_hw_tex_color) { glDeleteTextures(1, &g_hw_tex_color); g_hw_tex_color = 0; }
    if (g_hw_rb_depth)  { glDeleteRenderbuffers(1, &g_hw_rb_depth); g_hw_rb_depth = 0; }
    if (g_hw_blit_program) { glDeleteProgram(g_hw_blit_program); g_hw_blit_program = 0; }
    g_egl.destroy();
    g_vulkan.destroy();
    g_egl_offscreen = false;
    g_hw_readback_buf.clear();
    g_hw_readback_buf.shrink_to_fit();
    if (g_window) { ANativeWindow_release(g_window); g_window = nullptr; }
    g_core = CoreSymbols{};
    g_core_handle = DlHandle{};
    g_hw_render = false;
    g_hw_cb = {};
    g_hw_context_reset_pending.store(false);
    g_hw_context_failed.store(false);
    g_frame_buf.clear();
    g_frame_w = g_frame_h = 0;
    g_frame_ready.store(false);
    g_audio_write_pos = 0;
    g_pixel_fmt = RETRO_PIXEL_FORMAT_0RGB1555;
    {
        std::lock_guard<std::mutex> lock(g_options_mutex);
        g_core_options.clear();
    }
    g_options_updated.store(false);
    std::memset(g_joypad, 0, sizeof(g_joypad));
    std::memset(g_analog, 0, sizeof(g_analog));
}

/* ══════════════════════════════════════════════════════════════════
 *  JNI EXPORTS
 * ══════════════════════════════════════════════════════════════════ */

extern "C" {
/* ── JNI_OnLoad: cache JavaVM pointer for thread attachment ─────── */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    LOGI("JNI_OnLoad: JavaVM cached (%p)", vm);
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_vortex_emulator_emulation_VortexNative_loadCore(
    JNIEnv* env, jobject /*thiz*/,
    jstring jCorePath, jstring jSystemDir, jstring jSaveDir)
{
    const char* corePath  = env->GetStringUTFChars(jCorePath, nullptr);
    const char* systemDir = env->GetStringUTFChars(jSystemDir, nullptr);
    const char* saveDir   = env->GetStringUTFChars(jSaveDir, nullptr);

    // Store dirs before any early return
    g_system_dir = systemDir;
    g_save_dir   = saveDir;

    LOGI("Loading core: %s", corePath);

    // Clean up previous core
    if (g_core.deinit) g_core.deinit();
    g_core = CoreSymbols{};
    g_core_handle = DlHandle{};
    g_hw_render = false;
    g_hw_cb = {};
    g_hw_context_failed.store(false);
    g_debug_frame_counter = 0;

    void* handle = dlopen(corePath, RTLD_LAZY);

    // Release JNI strings immediately after use (fixes leak on error paths)
    env->ReleaseStringUTFChars(jCorePath, corePath);
    env->ReleaseStringUTFChars(jSystemDir, systemDir);
    env->ReleaseStringUTFChars(jSaveDir, saveDir);

    if (!handle) {
        LOGE("dlopen failed: %s", dlerror());
        return -1;
    }
    g_core_handle = DlHandle(handle);

    // Call the core's JNI_OnLoad if it exists — some cores (PPSSPP, etc.)
    // need the JavaVM pointer to attach worker threads to the JVM.
    if (g_jvm) {
        typedef jint (*JniOnLoad_t)(JavaVM*, void*);
        auto core_jni_on_load = reinterpret_cast<JniOnLoad_t>(
            dlsym(handle, "JNI_OnLoad"));
        if (core_jni_on_load) {
            jint ver = core_jni_on_load(g_jvm, nullptr);
            LOGI("Called core JNI_OnLoad → version 0x%x", ver);
        }

        // PPSSPP: set gJvm global so retro_init can set up thread attachment
        auto* jvm_ptr = reinterpret_cast<void**>(dlsym(handle, "gJvm"));
        if (jvm_ptr) {
            *jvm_ptr = reinterpret_cast<void*>(g_jvm);
            LOGI("Set gJvm in core to %p", *jvm_ptr);
        }

        // PPSSPP: set g_attach/g_detach function pointers directly
        auto* attach_ptr = reinterpret_cast<void*(**)(void)>(dlsym(handle, "g_attach"));
        auto* detach_ptr = reinterpret_cast<void(**)(void)>(dlsym(handle, "g_detach"));
        if (attach_ptr && detach_ptr) {
            *attach_ptr = jni_attach_current_thread;
            *detach_ptr = jni_detach_current_thread;
            LOGI("Set g_attach/g_detach in core");
        }
    }

    if (!loadCoreSymbols()) {
        LOGE("Failed to load all core symbols");
        g_core_handle = DlHandle{};
        return -2;
    }

    unsigned api = g_core.api_version();
    LOGI("Core API version: %u", api);

    g_core.set_environment(core_environment);
    g_core.init();
    g_core.set_video_refresh(core_video_refresh);
    g_core.set_audio_sample(core_audio_sample);
    g_core.set_audio_sample_batch(core_audio_sample_batch);
    g_core.set_input_poll(core_input_poll);
    g_core.set_input_state(core_input_state);

    struct retro_system_info sysInfo = {};
    g_core.get_system_info(&sysInfo);
    LOGI("Core: %s (%s)", sysInfo.library_name, sysInfo.library_version);
    LOGI("HW render: %s", g_hw_render ? "yes" : "no");

    // Pre-set critical options that cores may query but not register
    // via SET_VARIABLES/SET_CORE_OPTIONS. These are queried directly
    // via GET_VARIABLE and if missing, the core uses internal defaults
    // that may not work on Android (e.g., trying to cache shaders to
    // a non-existent path).
    {
        std::lock_guard<std::mutex> lock(g_options_mutex);
        // mupen64plus: disable shader storage (no reliable cache path on Android)
        if (g_core_options.find("mupen64plus-EnableShadersStorage") == g_core_options.end())
            g_core_options["mupen64plus-EnableShadersStorage"] = "False";
        // mupen64plus: enable framebuffer emulation only if not already set by user
        if (g_core_options.find("mupen64plus-EnableFBEmulation") == g_core_options.end())
            g_core_options["mupen64plus-EnableFBEmulation"] = "True";
        LOGI("Pre-set mupen64plus options: EnableShadersStorage=False, EnableFBEmulation=%s",
             g_core_options["mupen64plus-EnableFBEmulation"].c_str());
    }

    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_vortex_emulator_emulation_VortexNative_loadGame(
    JNIEnv* env, jobject /*thiz*/, jstring jRomPath)
{
    if (!g_core.load_game) return JNI_FALSE;

    const char* romPath = env->GetStringUTFChars(jRomPath, nullptr);
    LOGI("Loading game: %s", romPath);

    struct retro_game_info gameInfo = {};
    gameInfo.path = romPath;

    // For cores that don't use need_fullpath, load the ROM into memory
    struct retro_system_info sysInfo = {};
    g_core.get_system_info(&sysInfo);

    std::vector<uint8_t> romData;
    if (!sysInfo.need_fullpath) {
        FILE* f = fopen(romPath, "rb");
        if (f) {
            fseek(f, 0, SEEK_END);
            long size = ftell(f);
            fseek(f, 0, SEEK_SET);
            if (size > 0) {
                romData.resize(static_cast<size_t>(size));
                size_t bytesRead = fread(romData.data(), 1, romData.size(), f);
                if (bytesRead == romData.size()) {
                    gameInfo.data = romData.data();
                    gameInfo.size = romData.size();
                } else {
                    LOGW("Short read on ROM file (%zu of %zu)", bytesRead, romData.size());
                    romData.clear();
                }
            }
            fclose(f);
        }
    }

    bool ok = g_core.load_game(&gameInfo);
    env->ReleaseStringUTFChars(jRomPath, romPath);

    if (!ok) {
        LOGE("retro_load_game failed");
        return JNI_FALSE;
    }

    g_core.get_system_av_info(&g_av_info);
    g_fps = g_av_info.timing.fps;
    g_sample_rate = g_av_info.timing.sample_rate;
    LOGI("AV: %ux%u @ %.2f fps, audio %.0f Hz",
         g_av_info.geometry.base_width, g_av_info.geometry.base_height,
         g_fps, g_sample_rate);

    // Tell the core a standard joypad is connected on each port
    if (g_core.set_controller_port) {
        for (unsigned port = 0; port < MAX_PORTS; port++) {
            g_core.set_controller_port(port, RETRO_DEVICE_JOYPAD);
        }
        LOGI("Set controller port device: JOYPAD on ports 0-%d", MAX_PORTS - 1);
    }

    // HW FBO creation is deferred to the emulation thread (runFrame)
    // where it has a valid GL context.  Do NOT call createHWFBO here
    // because setSurface may have already released the EGL context.
    if (g_hw_render) {
        g_hw_context_reset_pending.store(true, std::memory_order_release);
        LOGI("HW render active — context_reset deferred to emulation thread");
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_runFrame(
    JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (!g_core.run) return;

    // Frame skip support
    int skip = g_frame_skip.load(std::memory_order_relaxed);
    if (skip > 0) {
        g_frame_skip_counter++;
        if (g_frame_skip_counter <= skip) {
            // Still run the core (for timing), but skip audio write
            g_audio_write_pos = 0;
            g_core.run();
            return;
        }
        g_frame_skip_counter = 0;
    }

    // Reset audio buffer position
    {
        std::lock_guard<std::mutex> lock(g_audio_mutex);
        g_audio_write_pos = 0;
    }

    // Make EGL current if we have a context (for HW rendering cores)
    // For Vulkan display: EGL is only needed for HW render cores (offscreen PBuffer)
    if (g_egl.valid())
        g_egl.makeCurrent();

    // Handle deferred context_reset (must happen on emulation thread with EGL current)
    if (g_hw_context_reset_pending.load(std::memory_order_acquire)) {
        g_hw_context_reset_pending.store(false, std::memory_order_release);
        if (g_hw_render) {
            // Lazily create offscreen EGL if using Vulkan display
            if (g_display_backend.load(std::memory_order_relaxed) == DISPLAY_VULKAN && !g_egl.valid()) {
                initEGLOffscreen();
            }
            if (g_egl.valid()) {
                g_egl.makeCurrent();
                if (!g_hw_fbo)
                    createHWFBO(g_av_info.geometry.max_width, g_av_info.geometry.max_height);

            // Flush any pending GL errors before context_reset
            while (glGetError() != GL_NO_ERROR) {}

            if (g_hw_cb.context_reset) {
                LOGI("Calling context_reset on emulation thread (fbo=%u %ux%u) GL=%s renderer=%s",
                     g_hw_fbo, g_hw_fbo_w, g_hw_fbo_h,
                     glGetString(GL_VERSION), glGetString(GL_RENDERER));

                // Guard against cores that throw during context_reset
                // (e.g. PPSSPP on certain Mali GPUs with GL_INVALID_VALUE).
                // Without this try-catch the exception escapes the noexcept
                // JNI boundary → std::terminate → SIGABRT.
                try {
                    g_hw_cb.context_reset();
                } catch (const std::exception& e) {
                    LOGE("context_reset threw std::exception: %s", e.what());
                    g_hw_context_failed.store(true, std::memory_order_release);
                } catch (...) {
                    LOGE("context_reset threw unknown exception");
                    g_hw_context_failed.store(true, std::memory_order_release);
                }

                // Check for GL errors after core initialization
                GLenum err;
                int errCount = 0;
                while ((err = glGetError()) != GL_NO_ERROR) {
                    LOGE("GL error after context_reset: 0x%x", err);
                    if (++errCount > 20) break;
                }
                if (errCount == 0) LOGI("context_reset completed (no GL errors)");
                else LOGW("context_reset completed with %d GL errors", errCount);
            }
            }  // g_egl.valid()
        }  // g_hw_render
    }

    // Reset per-frame swap flag
    g_hw_frame_presented.store(false, std::memory_order_release);

    // Prepare GL state for HW cores before retro_run.
    // RetroArch binds the core's FBO and sets the viewport before running.
    // GlideN64 and other cores may check what FBO is currently bound.
    if (g_hw_render && g_hw_fbo) {
        glBindFramebuffer(GL_FRAMEBUFFER, g_hw_fbo);
        glViewport(0, 0, g_hw_fbo_w, g_hw_fbo_h);
    }

    static int run_log_count = 0;
    if (run_log_count < 5)
        LOGI("runFrame: calling retro_run (hw=%d fbo=%u)", (int)g_hw_render, g_hw_fbo);

    // Early input polling: explicitly call input_poll before retro_run
    // so cores see the latest input state immediately (Gemini fix)
    if (g_input_poll_mode.load(std::memory_order_relaxed) == 1)
        core_input_poll();

    g_core.run();

    if (run_log_count < 5) {
        LOGI("runFrame: retro_run returned (hw_presented=%d)",
             (int)g_hw_frame_presented.load(std::memory_order_acquire));
        run_log_count++;
    }

    // After run: present frame
    if (g_display_backend.load(std::memory_order_relaxed) == DISPLAY_VULKAN) {
        // Vulkan display path
        if (g_hw_render) {
            // Non-blocking HW cores where video_refresh didn't present
            if (!g_hw_frame_presented.load(std::memory_order_acquire) && g_vulkan.isReady()) {
                unsigned rw = g_hw_fbo_w;
                unsigned rh = g_hw_fbo_h;
                if (rw > 0 && rh > 0) {
                    g_hw_readback_buf.resize(static_cast<size_t>(rw) * rh);
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, g_hw_fbo);
                    glReadPixels(0, 0, static_cast<GLsizei>(rw), static_cast<GLsizei>(rh),
                                 GL_RGBA, GL_UNSIGNED_BYTE, g_hw_readback_buf.data());
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
                    g_vulkan.presentFrame(g_hw_readback_buf.data(), rw, rh,
                                          false, g_hw_cb.bottom_left_origin);
                }
            }
        } else {
            // SW render: present from g_frame_buf
            if (g_frame_ready.load(std::memory_order_acquire) && g_vulkan.isReady()) {
                std::lock_guard<std::mutex> lock(g_video_mutex);
                if (!g_frame_buf.empty() && g_frame_w > 0 && g_frame_h > 0) {
                    g_vulkan.presentFrame(g_frame_buf.data(), g_frame_w, g_frame_h,
                                          true, false);
                    g_frame_ready.store(false, std::memory_order_release);
                }
            }
        }
    } else if (g_egl.valid() && g_egl.surface != EGL_NO_SURFACE) {
        // GL display path (original)
        if (g_hw_render) {
            if (!g_hw_frame_presented.load(std::memory_order_acquire)) {
                blitHardwareFrame();
                g_egl.swapBuffers();
            }
        } else {
            blitSoftwareFrame();
            g_egl.swapBuffers();
        }
    }
}

JNIEXPORT jintArray JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getFrameBuffer(
    JNIEnv* env, jobject /*thiz*/)
{
    std::lock_guard<std::mutex> lock(g_video_mutex);
    if (g_frame_buf.empty() || g_frame_w == 0 || g_frame_h == 0)
        return nullptr;

    size_t count = static_cast<size_t>(g_frame_w) * g_frame_h;
    jintArray arr = env->NewIntArray(static_cast<jsize>(count));
    if (arr)
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(count),
                               reinterpret_cast<const jint*>(g_frame_buf.data()));
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getFrameWidth(
    JNIEnv* /*env*/, jobject /*thiz*/) { return static_cast<jint>(g_frame_w); }

JNIEXPORT jint JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getFrameHeight(
    JNIEnv* /*env*/, jobject /*thiz*/) { return static_cast<jint>(g_frame_h); }

JNIEXPORT jshortArray JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getAudioBuffer(
    JNIEnv* env, jobject /*thiz*/)
{
    std::lock_guard<std::mutex> lock(g_audio_mutex);
    if (g_audio_write_pos == 0) return nullptr;

    jshortArray arr = env->NewShortArray(static_cast<jsize>(g_audio_write_pos));
    if (arr)
        env->SetShortArrayRegion(arr, 0, static_cast<jsize>(g_audio_write_pos),
                                 g_audio_buf.data());
    return arr;
}

JNIEXPORT jdouble JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getFps(
    JNIEnv* /*env*/, jobject /*thiz*/) { return g_fps; }

JNIEXPORT jboolean JNICALL
Java_com_vortex_emulator_emulation_VortexNative_isHwContextFailed(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_hw_context_failed.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdouble JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getSampleRate(
    JNIEnv* /*env*/, jobject /*thiz*/) { return g_sample_rate; }

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setInputState(
    JNIEnv* /*env*/, jobject /*thiz*/, jint port, jint buttonId, jint value)
{
    std::lock_guard<std::mutex> lock(g_input_mutex);
    if (port >= 0 && port < MAX_PORTS && buttonId >= 0 && buttonId < MAX_BUTTONS)
        g_joypad[port][buttonId] = static_cast<int16_t>(value);
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setAnalogState(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint port, jint index, jint axisId, jint value)
{
    std::lock_guard<std::mutex> lock(g_input_mutex);
    if (port >= 0 && port < MAX_PORTS && index >= 0 && index < 2 && axisId >= 0 && axisId < 2)
        g_analog[port][index][axisId] = static_cast<int16_t>(value);
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_resetGame(
    JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (g_core.reset) g_core.reset();
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_unloadGame(
    JNIEnv* /*env*/, jobject /*thiz*/)
{
    LOGI("Unloading game and core");
    if (g_core.unload_game) g_core.unload_game();
    if (g_core.deinit) g_core.deinit();
    cleanupAll();
}

JNIEXPORT jint JNICALL
Java_com_vortex_emulator_emulation_VortexNative_saveState(
    JNIEnv* env, jobject /*thiz*/, jstring jPath)
{
    if (!g_core.serialize_size || !g_core.serialize) return -1;

    size_t sz = g_core.serialize_size();
    if (sz == 0) return -2;

    std::vector<uint8_t> buf(sz);
    if (!g_core.serialize(buf.data(), sz)) return -3;

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    FILE* f = fopen(path, "wb");
    env->ReleaseStringUTFChars(jPath, path);
    if (!f) return -4;

    size_t written = fwrite(buf.data(), 1, sz, f);
    fclose(f);
    return (written == sz) ? 0 : -5;
}

JNIEXPORT jint JNICALL
Java_com_vortex_emulator_emulation_VortexNative_loadState(
    JNIEnv* env, jobject /*thiz*/, jstring jPath)
{
    if (!g_core.unserialize) return -1;

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    FILE* f = fopen(path, "rb");
    env->ReleaseStringUTFChars(jPath, path);
    if (!f) return -2;

    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (sz <= 0) { fclose(f); return -3; }

    std::vector<uint8_t> buf(static_cast<size_t>(sz));
    size_t bytesRead = fread(buf.data(), 1, buf.size(), f);
    fclose(f);
    if (bytesRead != buf.size()) return -4;

    return g_core.unserialize(buf.data(), buf.size()) ? 0 : -5;
}

JNIEXPORT jbyteArray JNICALL
Java_com_vortex_emulator_emulation_VortexNative_saveStateToMemory(
    JNIEnv* env, jobject /*thiz*/)
{
    if (!g_core.serialize_size || !g_core.serialize) return nullptr;

    size_t sz = g_core.serialize_size();
    if (sz == 0) return nullptr;

    std::vector<uint8_t> buf(sz);
    if (!g_core.serialize(buf.data(), sz)) return nullptr;

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(sz));
    if (arr)
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(sz),
                                reinterpret_cast<const jbyte*>(buf.data()));
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_vortex_emulator_emulation_VortexNative_loadStateFromMemory(
    JNIEnv* env, jobject /*thiz*/, jbyteArray jStateData)
{
    if (!g_core.unserialize || !jStateData) return JNI_FALSE;

    jsize len = env->GetArrayLength(jStateData);
    if (len <= 0) return JNI_FALSE;

    jbyte* data = env->GetByteArrayElements(jStateData, nullptr);
    if (!data) return JNI_FALSE;

    bool ok = g_core.unserialize(data, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(jStateData, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_vortex_emulator_emulation_VortexNative_getSerializeSize(
    JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (!g_core.serialize_size) return 0;
    return static_cast<jlong>(g_core.serialize_size());
}

JNIEXPORT jboolean JNICALL
Java_com_vortex_emulator_emulation_VortexNative_isHardwareRendered(
    JNIEnv* /*env*/, jobject /*thiz*/) { return g_hw_render ? JNI_TRUE : JNI_FALSE; }

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setCoreOption(
    JNIEnv* env, jobject /*thiz*/, jstring jKey, jstring jValue)
{
    const char* key = env->GetStringUTFChars(jKey, nullptr);
    const char* val = env->GetStringUTFChars(jValue, nullptr);
    {
        std::lock_guard<std::mutex> lock(g_options_mutex);
        g_core_options[key] = val;
    }
    g_options_updated.store(true, std::memory_order_release);

    // Handle frontend-level options that affect native behavior directly
    if (strcmp(key, "input_poll_type_behavior") == 0) {
        if (strcmp(val, "early") == 0) g_input_poll_mode.store(1, std::memory_order_relaxed);
        else if (strcmp(val, "late") == 0) g_input_poll_mode.store(2, std::memory_order_relaxed);
        else g_input_poll_mode.store(0, std::memory_order_relaxed);
        LOGI("Input poll mode set to: %s (%d)", val, g_input_poll_mode.load());
    }

    env->ReleaseStringUTFChars(jKey, key);
    env->ReleaseStringUTFChars(jValue, val);
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setPointerState(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint x, jint y, jboolean pressed)
{
    std::lock_guard<std::mutex> lock(g_input_mutex);
    g_pointer_x = static_cast<int16_t>(x);
    g_pointer_y = static_cast<int16_t>(y);
    g_pointer_pressed = (pressed == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_vortex_emulator_emulation_VortexNative_saveSRAM(
    JNIEnv* env, jobject /*thiz*/, jstring jPath)
{
    if (!g_core.get_memory_data || !g_core.get_memory_size) return JNI_FALSE;

    void* data = g_core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = g_core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return JNI_FALSE;

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    FILE* f = fopen(path, "wb");
    env->ReleaseStringUTFChars(jPath, path);
    if (!f) return JNI_FALSE;

    size_t written = fwrite(data, 1, size, f);
    fclose(f);
    return (written == size) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_vortex_emulator_emulation_VortexNative_loadSRAM(
    JNIEnv* env, jobject /*thiz*/, jstring jPath)
{
    if (!g_core.get_memory_data || !g_core.get_memory_size) return JNI_FALSE;

    void* data = g_core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = g_core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return JNI_FALSE;

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    FILE* f = fopen(path, "rb");
    env->ReleaseStringUTFChars(jPath, path);
    if (!f) return JNI_FALSE;

    size_t bytesRead = fread(data, 1, size, f);
    fclose(f);
    return (bytesRead == size) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setFrameSkip(
    JNIEnv* /*env*/, jobject /*thiz*/, jint skip)
{
    g_frame_skip.store(skip, std::memory_order_relaxed);
    g_frame_skip_counter = 0;
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setSurface(
    JNIEnv* env, jobject /*thiz*/, jobject jSurface)
{
    // Release previous window & resources
    if (g_window) {
        g_blit.destroy();
        g_egl.destroy();
        g_vulkan.destroy();
        g_egl_offscreen = false;
        // GL context is gone — reset stale GL object names
        g_hw_fbo = 0;
        g_hw_tex_color = 0;
        g_hw_rb_depth = 0;
        g_hw_fbo_w = 0;
        g_hw_fbo_h = 0;
        g_hw_blit_program = 0;
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }

    if (!jSurface) {
        LOGI("Surface cleared");
        return;
    }

    g_window = ANativeWindow_fromSurface(env, jSurface);
    if (!g_window) {
        LOGE("ANativeWindow_fromSurface failed");
        return;
    }

    g_surface_w = ANativeWindow_getWidth(g_window);
    g_surface_h = ANativeWindow_getHeight(g_window);
    LOGI("Surface set: %dx%d (display_backend=%d)", g_surface_w, g_surface_h,
         g_display_backend.load(std::memory_order_relaxed));

    bool useVulkan = (g_display_backend.load(std::memory_order_relaxed) == DISPLAY_VULKAN);

    if (useVulkan) {
        // Try Vulkan display
        if (!g_vulkan.init(g_window, g_surface_w, g_surface_h)) {
            LOGW("Vulkan init failed — falling back to OpenGL ES display");
            g_display_backend.store(DISPLAY_GL, std::memory_order_relaxed);
            useVulkan = false;
        }
    }

    if (useVulkan) {
        // Vulkan display active — only create EGL if HW render core needs GLES
        if (g_hw_render) {
            if (!initEGLOffscreen()) {
                LOGE("Offscreen EGL init failed — falling back to GL display");
                g_vulkan.destroy();
                g_display_backend.store(DISPLAY_GL, std::memory_order_relaxed);
                useVulkan = false;
            }
        }
    }

    if (!useVulkan) {
        // GL display path (original)
        if (!initEGL(g_window)) {
            LOGE("EGL init failed");
            ANativeWindow_release(g_window);
            g_window = nullptr;
            return;
        }
    }

    // Re-create HW FBO if needed
    if (g_hw_render && g_egl.valid()) {
        createHWFBO(g_av_info.geometry.max_width, g_av_info.geometry.max_height);
        g_hw_context_reset_pending.store(true, std::memory_order_release);
        LOGI("context_reset deferred (fbo=%u %ux%u, vulkan=%d)",
             g_hw_fbo, g_hw_fbo_w, g_hw_fbo_h, (int)useVulkan);
    }

    // Release EGL context from UI thread so emulation thread can acquire it
    if (g_egl.valid()) {
        eglMakeCurrent(g_egl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        LOGI("EGL context released from UI thread");
    }
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_surfaceChanged(
    JNIEnv* /*env*/, jobject /*thiz*/, jint width, jint height)
{
    g_surface_w = width;
    g_surface_h = height;
    LOGI("Surface changed: %dx%d", width, height);

    // Update Vulkan swapchain if active
    if (g_display_backend.load(std::memory_order_relaxed) == DISPLAY_VULKAN && g_vulkan.isReady()) {
        g_vulkan.surfaceChanged(width, height);
    }
}

/* ── Render backend selection (called from Kotlin before loadCore) ── */
JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setRenderBackend(
    JNIEnv* /*env*/, jobject /*thiz*/, jint backend)
{
    int old = g_display_backend.load(std::memory_order_relaxed);
    g_display_backend.store(backend, std::memory_order_relaxed);
    LOGI("Display backend set: %d → %d (%s)", old, backend,
         backend == DISPLAY_VULKAN ? "Vulkan" : "OpenGL ES");
}

} // extern "C"