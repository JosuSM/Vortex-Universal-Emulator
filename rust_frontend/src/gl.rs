//! EGL/GLES3 raw FFI bindings — only the functions we actually call.
//!
//! We link to libEGL.so and libGLESv3.so at the system level; these are
//! extern "C" declarations, not full bindgen output.

#![allow(non_camel_case_types, non_upper_case_globals, dead_code)]

use std::os::raw::{c_char, c_int, c_uint, c_void};

// ── EGL types ─────────────────────────────────────────────────
pub type EGLDisplay = *mut c_void;
pub type EGLConfig = *mut c_void;
pub type EGLContext = *mut c_void;
pub type EGLSurface = *mut c_void;
pub type EGLNativeWindowType = *mut c_void;
pub type EGLint = c_int;
pub type EGLBoolean = c_uint;

pub const EGL_NO_DISPLAY: EGLDisplay = std::ptr::null_mut();
pub const EGL_NO_CONTEXT: EGLContext = std::ptr::null_mut();
pub const EGL_NO_SURFACE: EGLSurface = std::ptr::null_mut();
pub const EGL_DEFAULT_DISPLAY: EGLNativeWindowType = std::ptr::null_mut();
pub const EGL_FALSE: EGLBoolean = 0;
pub const EGL_TRUE: EGLBoolean = 1;

// EGL attribute keys
pub const EGL_RENDERABLE_TYPE: EGLint = 0x3040;
pub const EGL_OPENGL_ES3_BIT: EGLint = 0x0040;
pub const EGL_SURFACE_TYPE: EGLint = 0x3033;
pub const EGL_WINDOW_BIT: EGLint = 0x0004;
pub const EGL_RED_SIZE: EGLint = 0x3024;
pub const EGL_GREEN_SIZE: EGLint = 0x3023;
pub const EGL_BLUE_SIZE: EGLint = 0x3022;
pub const EGL_ALPHA_SIZE: EGLint = 0x3021;
pub const EGL_DEPTH_SIZE: EGLint = 0x3025;
pub const EGL_STENCIL_SIZE: EGLint = 0x3026;
pub const EGL_NONE: EGLint = 0x3038;
pub const EGL_CONTEXT_CLIENT_VERSION: EGLint = 0x3098;

// ── GL types ──────────────────────────────────────────────────
pub type GLuint = c_uint;
pub type GLint = c_int;
pub type GLsizei = c_int;
pub type GLenum = c_uint;
pub type GLboolean = u8;
pub type GLfloat = f32;
pub type GLbitfield = c_uint;
pub type GLchar = c_char;

// GL constants
pub const GL_FALSE: GLboolean = 0;
pub const GL_TRUE: GLboolean = 1;
pub const GL_FRAMEBUFFER: GLenum = 0x8D40;
pub const GL_READ_FRAMEBUFFER: GLenum = 0x8CA8;
pub const GL_COLOR_ATTACHMENT0: GLenum = 0x8CE0;
pub const GL_DEPTH_ATTACHMENT: GLenum = 0x8D00;
pub const GL_DEPTH_STENCIL_ATTACHMENT: GLenum = 0x821A;
pub const GL_FRAMEBUFFER_COMPLETE: GLenum = 0x8CD5;
pub const GL_RENDERBUFFER: GLenum = 0x8D41;
pub const GL_TEXTURE_2D: GLenum = 0x0DE1;
pub const GL_TEXTURE_MIN_FILTER: GLenum = 0x2801;
pub const GL_TEXTURE_MAG_FILTER: GLenum = 0x2800;
pub const GL_TEXTURE_WRAP_S: GLenum = 0x2802;
pub const GL_TEXTURE_WRAP_T: GLenum = 0x2803;
pub const GL_LINEAR: GLint = 0x2601;
pub const GL_NEAREST: GLint = 0x2600;
pub const GL_CLAMP_TO_EDGE: GLint = 0x812F;
pub const GL_RGBA: GLenum = 0x1908;
pub const GL_RGBA8: GLenum = 0x8058;
pub const GL_RGB565: GLenum = 0x8D62;
pub const GL_UNSIGNED_BYTE: GLenum = 0x1401;
pub const GL_UNSIGNED_SHORT_5_6_5: GLenum = 0x8363;
pub const GL_FLOAT: GLenum = 0x1406;
pub const GL_ARRAY_BUFFER: GLenum = 0x8892;
pub const GL_STATIC_DRAW: GLenum = 0x88E4;
pub const GL_TRIANGLE_STRIP: GLenum = 0x0005;
pub const GL_VERTEX_SHADER: GLenum = 0x8B31;
pub const GL_FRAGMENT_SHADER: GLenum = 0x8B30;
pub const GL_COMPILE_STATUS: GLenum = 0x8B81;
pub const GL_LINK_STATUS: GLenum = 0x8B82;
pub const GL_DEPTH_TEST: GLenum = 0x0B71;
pub const GL_BLEND: GLenum = 0x0BE2;
pub const GL_COLOR_BUFFER_BIT: GLbitfield = 0x4000;
pub const GL_DEPTH_BUFFER_BIT: GLbitfield = 0x0100;
pub const GL_NO_ERROR: GLenum = 0;
pub const GL_DEPTH_COMPONENT24: GLenum = 0x81A6;
pub const GL_DEPTH24_STENCIL8: GLenum = 0x88F0;
pub const GL_TEXTURE0: GLenum = 0x84C0;
pub const GL_VERSION: GLenum = 0x1F02;
pub const GL_RENDERER: GLenum = 0x1F01;
pub const GL_FRAMEBUFFER_BINDING: GLenum = 0x8CA6;
pub const GL_NEAREST_MIPMAP_NEAREST: GLint = 0x2700;

#[link(name = "EGL")]
extern "C" {
    pub fn eglGetDisplay(display_id: EGLNativeWindowType) -> EGLDisplay;
    pub fn eglInitialize(dpy: EGLDisplay, major: *mut EGLint, minor: *mut EGLint) -> EGLBoolean;
    pub fn eglChooseConfig(
        dpy: EGLDisplay, attrib_list: *const EGLint, configs: *mut EGLConfig,
        config_size: EGLint, num_config: *mut EGLint,
    ) -> EGLBoolean;
    pub fn eglCreateContext(
        dpy: EGLDisplay, config: EGLConfig, share_context: EGLContext,
        attrib_list: *const EGLint,
    ) -> EGLContext;
    pub fn eglCreateWindowSurface(
        dpy: EGLDisplay, config: EGLConfig, win: EGLNativeWindowType,
        attrib_list: *const EGLint,
    ) -> EGLSurface;
    pub fn eglMakeCurrent(
        dpy: EGLDisplay, draw: EGLSurface, read: EGLSurface, ctx: EGLContext,
    ) -> EGLBoolean;
    pub fn eglSwapBuffers(dpy: EGLDisplay, surface: EGLSurface) -> EGLBoolean;
    pub fn eglDestroyContext(dpy: EGLDisplay, ctx: EGLContext) -> EGLBoolean;
    pub fn eglDestroySurface(dpy: EGLDisplay, surface: EGLSurface) -> EGLBoolean;
    pub fn eglTerminate(dpy: EGLDisplay) -> EGLBoolean;
    pub fn eglGetProcAddress(name: *const c_char) -> *const c_void;
}

#[link(name = "GLESv3")]
extern "C" {
    pub fn glGenFramebuffers(n: GLsizei, framebuffers: *mut GLuint);
    pub fn glDeleteFramebuffers(n: GLsizei, framebuffers: *const GLuint);
    pub fn glBindFramebuffer(target: GLenum, framebuffer: GLuint);
    pub fn glCheckFramebufferStatus(target: GLenum) -> GLenum;
    pub fn glFramebufferTexture2D(
        target: GLenum, attachment: GLenum, textarget: GLenum, texture: GLuint, level: GLint,
    );
    pub fn glFramebufferRenderbuffer(
        target: GLenum, attachment: GLenum, renderbuffertarget: GLenum, renderbuffer: GLuint,
    );
    pub fn glGenRenderbuffers(n: GLsizei, renderbuffers: *mut GLuint);
    pub fn glDeleteRenderbuffers(n: GLsizei, renderbuffers: *const GLuint);
    pub fn glBindRenderbuffer(target: GLenum, renderbuffer: GLuint);
    pub fn glRenderbufferStorage(
        target: GLenum, internalformat: GLenum, width: GLsizei, height: GLsizei,
    );
    pub fn glGenTextures(n: GLsizei, textures: *mut GLuint);
    pub fn glDeleteTextures(n: GLsizei, textures: *const GLuint);
    pub fn glBindTexture(target: GLenum, texture: GLuint);
    pub fn glTexImage2D(
        target: GLenum, level: GLint, internalformat: GLint, width: GLsizei, height: GLsizei,
        border: GLint, format: GLenum, type_: GLenum, pixels: *const c_void,
    );
    pub fn glTexParameteri(target: GLenum, pname: GLenum, param: GLint);
    pub fn glCopyTexSubImage2D(
        target: GLenum, level: GLint, xoffset: GLint, yoffset: GLint,
        x: GLint, y: GLint, width: GLsizei, height: GLsizei,
    );
    pub fn glGenVertexArrays(n: GLsizei, arrays: *mut GLuint);
    pub fn glDeleteVertexArrays(n: GLsizei, arrays: *const GLuint);
    pub fn glBindVertexArray(array: GLuint);
    pub fn glGenBuffers(n: GLsizei, buffers: *mut GLuint);
    pub fn glDeleteBuffers(n: GLsizei, buffers: *const GLuint);
    pub fn glBindBuffer(target: GLenum, buffer: GLuint);
    pub fn glBufferData(target: GLenum, size: isize, data: *const c_void, usage: GLenum);
    pub fn glVertexAttribPointer(
        index: GLuint, size: GLint, type_: GLenum, normalized: GLboolean,
        stride: GLsizei, pointer: *const c_void,
    );
    pub fn glEnableVertexAttribArray(index: GLuint);
    pub fn glCreateShader(type_: GLenum) -> GLuint;
    pub fn glDeleteShader(shader: GLuint);
    pub fn glShaderSource(
        shader: GLuint, count: GLsizei, string: *const *const GLchar, length: *const GLint,
    );
    pub fn glCompileShader(shader: GLuint);
    pub fn glGetShaderiv(shader: GLuint, pname: GLenum, params: *mut GLint);
    pub fn glGetShaderInfoLog(
        shader: GLuint, bufSize: GLsizei, length: *mut GLsizei, infoLog: *mut GLchar,
    );
    pub fn glCreateProgram() -> GLuint;
    pub fn glDeleteProgram(program: GLuint);
    pub fn glAttachShader(program: GLuint, shader: GLuint);
    pub fn glLinkProgram(program: GLuint);
    pub fn glGetProgramiv(program: GLuint, pname: GLenum, params: *mut GLint);
    pub fn glGetProgramInfoLog(
        program: GLuint, bufSize: GLsizei, length: *mut GLsizei, infoLog: *mut GLchar,
    );
    pub fn glUseProgram(program: GLuint);
    pub fn glGetUniformLocation(program: GLuint, name: *const GLchar) -> GLint;
    pub fn glUniform1f(location: GLint, v0: GLfloat);
    pub fn glUniform1i(location: GLint, v0: GLint);
    pub fn glDrawArrays(mode: GLenum, first: GLint, count: GLsizei);
    pub fn glViewport(x: GLint, y: GLint, width: GLsizei, height: GLsizei);
    pub fn glClearColor(red: GLfloat, green: GLfloat, blue: GLfloat, alpha: GLfloat);
    pub fn glClear(mask: GLbitfield);
    pub fn glEnable(cap: GLenum);
    pub fn glDisable(cap: GLenum);
    pub fn glActiveTexture(texture: GLenum);
    pub fn glGetIntegerv(pname: GLenum, data: *mut GLint);
    pub fn glGetString(name: GLenum) -> *const u8;
    pub fn glGetError() -> GLenum;
    pub fn glFinish();
    pub fn glFlush();
    pub fn glReadPixels(
        x: GLint, y: GLint, width: GLsizei, height: GLsizei,
        format: GLenum, type_: GLenum, pixels: *mut c_void,
    );
    pub fn glBlitFramebuffer(
        srcX0: GLint, srcY0: GLint, srcX1: GLint, srcY1: GLint,
        dstX0: GLint, dstY0: GLint, dstX1: GLint, dstY1: GLint,
        mask: GLbitfield, filter: GLenum,
    );
}

// ── Android NDK ───────────────────────────────────────────────
#[link(name = "android")]
extern "C" {
    pub fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut c_void;
    pub fn ANativeWindow_release(window: *mut c_void);
    pub fn ANativeWindow_getWidth(window: *mut c_void) -> i32;
    pub fn ANativeWindow_getHeight(window: *mut c_void) -> i32;
}

// ── dl ────────────────────────────────────────────────────────
#[link(name = "dl")]
extern "C" {
    pub fn dlopen(filename: *const c_char, flags: c_int) -> *mut c_void;
    pub fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    pub fn dlclose(handle: *mut c_void) -> c_int;
    pub fn dlerror() -> *const c_char;
}

pub const RTLD_LAZY: c_int = 0x00001;

// ── Android logging ───────────────────────────────────────────
pub const ANDROID_LOG_DEBUG: c_int = 3;
pub const ANDROID_LOG_INFO: c_int = 4;
pub const ANDROID_LOG_WARN: c_int = 5;
pub const ANDROID_LOG_ERROR: c_int = 6;

#[link(name = "log")]
extern "C" {
    pub fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

/// Safe logging macro that formats and sends to Android logcat.
#[macro_export]
macro_rules! alog {
    ($prio:expr, $tag:expr, $($arg:tt)*) => {{
        let msg = format!($($arg)*);
        let tag_c = std::ffi::CString::new($tag).unwrap_or_default();
        let msg_c = std::ffi::CString::new(msg).unwrap_or_default();
        unsafe { $crate::gl::__android_log_write($prio, tag_c.as_ptr(), msg_c.as_ptr()); }
    }};
}

#[macro_export]
macro_rules! logi { ($($arg:tt)*) => { $crate::alog!($crate::gl::ANDROID_LOG_INFO, "VortexRust", $($arg)*); }; }
#[macro_export]
macro_rules! logw { ($($arg:tt)*) => { $crate::alog!($crate::gl::ANDROID_LOG_WARN, "VortexRust", $($arg)*); }; }
#[macro_export]
macro_rules! loge { ($($arg:tt)*) => { $crate::alog!($crate::gl::ANDROID_LOG_ERROR, "VortexRust", $($arg)*); }; }
#[macro_export]
macro_rules! logd { ($($arg:tt)*) => { $crate::alog!($crate::gl::ANDROID_LOG_DEBUG, "VortexRust", $($arg)*); }; }
