//! VortexEmulator GPU-adaptive Rust frontend — complete libretro frontend.
//!
//! Innovations over C/C++ frontends:
//! - Runtime GPU vendor detection (Mali/Adreno/PowerVR) with vendor-specific workarounds
//! - Lock-free ring buffer for audio (zero mutex on hot path)
//! - Triple-buffered video with atomic frame swap
//! - Memory-safe libretro environment handling
//! - Zero-cost abstractions over raw GL/EGL

#![allow(non_snake_case)]

mod gl;
mod libretro;
pub mod core_catalog;
pub mod core_presets;

use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_uint, c_void};
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicI16, AtomicI32, AtomicU32, AtomicUsize, Ordering};
use std::sync::{Mutex, OnceLock};

// ── Global Atomics for lock-free hot-path callbacks ───────────
// These mirror what C++ does with globals — no mutex needed.
static HW_FBO: AtomicU32 = AtomicU32::new(0);
static HW_FRAME_PRESENTED: AtomicBool = AtomicBool::new(false);
static INPUT_POLL_MODE: AtomicI32 = AtomicI32::new(0); // 0=normal, 1=early, 2=late
// Global audio ring — accessible from audio callbacks without STATE lock
static GLOBAL_AUDIO: OnceLock<AudioRing> = OnceLock::new();

use jni::objects::{JByteArray, JClass, JIntArray, JObject, JShortArray, JString};
use jni::sys::{jboolean, jdouble, jint, jlong, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6};
use jni::JNIEnv;

// ── Lock-free Global Input State ──────────────────────────────
// Separate from STATE mutex so input writes from UI thread never
// contend with core callbacks during retro_run().
// Using raw AtomicI16 arrays with UnsafeCell-free access via pointers.

struct AtomicInputState {
    // Flat arrays: joypad[port * MAX_BUTTONS + btn], analog[port * 4 + index * 2 + axis]
    joypad: [AtomicI16; 64],  // MAX_PORTS(4) * MAX_BUTTONS(16)
    analog: [AtomicI16; 16],  // MAX_PORTS(4) * 2 indices * 2 axes
    pointer_x: AtomicI16,
    pointer_y: AtomicI16,
    pointer_pressed: AtomicBool,
}

// Safety: all fields are atomic
unsafe impl Send for AtomicInputState {}
unsafe impl Sync for AtomicInputState {}

macro_rules! atomic_i16_array {
    ($n:expr) => {{
        // Initialize array of AtomicI16 at compile time
        const ZERO: AtomicI16 = AtomicI16::new(0);
        [ZERO; $n]
    }};
}

static INPUT: AtomicInputState = AtomicInputState {
    joypad: atomic_i16_array!(64),
    analog: atomic_i16_array!(16),
    pointer_x: AtomicI16::new(0),
    pointer_y: AtomicI16::new(0),
    pointer_pressed: AtomicBool::new(false),
};

// ── GPU Vendor Detection ──────────────────────────────────────
#[derive(Debug, Clone, Copy, PartialEq)]
enum GpuVendor {
    Mali,
    Adreno,
    PowerVR,
    Unknown,
}

fn detect_gpu_vendor() -> GpuVendor {
    unsafe {
        let renderer = gl::glGetString(gl::GL_RENDERER);
        if renderer.is_null() {
            return GpuVendor::Unknown;
        }
        let s = CStr::from_ptr(renderer as *const c_char).to_string_lossy();
        let lower = s.to_lowercase();
        if lower.contains("mali") || lower.contains("immortalis") || lower.contains("bifrost") || lower.contains("valhall") {
            GpuVendor::Mali
        } else if lower.contains("adreno") {
            GpuVendor::Adreno
        } else if lower.contains("powervr") || lower.contains("rogue") {
            GpuVendor::PowerVR
        } else {
            GpuVendor::Unknown
        }
    }
}

// ── Lock-Free Audio Ring Buffer ───────────────────────────────
const AUDIO_RING_SIZE: usize = 32768; // must be power of 2
const AUDIO_RING_MASK: usize = AUDIO_RING_SIZE - 1;

struct AudioRing {
    buf: Box<[i16; AUDIO_RING_SIZE]>,
    write: AtomicUsize,
    read: AtomicUsize,
}

impl AudioRing {
    fn new() -> Self {
        Self {
            buf: Box::new([0i16; AUDIO_RING_SIZE]),
            write: AtomicUsize::new(0),
            read: AtomicUsize::new(0),
        }
    }

    fn push_sample(&self, left: i16, right: i16) {
        let w = self.write.load(Ordering::Relaxed);
        let r = self.read.load(Ordering::Acquire);
        if (w.wrapping_sub(r)) >= AUDIO_RING_SIZE - 2 {
            return; // full, drop
        }
        // Safety: we only write at positions we own
        let buf = self.buf.as_ptr() as *mut i16;
        unsafe {
            *buf.add(w & AUDIO_RING_MASK) = left;
            *buf.add((w + 1) & AUDIO_RING_MASK) = right;
        }
        self.write.store(w.wrapping_add(2), Ordering::Release);
    }

    fn push_batch(&self, data: *const i16, frames: usize) {
        let samples = frames * 2;
        let w = self.write.load(Ordering::Relaxed);
        let r = self.read.load(Ordering::Acquire);
        let avail = AUDIO_RING_SIZE - w.wrapping_sub(r);
        let to_copy = samples.min(avail);
        let buf = self.buf.as_ptr() as *mut i16;
        for i in 0..to_copy {
            unsafe {
                *buf.add((w + i) & AUDIO_RING_MASK) = *data.add(i);
            }
        }
        self.write.store(w.wrapping_add(to_copy), Ordering::Release);
    }

    fn drain(&self, out: &mut Vec<i16>) {
        let w = self.write.load(Ordering::Acquire);
        let r = self.read.load(Ordering::Relaxed);
        let count = w.wrapping_sub(r);
        if count == 0 {
            out.clear();
            return;
        }
        out.clear();
        out.reserve(count);
        let buf = self.buf.as_ptr();
        for i in 0..count {
            unsafe { out.push(*buf.add((r + i) & AUDIO_RING_MASK)); }
        }
        self.read.store(w, Ordering::Release);
    }

    fn reset(&self) {
        self.write.store(0, Ordering::SeqCst);
        self.read.store(0, Ordering::SeqCst);
    }
}

// Safety: the ring buffer uses atomics for synchronization
unsafe impl Send for AudioRing {}
unsafe impl Sync for AudioRing {}

// ── Core Function Pointers ────────────────────────────────────
struct CoreFuncs {
    handle: *mut c_void,
    init: unsafe extern "C" fn(),
    deinit: unsafe extern "C" fn(),
    api_version: unsafe extern "C" fn() -> c_uint,
    get_system_info: unsafe extern "C" fn(*mut libretro::retro_system_info),
    get_system_av_info: unsafe extern "C" fn(*mut libretro::retro_system_av_info),
    set_environment: unsafe extern "C" fn(libretro::retro_environment_t),
    set_video_refresh: unsafe extern "C" fn(libretro::retro_video_refresh_t),
    set_audio_sample: unsafe extern "C" fn(libretro::retro_audio_sample_t),
    set_audio_sample_batch: unsafe extern "C" fn(libretro::retro_audio_sample_batch_t),
    set_input_poll: unsafe extern "C" fn(libretro::retro_input_poll_t),
    set_input_state: unsafe extern "C" fn(libretro::retro_input_state_t),
    load_game: unsafe extern "C" fn(*const libretro::retro_game_info) -> bool,
    unload_game: unsafe extern "C" fn(),
    run: unsafe extern "C" fn(),
    reset: unsafe extern "C" fn(),
    // Optional
    serialize_size: Option<unsafe extern "C" fn() -> usize>,
    serialize: Option<unsafe extern "C" fn(*mut c_void, usize) -> bool>,
    unserialize: Option<unsafe extern "C" fn(*const c_void, usize) -> bool>,
    set_controller_port: Option<unsafe extern "C" fn(c_uint, c_uint)>,
    get_memory_data: Option<unsafe extern "C" fn(c_uint) -> *mut c_void>,
    get_memory_size: Option<unsafe extern "C" fn(c_uint) -> usize>,
}

unsafe impl Send for CoreFuncs {}
unsafe impl Sync for CoreFuncs {}

// ── EGL State ─────────────────────────────────────────────────
struct EglState {
    display: gl::EGLDisplay,
    context: gl::EGLContext,
    surface: gl::EGLSurface,
    config: gl::EGLConfig,
}

impl EglState {
    fn none() -> Self {
        Self {
            display: gl::EGL_NO_DISPLAY,
            context: gl::EGL_NO_CONTEXT,
            surface: gl::EGL_NO_SURFACE,
            config: ptr::null_mut(),
        }
    }

    fn valid(&self) -> bool {
        self.display != gl::EGL_NO_DISPLAY && self.context != gl::EGL_NO_CONTEXT
    }

    fn make_current(&self) {
        if self.valid() {
            let s = if self.surface != gl::EGL_NO_SURFACE { self.surface } else { gl::EGL_NO_SURFACE };
            unsafe { gl::eglMakeCurrent(self.display, s, s, self.context); }
        }
    }

    fn swap(&self) {
        if self.display != gl::EGL_NO_DISPLAY && self.surface != gl::EGL_NO_SURFACE {
            unsafe { gl::eglSwapBuffers(self.display, self.surface); }
        }
    }

    fn destroy(&mut self) {
        unsafe {
            if self.display != gl::EGL_NO_DISPLAY {
                gl::eglMakeCurrent(self.display, gl::EGL_NO_SURFACE, gl::EGL_NO_SURFACE, gl::EGL_NO_CONTEXT);
                if self.surface != gl::EGL_NO_SURFACE {
                    gl::eglDestroySurface(self.display, self.surface);
                }
                if self.context != gl::EGL_NO_CONTEXT {
                    gl::eglDestroyContext(self.display, self.context);
                }
                gl::eglTerminate(self.display);
            }
        }
        *self = Self::none();
    }
}

// ── Blit Resources ────────────────────────────────────────────
struct BlitRes {
    vao: gl::GLuint,
    vbo: gl::GLuint,
    texture: gl::GLuint,
    sw_program: gl::GLuint,
    hw_program: gl::GLuint,
}

impl BlitRes {
    fn zeroed() -> Self {
        Self { vao: 0, vbo: 0, texture: 0, sw_program: 0, hw_program: 0 }
    }
}

// ── Global State ──────────────────────────────────────────────
struct FrontendState {
    core: Option<CoreFuncs>,
    egl: EglState,
    blit: BlitRes,
    window: *mut c_void,
    surface_w: i32,
    surface_h: i32,
    // HW rendering
    hw_render: bool,
    hw_cb: libretro::retro_hw_render_callback,
    hw_fbo: gl::GLuint,
    hw_tex: gl::GLuint,
    hw_depth: gl::GLuint,
    hw_fbo_w: u32,
    hw_fbo_h: u32,
    // AV info
    av_info: libretro::retro_system_av_info,
    fps: f64,
    sample_rate: f64,
    pixel_fmt: u32,
    // Frame buffer (SW rendering)
    frame_buf: Vec<u32>,
    frame_w: u32,
    frame_h: u32,
    frame_ready: AtomicBool,
    // Audio
    audio: AudioRing,
    audio_drain: Vec<i16>,
    // Input
    joypad: [[i16; libretro::MAX_BUTTONS]; libretro::MAX_PORTS],
    analog: [[[i16; 2]; 2]; libretro::MAX_PORTS],
    pointer_x: i16,
    pointer_y: i16,
    pointer_pressed: bool,
    // Core options
    options: HashMap<String, CString>,
    overrides: HashMap<String, CString>,  // setCoreOption writes here; always wins over core defaults
    options_updated: AtomicBool,
    // Paths
    system_dir: CString,
    save_dir: CString,
    // Frame control
    frame_skip: AtomicI32,
    frame_skip_counter: i32,
    hw_ctx_reset_pending: AtomicBool,
    hw_frame_presented: AtomicBool,
    debug_frame_count: i32,
    // GPU vendor
    gpu_vendor: GpuVendor,
    // JVM
    jvm: *mut c_void,
}

unsafe impl Send for FrontendState {}
unsafe impl Sync for FrontendState {}

impl FrontendState {
    fn new() -> Self {
        Self {
            core: None,
            egl: EglState::none(),
            blit: BlitRes::zeroed(),
            window: ptr::null_mut(),
            surface_w: 0,
            surface_h: 0,
            hw_render: false,
            hw_cb: libretro::retro_hw_render_callback::default(),
            hw_fbo: 0,
            hw_tex: 0,
            hw_depth: 0,
            hw_fbo_w: 0,
            hw_fbo_h: 0,
            av_info: libretro::retro_system_av_info::default(),
            fps: 60.0,
            sample_rate: 44100.0,
            pixel_fmt: 0,
            frame_buf: Vec::new(),
            frame_w: 0,
            frame_h: 0,
            frame_ready: AtomicBool::new(false),
            audio: AudioRing::new(),
            audio_drain: Vec::new(),
            joypad: [[0; libretro::MAX_BUTTONS]; libretro::MAX_PORTS],
            analog: [[[0; 2]; 2]; libretro::MAX_PORTS],
            pointer_x: 0,
            pointer_y: 0,
            pointer_pressed: false,
            options: HashMap::new(),
            overrides: HashMap::new(),
            options_updated: AtomicBool::new(false),
            system_dir: CString::new("").unwrap(),
            save_dir: CString::new("").unwrap(),
            frame_skip: AtomicI32::new(0),
            frame_skip_counter: 0,
            hw_ctx_reset_pending: AtomicBool::new(false),
            hw_frame_presented: AtomicBool::new(false),
            debug_frame_count: 0,
            gpu_vendor: GpuVendor::Unknown,
            jvm: ptr::null_mut(),
        }
    }
}

static STATE: Mutex<Option<FrontendState>> = Mutex::new(None);

fn with_state<F, R>(f: F) -> R
where
    F: FnOnce(&mut FrontendState) -> R,
{
    let mut guard = STATE.lock().unwrap();
    let state = guard.get_or_insert_with(FrontendState::new);
    f(state)
}

// ── Shader Sources ────────────────────────────────────────────
const VERT_SRC: &str = "#version 300 es\n\
    layout(location=0) in vec2 aPos;\n\
    layout(location=1) in vec2 aUV;\n\
    out vec2 vUV;\n\
    void main() {\n\
        gl_Position = vec4(aPos, 0.0, 1.0);\n\
        vUV = aUV;\n\
    }\n";

const FRAG_SW_SRC: &str = "#version 300 es\n\
    precision mediump float;\n\
    in vec2 vUV;\n\
    out vec4 fragColor;\n\
    uniform sampler2D uTex;\n\
    void main() {\n\
        fragColor = texture(uTex, vUV).bgra;\n\
    }\n";

const FRAG_HW_SRC: &str = "#version 300 es\n\
    precision mediump float;\n\
    in vec2 vUV;\n\
    out vec4 fragColor;\n\
    uniform sampler2D uTex;\n\
    uniform float uFlipY;\n\
    void main() {\n\
        vec2 uv = vUV;\n\
        if (uFlipY > 0.5) uv.y = 1.0 - uv.y;\n\
        fragColor = texture(uTex, uv);\n\
    }\n";

// ── GL Helpers ────────────────────────────────────────────────
unsafe fn compile_shader(shader_type: gl::GLenum, src: &str) -> gl::GLuint {
    let s = gl::glCreateShader(shader_type);
    let c_src = CString::new(src).unwrap();
    let ptr = c_src.as_ptr();
    gl::glShaderSource(s, 1, &ptr, ptr::null());
    gl::glCompileShader(s);
    let mut ok: gl::GLint = 0;
    gl::glGetShaderiv(s, gl::GL_COMPILE_STATUS, &mut ok);
    if ok == 0 {
        let mut log_buf = [0u8; 512];
        gl::glGetShaderInfoLog(s, 512, ptr::null_mut(), log_buf.as_mut_ptr() as *mut gl::GLchar);
        let msg = CStr::from_ptr(log_buf.as_ptr() as *const c_char).to_string_lossy();
        loge!("Shader compile error: {}", msg);
        gl::glDeleteShader(s);
        return 0;
    }
    s
}

unsafe fn link_program(vsrc: &str, fsrc: &str) -> gl::GLuint {
    let vs = compile_shader(gl::GL_VERTEX_SHADER, vsrc);
    let fs = compile_shader(gl::GL_FRAGMENT_SHADER, fsrc);
    if vs == 0 || fs == 0 {
        if vs != 0 { gl::glDeleteShader(vs); }
        if fs != 0 { gl::glDeleteShader(fs); }
        return 0;
    }
    let prog = gl::glCreateProgram();
    gl::glAttachShader(prog, vs);
    gl::glAttachShader(prog, fs);
    gl::glLinkProgram(prog);
    gl::glDeleteShader(vs);
    gl::glDeleteShader(fs);
    let mut ok: gl::GLint = 0;
    gl::glGetProgramiv(prog, gl::GL_LINK_STATUS, &mut ok);
    if ok == 0 {
        let mut log_buf = [0u8; 512];
        gl::glGetProgramInfoLog(prog, 512, ptr::null_mut(), log_buf.as_mut_ptr() as *mut gl::GLchar);
        let msg = CStr::from_ptr(log_buf.as_ptr() as *const c_char).to_string_lossy();
        loge!("Program link error: {}", msg);
        gl::glDeleteProgram(prog);
        return 0;
    }
    prog
}

unsafe fn init_blit(blit: &mut BlitRes) {
    let quad: [f32; 16] = [
        // pos(x,y)   uv(u,v)  — V=1 at bottom, V=0 at top (matches C++ orientation)
        -1.0, -1.0, 0.0, 1.0,
         1.0, -1.0, 1.0, 1.0,
        -1.0,  1.0, 0.0, 0.0,
         1.0,  1.0, 1.0, 0.0,
    ];
    gl::glGenVertexArrays(1, &mut blit.vao);
    gl::glGenBuffers(1, &mut blit.vbo);
    gl::glBindVertexArray(blit.vao);
    gl::glBindBuffer(gl::GL_ARRAY_BUFFER, blit.vbo);
    gl::glBufferData(gl::GL_ARRAY_BUFFER, std::mem::size_of_val(&quad) as isize, quad.as_ptr() as *const c_void, gl::GL_STATIC_DRAW);
    gl::glVertexAttribPointer(0, 2, gl::GL_FLOAT, gl::GL_FALSE, 16, ptr::null());
    gl::glEnableVertexAttribArray(0);
    gl::glVertexAttribPointer(1, 2, gl::GL_FLOAT, gl::GL_FALSE, 16, 8 as *const c_void);
    gl::glEnableVertexAttribArray(1);
    gl::glBindVertexArray(0);

    gl::glGenTextures(1, &mut blit.texture);
    gl::glBindTexture(gl::GL_TEXTURE_2D, blit.texture);
    gl::glTexParameteri(gl::GL_TEXTURE_2D, gl::GL_TEXTURE_MIN_FILTER, gl::GL_LINEAR);
    gl::glTexParameteri(gl::GL_TEXTURE_2D, gl::GL_TEXTURE_MAG_FILTER, gl::GL_LINEAR);
    gl::glTexParameteri(gl::GL_TEXTURE_2D, gl::GL_TEXTURE_WRAP_S, gl::GL_CLAMP_TO_EDGE);
    gl::glTexParameteri(gl::GL_TEXTURE_2D, gl::GL_TEXTURE_WRAP_T, gl::GL_CLAMP_TO_EDGE);
    gl::glBindTexture(gl::GL_TEXTURE_2D, 0);

    blit.sw_program = link_program(VERT_SRC, FRAG_SW_SRC);
    blit.hw_program = link_program(VERT_SRC, FRAG_HW_SRC);
    logi!("Blit init: vao={} vbo={} tex={} sw={} hw={}", blit.vao, blit.vbo, blit.texture, blit.sw_program, blit.hw_program);
}

unsafe fn destroy_blit(blit: &mut BlitRes) {
    if blit.vao != 0 { gl::glDeleteVertexArrays(1, &blit.vao); blit.vao = 0; }
    if blit.vbo != 0 { gl::glDeleteBuffers(1, &blit.vbo); blit.vbo = 0; }
    if blit.texture != 0 { gl::glDeleteTextures(1, &blit.texture); blit.texture = 0; }
    if blit.sw_program != 0 { gl::glDeleteProgram(blit.sw_program); blit.sw_program = 0; }
    if blit.hw_program != 0 { gl::glDeleteProgram(blit.hw_program); blit.hw_program = 0; }
}

// ── EGL Init ──────────────────────────────────────────────────
unsafe fn init_egl(egl: &mut EglState, window: *mut c_void) -> bool {
    egl.destroy();
    egl.display = gl::eglGetDisplay(gl::EGL_DEFAULT_DISPLAY);
    if egl.display == gl::EGL_NO_DISPLAY { loge!("eglGetDisplay failed"); return false; }
    if gl::eglInitialize(egl.display, ptr::null_mut(), ptr::null_mut()) == gl::EGL_FALSE {
        loge!("eglInitialize failed"); egl.destroy(); return false;
    }
    let cfg_attrs: [gl::EGLint; 17] = [
        gl::EGL_RENDERABLE_TYPE, gl::EGL_OPENGL_ES3_BIT,
        gl::EGL_SURFACE_TYPE, gl::EGL_WINDOW_BIT,
        gl::EGL_RED_SIZE, 8, gl::EGL_GREEN_SIZE, 8,
        gl::EGL_BLUE_SIZE, 8, gl::EGL_ALPHA_SIZE, 8,
        gl::EGL_DEPTH_SIZE, 16, gl::EGL_STENCIL_SIZE, 8,
        gl::EGL_NONE,
    ];
    let mut num_configs: gl::EGLint = 0;
    gl::eglChooseConfig(egl.display, cfg_attrs.as_ptr(), &mut egl.config, 1, &mut num_configs);
    if num_configs == 0 { loge!("No EGL config"); egl.destroy(); return false; }

    let ctx_attrs: [gl::EGLint; 3] = [gl::EGL_CONTEXT_CLIENT_VERSION, 3, gl::EGL_NONE];
    egl.context = gl::eglCreateContext(egl.display, egl.config, gl::EGL_NO_CONTEXT, ctx_attrs.as_ptr());
    if egl.context == gl::EGL_NO_CONTEXT { loge!("eglCreateContext failed"); egl.destroy(); return false; }

    egl.surface = gl::eglCreateWindowSurface(egl.display, egl.config, window, ptr::null());
    if egl.surface == gl::EGL_NO_SURFACE { loge!("eglCreateWindowSurface failed"); egl.destroy(); return false; }

    egl.make_current();
    logi!("EGL initialized");
    true
}

// ── HW FBO ────────────────────────────────────────────────────
unsafe fn create_hw_fbo(st: &mut FrontendState, mut w: u32, mut h: u32) {
    if w == 0 || h == 0 {
        if st.surface_w > 0 && st.surface_h > 0 {
            w = st.surface_w as u32; h = st.surface_h as u32;
        } else { return; }
    }
    if st.hw_fbo != 0 { gl::glDeleteFramebuffers(1, &st.hw_fbo); st.hw_fbo = 0; HW_FBO.store(0, Ordering::Release); }
    if st.hw_tex != 0 { gl::glDeleteTextures(1, &st.hw_tex); st.hw_tex = 0; }
    if st.hw_depth != 0 { gl::glDeleteRenderbuffers(1, &st.hw_depth); st.hw_depth = 0; }

    gl::glGenFramebuffers(1, &mut st.hw_fbo);
    gl::glBindFramebuffer(gl::GL_FRAMEBUFFER, st.hw_fbo);

    gl::glGenTextures(1, &mut st.hw_tex);
    gl::glBindTexture(gl::GL_TEXTURE_2D, st.hw_tex);
    gl::glTexImage2D(gl::GL_TEXTURE_2D, 0, gl::GL_RGBA8 as gl::GLint, w as gl::GLsizei, h as gl::GLsizei, 0, gl::GL_RGBA, gl::GL_UNSIGNED_BYTE, ptr::null());
    gl::glTexParameteri(gl::GL_TEXTURE_2D, gl::GL_TEXTURE_MIN_FILTER, gl::GL_LINEAR);
    gl::glTexParameteri(gl::GL_TEXTURE_2D, gl::GL_TEXTURE_MAG_FILTER, gl::GL_LINEAR);
    gl::glTexParameteri(gl::GL_TEXTURE_2D, gl::GL_TEXTURE_WRAP_S, gl::GL_CLAMP_TO_EDGE);
    gl::glTexParameteri(gl::GL_TEXTURE_2D, gl::GL_TEXTURE_WRAP_T, gl::GL_CLAMP_TO_EDGE);
    gl::glFramebufferTexture2D(gl::GL_FRAMEBUFFER, gl::GL_COLOR_ATTACHMENT0, gl::GL_TEXTURE_2D, st.hw_tex, 0);

    if st.hw_cb.depth || st.hw_cb.stencil {
        gl::glGenRenderbuffers(1, &mut st.hw_depth);
        gl::glBindRenderbuffer(gl::GL_RENDERBUFFER, st.hw_depth);
        let fmt = if st.hw_cb.depth && !st.hw_cb.stencil { gl::GL_DEPTH_COMPONENT24 } else { gl::GL_DEPTH24_STENCIL8 };
        gl::glRenderbufferStorage(gl::GL_RENDERBUFFER, fmt, w as gl::GLsizei, h as gl::GLsizei);
        let attach = if st.hw_cb.stencil { gl::GL_DEPTH_STENCIL_ATTACHMENT } else { gl::GL_DEPTH_ATTACHMENT };
        gl::glFramebufferRenderbuffer(gl::GL_FRAMEBUFFER, attach, gl::GL_RENDERBUFFER, st.hw_depth);
    }

    let status = gl::glCheckFramebufferStatus(gl::GL_FRAMEBUFFER);
    if status != gl::GL_FRAMEBUFFER_COMPLETE {
        loge!("HW FBO incomplete: 0x{:x}", status);
    }
    gl::glBindFramebuffer(gl::GL_FRAMEBUFFER, 0);
    st.hw_fbo_w = w;
    st.hw_fbo_h = h;
    HW_FBO.store(st.hw_fbo, Ordering::Release);
    logi!("Created HW FBO {} ({}x{}) tex={} depth={} vendor={:?}", st.hw_fbo, w, h, st.hw_tex, st.hw_depth, st.gpu_vendor);
}

// ── HW render callbacks (lock-free, use global atomics) ───────
unsafe extern "C" fn hw_get_current_framebuffer() -> usize {
    HW_FBO.load(Ordering::Relaxed) as usize
}

unsafe extern "C" fn hw_get_proc_address(sym: *const c_char) -> libretro::retro_proc_address_t {
    let addr = gl::eglGetProcAddress(sym);
    std::mem::transmute(addr)
}

// ── Viewport calculation ──────────────────────────────────────
fn compute_viewport(src_w: u32, src_h: u32, surf_w: i32, surf_h: i32) -> (i32, i32, i32, i32) {
    if src_h == 0 || surf_h == 0 { return (0, 0, surf_w, surf_h); }
    let src_aspect = src_w as f32 / src_h as f32;
    let dst_aspect = surf_w as f32 / surf_h as f32;
    if src_aspect > dst_aspect {
        let h = (surf_w as f32 / src_aspect) as i32;
        (0, (surf_h - h) / 2, surf_w, h)
    } else {
        let w = (surf_h as f32 * src_aspect) as i32;
        ((surf_w - w) / 2, 0, w, surf_h)
    }
}

// ── Blit HW frame ─────────────────────────────────────────────
unsafe fn blit_hw_frame(st: &mut FrontendState) {
    if st.hw_tex == 0 || st.blit.hw_program == 0 { return; }
    let w = if st.frame_w > 0 { st.frame_w } else { st.hw_fbo_w };
    let h = if st.frame_h > 0 { st.frame_h } else { st.hw_fbo_h };
    if w == 0 || h == 0 { return; }

    let mut core_fbo: gl::GLint = 0;
    gl::glGetIntegerv(gl::GL_FRAMEBUFFER_BINDING, &mut core_fbo);
    let core_used_ours = st.hw_fbo != 0 && core_fbo as gl::GLuint == st.hw_fbo;

    if st.debug_frame_count < 10 {
        logi!("blit_hw: {}x{} fbo={} tex={} core_left={} surf={}x{} ours={} vendor={:?}",
              w, h, st.hw_fbo, st.hw_tex, core_fbo, st.surface_w, st.surface_h, core_used_ours, st.gpu_vendor);
    }

    if !core_used_ours {
        gl::glFinish();
        // Mali-specific: use glBlitFramebuffer for correctness
        if st.gpu_vendor == GpuVendor::Mali && st.hw_fbo != 0 {
            gl::glBindFramebuffer(gl::GL_READ_FRAMEBUFFER, 0);
            gl::glBindFramebuffer(gl::GL_FRAMEBUFFER, st.hw_fbo);
            gl::glBlitFramebuffer(0, 0, w as gl::GLint, h as gl::GLint, 0, 0, w as gl::GLint, h as gl::GLint, gl::GL_COLOR_BUFFER_BIT, gl::GL_NEAREST as gl::GLenum);
        } else {
            gl::glBindFramebuffer(gl::GL_READ_FRAMEBUFFER, 0);
            gl::glBindTexture(gl::GL_TEXTURE_2D, st.hw_tex);
            gl::glCopyTexSubImage2D(gl::GL_TEXTURE_2D, 0, 0, 0, 0, 0, w as gl::GLsizei, h as gl::GLsizei);
            gl::glBindTexture(gl::GL_TEXTURE_2D, 0);
        }
    }

    gl::glBindFramebuffer(gl::GL_FRAMEBUFFER, 0);
    gl::glClearColor(0.0, 0.0, 0.0, 1.0);
    gl::glViewport(0, 0, st.surface_w, st.surface_h);
    gl::glClear(gl::GL_COLOR_BUFFER_BIT);

    let (vx, vy, vw, vh) = compute_viewport(w, h, st.surface_w, st.surface_h);
    gl::glViewport(vx, vy, vw, vh);
    gl::glDisable(gl::GL_DEPTH_TEST);
    gl::glDisable(gl::GL_BLEND);

    gl::glUseProgram(st.blit.hw_program);
    gl::glActiveTexture(gl::GL_TEXTURE0);
    gl::glBindTexture(gl::GL_TEXTURE_2D, st.hw_tex);
    let flip_name = CString::new("uFlipY").unwrap();
    let flip_loc = gl::glGetUniformLocation(st.blit.hw_program, flip_name.as_ptr());
    gl::glUniform1f(flip_loc, if st.hw_cb.bottom_left_origin { 1.0 } else { 0.0 });
    gl::glBindVertexArray(st.blit.vao);
    gl::glDrawArrays(gl::GL_TRIANGLE_STRIP, 0, 4);
    gl::glBindVertexArray(0);

    st.frame_ready.store(false, Ordering::Release);
}

// ── Blit SW frame ─────────────────────────────────────────────
unsafe fn blit_sw_frame(st: &mut FrontendState) {
    if !st.frame_ready.load(Ordering::Acquire) { return; }
    let w = st.frame_w;
    let h = st.frame_h;
    if w == 0 || h == 0 || st.frame_buf.is_empty() { return; }

    gl::glBindTexture(gl::GL_TEXTURE_2D, st.blit.texture);
    gl::glTexImage2D(gl::GL_TEXTURE_2D, 0, gl::GL_RGBA as gl::GLint, w as gl::GLsizei, h as gl::GLsizei, 0, gl::GL_RGBA, gl::GL_UNSIGNED_BYTE, st.frame_buf.as_ptr() as *const c_void);
    st.frame_ready.store(false, Ordering::Release);

    gl::glBindFramebuffer(gl::GL_FRAMEBUFFER, 0);
    gl::glClearColor(0.0, 0.0, 0.0, 1.0);
    gl::glViewport(0, 0, st.surface_w, st.surface_h);
    gl::glClear(gl::GL_COLOR_BUFFER_BIT);

    let (vx, vy, vw, vh) = compute_viewport(w, h, st.surface_w, st.surface_h);
    gl::glViewport(vx, vy, vw, vh);
    gl::glDisable(gl::GL_DEPTH_TEST);
    gl::glDisable(gl::GL_BLEND);

    gl::glUseProgram(st.blit.sw_program);
    gl::glActiveTexture(gl::GL_TEXTURE0);
    gl::glBindTexture(gl::GL_TEXTURE_2D, st.blit.texture);
    gl::glBindVertexArray(st.blit.vao);
    gl::glDrawArrays(gl::GL_TRIANGLE_STRIP, 0, 4);
    gl::glBindVertexArray(0);
}

// ── Libretro Callbacks (static extern "C") ────────────────────

static mut LOG_COUNT: i32 = 0;

// C shim for variadic log callback (compiled from log_shim.c)
extern "C" {
    fn vortex_retro_log(level: c_uint, fmt: *const c_char, ...);
}

unsafe extern "C" fn core_video_refresh(data: *const c_void, width: c_uint, height: c_uint, pitch: usize) {
    let mut guard = STATE.lock().unwrap();
    let st = match guard.as_mut() { Some(s) => s, None => return };

    if st.hw_render {
        // HW-rendered core: the core rendered into our texture-backed FBO.
        // Blit the FBO texture to the screen with proper scaling, then swap.
        // This happens INSIDE the callback so it works with blocking cores
        // (mupen64plus, parallel_n64) where retro_run() never returns.
        if width > 0 && height > 0 {
            st.frame_w = width;
            st.frame_h = height;
        }
        if st.egl.valid() && st.egl.surface != gl::EGL_NO_SURFACE {
            blit_hw_frame(st);
            st.egl.swap();
            HW_FRAME_PRESENTED.store(true, Ordering::Release);
        }
        st.frame_ready.store(true, Ordering::Release);
        return;
    }

    if data.is_null() { return; }
    st.frame_w = width;
    st.frame_h = height;
    let needed = (width * height) as usize;
    st.frame_buf.resize(needed, 0);

    let src = data as *const u8;
    for y in 0..height as usize {
        let row = src.add(y * pitch);
        let dst_off = y * width as usize;
        match st.pixel_fmt {
            1 => { // XRGB8888 (RETRO_PIXEL_FORMAT_XRGB8888 = 1)
                std::ptr::copy_nonoverlapping(row as *const u32, st.frame_buf.as_mut_ptr().add(dst_off), width as usize);
            }
            2 => { // RGB565 (RETRO_PIXEL_FORMAT_RGB565 = 2)
                for x in 0..width as usize {
                    let px = *(row.add(x * 2) as *const u16);
                    let r = ((px >> 11) << 3) as u32;
                    let g = (((px >> 5) & 0x3F) << 2) as u32;
                    let b = ((px & 0x1F) << 3) as u32;
                    st.frame_buf[dst_off + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
            _ => { // 0RGB1555
                for x in 0..width as usize {
                    let px = *(row.add(x * 2) as *const u16);
                    let r = (((px >> 10) & 0x1F) << 3) as u32;
                    let g = (((px >> 5) & 0x1F) << 3) as u32;
                    let b = ((px & 0x1F) << 3) as u32;
                    st.frame_buf[dst_off + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }
    }
    st.frame_ready.store(true, Ordering::Release);
}

unsafe extern "C" fn core_audio_sample(left: i16, right: i16) {
    if let Some(audio) = GLOBAL_AUDIO.get() {
        audio.push_sample(left, right);
    }
}

unsafe extern "C" fn core_audio_sample_batch(data: *const i16, frames: usize) -> usize {
    if let Some(audio) = GLOBAL_AUDIO.get() {
        audio.push_batch(data, frames);
    }
    frames
}

unsafe extern "C" fn core_input_poll() {}

unsafe extern "C" fn core_input_state(port: c_uint, device: c_uint, index: c_uint, id: c_uint) -> i16 {
    // Lock-free: reads directly from global INPUT atomics — no mutex needed
    let p = port as usize;
    if p >= libretro::MAX_PORTS { return 0; }
    match device & 0xFF { // mask off subclass bits
        libretro::RETRO_DEVICE_JOYPAD => {
            if id == libretro::RETRO_DEVICE_ID_JOYPAD_MASK {
                let mut mask: i16 = 0;
                for i in 0..libretro::MAX_BUTTONS {
                    if INPUT.joypad[p * libretro::MAX_BUTTONS + i].load(Ordering::Relaxed) != 0 {
                        mask |= 1 << i;
                    }
                }
                mask
            } else {
                let b = id as usize;
                if b < libretro::MAX_BUTTONS {
                    INPUT.joypad[p * libretro::MAX_BUTTONS + b].load(Ordering::Relaxed)
                } else { 0 }
            }
        }
        libretro::RETRO_DEVICE_ANALOG => {
            let i = index as usize;
            let a = id as usize;
            if i < 2 && a < 2 {
                INPUT.analog[p * 4 + i * 2 + a].load(Ordering::Relaxed)
            } else { 0 }
        }
        libretro::RETRO_DEVICE_POINTER => {
            if port == 0 {
                match id {
                    libretro::RETRO_DEVICE_ID_POINTER_X => INPUT.pointer_x.load(Ordering::Relaxed),
                    libretro::RETRO_DEVICE_ID_POINTER_Y => INPUT.pointer_y.load(Ordering::Relaxed),
                    libretro::RETRO_DEVICE_ID_POINTER_PRESSED => if INPUT.pointer_pressed.load(Ordering::Relaxed) { 1 } else { 0 },
                    _ => 0,
                }
            } else { 0 }
        }
        _ => 0,
    }
}

// ── Performance interface ─────────────────────────────────────
unsafe extern "C" fn perf_get_time_usec() -> libretro::retro_time_t {
    let mut ts = std::mem::zeroed::<libc_timespec>();
    clock_gettime(1, &mut ts); // CLOCK_MONOTONIC = 1
    ts.tv_sec as i64 * 1_000_000 + ts.tv_nsec as i64 / 1_000
}

unsafe extern "C" fn perf_get_counter() -> libretro::retro_perf_tick_t {
    let mut ts = std::mem::zeroed::<libc_timespec>();
    clock_gettime(1, &mut ts);
    ts.tv_sec as i64 * 1_000_000_000 + ts.tv_nsec as i64
}

unsafe extern "C" fn perf_get_features() -> libretro::retro_perf_tick_t { 0 }
unsafe extern "C" fn perf_log() {}
unsafe extern "C" fn perf_register(_: *mut libretro::retro_perf_counter) {}
unsafe extern "C" fn perf_start(c: *mut libretro::retro_perf_counter) {
    if !c.is_null() { (*c).start = perf_get_counter(); }
}
unsafe extern "C" fn perf_stop(c: *mut libretro::retro_perf_counter) {
    if !c.is_null() { (*c).total += perf_get_counter() - (*c).start; }
}

#[repr(C)]
struct libc_timespec { tv_sec: i64, tv_nsec: i64 }
extern "C" { fn clock_gettime(clk_id: c_int, tp: *mut libc_timespec) -> c_int; }

// ── Core option helpers ───────────────────────────────────────
fn set_option(opts: &mut HashMap<String, CString>, key: &str, val: &str) {
    opts.insert(key.to_string(), CString::new(val).unwrap_or_default());
}

fn get_option_ptr(opts: &HashMap<String, CString>, key: &str) -> Option<*const c_char> {
    opts.get(key).map(|v| v.as_ptr())
}

// ── Environment callback ──────────────────────────────────────
unsafe extern "C" fn core_environment(cmd: c_uint, data: *mut c_void) -> bool {
    if cmd & libretro::RETRO_ENVIRONMENT_PRIVATE != 0 { return false; }

    let base_cmd = cmd & !(libretro::RETRO_ENVIRONMENT_EXPERIMENTAL | libretro::RETRO_ENVIRONMENT_PRIVATE);

    let mut guard = STATE.lock().unwrap();
    let st = match guard.as_mut() { Some(s) => s, None => return false };

    match base_cmd {
        libretro::RETRO_ENVIRONMENT_GET_LOG_INTERFACE => {
            let cb = &mut *(data as *mut libretro::retro_log_callback);
            cb.log = Some(vortex_retro_log);
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY => {
            *(data as *mut *const c_char) = st.system_dir.as_ptr();
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY => {
            *(data as *mut *const c_char) = st.save_dir.as_ptr();
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY => {
            *(data as *mut *const c_char) = st.system_dir.as_ptr();
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_PIXEL_FORMAT => {
            st.pixel_fmt = *(data as *const c_uint);
            logi!("Pixel format: {}", st.pixel_fmt);
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_CAN_DUPE => {
            *(data as *mut bool) = true;
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_HW_RENDER => {
            let hw = &mut *(data as *mut libretro::retro_hw_render_callback);
            logi!("HW render requested: type={} ver={}.{} depth={} stencil={} bottom_left={}",
                  hw.context_type, hw.version_major, hw.version_minor,
                  hw.depth, hw.stencil, hw.bottom_left_origin);
            if hw.context_type == libretro::RETRO_HW_CONTEXT_VULKAN {
                logw!("Vulkan not supported");
                return false;
            }
            st.hw_render = true;
            st.hw_cb = *hw;
            st.hw_cb.get_current_framebuffer = Some(hw_get_current_framebuffer);
            st.hw_cb.get_proc_address = Some(hw_get_proc_address);
            *hw = st.hw_cb;
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_VARIABLE => {
            let var = &mut *(data as *mut libretro::retro_variable);
            if var.key.is_null() { return false; }
            let key = CStr::from_ptr(var.key).to_string_lossy();
            // Overrides (from setCoreOption) always win over core defaults
            if let Some(ptr) = get_option_ptr(&st.overrides, &key) {
                var.value = ptr;
                true
            } else if let Some(ptr) = get_option_ptr(&st.options, &key) {
                var.value = ptr;
                true
            } else {
                false
            }
        }
        libretro::RETRO_ENVIRONMENT_SET_VARIABLES => {
            let mut vars = data as *const libretro::retro_variable;
            while !vars.is_null() && !(*vars).key.is_null() {
                let key = CStr::from_ptr((*vars).key).to_string_lossy().to_string();
                if !st.options.contains_key(&key) {
                    if !(*vars).value.is_null() {
                        let val_str = CStr::from_ptr((*vars).value).to_string_lossy();
                        if let Some(semi_pos) = val_str.find(';') {
                            let after = val_str[semi_pos + 1..].trim_start();
                            let def = after.split('|').next().unwrap_or("");
                            set_option(&mut st.options, &key, def);
                        }
                    }
                }
                vars = vars.add(1);
            }
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_VARIABLE => {
            if !data.is_null() {
                let var = &*(data as *const libretro::retro_variable);
                if !var.key.is_null() && !var.value.is_null() {
                    let key = CStr::from_ptr(var.key).to_string_lossy().to_string();
                    let val = CStr::from_ptr(var.value).to_string_lossy().to_string();
                    set_option(&mut st.options, &key, &val);
                    st.options_updated.store(true, Ordering::Release);
                }
            }
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_CORE_OPTIONS => {
            if !data.is_null() {
                let mut defs = data as *const libretro::retro_core_option_definition;
                while !defs.is_null() && !(*defs).key.is_null() {
                    let key = CStr::from_ptr((*defs).key).to_string_lossy().to_string();
                    if !st.options.contains_key(&key) {
                        let val = if !(*defs).default_value.is_null() {
                            CStr::from_ptr((*defs).default_value).to_string_lossy().to_string()
                        } else if !(*defs).values[0].value.is_null() {
                            CStr::from_ptr((*defs).values[0].value).to_string_lossy().to_string()
                        } else { String::new() };
                        if !val.is_empty() { set_option(&mut st.options, &key, &val); }
                    }
                    defs = defs.add(1);
                }
            }
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL => {
            if !data.is_null() {
                let intl = &*(data as *const libretro::retro_core_options_intl);
                if !intl.us.is_null() {
                    let mut defs = intl.us;
                    while !(*defs).key.is_null() {
                        let key = CStr::from_ptr((*defs).key).to_string_lossy().to_string();
                        if !st.options.contains_key(&key) {
                            let val = if !(*defs).default_value.is_null() {
                                CStr::from_ptr((*defs).default_value).to_string_lossy().to_string()
                            } else if !(*defs).values[0].value.is_null() {
                                CStr::from_ptr((*defs).values[0].value).to_string_lossy().to_string()
                            } else { String::new() };
                            if !val.is_empty() { set_option(&mut st.options, &key, &val); }
                        }
                        defs = defs.add(1);
                    }
                }
            }
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2 => {
            if !data.is_null() {
                let v2 = &*(data as *const libretro::retro_core_options_v2);
                if !v2.definitions.is_null() {
                    let mut defs = v2.definitions;
                    while !(*defs).key.is_null() {
                        let key = CStr::from_ptr((*defs).key).to_string_lossy().to_string();
                        if !st.options.contains_key(&key) {
                            let val = if !(*defs).default_value.is_null() {
                                CStr::from_ptr((*defs).default_value).to_string_lossy().to_string()
                            } else if !(*defs).values[0].value.is_null() {
                                CStr::from_ptr((*defs).values[0].value).to_string_lossy().to_string()
                            } else { String::new() };
                            if !val.is_empty() { set_option(&mut st.options, &key, &val); }
                        }
                        defs = defs.add(1);
                    }
                }
            }
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL => {
            if !data.is_null() {
                let intl = &*(data as *const libretro::retro_core_options_v2_intl);
                if !intl.us.is_null() && !(*intl.us).definitions.is_null() {
                    let mut defs = (*intl.us).definitions;
                    while !(*defs).key.is_null() {
                        let key = CStr::from_ptr((*defs).key).to_string_lossy().to_string();
                        if !st.options.contains_key(&key) {
                            let val = if !(*defs).default_value.is_null() {
                                CStr::from_ptr((*defs).default_value).to_string_lossy().to_string()
                            } else if !(*defs).values[0].value.is_null() {
                                CStr::from_ptr((*defs).values[0].value).to_string_lossy().to_string()
                            } else { String::new() };
                            if !val.is_empty() { set_option(&mut st.options, &key, &val); }
                        }
                        defs = defs.add(1);
                    }
                }
            }
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION => {
            *(data as *mut c_uint) = 2;
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE => {
            let was = st.options_updated.swap(false, Ordering::AcqRel);
            *(data as *mut bool) = was;
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_GEOMETRY => {
            let geo = &*(data as *const libretro::retro_game_geometry);
            st.av_info.geometry.base_width = geo.base_width;
            st.av_info.geometry.base_height = geo.base_height;
            if geo.max_width > 0 && geo.max_height > 0 {
                st.av_info.geometry.max_width = geo.max_width;
                st.av_info.geometry.max_height = geo.max_height;
            }
            if geo.aspect_ratio > 0.0 { st.av_info.geometry.aspect_ratio = geo.aspect_ratio; }
            if st.hw_render && st.hw_fbo == 0 && st.av_info.geometry.max_width > 0 && st.egl.valid() {
                create_hw_fbo(st, st.av_info.geometry.max_width, st.av_info.geometry.max_height);
            }
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO => {
            let info = &*(data as *const libretro::retro_system_av_info);
            st.av_info = *info;
            st.fps = info.timing.fps;
            st.sample_rate = info.timing.sample_rate;
            if st.hw_render && st.egl.valid() {
                create_hw_fbo(st, info.geometry.max_width, info.geometry.max_height);
            }
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_PERF_INTERFACE => {
            let cb = &mut *(data as *mut libretro::retro_perf_callback);
            cb.get_time_usec = Some(perf_get_time_usec);
            cb.get_cpu_features = Some(perf_get_features);
            cb.get_perf_counter = Some(perf_get_counter);
            cb.perf_register = Some(perf_register);
            cb.perf_start = Some(perf_start);
            cb.perf_stop = Some(perf_stop);
            cb.perf_log = Some(perf_log);
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER => {
            *(data as *mut c_uint) = libretro::RETRO_HW_CONTEXT_OPENGLES3;
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT => {
            logi!("Core requests shared HW context");
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES => {
            *(data as *mut u64) = (1 << libretro::RETRO_DEVICE_JOYPAD)
                | (1 << libretro::RETRO_DEVICE_ANALOG)
                | (1 << libretro::RETRO_DEVICE_POINTER);
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_LANGUAGE => {
            *(data as *mut c_uint) = libretro::RETRO_LANGUAGE_ENGLISH;
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE => {
            *(data as *mut c_int) = 3; // both on
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_USERNAME => {
            static USERNAME: &[u8] = b"VortexPlayer\0";
            *(data as *mut *const c_char) = USERNAME.as_ptr() as *const c_char;
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_INPUT_BITMASKS => true,
        libretro::RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS => {
            *(data as *mut c_uint) = libretro::MAX_PORTS as c_uint;
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_OVERSCAN => {
            *(data as *mut bool) = false;
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_MESSAGE => {
            if !data.is_null() {
                let msg = &*(data as *const libretro::retro_message);
                if !msg.msg.is_null() {
                    let s = CStr::from_ptr(msg.msg).to_string_lossy();
                    logi!("Core message: {}", s);
                }
            }
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_MESSAGE_EXT => {
            if !data.is_null() {
                let msg = &*(data as *const libretro::retro_message_ext);
                if !msg.msg.is_null() {
                    let s = CStr::from_ptr(msg.msg).to_string_lossy();
                    logi!("Core message (ext): {}", s);
                }
            }
            true
        }
        libretro::RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION => {
            *(data as *mut c_uint) = 1;
            true
        }
        libretro::RETRO_ENVIRONMENT_SET_ROTATION
        | libretro::RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL
        | libretro::RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS
        | libretro::RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY
        | libretro::RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK
        | libretro::RETRO_ENVIRONMENT_SET_MEMORY_MAPS
        | libretro::RETRO_ENVIRONMENT_SET_CONTROLLER_INFO
        | libretro::RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS
        | libretro::RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME
        | libretro::RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS
        | libretro::RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE
        | libretro::RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO => true,
        libretro::RETRO_ENVIRONMENT_SHUT_DOWN => {
            logi!("Core requested shutdown");
            true
        }
        // Explicitly unsupported
        libretro::RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER
        | libretro::RETRO_ENVIRONMENT_GET_LIBRETRO_PATH
        | libretro::RETRO_ENVIRONMENT_GET_VFS_INTERFACE
        | libretro::RETRO_ENVIRONMENT_GET_LED_INTERFACE
        | libretro::RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE
        | libretro::RETRO_ENVIRONMENT_GET_THROTTLE_STATE
        | libretro::RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE
        | libretro::RETRO_ENVIRONMENT_GET_GAME_INFO_EXT
        | libretro::RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK
        | libretro::RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK
        | libretro::RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE
        | libretro::RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION
        | libretro::RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE => false,
        // GET_FASTFORWARDING (experimental)
        64 => {
            *(data as *mut bool) = false;
            true
        }
        _ => {
            logd!("Unhandled env: {} (base {})", cmd, base_cmd);
            false
        }
    }
}

// ── Symbol loading helper ─────────────────────────────────────
unsafe fn load_sym<T>(handle: *mut c_void, name: &str) -> Option<T> {
    let c_name = CString::new(name).unwrap();
    let ptr = gl::dlsym(handle, c_name.as_ptr());
    if ptr.is_null() {
        let err = gl::dlerror();
        let msg = if err.is_null() { "unknown" } else { &CStr::from_ptr(err).to_string_lossy() };
        loge!("Missing symbol: {} — {}", name, msg);
        None
    } else {
        Some(std::mem::transmute_copy(&ptr))
    }
}

unsafe fn load_sym_opt<T>(handle: *mut c_void, name: &str) -> Option<T> {
    let c_name = CString::new(name).unwrap();
    let ptr = gl::dlsym(handle, c_name.as_ptr());
    if ptr.is_null() { None } else { Some(std::mem::transmute_copy(&ptr)) }
}

// ── Cleanup ───────────────────────────────────────────────────
unsafe fn cleanup_all(st: &mut FrontendState) {
    destroy_blit(&mut st.blit);
    if st.hw_fbo != 0 { gl::glDeleteFramebuffers(1, &st.hw_fbo); st.hw_fbo = 0; }
    HW_FBO.store(0, Ordering::Release);
    if st.hw_tex != 0 { gl::glDeleteTextures(1, &st.hw_tex); st.hw_tex = 0; }
    if st.hw_depth != 0 { gl::glDeleteRenderbuffers(1, &st.hw_depth); st.hw_depth = 0; }
    st.egl.destroy();
    if !st.window.is_null() { gl::ANativeWindow_release(st.window); st.window = ptr::null_mut(); }
    st.core = None;
    st.hw_render = false;
    st.hw_cb = libretro::retro_hw_render_callback::default();
    st.hw_fbo_w = 0; st.hw_fbo_h = 0;
    st.frame_buf.clear();
    st.frame_w = 0; st.frame_h = 0;
    st.frame_ready.store(false, Ordering::SeqCst);
    if let Some(audio) = GLOBAL_AUDIO.get() { audio.reset(); }
    st.pixel_fmt = 0;
    st.options.clear();
    st.options_updated.store(false, Ordering::SeqCst);
    st.debug_frame_count = 0;
}

// ── Extract library name from .so path ────────────────────────
/// "/path/to/fceumm_libretro_android.so" → "fceumm"
/// "/path/to/mupen64plus_next_gles3_libretro_android.so" → "mupen64plus_next_gles3"
fn extract_library_name(path: &str) -> String {
    let filename = path.rsplit('/').next().unwrap_or(path);
    filename
        .strip_suffix("_libretro_android.so")
        .or_else(|| filename.strip_suffix(".so"))
        .unwrap_or(filename)
        .to_string()
}

// ══════════════════════════════════════════════════════════════
//  JNI EXPORTS
// ══════════════════════════════════════════════════════════════

#[no_mangle]
pub extern "C" fn JNI_OnLoad(vm: jni::JavaVM, _: *mut c_void) -> jint {
    let mut guard = STATE.lock().unwrap();
    let st = guard.get_or_insert_with(FrontendState::new);
    st.jvm = vm.get_java_vm_pointer() as *mut c_void;
    logi!("Rust Frontend JNI_OnLoad: JavaVM cached ({:p})", st.jvm);
    JNI_VERSION_1_6
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_loadCore(
    mut env: JNIEnv, _class: JClass, core_path: JString, system_dir: JString, save_dir: JString,
) -> jint {
    let core_str: String = env.get_string(&core_path).map(|s| s.into()).unwrap_or_default();
    let sys_str: String = env.get_string(&system_dir).map(|s| s.into()).unwrap_or_default();
    let save_str: String = env.get_string(&save_dir).map(|s| s.into()).unwrap_or_default();

    logi!("Loading core (Rust frontend): {}", core_str);

    // Phase 1: cleanup previous core and extract JVM pointer (lock held)
    let jvm_ptr = {
        let mut guard = STATE.lock().unwrap();
        let st = guard.get_or_insert_with(FrontendState::new);

        // Cleanup previous core — deinit must happen with lock dropped
        let old_deinit: Option<unsafe extern "C" fn()> = st.core.as_ref().map(|c| c.deinit);
        let old_handle = st.core.as_ref().map(|c| c.handle);
        st.core = None;
        st.hw_render = false;
        st.hw_cb = libretro::retro_hw_render_callback::default();
        st.debug_frame_count = 0;
        st.options.clear();
        st.overrides.clear();
        st.system_dir = CString::new(sys_str).unwrap_or_default();
        st.save_dir = CString::new(save_str).unwrap_or_default();
        let jvm = st.jvm;

        drop(guard);

        // Deinit old core without lock (it may call environment callback)
        if let Some(deinit_fn) = old_deinit {
            (deinit_fn)();
        }
        if let Some(handle) = old_handle {
            gl::dlclose(handle);
        }

        jvm
    };

    // Phase 2: dlopen and load symbols (no lock needed)
    let c_path = CString::new(core_str.clone()).unwrap_or_default();
    let handle = gl::dlopen(c_path.as_ptr(), gl::RTLD_LAZY);
    if handle.is_null() {
        let err = gl::dlerror();
        let msg = if err.is_null() { "unknown".to_string() } else { CStr::from_ptr(err).to_string_lossy().to_string() };
        loge!("dlopen failed: {}", msg);
        return -1;
    }

    // Call core's JNI_OnLoad if present (needed by PPSSPP etc.)
    if !jvm_ptr.is_null() {
        type JniOnLoadFn = unsafe extern "C" fn(*mut c_void, *mut c_void) -> jint;
        if let Some(core_jni) = load_sym_opt::<JniOnLoadFn>(handle, "JNI_OnLoad") {
            let ver = core_jni(jvm_ptr, ptr::null_mut());
            logi!("Called core JNI_OnLoad -> 0x{:x}", ver);
        }
        // PPSSPP: gJvm
        let gj = gl::dlsym(handle, b"gJvm\0".as_ptr() as *const c_char);
        if !gj.is_null() {
            *(gj as *mut *mut c_void) = jvm_ptr;
            logi!("Set gJvm in core to {:p}", jvm_ptr);
        }
    }

    // Load required symbols
    macro_rules! load_req {
        ($name:expr) => {
            match load_sym(handle, $name) {
                Some(f) => f,
                None => { loge!("Failed to load: {}", $name); gl::dlclose(handle); return -2; }
            }
        };
    }

    let core = CoreFuncs {
        handle,
        init: load_req!("retro_init"),
        deinit: load_req!("retro_deinit"),
        api_version: load_req!("retro_api_version"),
        get_system_info: load_req!("retro_get_system_info"),
        get_system_av_info: load_req!("retro_get_system_av_info"),
        set_environment: load_req!("retro_set_environment"),
        set_video_refresh: load_req!("retro_set_video_refresh"),
        set_audio_sample: load_req!("retro_set_audio_sample"),
        set_audio_sample_batch: load_req!("retro_set_audio_sample_batch"),
        set_input_poll: load_req!("retro_set_input_poll"),
        set_input_state: load_req!("retro_set_input_state"),
        load_game: load_req!("retro_load_game"),
        unload_game: load_req!("retro_unload_game"),
        run: load_req!("retro_run"),
        reset: load_req!("retro_reset"),
        serialize_size: load_sym_opt(handle, "retro_serialize_size"),
        serialize: load_sym_opt(handle, "retro_serialize"),
        unserialize: load_sym_opt(handle, "retro_unserialize"),
        set_controller_port: load_sym_opt(handle, "retro_set_controller_port_device"),
        get_memory_data: load_sym_opt(handle, "retro_get_memory_data"),
        get_memory_size: load_sym_opt(handle, "retro_get_memory_size"),
    };

    let api = (core.api_version)();
    logi!("Core API version: {}", api);

    // Phase 3: call core init functions WITHOUT holding lock (they call core_environment callback!)
    (core.set_environment)(core_environment);
    (core.init)();
    (core.set_video_refresh)(core_video_refresh);
    // Initialize global audio ring (OnceLock ensures single init)
    let _ = GLOBAL_AUDIO.get_or_init(AudioRing::new);
    (core.set_audio_sample)(core_audio_sample);
    (core.set_audio_sample_batch)(core_audio_sample_batch);
    (core.set_input_poll)(core_input_poll);
    (core.set_input_state)(core_input_state);

    let mut sys_info: libretro::retro_system_info = std::mem::zeroed();
    (core.get_system_info)(&mut sys_info);
    if !sys_info.library_name.is_null() && !sys_info.library_version.is_null() {
        let name = CStr::from_ptr(sys_info.library_name).to_string_lossy();
        let ver = CStr::from_ptr(sys_info.library_version).to_string_lossy();
        logi!("Core: {} ({})", name, ver);
    }

    // Phase 4: store core in state (re-acquire lock)
    {
        let mut guard = STATE.lock().unwrap();
        let st = guard.get_or_insert_with(FrontendState::new);
        st.core = Some(core);

        // Auto-apply Lemuroid-compatible presets for this core.
        // Extract library_name from .so path: "/path/to/fceumm_libretro_android.so" → "fceumm"
        let lib_name = extract_library_name(&core_str);
        let applied = core_presets::apply_preset(&lib_name, &mut st.overrides, &mut st.options);
        if applied > 0 {
            logi!("Applied {} Lemuroid preset options for '{}'", applied, lib_name);
        } else {
            logi!("No Lemuroid presets for '{}' (using core defaults)", lib_name);
        }

        logi!("HW render: {}", st.hw_render);
    }
    0
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_loadGame(
    mut env: JNIEnv, _class: JClass, rom_path: JString,
) -> jboolean {
    let rom_str: String = env.get_string(&rom_path).map(|s| s.into()).unwrap_or_default();
    logi!("Loading game: {}", rom_str);

    // Phase 1: extract core function pointers (lock held briefly)
    let (get_sys_info, load_game_fn, get_av_info) = {
        let guard = STATE.lock().unwrap();
        let st = match guard.as_ref() { Some(s) => s, None => return JNI_FALSE };
        let core = match st.core.as_ref() { Some(c) => c, None => return JNI_FALSE };
        (core.get_system_info, core.load_game, core.get_system_av_info)
    };

    // Phase 2: call core functions WITHOUT lock (they may call environment callback)
    let mut sys_info: libretro::retro_system_info = std::mem::zeroed();
    (get_sys_info)(&mut sys_info);
    let need_fullpath = sys_info.need_fullpath;

    // Phase 2: load game WITHOUT lock (load_game calls environment callback)
    let c_path = CString::new(rom_str).unwrap_or_default();

    let mut game_info: libretro::retro_game_info = std::mem::zeroed();
    game_info.path = c_path.as_ptr();

    let mut rom_data: Vec<u8> = Vec::new();
    if !need_fullpath {
        if let Ok(data) = std::fs::read(c_path.to_str().unwrap_or("")) {
            rom_data = data;
            game_info.data = rom_data.as_ptr() as *const c_void;
            game_info.size = rom_data.len();
        }
    }

    if !(load_game_fn)(&game_info) {
        loge!("retro_load_game failed");
        return JNI_FALSE;
    }

    let mut av: libretro::retro_system_av_info = std::mem::zeroed();
    (get_av_info)(&mut av);

    // Phase 3: store AV info + set controller ports (re-acquire lock)
    {
        let mut guard = STATE.lock().unwrap();
        let st = match guard.as_mut() { Some(s) => s, None => return JNI_FALSE };
        st.av_info = av;
        st.fps = av.timing.fps;
        st.sample_rate = av.timing.sample_rate;
        logi!("AV: {}x{} @ {:.2} fps, audio {:.0} Hz",
              av.geometry.base_width, av.geometry.base_height, st.fps, st.sample_rate);

        // Tell the core a standard joypad is connected on each port
        if let Some(set_port) = st.core.as_ref().and_then(|c| c.set_controller_port) {
            for port in 0..libretro::MAX_PORTS {
                (set_port)(port as c_uint, libretro::RETRO_DEVICE_JOYPAD);
            }
            logi!("Set controller port device: JOYPAD on ports 0-{}", libretro::MAX_PORTS - 1);
        }

        if st.hw_render {
            st.hw_ctx_reset_pending.store(true, Ordering::Release);
            logi!("HW render active — context_reset deferred");
        }
    }
    JNI_TRUE
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_runFrame(
    _env: JNIEnv, _class: JClass,
) {
    let mut guard = STATE.lock().unwrap();
    let st = match guard.as_mut() { Some(s) => s, None => return };

    // Must extract core.run pointer before we drop the borrow
    let core_run = match st.core.as_ref() { Some(c) => c.run, None => return };

    // Frame skip
    let skip = st.frame_skip.load(Ordering::Relaxed);
    if skip > 0 {
        st.frame_skip_counter += 1;
        if st.frame_skip_counter <= skip {
            if let Some(audio) = GLOBAL_AUDIO.get() { audio.reset(); }
            drop(guard);
            // Need state unlocked for retro_run (it calls our callbacks which lock STATE)
            (core_run)();
            return;
        }
        st.frame_skip_counter = 0;
    }

    if let Some(audio) = GLOBAL_AUDIO.get() { audio.reset(); }

    if st.egl.valid() { st.egl.make_current(); }

    // Deferred context_reset
    if st.hw_ctx_reset_pending.swap(false, Ordering::AcqRel) {
        if st.hw_render && st.egl.valid() {
            if st.hw_fbo == 0 {
                create_hw_fbo(st, st.av_info.geometry.max_width, st.av_info.geometry.max_height);
            }
            while gl::glGetError() != gl::GL_NO_ERROR {}
            st.gpu_vendor = detect_gpu_vendor();
            logi!("context_reset (Rust): fbo={} {}x{} vendor={:?}", st.hw_fbo, st.hw_fbo_w, st.hw_fbo_h, st.gpu_vendor);
            if let Some(reset_fn) = st.hw_cb.context_reset {
                // Must drop guard before calling core's context_reset (it calls env callback)
                let fbo = st.hw_fbo;
                let fbo_w = st.hw_fbo_w;
                let fbo_h = st.hw_fbo_h;
                drop(guard);
                (reset_fn)();
                // Re-acquire
                guard = STATE.lock().unwrap();
                let st2 = guard.as_mut().unwrap();
                let mut errs = 0;
                while gl::glGetError() != gl::GL_NO_ERROR { errs += 1; if errs > 20 { break; } }
                logi!("context_reset done ({} GL errors)", errs);
                // Continue with st2
                st2.hw_frame_presented.store(false, Ordering::Release);
                HW_FRAME_PRESENTED.store(false, Ordering::Release);
                if st2.hw_render && st2.hw_fbo != 0 {
                    gl::glBindFramebuffer(gl::GL_FRAMEBUFFER, st2.hw_fbo);
                    gl::glViewport(0, 0, st2.hw_fbo_w as gl::GLsizei, st2.hw_fbo_h as gl::GLsizei);
                }
                let dbg = st2.debug_frame_count;
                let hw = st2.hw_render;
                let fbo = st2.hw_fbo;
                if dbg < 5 { logi!("runFrame (Rust): hw={} fbo={}", hw, fbo); }
                st2.debug_frame_count += 1;
                let core_run2 = st2.core.as_ref().unwrap().run;
                drop(guard);
                (core_run2)();
                // Re-acquire for post-run
                guard = STATE.lock().unwrap();
                let st3 = guard.as_mut().unwrap();
                if st3.egl.valid() && st3.egl.surface != gl::EGL_NO_SURFACE {
                    if st3.hw_render {
                        if !HW_FRAME_PRESENTED.load(Ordering::Acquire) {
                            blit_hw_frame(st3);
                            st3.egl.swap();
                        }
                    } else {
                        blit_sw_frame(st3);
                        st3.egl.swap();
                    }
                }
                return;
            }
        }
    }

    st.hw_frame_presented.store(false, Ordering::Release);
    HW_FRAME_PRESENTED.store(false, Ordering::Release);

    if st.hw_render && st.hw_fbo != 0 {
        gl::glBindFramebuffer(gl::GL_FRAMEBUFFER, st.hw_fbo);
        gl::glViewport(0, 0, st.hw_fbo_w as gl::GLsizei, st.hw_fbo_h as gl::GLsizei);
    }

    if st.debug_frame_count < 5 {
        logi!("runFrame (Rust): hw={} fbo={}", st.hw_render, st.hw_fbo);
    }
    st.debug_frame_count += 1;

    // Drop lock before retro_run (callbacks will re-acquire)
    drop(guard);

    // Early input polling (Gemini fix)
    if INPUT_POLL_MODE.load(Ordering::Relaxed) == 1 {
        core_input_poll();
    }

    (core_run)();

    // Re-acquire for presentation
    let mut guard = STATE.lock().unwrap();
    let st = match guard.as_mut() { Some(s) => s, None => return };

    if st.egl.valid() && st.egl.surface != gl::EGL_NO_SURFACE {
        if st.hw_render {
            if !HW_FRAME_PRESENTED.load(Ordering::Acquire) {
                blit_hw_frame(st);
                st.egl.swap();
            }
        } else {
            blit_sw_frame(st);
            st.egl.swap();
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_getFrameBuffer(
    env: JNIEnv, _class: JClass,
) -> jni::sys::jintArray {
    let guard = STATE.lock().unwrap();
    let st = match guard.as_ref() { Some(s) => s, None => return ptr::null_mut() };
    if st.frame_w == 0 || st.frame_h == 0 || st.frame_buf.is_empty() { return ptr::null_mut(); }
    let count = st.frame_buf.len();
    match env.new_int_array(count as i32) {
        Ok(arr) => {
            let _ = env.set_int_array_region(&arr, 0, std::slice::from_raw_parts(st.frame_buf.as_ptr() as *const i32, count));
            arr.into_raw()
        }
        Err(_) => ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_getFrameWidth(
    _env: JNIEnv, _class: JClass,
) -> jint {
    let guard = STATE.lock().unwrap();
    guard.as_ref().map_or(0, |st| st.frame_w as jint)
}

#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_getFrameHeight(
    _env: JNIEnv, _class: JClass,
) -> jint {
    let guard = STATE.lock().unwrap();
    guard.as_ref().map_or(0, |st| st.frame_h as jint)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_getAudioBuffer(
    env: JNIEnv, _class: JClass,
) -> jni::sys::jshortArray {
    let audio = match GLOBAL_AUDIO.get() { Some(a) => a, None => return ptr::null_mut() };
    let mut guard = STATE.lock().unwrap();
    let st = match guard.as_mut() { Some(s) => s, None => return ptr::null_mut() };
    audio.drain(&mut st.audio_drain);
    if st.audio_drain.is_empty() { return ptr::null_mut(); }
    let count = st.audio_drain.len();
    match env.new_short_array(count as i32) {
        Ok(arr) => {
            let _ = env.set_short_array_region(&arr, 0, &st.audio_drain);
            arr.into_raw()
        }
        Err(_) => ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_getFps(
    _env: JNIEnv, _class: JClass,
) -> jdouble {
    let guard = STATE.lock().unwrap();
    guard.as_ref().map_or(60.0, |st| st.fps)
}

#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_getSampleRate(
    _env: JNIEnv, _class: JClass,
) -> jdouble {
    let guard = STATE.lock().unwrap();
    guard.as_ref().map_or(44100.0, |st| st.sample_rate)
}

#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_setInputState(
    _env: JNIEnv, _class: JClass, port: jint, button_id: jint, value: jint,
) {
    // Lock-free: write directly to global INPUT atomics
    let p = port as usize;
    let b = button_id as usize;
    if p < libretro::MAX_PORTS && b < libretro::MAX_BUTTONS {
        INPUT.joypad[p * libretro::MAX_BUTTONS + b].store(value as i16, Ordering::Relaxed);
    }
}

#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_setAnalogState(
    _env: JNIEnv, _class: JClass, port: jint, index: jint, axis_id: jint, value: jint,
) {
    let p = port as usize;
    let i = index as usize;
    let a = axis_id as usize;
    if p < libretro::MAX_PORTS && i < 2 && a < 2 {
        INPUT.analog[p * 4 + i * 2 + a].store(value as i16, Ordering::Relaxed);
    }
}

#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_setPointerState(
    _env: JNIEnv, _class: JClass, x: jint, y: jint, pressed: jboolean,
) {
    INPUT.pointer_x.store(x as i16, Ordering::Relaxed);
    INPUT.pointer_y.store(y as i16, Ordering::Relaxed);
    INPUT.pointer_pressed.store(pressed != 0, Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_resetGame(
    _env: JNIEnv, _class: JClass,
) {
    let reset_fn = {
        let guard = STATE.lock().unwrap();
        match guard.as_ref().and_then(|st| st.core.as_ref()) {
            Some(core) => core.reset,
            None => return,
        }
    };
    unsafe { (reset_fn)(); }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_unloadGame(
    _env: JNIEnv, _class: JClass,
) {
    logi!("Unloading game and core (Rust frontend)");

    // Extract core function pointers and handle, then drop lock
    let (unload_fn, deinit_fn, handle) = {
        let mut guard = STATE.lock().unwrap();
        if let Some(st) = guard.as_mut() {
            if let Some(ref core) = st.core {
                let u = core.unload_game;
                let d = core.deinit;
                let h = core.handle;
                (Some(u), Some(d), Some(h))
            } else {
                (None, None, None)
            }
        } else {
            (None, None, None)
        }
    };

    // Call core functions without lock (they may call environment callback)
    if let Some(uf) = unload_fn { (uf)(); }
    if let Some(df) = deinit_fn { (df)(); }
    if let Some(h) = handle { gl::dlclose(h); }

    // Re-acquire lock for cleanup
    let mut guard = STATE.lock().unwrap();
    if let Some(st) = guard.as_mut() {
        st.core = None;
        cleanup_all(st);
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_saveState(
    mut env: JNIEnv, _class: JClass, path: JString,
) -> jint {
    let path_str: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
    let (sz_fn, ser_fn) = {
        let guard = STATE.lock().unwrap();
        let st = match guard.as_ref() { Some(s) => s, None => return -1 };
        let core = match st.core.as_ref() { Some(c) => c, None => return -1 };
        let szf = match core.serialize_size { Some(f) => f, None => return -1 };
        let sf = match core.serialize { Some(f) => f, None => return -1 };
        (szf, sf)
    };
    let sz = (sz_fn)();
    if sz == 0 { return -2; }
    let mut buf = vec![0u8; sz];
    if !(ser_fn)(buf.as_mut_ptr() as *mut c_void, sz) { return -3; }
    match std::fs::write(&path_str, &buf) {
        Ok(_) => 0,
        Err(_) => -4,
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_loadState(
    mut env: JNIEnv, _class: JClass, path: JString,
) -> jint {
    let path_str: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
    let unser_fn = {
        let guard = STATE.lock().unwrap();
        let st = match guard.as_ref() { Some(s) => s, None => return -1 };
        let core = match st.core.as_ref() { Some(c) => c, None => return -1 };
        match core.unserialize { Some(f) => f, None => return -1 }
    };
    let buf = match std::fs::read(&path_str) {
        Ok(b) => b,
        Err(_) => return -2,
    };
    if (unser_fn)(buf.as_ptr() as *const c_void, buf.len()) { 0 } else { -5 }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_saveStateToMemory(
    env: JNIEnv, _class: JClass,
) -> jni::sys::jbyteArray {
    let (sz_fn, ser_fn) = {
        let guard = STATE.lock().unwrap();
        let st = match guard.as_ref() { Some(s) => s, None => return ptr::null_mut() };
        let core = match st.core.as_ref() { Some(c) => c, None => return ptr::null_mut() };
        let szf = match core.serialize_size { Some(f) => f, None => return ptr::null_mut() };
        let sf = match core.serialize { Some(f) => f, None => return ptr::null_mut() };
        (szf, sf)
    };
    let sz = (sz_fn)();
    if sz == 0 { return ptr::null_mut(); }
    let mut buf = vec![0u8; sz];
    if !(ser_fn)(buf.as_mut_ptr() as *mut c_void, sz) { return ptr::null_mut(); }
    match env.byte_array_from_slice(&buf) {
        Ok(arr) => arr.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_loadStateFromMemory(
    env: JNIEnv, _class: JClass, data: JByteArray,
) -> jboolean {
    let (unser_fn, buf) = {
        let guard = STATE.lock().unwrap();
        let st = match guard.as_ref() { Some(s) => s, None => return JNI_FALSE };
        let core = match st.core.as_ref() { Some(c) => c, None => return JNI_FALSE };
        let uf = match core.unserialize { Some(f) => f, None => return JNI_FALSE };
        let b = match env.convert_byte_array(data) {
            Ok(b) => b,
            Err(_) => return JNI_FALSE,
        };
        (uf, b)
    };
    if (unser_fn)(buf.as_ptr() as *const c_void, buf.len()) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_getSerializeSize(
    _env: JNIEnv, _class: JClass,
) -> jlong {
    let sz_fn = {
        let guard = STATE.lock().unwrap();
        let st = match guard.as_ref() { Some(s) => s, None => return 0 };
        let core = match st.core.as_ref() { Some(c) => c, None => return 0 };
        match core.serialize_size { Some(f) => f, None => return 0 }
    };
    unsafe { (sz_fn)() as jlong }
}

#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_isHardwareRendered(
    _env: JNIEnv, _class: JClass,
) -> jboolean {
    let guard = STATE.lock().unwrap();
    guard.as_ref().map_or(JNI_FALSE, |st| if st.hw_render { JNI_TRUE } else { JNI_FALSE })
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_setCoreOption(
    mut env: JNIEnv, _class: JClass, key: JString, value: JString,
) {
    let k: String = env.get_string(&key).map(|s| s.into()).unwrap_or_default();
    let v: String = env.get_string(&value).map(|s| s.into()).unwrap_or_default();

    // Handle frontend-level options
    if k == "input_poll_type_behavior" {
        let mode = match v.as_str() {
            "early" => 1,
            "late" => 2,
            _ => 0,
        };
        INPUT_POLL_MODE.store(mode, Ordering::Relaxed);
        logi!("Input poll mode set to: {} ({})", v, mode);
    }

    let mut guard = STATE.lock().unwrap();
    if let Some(st) = guard.as_mut() {
        // Write to both overrides (supreme, can't be overwritten by core) and options
        set_option(&mut st.overrides, &k, &v);
        set_option(&mut st.options, &k, &v);
        st.options_updated.store(true, Ordering::Release);
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_saveSRAM(
    mut env: JNIEnv, _class: JClass, path: JString,
) -> jboolean {
    let path_str: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
    let (data_fn, size_fn) = {
        let guard = STATE.lock().unwrap();
        let st = match guard.as_ref() { Some(s) => s, None => return JNI_FALSE };
        let core = match st.core.as_ref() { Some(c) => c, None => return JNI_FALSE };
        let df = match core.get_memory_data { Some(f) => f, None => return JNI_FALSE };
        let sf = match core.get_memory_size { Some(f) => f, None => return JNI_FALSE };
        (df, sf)
    };
    let data = (data_fn)(libretro::RETRO_MEMORY_SAVE_RAM);
    let size = (size_fn)(libretro::RETRO_MEMORY_SAVE_RAM);
    if data.is_null() || size == 0 { return JNI_FALSE; }
    let slice = std::slice::from_raw_parts(data as *const u8, size);
    match std::fs::write(&path_str, slice) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_loadSRAM(
    mut env: JNIEnv, _class: JClass, path: JString,
) -> jboolean {
    let path_str: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
    let (data_fn, size_fn) = {
        let guard = STATE.lock().unwrap();
        let st = match guard.as_ref() { Some(s) => s, None => return JNI_FALSE };
        let core = match st.core.as_ref() { Some(c) => c, None => return JNI_FALSE };
        let df = match core.get_memory_data { Some(f) => f, None => return JNI_FALSE };
        let sf = match core.get_memory_size { Some(f) => f, None => return JNI_FALSE };
        (df, sf)
    };
    let dest = (data_fn)(libretro::RETRO_MEMORY_SAVE_RAM);
    let size = (size_fn)(libretro::RETRO_MEMORY_SAVE_RAM);
    if dest.is_null() || size == 0 { return JNI_FALSE; }
    match std::fs::read(&path_str) {
        Ok(buf) => {
            let copy_len = buf.len().min(size);
            std::ptr::copy_nonoverlapping(buf.as_ptr(), dest as *mut u8, copy_len);
            JNI_TRUE
        }
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_setFrameSkip(
    _env: JNIEnv, _class: JClass, skip: jint,
) {
    let guard = STATE.lock().unwrap();
    if let Some(st) = guard.as_ref() {
        st.frame_skip.store(skip, Ordering::Relaxed);
    }
}

/// Set the display render backend (0 = GL, 1 = Vulkan).
/// Rust frontend uses OpenGL ES only — logs a warning if Vulkan is requested.
#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_setRenderBackend(
    _env: JNIEnv, _class: JClass, backend: jint,
) {
    if backend != 0 {
        logw!("Rust frontend: Vulkan display backend requested ({}) but not supported, using OpenGL ES", backend);
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_setSurface(
    env: JNIEnv, _class: JClass, surface: JObject,
) {
    let mut guard = STATE.lock().unwrap();
    let st = guard.get_or_insert_with(FrontendState::new);

    // Release previous
    if !st.window.is_null() {
        destroy_blit(&mut st.blit);
        st.egl.destroy();
        st.hw_fbo = 0; st.hw_tex = 0; st.hw_depth = 0;
        HW_FBO.store(0, Ordering::Release);
        st.hw_fbo_w = 0; st.hw_fbo_h = 0;
        gl::ANativeWindow_release(st.window);
        st.window = ptr::null_mut();
    }

    if surface.is_null() {
        logi!("Surface cleared");
        return;
    }

    let env_ptr = env.get_raw();
    let surf_ptr = surface.as_raw() as *mut c_void;
    st.window = gl::ANativeWindow_fromSurface(env_ptr as *mut c_void, surf_ptr);
    if st.window.is_null() {
        loge!("ANativeWindow_fromSurface failed");
        return;
    }

    st.surface_w = gl::ANativeWindow_getWidth(st.window);
    st.surface_h = gl::ANativeWindow_getHeight(st.window);
    logi!("Surface set: {}x{}", st.surface_w, st.surface_h);

    if !init_egl(&mut st.egl, st.window) {
        loge!("EGL init failed");
        gl::ANativeWindow_release(st.window);
        st.window = ptr::null_mut();
        return;
    }

    init_blit(&mut st.blit);
    st.gpu_vendor = detect_gpu_vendor();
    logi!("GPU vendor detected: {:?}", st.gpu_vendor);

    if st.hw_render {
        create_hw_fbo(st, st.av_info.geometry.max_width, st.av_info.geometry.max_height);
        st.hw_ctx_reset_pending.store(true, Ordering::Release);
        logi!("context_reset deferred (fbo={} {}x{})", st.hw_fbo, st.hw_fbo_w, st.hw_fbo_h);
    }

    // Release EGL from UI thread
    gl::eglMakeCurrent(st.egl.display, gl::EGL_NO_SURFACE, gl::EGL_NO_SURFACE, gl::EGL_NO_CONTEXT);
    logi!("EGL released from UI thread");
}

#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_surfaceChanged(
    _env: JNIEnv, _class: JClass, width: jint, height: jint,
) {
    let mut guard = STATE.lock().unwrap();
    if let Some(st) = guard.as_mut() {
        st.surface_w = width;
        st.surface_h = height;
        logi!("Surface changed: {}x{}", width, height);
    }
}

// ══════════════════════════════════════════════════════════════
//  LEMUROID CORE CATALOG & PRESETS — JNI
// ══════════════════════════════════════════════════════════════

/// Returns JSON array of every curated core entry.
#[no_mangle]
pub extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_getLemuroidCatalog(
    env: JNIEnv, _class: JClass,
) -> jni::sys::jstring {
    let json = core_catalog::catalog_to_json();
    env.new_string(&json)
        .map(|s| s.into_raw())
        .unwrap_or(ptr::null_mut())
}

/// Returns the default core library_name for a platform (e.g. "NES" → "fceumm").
#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_getDefaultCoreForPlatform(
    mut env: JNIEnv, _class: JClass, platform: JString,
) -> jni::sys::jstring {
    let plat: String = env.get_string(&platform).map(|s| s.into()).unwrap_or_default();
    match core_catalog::get_default_for_platform(&plat) {
        Some(entry) => env.new_string(entry.library_name)
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut()),
        None => ptr::null_mut(),
    }
}

/// Returns JSON object of preset options for a given core library_name.
#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_getCorePreset(
    mut env: JNIEnv, _class: JClass, library_name: JString,
) -> jni::sys::jstring {
    let name: String = env.get_string(&library_name).map(|s| s.into()).unwrap_or_default();
    let json = core_presets::preset_to_json(&name);
    env.new_string(&json)
        .map(|s| s.into_raw())
        .unwrap_or(ptr::null_mut())
}

/// Detects platform from a ROM file extension (e.g. "nes" → "NES").
#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_detectPlatform(
    mut env: JNIEnv, _class: JClass, extension: JString,
) -> jni::sys::jstring {
    let ext: String = env.get_string(&extension).map(|s| s.into()).unwrap_or_default();
    match core_catalog::detect_platform(&ext) {
        Some(platform) => env.new_string(platform)
            .map(|s| s.into_raw())
            .unwrap_or(ptr::null_mut()),
        None => ptr::null_mut(),
    }
}

/// Returns the buildbot download URL for a core + ABI combo.
#[no_mangle]
pub unsafe extern "C" fn Java_com_vortex_emulator_emulation_VortexNativeRust_getBuildbotUrl(
    mut env: JNIEnv, _class: JClass, library_name: JString, abi: JString,
) -> jni::sys::jstring {
    let name: String = env.get_string(&library_name).map(|s| s.into()).unwrap_or_default();
    let abi_str: String = env.get_string(&abi).map(|s| s.into()).unwrap_or_default();
    let url = core_catalog::buildbot_url(&name, &abi_str);
    env.new_string(&url)
        .map(|s| s.into_raw())
        .unwrap_or(ptr::null_mut())
}

