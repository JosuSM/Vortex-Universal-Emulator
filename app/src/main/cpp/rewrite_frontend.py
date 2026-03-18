#!/usr/bin/env python3
"""Rewrite vortex_frontend.c: window surface + direct blit pipeline."""
import re, os

SRC = os.path.join(os.path.dirname(__file__), "vortex_frontend.c")
with open(SRC, "r") as f:
    code = f.read()

# ─── 1. Update top comment ────────────────────────────────────────
old_comment = '''/*
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
 */'''
new_comment = '''/*
 * vortex_frontend.c — libretro frontend for Vortex Emulator
 *
 * Loads a libretro core (.so), initialises it, runs frames, and presents
 * video directly to an Android SurfaceView via EGL window surface.
 *
 * Display pipeline (zero-copy GPU path):
 *   HW cores (non-shared):  core → FBO → blit texture to surface → eglSwapBuffers
 *   HW cores (shared ctx):  core → window surface FBO 0 → eglSwapBuffers
 *   SW cores:               core → pixel buffer → texture upload → blit → eglSwapBuffers
 *
 * No glReadPixels, no Bitmap transfer, no CPU pixel copy in the hot path.
 * Falls back to PBuffer + getFrameBuffer() if no SurfaceView is attached.
 */'''
code = code.replace(old_comment, new_comment, 1)

# ─── 2. Add new globals after g_shared_context_active ─────────────
old_shared = '''static bool g_shared_context_active = false;

static bool init_egl('''
new_shared = '''static bool g_shared_context_active = false;

/* ═══════════════════════════════════════════════════════════════════
 *  WINDOW SURFACE — SurfaceView direct rendering (no readback)
 * ═══════════════════════════════════════════════════════════════════ */
static ANativeWindow *g_native_window = NULL;
static bool g_use_window_surface = false;
static unsigned g_surface_width  = 0;
static unsigned g_surface_height = 0;

/* Blit resources: shader + fullscreen quad for presenting to surface */
static GLuint g_blit_program   = 0;
static GLuint g_blit_vao       = 0;
static GLuint g_blit_vbo       = 0;
static GLint  g_blit_flip_loc  = -1;
static GLint  g_blit_swiz_loc  = -1;

/* SW rendering: texture for uploading CPU pixel data to GPU */
static GLuint   g_sw_texture = 0;
static unsigned g_sw_tex_w   = 0;
static unsigned g_sw_tex_h   = 0;

/* Last HW frame dimensions reported by video_refresh_cb */
static unsigned g_hw_last_w = 0;
static unsigned g_hw_last_h = 0;

static bool init_egl('''
code = code.replace(old_shared, new_shared, 1)

# ─── 3. Rewrite init_egl to support window surface ────────────────
# Find and replace the entire init_egl function
init_egl_start = code.index("static bool init_egl(unsigned context_type, unsigned major, unsigned minor) {")
# Find end: next function (ensure_pbuffer_size)
init_egl_end = code.index("\n/* Resize PBuffer surface")
old_init_egl = code[init_egl_start:init_egl_end]

new_init_egl = '''static bool init_egl(unsigned context_type, unsigned major, unsigned minor) {
    g_egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_egl_display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }
    if (!eglInitialize(g_egl_display, NULL, NULL)) {
        LOGE("eglInitialize failed: 0x%x", eglGetError());
        return false;
    }

    /* Always request GLES3 with fallback to GLES2. */
    int gles_version = 3;

    /* Request a config that supports both window + pbuffer surfaces.
     * Window for the SurfaceView, PBuffer for the shared context. */
    EGLint surface_bits = g_native_window
        ? (EGL_WINDOW_BIT | EGL_PBUFFER_BIT)
        : EGL_PBUFFER_BIT;

    EGLint config_attribs[] = {
        EGL_SURFACE_TYPE,    surface_bits,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
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
        LOGW("GLES3 config not found, falling back to GLES2");
        gles_version = 2;
        config_attribs[3] = EGL_OPENGL_ES2_BIT;
        if (!eglChooseConfig(g_egl_display, config_attribs, &g_egl_config, 1, &num_configs) ||
            num_configs == 0) {
            LOGE("eglChooseConfig failed: 0x%x", eglGetError());
            return false;
        }
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

    /* Create main surface: prefer window surface from SurfaceView,
     * fall back to PBuffer when no surface is attached. */
    g_use_window_surface = false;
    if (g_native_window) {
        g_egl_surface = eglCreateWindowSurface(g_egl_display, g_egl_config,
                                                g_native_window, NULL);
        if (g_egl_surface != EGL_NO_SURFACE) {
            g_use_window_surface = true;
            EGLint sw = 0, sh = 0;
            eglQuerySurface(g_egl_display, g_egl_surface, EGL_WIDTH, &sw);
            eglQuerySurface(g_egl_display, g_egl_surface, EGL_HEIGHT, &sh);
            g_surface_width  = (unsigned)sw;
            g_surface_height = (unsigned)sh;
            LOGI("Window surface created: %ux%u", g_surface_width, g_surface_height);
        } else {
            LOGW("eglCreateWindowSurface failed: 0x%x — falling back to PBuffer", eglGetError());
        }
    }

    if (!g_use_window_surface) {
        unsigned pbw = 640, pbh = 480;
        EGLint pbuf_attribs[] = { EGL_WIDTH, (EGLint)pbw, EGL_HEIGHT, (EGLint)pbh, EGL_NONE };
        g_egl_surface = eglCreatePbufferSurface(g_egl_display, g_egl_config, pbuf_attribs);
        if (g_egl_surface == EGL_NO_SURFACE) {
            LOGE("eglCreatePbufferSurface failed: 0x%x", eglGetError());
            return false;
        }
        g_pbuf_width = pbw;
        g_pbuf_height = pbh;
    }

    if (!eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, g_egl_context)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }

    /* Disable vsync blocking — frame pacing handled by Kotlin side */
    if (g_use_window_surface) {
        eglSwapInterval(g_egl_display, 0);
    }

    g_egl_bound_to_emu_thread = false;
    g_first_hw_frame = true;

    /* Shared EGL context for cores with internal render threads (e.g. PPSSPP) */
    EGLint shared_ctx_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, gles_version,
        EGL_NONE
    };
    g_egl_shared_context = eglCreateContext(g_egl_display, g_egl_config,
                                             g_egl_context, shared_ctx_attribs);
    if (g_egl_shared_context == EGL_NO_CONTEXT) {
        LOGW("Shared EGL context failed: 0x%x", eglGetError());
    } else {
        EGLint shared_pbuf[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        g_egl_shared_surface = eglCreatePbufferSurface(g_egl_display, g_egl_config, shared_pbuf);
        if (g_egl_shared_surface == EGL_NO_SURFACE) {
            LOGW("Shared PBuffer failed: 0x%x", eglGetError());
            eglDestroyContext(g_egl_display, g_egl_shared_context);
            g_egl_shared_context = EGL_NO_CONTEXT;
        } else {
            LOGI("Shared EGL context created OK");
        }
    }

    LOGI("EGL init OK: GLES%d.0, windowSurface=%s, surface=%ux%u, shared=%s",
         gles_version,
         g_use_window_surface ? "yes" : "no (PBuffer)",
         g_use_window_surface ? g_surface_width : g_pbuf_width,
         g_use_window_surface ? g_surface_height : g_pbuf_height,
         g_egl_shared_context != EGL_NO_CONTEXT ? "yes" : "no");
    return true;
}
'''
code = code.replace(old_init_egl, new_init_egl, 1)

# ─── 4. Add window surface guard to ensure_pbuffer_size ───────────
code = code.replace(
    "static void ensure_pbuffer_size(unsigned w, unsigned h) {\n"
    "    if (w <= g_pbuf_width && h <= g_pbuf_height) return;\n",
    "static void ensure_pbuffer_size(unsigned w, unsigned h) {\n"
    "    if (g_use_window_surface) return; /* Window surface doesn't resize */\n"
    "    if (w <= g_pbuf_width && h <= g_pbuf_height) return;\n",
    1
)

# ─── 5. Enhance destroy_egl to clean up blit + window resources ──
old_destroy = '''static void destroy_egl(void) {
    if (g_egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_egl_shared_surface != EGL_NO_SURFACE) {
            eglDestroySurface(g_egl_display, g_egl_shared_surface);
            g_egl_shared_surface = EGL_NO_SURFACE;
        }
        if (g_egl_shared_context != EGL_NO_CONTEXT) {
            eglDestroyContext(g_egl_display, g_egl_shared_context);
            g_egl_shared_context = EGL_NO_CONTEXT;
        }
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
}'''
new_destroy = '''static void destroy_blit_resources(void);  /* forward declaration */

static void destroy_egl(void) {
    if (g_egl_display != EGL_NO_DISPLAY) {
        destroy_blit_resources();
        eglMakeCurrent(g_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_egl_shared_surface != EGL_NO_SURFACE) {
            eglDestroySurface(g_egl_display, g_egl_shared_surface);
            g_egl_shared_surface = EGL_NO_SURFACE;
        }
        if (g_egl_shared_context != EGL_NO_CONTEXT) {
            eglDestroyContext(g_egl_display, g_egl_shared_context);
            g_egl_shared_context = EGL_NO_CONTEXT;
        }
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
    g_use_window_surface = false;
    g_surface_width = 0;
    g_surface_height = 0;
}'''
code = code.replace(old_destroy, new_destroy, 1)

# ─── 6. Replace g_tmp_row + hw_readback_frame with BLIT infra ────
# Find the section from g_tmp_row declaration to end of hw_readback_frame
old_readback_start = "/* Persistent row buffer for vertical flip"
old_readback_end_marker = "/* ── Libretro callbacks"
i_start = code.index(old_readback_start)
i_end = code.index(old_readback_end_marker)
old_readback = code[i_start:i_end]

new_blit_infra = '''/* ═══════════════════════════════════════════════════════════════════
 *  BLIT INFRASTRUCTURE — present textures to window surface
 *  Used for both HW (FBO texture → screen) and SW (CPU pixels → screen).
 * ═══════════════════════════════════════════════════════════════════ */
static const char *blit_vert_src =
    "#version 300 es\\n"
    "in vec2 aPos;\\n"
    "in vec2 aTC;\\n"
    "out vec2 vTC;\\n"
    "uniform float uFlipY;\\n"
    "void main() {\\n"
    "    gl_Position = vec4(aPos, 0.0, 1.0);\\n"
    "    vec2 tc = aTC;\\n"
    "    if (uFlipY > 0.5) tc.y = 1.0 - tc.y;\\n"
    "    vTC = tc;\\n"
    "}\\n";

static const char *blit_frag_src =
    "#version 300 es\\n"
    "precision mediump float;\\n"
    "in vec2 vTC;\\n"
    "out vec4 fragColor;\\n"
    "uniform sampler2D uTex;\\n"
    "uniform int uSwizzle;\\n"
    "void main() {\\n"
    "    vec4 c = texture(uTex, vTC);\\n"
    "    fragColor = (uSwizzle == 1) ? c.bgra : c;\\n"
    "}\\n";

static GLuint compile_shader(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        LOGE("Shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static bool init_blit_resources(void) {
    if (g_blit_program) return true;

    GLuint vs = compile_shader(GL_VERTEX_SHADER, blit_vert_src);
    GLuint fs = compile_shader(GL_FRAGMENT_SHADER, blit_frag_src);
    if (!vs || !fs) { glDeleteShader(vs); glDeleteShader(fs); return false; }

    g_blit_program = glCreateProgram();
    glAttachShader(g_blit_program, vs);
    glAttachShader(g_blit_program, fs);
    glLinkProgram(g_blit_program);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint ok;
    glGetProgramiv(g_blit_program, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetProgramInfoLog(g_blit_program, sizeof(log), NULL, log);
        LOGE("Blit program link failed: %s", log);
        glDeleteProgram(g_blit_program);
        g_blit_program = 0;
        return false;
    }

    g_blit_flip_loc = glGetUniformLocation(g_blit_program, "uFlipY");
    g_blit_swiz_loc = glGetUniformLocation(g_blit_program, "uSwizzle");

    /* Fullscreen quad: position (x,y) + texcoord (u,v) */
    static const float quad[] = {
        -1.f, -1.f,  0.f, 0.f,
         1.f, -1.f,  1.f, 0.f,
        -1.f,  1.f,  0.f, 1.f,
         1.f,  1.f,  1.f, 1.f,
    };

    glGenVertexArrays(1, &g_blit_vao);
    glGenBuffers(1, &g_blit_vbo);
    glBindVertexArray(g_blit_vao);
    glBindBuffer(GL_ARRAY_BUFFER, g_blit_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);

    GLint pos_loc = glGetAttribLocation(g_blit_program, "aPos");
    GLint tc_loc  = glGetAttribLocation(g_blit_program, "aTC");
    glEnableVertexAttribArray(pos_loc);
    glVertexAttribPointer(pos_loc, 2, GL_FLOAT, GL_FALSE, 16, (void *)0);
    glEnableVertexAttribArray(tc_loc);
    glVertexAttribPointer(tc_loc, 2, GL_FLOAT, GL_FALSE, 16, (void *)8);

    glBindVertexArray(0);
    LOGI("Blit resources initialised (program=%u, vao=%u)", g_blit_program, g_blit_vao);
    return true;
}

static void destroy_blit_resources(void) {
    if (g_blit_vao) { glDeleteVertexArrays(1, &g_blit_vao); g_blit_vao = 0; }
    if (g_blit_vbo) { glDeleteBuffers(1, &g_blit_vbo); g_blit_vbo = 0; }
    if (g_blit_program) { glDeleteProgram(g_blit_program); g_blit_program = 0; }
    if (g_sw_texture) { glDeleteTextures(1, &g_sw_texture); g_sw_texture = 0; }
    g_sw_tex_w = 0;
    g_sw_tex_h = 0;
}

/* Blit a texture to the window surface with aspect-ratio-correct viewport. */
static void blit_to_screen(GLuint texture, unsigned frame_w, unsigned frame_h,
                           bool flip_y, bool swizzle_bgra) {
    if (!g_blit_program || !texture || !g_surface_width || !g_surface_height ||
        frame_w == 0 || frame_h == 0)
        return;

    /* Save GL state that cores may have changed */
    GLint prev_pgm, prev_fbo, prev_vao;
    glGetIntegerv(GL_CURRENT_PROGRAM, &prev_pgm);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prev_vao);
    GLboolean depth_on   = glIsEnabled(GL_DEPTH_TEST);
    GLboolean blend_on   = glIsEnabled(GL_BLEND);
    GLboolean cull_on    = glIsEnabled(GL_CULL_FACE);
    GLboolean scissor_on = glIsEnabled(GL_SCISSOR_TEST);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    /* Aspect-ratio-correct viewport */
    float fa = (float)frame_w / (float)frame_h;
    float sa = (float)g_surface_width / (float)g_surface_height;
    int vp_x, vp_y, vp_w, vp_h;
    if (fa > sa) {
        vp_w = (int)g_surface_width;
        vp_h = (int)((float)g_surface_width / fa);
        vp_x = 0;
        vp_y = ((int)g_surface_height - vp_h) / 2;
    } else {
        vp_h = (int)g_surface_height;
        vp_w = (int)((float)g_surface_height * fa);
        vp_x = ((int)g_surface_width - vp_w) / 2;
        vp_y = 0;
    }

    /* Clear full surface to black (letterbox / pillarbox) */
    glViewport(0, 0, (GLsizei)g_surface_width, (GLsizei)g_surface_height);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    glViewport(vp_x, vp_y, vp_w, vp_h);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);

    glUseProgram(g_blit_program);
    glUniform1f(g_blit_flip_loc, flip_y ? 1.0f : 0.0f);
    glUniform1i(g_blit_swiz_loc, swizzle_bgra ? 1 : 0);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);

    glBindVertexArray(g_blit_vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    /* Restore GL state */
    glUseProgram(prev_pgm);
    glBindFramebuffer(GL_FRAMEBUFFER, prev_fbo);
    glBindVertexArray(prev_vao);
    if (depth_on)   glEnable(GL_DEPTH_TEST);
    if (blend_on)   glEnable(GL_BLEND);
    if (cull_on)    glEnable(GL_CULL_FACE);
    if (scissor_on) glEnable(GL_SCISSOR_TEST);
}

static void ensure_sw_texture(unsigned w, unsigned h) {
    if (g_sw_texture && g_sw_tex_w == w && g_sw_tex_h == h) return;
    if (!g_sw_texture) glGenTextures(1, &g_sw_texture);
    glBindTexture(GL_TEXTURE_2D, g_sw_texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    g_sw_tex_w = w;
    g_sw_tex_h = h;
    LOGI("SW texture created: %ux%u", w, h);
}

/* Legacy readback for PBuffer fallback + screenshots */
static uint32_t *g_tmp_row     = NULL;
static unsigned   g_tmp_row_cap = 0;

static void hw_readback_frame(unsigned w, unsigned h) {
    if (w == 0 || h == 0) return;
    if (!g_hw_fbo && !g_shared_context_active) return;

    GLuint read_fbo;
    if (g_shared_context_active) {
        if (g_egl_display != EGL_NO_DISPLAY)
            eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, g_egl_context);
        read_fbo = 0;
        if (g_pbuf_width > 0 && w > g_pbuf_width) w = g_pbuf_width;
        if (g_pbuf_height > 0 && h > g_pbuf_height) h = g_pbuf_height;
    } else {
        read_fbo = g_hw_fbo;
        if (g_hw_fbo_width > 0 && w > g_hw_fbo_width) w = g_hw_fbo_width;
        if (g_hw_fbo_height > 0 && h > g_hw_fbo_height) h = g_hw_fbo_height;
    }

    size_t needed = (size_t)w * h;
    if (g_frame_width != w || g_frame_height != h) {
        free(g_frame_buf);
        g_frame_buf = (uint32_t *)malloc(needed * 4);
        if (!g_frame_buf) { g_frame_width = 0; g_frame_height = 0; return; }
        g_frame_width = w;
        g_frame_height = h;
    }
    if (g_tmp_row_cap < w) {
        free(g_tmp_row);
        g_tmp_row = (uint32_t *)malloc(w * 4);
        g_tmp_row_cap = g_tmp_row ? w : 0;
    }

    glFinish();
    glBindFramebuffer(GL_FRAMEBUFFER, read_fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glPixelStorei(GL_PACK_ROW_LENGTH, 0);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, g_frame_buf);

    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    if (g_hw_render.bottom_left_origin && g_tmp_row) {
        for (unsigned y = 0; y < h / 2; y++) {
            uint32_t *t = g_frame_buf + y * w;
            uint32_t *b = g_frame_buf + (h - 1 - y) * w;
            memcpy(g_tmp_row, t, w * 4);
            memcpy(t, b, w * 4);
            memcpy(b, g_tmp_row, w * 4);
        }
    }
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

'''
code = code.replace(old_readback, new_blit_infra, 1)

# ─── 7. Modify video_refresh_cb ───────────────────────────────────
old_video = '''static void video_refresh_cb(const void *data, unsigned width,
                              unsigned height, size_t pitch) {
    if (g_hw_render_enabled) {
        /* Hardware rendering: the core drew into our FBO. */
        if (data == (void *)(uintptr_t)RETRO_HW_FRAME_BUFFER_VALID) {
            /* Core rendered a new frame into the FBO — read it back. */
            if (width > 0 && height > 0)
                hw_readback_frame(width, height);
        } else if (data == NULL) {
            /* Frame duping — reuse the last readback if available,
             * otherwise do a fresh readback from the (unchanged) FBO. */
            if (!g_frame_ready && g_hw_fbo_width > 0 && g_hw_fbo_height > 0)
                hw_readback_frame(g_hw_fbo_width, g_hw_fbo_height);
            else
                g_frame_ready = true; /* keep previous frame */
        } else {
            /* Some HW cores may fall back to software for certain frames
             * (menus, loading screens, etc.) — handle the pixel data. */
            LOGI("HW core sent software frame %ux%u — processing as SW", width, height);
            goto sw_path;
        }
        return;
    }'''

new_video = '''static void video_refresh_cb(const void *data, unsigned width,
                              unsigned height, size_t pitch) {
    if (g_hw_render_enabled) {
        /* Hardware rendering path.
         * IMPORTANT: Do NOT perform GL operations here when shared-context is
         * active — this callback may be invoked from the core's internal render
         * thread, not the emulation thread.  Just set flags; presentation
         * happens in runFrame after core_run returns on the emu thread. */
        if (data == (void *)(uintptr_t)RETRO_HW_FRAME_BUFFER_VALID) {
            g_hw_last_w = width;
            g_hw_last_h = height;
            g_frame_ready = true;
        } else if (data == NULL) {
            g_frame_ready = true; /* frame dupe — reuse previous */
        } else {
            goto sw_path;
        }
        return;
    }'''
code = code.replace(old_video, new_video, 1)

# ─── 8. Rewrite runFrame ──────────────────────────────────────────
old_run_start = '''JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_runFrame(
    JNIEnv *env, jobject thiz)
{'''
old_run_end_marker = "\nJNIEXPORT jintArray"
i_run_start = code.index(old_run_start)
i_run_end = code.index(old_run_end_marker, i_run_start)
old_run = code[i_run_start:i_run_end]

new_run = '''JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_runFrame(
    JNIEnv *env, jobject thiz)
{
    if (!core_run) return;

    /* Frame skip */
    bool skip_render = false;
    if (g_frameskip > 0) {
        g_frame_counter++;
        if ((g_frame_counter % (g_frameskip + 1)) != 0)
            skip_render = true;
    }

    /* ── EGL context binding on emulation thread ─────────────────── */
    if (g_hw_render_enabled && g_egl_display != EGL_NO_DISPLAY) {
        if (!g_egl_bound_to_emu_thread) {
            if (!eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, g_egl_context)) {
                LOGE("eglMakeCurrent failed in runFrame: 0x%x", eglGetError());
                destroy_egl();
                if (init_egl(g_hw_render.context_type,
                             g_hw_render.version_major,
                             g_hw_render.version_minor)) {
                    unsigned fw = g_hw_desired_fbo_w > 0 ? g_hw_desired_fbo_w : 640;
                    unsigned fh = g_hw_desired_fbo_h > 0 ? g_hw_desired_fbo_h : 480;
                    g_hw_fbo = 0;
                    g_hw_fbo_width = 0;
                    g_hw_fbo_height = 0;
                    ensure_hw_fbo(fw, fh);
                    init_blit_resources();
                    if (g_hw_render.context_reset) g_hw_render.context_reset();
                    g_egl_bound_to_emu_thread = true;
                } else {
                    g_hw_render_enabled = false;
                }
            } else {
                g_egl_bound_to_emu_thread = true;

                /* First frame: create FBO, init blit, context_reset */
                if (g_first_hw_frame) {
                    g_first_hw_frame = false;
                    LOGI("First HW frame — setting up FBO + blit + context_reset");
                    unsigned fw = g_hw_desired_fbo_w > 0 ? g_hw_desired_fbo_w : 640;
                    unsigned fh = g_hw_desired_fbo_h > 0 ? g_hw_desired_fbo_h : 480;
                    ensure_hw_fbo(fw, fh);
                    init_blit_resources();
                    if (g_hw_render.context_reset) {
                        g_hw_render.context_reset();
                        LOGI("context_reset invoked on emu thread (FBO=%u, %ux%u)",
                             g_hw_fbo, fw, fh);
                    }
                }
            }
        }
    }

    /* ── SW-only EGL init (for window surface blit) ──────────────── */
    if (!g_hw_render_enabled && g_native_window && g_egl_display == EGL_NO_DISPLAY) {
        if (init_egl(RETRO_HW_CONTEXT_OPENGLES2, 2, 0)) {
            init_blit_resources();
            LOGI("SW-mode EGL + blit initialised for window surface");
        }
    }

    /* ── Run one frame ───────────────────────────────────────────── */
    g_frame_ready = false;
    g_audio_write_pos = 0;
    core_run();

    /* Re-bind EGL after core_run — shared-context cores (PPSSPP)
     * may have stolen our surface on their internal render thread. */
    if (g_shared_context_active && g_egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, g_egl_context);
    }

    if (skip_render) {
        g_frame_ready = false;
        return;
    }

    /* ── Present frame to window surface ─────────────────────────── */
    if (g_use_window_surface && g_egl_display != EGL_NO_DISPLAY) {
        if (g_hw_render_enabled) {
            if (g_shared_context_active) {
                /* PPSSPP rendered directly to window surface (FBO 0).
                 * Just swap to present the frame. */
                eglSwapBuffers(g_egl_display, g_egl_surface);
                g_frame_ready = true;
            } else if (g_frame_ready || g_hw_fbo_width > 0) {
                /* Blit the FBO's color texture to the window surface */
                unsigned fw = g_hw_last_w > 0 ? g_hw_last_w : g_hw_fbo_width;
                unsigned fh = g_hw_last_h > 0 ? g_hw_last_h : g_hw_fbo_height;
                if (fw > 0 && fh > 0) {
                    bool flip_y = !g_hw_render.bottom_left_origin;
                    blit_to_screen(g_hw_color_tex, fw, fh, flip_y, false);
                    eglSwapBuffers(g_egl_display, g_egl_surface);
                    g_frame_ready = true;
                }
            }
        } else if (g_frame_ready && g_frame_buf &&
                   g_frame_width > 0 && g_frame_height > 0) {
            /* SW core: upload pixel buffer to texture and blit */
            ensure_sw_texture(g_frame_width, g_frame_height);
            glBindTexture(GL_TEXTURE_2D, g_sw_texture);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                            g_frame_width, g_frame_height,
                            GL_RGBA, GL_UNSIGNED_BYTE, g_frame_buf);
            blit_to_screen(g_sw_texture, g_frame_width, g_frame_height,
                           true, true);
            eglSwapBuffers(g_egl_display, g_egl_surface);
        }
    } else if (!g_use_window_surface && g_hw_render_enabled && !g_frame_ready) {
        /* PBuffer fallback: readback for getFrameBuffer compatibility */
        unsigned rw = g_hw_fbo_width, rh = g_hw_fbo_height;
        if (g_shared_context_active && rw == 0 && rh == 0) {
            rw = g_pbuf_width;
            rh = g_pbuf_height;
        }
        if (rw > 0 && rh > 0)
            hw_readback_frame(rw, rh);
    }
}
'''
code = code.replace(old_run, new_run, 1)

# ─── 9. Modify loadGame — defer EGL to runFrame, but keep compatibility ──
# The current loadGame calls init_egl + defers FBO to emu thread.
# Keep init_egl call IF a surface is available (so first runFrame has EGL ready).
# If no surface yet, init_egl will happen on the first runFrame.
old_load_hw = '''        if (g_hw_render_enabled) {
            LOGI("Initialising EGL context (FBO + context_reset deferred to emu thread)...");
            if (!init_egl(g_hw_render.context_type,
                          g_hw_render.version_major,
                          g_hw_render.version_minor)) {
                LOGE("EGL init failed — falling back to software");
                g_hw_render_enabled = false;
            } else {
                /* Save desired FBO dimensions for emulation thread */
                g_hw_desired_fbo_w = av.geometry.max_width > 0 ?
                    av.geometry.max_width : av.geometry.base_width;
                g_hw_desired_fbo_h = av.geometry.max_height > 0 ?
                    av.geometry.max_height : av.geometry.base_height;
                if (g_hw_desired_fbo_w == 0) g_hw_desired_fbo_w = 640;
                if (g_hw_desired_fbo_h == 0) g_hw_desired_fbo_h = 480;

                LOGI("EGL ready, desired FBO: %ux%u", g_hw_desired_fbo_w, g_hw_desired_fbo_h);

                /* Release EGL from this (IO) thread; the emulation thread will
                 * acquire it, create the FBO, and call context_reset. */
                eglMakeCurrent(g_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            }
        }'''

new_load_hw = '''        if (g_hw_render_enabled) {
            /* Save desired FBO dimensions. */
            g_hw_desired_fbo_w = av.geometry.max_width > 0 ?
                av.geometry.max_width : av.geometry.base_width;
            g_hw_desired_fbo_h = av.geometry.max_height > 0 ?
                av.geometry.max_height : av.geometry.base_height;
            if (g_hw_desired_fbo_w == 0) g_hw_desired_fbo_w = 640;
            if (g_hw_desired_fbo_h == 0) g_hw_desired_fbo_h = 480;

            /* Init EGL now so the context exists before the emu thread starts.
             * The window surface (if available) is used; otherwise PBuffer. */
            LOGI("Initialising EGL (FBO + context_reset deferred to emu thread)...");
            if (!init_egl(g_hw_render.context_type,
                          g_hw_render.version_major,
                          g_hw_render.version_minor)) {
                LOGE("EGL init failed — falling back to software");
                g_hw_render_enabled = false;
            } else {
                LOGI("EGL ready, desired FBO: %ux%u, windowSurface=%s",
                     g_hw_desired_fbo_w, g_hw_desired_fbo_h,
                     g_use_window_surface ? "yes" : "no");
                /* Release from IO thread; emu thread acquires in runFrame. */
                eglMakeCurrent(g_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            }
        }'''
code = code.replace(old_load_hw, new_load_hw, 1)

# ─── 10. Modify unloadGame — clean up new resources ───────────────
old_unload_cleanup = '''    destroy_egl();
    g_hw_render_enabled = false;
    g_egl_bound_to_emu_thread = false;
    g_first_hw_frame = true;
    g_shared_context_active = false;
    g_pbuf_width = 0;
    g_pbuf_height = 0;
    g_hw_desired_fbo_w = 0;
    g_hw_desired_fbo_h = 0;

    free(g_frame_buf);
    g_frame_buf = NULL;
    g_frame_width = 0;
    g_frame_height = 0;
    g_frame_ready = false;

    free(g_tmp_row);
    g_tmp_row = NULL;
    g_tmp_row_cap = 0;

    g_audio_write_pos = 0;
    g_core_option_count = 0;'''

new_unload_cleanup = '''    destroy_egl();
    g_hw_render_enabled = false;
    g_egl_bound_to_emu_thread = false;
    g_first_hw_frame = true;
    g_shared_context_active = false;
    g_use_window_surface = false;
    g_surface_width = 0;
    g_surface_height = 0;
    g_pbuf_width = 0;
    g_pbuf_height = 0;
    g_hw_desired_fbo_w = 0;
    g_hw_desired_fbo_h = 0;
    g_hw_last_w = 0;
    g_hw_last_h = 0;

    free(g_frame_buf);
    g_frame_buf = NULL;
    g_frame_width = 0;
    g_frame_height = 0;
    g_frame_ready = false;

    free(g_tmp_row);
    g_tmp_row = NULL;
    g_tmp_row_cap = 0;

    g_audio_write_pos = 0;
    g_core_option_count = 0;'''
code = code.replace(old_unload_cleanup, new_unload_cleanup, 1)

# ─── 11. Add setSurface + surfaceChanged JNI ──────────────────────
# Insert before the setFrameSkip/SRAM functions, after unloadGame
sram_marker = "/* ── SRAM save/load"
if sram_marker not in code:
    sram_marker = "JNIEXPORT jboolean JNICALL\nJava_com_vortex_emulator_emulation_VortexNative_saveSRAM"

new_surface_jni = '''
/* ── Window surface management (called from SurfaceView lifecycle) ── */

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_setSurface(
    JNIEnv *env, jobject thiz, jobject surface)
{
    if (g_native_window) {
        ANativeWindow_release(g_native_window);
        g_native_window = NULL;
    }
    if (surface) {
        g_native_window = ANativeWindow_fromSurface(env, surface);
        LOGI("Native window set: %p (%dx%d)", g_native_window,
             g_native_window ? ANativeWindow_getWidth(g_native_window) : 0,
             g_native_window ? ANativeWindow_getHeight(g_native_window) : 0);
    } else {
        LOGI("Native window released");
    }
}

JNIEXPORT void JNICALL
Java_com_vortex_emulator_emulation_VortexNative_surfaceChanged(
    JNIEnv *env, jobject thiz, jint width, jint height)
{
    if (g_use_window_surface && g_egl_display != EGL_NO_DISPLAY) {
        g_surface_width  = (unsigned)width;
        g_surface_height = (unsigned)height;
        LOGI("Surface changed: %ux%u", g_surface_width, g_surface_height);
    }
}

'''

code = code.replace(sram_marker, new_surface_jni + sram_marker, 1)

# ─── Write output ─────────────────────────────────────────────────
with open(SRC, "w") as f:
    f.write(code)

print(f"Rewrite complete. File size: {len(code)} chars, {code.count(chr(10))+1} lines")
