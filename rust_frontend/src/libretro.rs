//! libretro.h FFI bindings — complete libretro API v1 types for Vortex.
//!
//! All types mirror the C header exactly, with #[repr(C)] for ABI compat.

#![allow(non_camel_case_types, non_upper_case_globals, dead_code)]

use std::os::raw::{c_char, c_int, c_uint, c_void};

// ── Pixel formats ─────────────────────────────────────────────
pub const RETRO_PIXEL_FORMAT_0RGB1555: u32 = 0;
pub const RETRO_PIXEL_FORMAT_XRGB8888: u32 = 1;
pub const RETRO_PIXEL_FORMAT_RGB565: u32 = 2;

// ── Environment callback keys ─────────────────────────────────
pub const RETRO_ENVIRONMENT_SET_ROTATION: u32 = 1;
pub const RETRO_ENVIRONMENT_GET_OVERSCAN: u32 = 2;
pub const RETRO_ENVIRONMENT_GET_CAN_DUPE: u32 = 3;
pub const RETRO_ENVIRONMENT_SET_MESSAGE: u32 = 6;
pub const RETRO_ENVIRONMENT_SHUT_DOWN: u32 = 7;
pub const RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL: u32 = 8;
pub const RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: u32 = 9;
pub const RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: u32 = 10;
pub const RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS: u32 = 11;
pub const RETRO_ENVIRONMENT_SET_HW_RENDER: u32 = 14;
pub const RETRO_ENVIRONMENT_GET_VARIABLE: u32 = 15;
pub const RETRO_ENVIRONMENT_SET_VARIABLES: u32 = 16;
pub const RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: u32 = 17;
pub const RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME: u32 = 18;
pub const RETRO_ENVIRONMENT_GET_LIBRETRO_PATH: u32 = 19;
pub const RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK: u32 = 21;
pub const RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK: u32 = 22;
pub const RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: u32 = 23;
pub const RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES: u32 = 24;
pub const RETRO_ENVIRONMENT_GET_LOG_INTERFACE: u32 = 27;
pub const RETRO_ENVIRONMENT_GET_PERF_INTERFACE: u32 = 28;
pub const RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: u32 = 30;
pub const RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: u32 = 31;
pub const RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: u32 = 32;
pub const RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO: u32 = 34;
pub const RETRO_ENVIRONMENT_SET_CONTROLLER_INFO: u32 = 35;
pub const RETRO_ENVIRONMENT_SET_MEMORY_MAPS: u32 = 36;
pub const RETRO_ENVIRONMENT_SET_GEOMETRY: u32 = 37;
pub const RETRO_ENVIRONMENT_GET_USERNAME: u32 = 38;
pub const RETRO_ENVIRONMENT_GET_LANGUAGE: u32 = 39;
pub const RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER: u32 = 40;
pub const RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE: u32 = 41;
pub const RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS: u32 = 42;
pub const RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: u32 = 43;
pub const RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS: u32 = 44;
pub const RETRO_ENVIRONMENT_EXPERIMENTAL: u32 = 0x10000;
pub const RETRO_ENVIRONMENT_PRIVATE: u32 = 0x800000;
pub const RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT: u32 = 44 | RETRO_ENVIRONMENT_EXPERIMENTAL;
pub const RETRO_ENVIRONMENT_GET_VFS_INTERFACE: u32 = 45;
pub const RETRO_ENVIRONMENT_GET_LED_INTERFACE: u32 = 46;
pub const RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: u32 = 47;
pub const RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: u32 = 51;
pub const RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: u32 = 52;
pub const RETRO_ENVIRONMENT_SET_CORE_OPTIONS: u32 = 53;
pub const RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: u32 = 54;
pub const RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY: u32 = 55;
pub const RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: u32 = 56;
pub const RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION: u32 = 57;
pub const RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE: u32 = 58;
pub const RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION: u32 = 59;
pub const RETRO_ENVIRONMENT_SET_MESSAGE_EXT: u32 = 60;
pub const RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS: u32 = 61;
pub const RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE: u32 = 65;
pub const RETRO_ENVIRONMENT_GET_GAME_INFO_EXT: u32 = 66;
pub const RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: u32 = 67;
pub const RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: u32 = 68;
pub const RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK: u32 = 69;
pub const RETRO_ENVIRONMENT_SET_VARIABLE: u32 = 70;
pub const RETRO_ENVIRONMENT_GET_THROTTLE_STATE: u32 = 71;
/// GET_FASTFORWARDING (experimental, cmd 64 | 0x10000)
pub const RETRO_ENVIRONMENT_GET_FASTFORWARDING: u32 = 64 | RETRO_ENVIRONMENT_EXPERIMENTAL;

// ── HW context types ──────────────────────────────────────────
pub const RETRO_HW_CONTEXT_NONE: u32 = 0;
pub const RETRO_HW_CONTEXT_OPENGL: u32 = 1;
pub const RETRO_HW_CONTEXT_OPENGLES2: u32 = 2;
pub const RETRO_HW_CONTEXT_OPENGL_CORE: u32 = 3;
pub const RETRO_HW_CONTEXT_OPENGLES3: u32 = 4;
pub const RETRO_HW_CONTEXT_OPENGLES_VERSION: u32 = 5;
pub const RETRO_HW_CONTEXT_VULKAN: u32 = 6;

// ── Device types ──────────────────────────────────────────────
pub const RETRO_DEVICE_NONE: u32 = 0;
pub const RETRO_DEVICE_JOYPAD: u32 = 1;
pub const RETRO_DEVICE_MOUSE: u32 = 2;
pub const RETRO_DEVICE_KEYBOARD: u32 = 3;
pub const RETRO_DEVICE_LIGHTGUN: u32 = 4;
pub const RETRO_DEVICE_ANALOG: u32 = 5;
pub const RETRO_DEVICE_POINTER: u32 = 6;

// ── Pointer IDs ───────────────────────────────────────────────
pub const RETRO_DEVICE_ID_POINTER_X: u32 = 0;
pub const RETRO_DEVICE_ID_POINTER_Y: u32 = 1;
pub const RETRO_DEVICE_ID_POINTER_PRESSED: u32 = 2;

// ── Memory types ──────────────────────────────────────────────
pub const RETRO_MEMORY_SAVE_RAM: u32 = 0;

// ── Language ──────────────────────────────────────────────────
pub const RETRO_LANGUAGE_ENGLISH: u32 = 0;

// ── Log levels ────────────────────────────────────────────────
pub const RETRO_LOG_DEBUG: u32 = 0;
pub const RETRO_LOG_INFO: u32 = 1;
pub const RETRO_LOG_WARN: u32 = 2;
pub const RETRO_LOG_ERROR: u32 = 3;

pub const MAX_PORTS: usize = 4;
pub const MAX_BUTTONS: usize = 16;
pub const RETRO_DEVICE_ID_JOYPAD_MASK: c_uint = 256;
pub const RETRO_NUM_CORE_OPTION_VALUES_MAX: usize = 128;

// ── Callback typedefs ─────────────────────────────────────────
pub type retro_environment_t = unsafe extern "C" fn(cmd: c_uint, data: *mut c_void) -> bool;
pub type retro_video_refresh_t =
    unsafe extern "C" fn(data: *const c_void, width: c_uint, height: c_uint, pitch: usize);
pub type retro_audio_sample_t = unsafe extern "C" fn(left: i16, right: i16);
pub type retro_audio_sample_batch_t =
    unsafe extern "C" fn(data: *const i16, frames: usize) -> usize;
pub type retro_input_poll_t = unsafe extern "C" fn();
pub type retro_input_state_t =
    unsafe extern "C" fn(port: c_uint, device: c_uint, index: c_uint, id: c_uint) -> i16;

pub type retro_hw_context_reset_t = unsafe extern "C" fn();
pub type retro_hw_get_current_framebuffer_t = unsafe extern "C" fn() -> usize;
pub type retro_proc_address_t = unsafe extern "C" fn();
pub type retro_hw_get_proc_address_t =
    unsafe extern "C" fn(sym: *const c_char) -> retro_proc_address_t;

// ── Structures ────────────────────────────────────────────────
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct retro_hw_render_callback {
    pub context_type: c_uint,
    pub context_reset: Option<retro_hw_context_reset_t>,
    pub get_current_framebuffer: Option<retro_hw_get_current_framebuffer_t>,
    pub get_proc_address: Option<retro_hw_get_proc_address_t>,
    pub depth: bool,
    pub stencil: bool,
    pub bottom_left_origin: bool,
    pub version_major: c_uint,
    pub version_minor: c_uint,
    pub cache_context: bool,
    pub context_destroy: Option<retro_hw_context_reset_t>,
    pub debug_context: bool,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct retro_game_geometry {
    pub base_width: c_uint,
    pub base_height: c_uint,
    pub max_width: c_uint,
    pub max_height: c_uint,
    pub aspect_ratio: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct retro_system_timing {
    pub fps: f64,
    pub sample_rate: f64,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct retro_system_av_info {
    pub geometry: retro_game_geometry,
    pub timing: retro_system_timing,
}

#[repr(C)]
pub struct retro_system_info {
    pub library_name: *const c_char,
    pub library_version: *const c_char,
    pub valid_extensions: *const c_char,
    pub need_fullpath: bool,
    pub block_extract: bool,
}

#[repr(C)]
pub struct retro_game_info {
    pub path: *const c_char,
    pub data: *const c_void,
    pub size: usize,
    pub meta: *const c_char,
}

#[repr(C)]
pub struct retro_log_callback {
    pub log: Option<
        unsafe extern "C" fn(level: c_uint, fmt: *const c_char, ...),
    >,
}

#[repr(C)]
pub struct retro_variable {
    pub key: *const c_char,
    pub value: *const c_char,
}

#[repr(C)]
pub struct retro_core_option_value {
    pub value: *const c_char,
    pub label: *const c_char,
}

#[repr(C)]
pub struct retro_core_option_definition {
    pub key: *const c_char,
    pub desc: *const c_char,
    pub info: *const c_char,
    pub values: [retro_core_option_value; RETRO_NUM_CORE_OPTION_VALUES_MAX],
    pub default_value: *const c_char,
}

#[repr(C)]
pub struct retro_core_options_intl {
    pub us: *const retro_core_option_definition,
    pub local: *const retro_core_option_definition,
}

#[repr(C)]
pub struct retro_core_option_v2_definition {
    pub key: *const c_char,
    pub desc: *const c_char,
    pub desc_categorized: *const c_char,
    pub info: *const c_char,
    pub info_categorized: *const c_char,
    pub category_key: *const c_char,
    pub values: [retro_core_option_value; RETRO_NUM_CORE_OPTION_VALUES_MAX],
    pub default_value: *const c_char,
}

#[repr(C)]
pub struct retro_core_option_v2_category {
    pub key: *const c_char,
    pub desc: *const c_char,
    pub info: *const c_char,
}

#[repr(C)]
pub struct retro_core_options_v2 {
    pub categories: *const retro_core_option_v2_category,
    pub definitions: *const retro_core_option_v2_definition,
}

#[repr(C)]
pub struct retro_core_options_v2_intl {
    pub us: *const retro_core_options_v2,
    pub local: *const retro_core_options_v2,
}

#[repr(C)]
pub struct retro_message {
    pub msg: *const c_char,
    pub frames: c_uint,
}

#[repr(C)]
pub struct retro_message_ext {
    pub msg: *const c_char,
    pub duration: c_uint,
    pub priority: c_uint,
    pub level: c_int,
    pub target: c_uint,
    pub msg_type: c_uint,
    pub progress: c_int,
}

pub type retro_perf_tick_t = i64;
pub type retro_time_t = i64;

#[repr(C)]
pub struct retro_perf_counter {
    pub ident: *const c_char,
    pub start: retro_perf_tick_t,
    pub total: retro_perf_tick_t,
    pub call_cnt: retro_perf_tick_t,
    pub registered: bool,
}

pub type retro_perf_get_time_usec_t = unsafe extern "C" fn() -> retro_time_t;
pub type retro_perf_get_counter_t = unsafe extern "C" fn() -> retro_perf_tick_t;
pub type retro_get_cpu_features_t = unsafe extern "C" fn() -> u64;
pub type retro_perf_log_t = unsafe extern "C" fn();
pub type retro_perf_register_t = unsafe extern "C" fn(counter: *mut retro_perf_counter);
pub type retro_perf_start_t = unsafe extern "C" fn(counter: *mut retro_perf_counter);
pub type retro_perf_stop_t = unsafe extern "C" fn(counter: *mut retro_perf_counter);

#[repr(C)]
pub struct retro_perf_callback {
    pub get_time_usec: Option<retro_perf_get_time_usec_t>,
    pub get_cpu_features: Option<retro_perf_get_counter_t>,
    pub get_perf_counter: Option<retro_perf_get_counter_t>,
    pub perf_register: Option<retro_perf_register_t>,
    pub perf_start: Option<retro_perf_start_t>,
    pub perf_stop: Option<retro_perf_stop_t>,
    pub perf_log: Option<retro_perf_log_t>,
}
