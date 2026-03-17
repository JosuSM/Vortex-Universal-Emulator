/* libretro.h — Extended subset of the libretro API v1 for Vortex frontend.
 * The full specification is public domain. This version adds hardware rendering,
 * core options, disk control, and additional environment callbacks needed by
 * complex cores (PPSSPP, melonDS, Flycast, Play!, mupen64plus, etc.).
 */
#ifndef LIBRETRO_H__
#define LIBRETRO_H__

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ── Pixel formats ─────────────────────────────────────────────── */
#define RETRO_PIXEL_FORMAT_0RGB1555  0
#define RETRO_PIXEL_FORMAT_XRGB8888  1
#define RETRO_PIXEL_FORMAT_RGB565    2

/* ── Environment callback keys ─────────────────────────────────── */
#define RETRO_ENVIRONMENT_SET_ROTATION          1
#define RETRO_ENVIRONMENT_GET_OVERSCAN          2
#define RETRO_ENVIRONMENT_GET_CAN_DUPE          3
#define RETRO_ENVIRONMENT_SET_MESSAGE           6
#define RETRO_ENVIRONMENT_SHUT_DOWN             7
#define RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL 8
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY  9
#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT     10
#define RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS 11
#define RETRO_ENVIRONMENT_SET_HW_RENDER        14
#define RETRO_ENVIRONMENT_GET_VARIABLE         15
#define RETRO_ENVIRONMENT_SET_VARIABLES        16
#define RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE  17
#define RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME  18
#define RETRO_ENVIRONMENT_GET_LIBRETRO_PATH    19
#define RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK 21
#define RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK   22
#define RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE 23
#define RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES 24
#define RETRO_ENVIRONMENT_GET_LOG_INTERFACE    27
#define RETRO_ENVIRONMENT_GET_PERF_INTERFACE   28
#define RETRO_ENVIRONMENT_GET_LOCATION_INTERFACE 29
#define RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY 30
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY   31
#define RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO   32
#define RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO   34
#define RETRO_ENVIRONMENT_SET_CONTROLLER_INFO  35
#define RETRO_ENVIRONMENT_SET_MEMORY_MAPS      36
#define RETRO_ENVIRONMENT_SET_GEOMETRY         37
#define RETRO_ENVIRONMENT_GET_USERNAME         38
#define RETRO_ENVIRONMENT_GET_LANGUAGE         39
#define RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER 40
#define RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE 41
#define RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS 42
#define RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE 43
#define RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS 44
#define RETRO_ENVIRONMENT_EXPERIMENTAL 0x10000
#define RETRO_ENVIRONMENT_PRIVATE      0x800000
#define RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT (44 | RETRO_ENVIRONMENT_EXPERIMENTAL)
#define RETRO_ENVIRONMENT_GET_VFS_INTERFACE    45
#define RETRO_ENVIRONMENT_GET_LED_INTERFACE    46
#define RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE 47
#define RETRO_ENVIRONMENT_GET_INPUT_BITMASKS   51
#define RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION 52
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS     53
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL 54
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY 55
#define RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER 56
#define RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION 57
#define RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE 58
#define RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION 59
#define RETRO_ENVIRONMENT_SET_MESSAGE_EXT      60
#define RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS  61
#define RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE 65
#define RETRO_ENVIRONMENT_GET_GAME_INFO_EXT    66
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2  67
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL 68
#define RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK 69
#define RETRO_ENVIRONMENT_SET_VARIABLE         70
#define RETRO_ENVIRONMENT_GET_THROTTLE_STATE   71

/* ── Hardware rendering context types ──────────────────────────── */
#define RETRO_HW_CONTEXT_NONE          0
#define RETRO_HW_CONTEXT_OPENGL        1
#define RETRO_HW_CONTEXT_OPENGLES2     2
#define RETRO_HW_CONTEXT_OPENGL_CORE   3
#define RETRO_HW_CONTEXT_OPENGLES3     4
#define RETRO_HW_CONTEXT_OPENGLES_VERSION 5
#define RETRO_HW_CONTEXT_VULKAN        6

/* Framebuffer target constant */
#define RETRO_HW_FRAME_BUFFER_VALID ((uintptr_t)-1)

/* ── Device types for input ────────────────────────────────────── */
#define RETRO_DEVICE_NONE       0
#define RETRO_DEVICE_JOYPAD     1
#define RETRO_DEVICE_MOUSE      2
#define RETRO_DEVICE_KEYBOARD   3
#define RETRO_DEVICE_LIGHTGUN   4
#define RETRO_DEVICE_ANALOG     5
#define RETRO_DEVICE_POINTER    6

/* ── Joypad button IDs ─────────────────────────────────────────── */
#define RETRO_DEVICE_ID_JOYPAD_B        0
#define RETRO_DEVICE_ID_JOYPAD_Y        1
#define RETRO_DEVICE_ID_JOYPAD_SELECT   2
#define RETRO_DEVICE_ID_JOYPAD_START    3
#define RETRO_DEVICE_ID_JOYPAD_UP       4
#define RETRO_DEVICE_ID_JOYPAD_DOWN     5
#define RETRO_DEVICE_ID_JOYPAD_LEFT     6
#define RETRO_DEVICE_ID_JOYPAD_RIGHT    7
#define RETRO_DEVICE_ID_JOYPAD_A        8
#define RETRO_DEVICE_ID_JOYPAD_X        9
#define RETRO_DEVICE_ID_JOYPAD_L       10
#define RETRO_DEVICE_ID_JOYPAD_R       11
#define RETRO_DEVICE_ID_JOYPAD_L2      12
#define RETRO_DEVICE_ID_JOYPAD_R2      13
#define RETRO_DEVICE_ID_JOYPAD_L3      14
#define RETRO_DEVICE_ID_JOYPAD_R3      15
#define RETRO_DEVICE_ID_JOYPAD_MASK   256

/* ── Device type mask (strip subclass bits) ────────────────────── */
#define RETRO_DEVICE_TYPE_SHIFT   8
#define RETRO_DEVICE_MASK         ((1 << RETRO_DEVICE_TYPE_SHIFT) - 1)

/* ── Pointer device IDs ────────────────────────────────────────── */
#define RETRO_DEVICE_ID_POINTER_X       0
#define RETRO_DEVICE_ID_POINTER_Y       1
#define RETRO_DEVICE_ID_POINTER_PRESSED 2

/* ── Analog indices ────────────────────────────────────────────── */
#define RETRO_DEVICE_INDEX_ANALOG_LEFT   0
#define RETRO_DEVICE_INDEX_ANALOG_RIGHT  1
#define RETRO_DEVICE_ID_ANALOG_X         0
#define RETRO_DEVICE_ID_ANALOG_Y         1

/* ── Memory types ──────────────────────────────────────────────── */
#define RETRO_MEMORY_SAVE_RAM    0
#define RETRO_MEMORY_RTC         1
#define RETRO_MEMORY_SYSTEM_RAM  2
#define RETRO_MEMORY_VIDEO_RAM   3

/* ── Language ──────────────────────────────────────────────────── */
#define RETRO_LANGUAGE_ENGLISH 0

/* ── Log levels ────────────────────────────────────────────────── */
enum retro_log_level {
    RETRO_LOG_DEBUG = 0,
    RETRO_LOG_INFO,
    RETRO_LOG_WARN,
    RETRO_LOG_ERROR
};

/* ── Callback typedefs ─────────────────────────────────────────── */
typedef bool (*retro_environment_t)(unsigned cmd, void *data);
typedef void (*retro_video_refresh_t)(const void *data, unsigned width,
                                      unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device,
                                       unsigned index, unsigned id);

/* ── Hardware rendering callbacks ──────────────────────────────── */
typedef void (*retro_hw_context_reset_t)(void);
typedef uintptr_t (*retro_hw_get_current_framebuffer_t)(void);
typedef void (*retro_proc_address_t)(void);
typedef retro_proc_address_t (*retro_hw_get_proc_address_t)(const char *sym);

struct retro_hw_render_callback {
    unsigned context_type;
    retro_hw_context_reset_t context_reset;
    retro_hw_get_current_framebuffer_t get_current_framebuffer;
    retro_hw_get_proc_address_t get_proc_address;  /* actually retro_hw_get_proc_address_t */
    bool depth;
    bool stencil;
    bool bottom_left_origin;
    unsigned version_major;
    unsigned version_minor;
    bool cache_context;
    retro_hw_context_reset_t context_destroy;
    bool debug_context;
};

/* ── Structures ────────────────────────────────────────────────── */
struct retro_system_info {
    const char *library_name;
    const char *library_version;
    const char *valid_extensions;
    bool need_fullpath;
    bool block_extract;
};

struct retro_system_av_info {
    struct {
        unsigned base_width;
        unsigned base_height;
        unsigned max_width;
        unsigned max_height;
        float aspect_ratio;
    } geometry;
    struct {
        double fps;
        double sample_rate;
    } timing;
};

struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
};

struct retro_log_callback {
    void (*log)(enum retro_log_level level, const char *fmt, ...);
};

struct retro_variable {
    const char *key;
    const char *value;
};

struct retro_game_geometry {
    unsigned base_width;
    unsigned base_height;
    unsigned max_width;
    unsigned max_height;
    float aspect_ratio;
};

/* ── Core options v1 structures ─────────────────────────────────── */
struct retro_core_option_value {
    const char *value;
    const char *label;
};

#define RETRO_NUM_CORE_OPTION_VALUES_MAX 128

struct retro_core_option_definition {
    const char *key;
    const char *desc;
    const char *info;
    struct retro_core_option_value values[RETRO_NUM_CORE_OPTION_VALUES_MAX];
    const char *default_value;
};

struct retro_core_options_intl {
    struct retro_core_option_definition *us;
    struct retro_core_option_definition *local;
};

/* ── Core options v2 structures ─────────────────────────────────── */
struct retro_core_option_v2_category {
    const char *key;
    const char *desc;
    const char *info;
};

struct retro_core_option_v2_definition {
    const char *key;
    const char *desc;
    const char *desc_categorized;
    const char *info;
    const char *info_categorized;
    const char *category_key;
    struct retro_core_option_value values[RETRO_NUM_CORE_OPTION_VALUES_MAX];
    const char *default_value;
};

struct retro_core_options_v2 {
    struct retro_core_option_v2_category *categories;
    struct retro_core_option_v2_definition *definitions;
};

struct retro_core_options_v2_intl {
    struct retro_core_options_v2 *us;
    struct retro_core_options_v2 *local;
};

struct retro_core_option_display {
    const char *key;
    bool visible;
};

/* ── Input descriptor ──────────────────────────────────────────── */
struct retro_input_descriptor {
    unsigned port;
    unsigned device;
    unsigned index;
    unsigned id;
    const char *description;
};

struct retro_controller_description {
    const char *desc;
    unsigned id;
};

struct retro_controller_info {
    const struct retro_controller_description *types;
    unsigned num_types;
};

/* ── Disk control interface ────────────────────────────────────── */
typedef bool (*retro_set_eject_state_t)(bool ejected);
typedef bool (*retro_get_eject_state_t)(void);
typedef unsigned (*retro_get_image_index_t)(void);
typedef bool (*retro_set_image_index_t)(unsigned index);
typedef unsigned (*retro_get_num_images_t)(void);
typedef bool (*retro_replace_image_index_t)(unsigned index, const struct retro_game_info *info);
typedef bool (*retro_add_image_index_t)(void);

struct retro_disk_control_callback {
    retro_set_eject_state_t set_eject_state;
    retro_get_eject_state_t get_eject_state;
    retro_get_image_index_t get_image_index;
    retro_set_image_index_t set_image_index;
    retro_get_num_images_t get_num_images;
    retro_replace_image_index_t replace_image_index;
    retro_add_image_index_t add_image_index;
};

/* ── Rumble interface ──────────────────────────────────────────── */
#define RETRO_RUMBLE_STRONG 0
#define RETRO_RUMBLE_WEAK   1

typedef bool (*retro_set_rumble_state_t)(unsigned port, unsigned effect, uint16_t strength);

struct retro_rumble_interface {
    retro_set_rumble_state_t set_rumble_state;
};

/* ── Performance interface ─────────────────────────────────────── */
typedef int64_t retro_perf_tick_t;
typedef int64_t retro_time_t;

struct retro_perf_counter {
    const char *ident;
    retro_perf_tick_t start;
    retro_perf_tick_t total;
    retro_perf_tick_t call_cnt;
    bool registered;
};

typedef retro_time_t (*retro_perf_get_time_usec_t)(void);
typedef retro_perf_tick_t (*retro_perf_get_counter_t)(void);
typedef uint64_t (*retro_get_cpu_features_t)(void);
typedef void (*retro_perf_log_t)(void);
typedef void (*retro_perf_register_t)(struct retro_perf_counter *counter);
typedef void (*retro_perf_start_t)(struct retro_perf_counter *counter);
typedef void (*retro_perf_stop_t)(struct retro_perf_counter *counter);

struct retro_perf_callback {
    retro_perf_get_time_usec_t get_time_usec;
    retro_perf_get_counter_t   get_cpu_features;
    retro_perf_get_counter_t   get_perf_counter;
    retro_perf_register_t      perf_register;
    retro_perf_start_t         perf_start;
    retro_perf_stop_t          perf_stop;
    retro_perf_log_t           perf_log;
};

/* ── Message ───────────────────────────────────────────────────── */
struct retro_message {
    const char *msg;
    unsigned frames;
};

struct retro_message_ext {
    const char *msg;
    unsigned duration;
    unsigned priority;
    int level;  /* retro_log_level */
    unsigned target;
    unsigned type;
    int       progress;   /* -1 = indeterminate */
};

/* ── Core function pointer typedefs ────────────────────────────── */
typedef void (*retro_init_t)(void);
typedef void (*retro_deinit_t)(void);
typedef unsigned (*retro_api_version_t)(void);
typedef void (*retro_get_system_info_t)(struct retro_system_info *info);
typedef void (*retro_get_system_av_info_t)(struct retro_system_av_info *info);
typedef void (*retro_set_environment_t)(retro_environment_t);
typedef void (*retro_set_video_refresh_t)(retro_video_refresh_t);
typedef void (*retro_set_audio_sample_t)(retro_audio_sample_t);
typedef void (*retro_set_audio_sample_batch_t)(retro_audio_sample_batch_t);
typedef void (*retro_set_input_poll_t)(retro_input_poll_t);
typedef void (*retro_set_input_state_t)(retro_input_state_t);
typedef bool (*retro_load_game_t)(const struct retro_game_info *game);
typedef void (*retro_unload_game_t)(void);
typedef void (*retro_run_t)(void);
typedef void (*retro_reset_t)(void);
typedef size_t (*retro_serialize_size_t)(void);
typedef bool (*retro_serialize_t)(void *data, size_t size);
typedef bool (*retro_unserialize_t)(const void *data, size_t size);
typedef void (*retro_set_controller_port_device_t)(unsigned port, unsigned device);
typedef void *(*retro_get_memory_data_t)(unsigned id);
typedef size_t (*retro_get_memory_size_t)(unsigned id);

#ifdef __cplusplus
}
#endif

#endif /* LIBRETRO_H__ */
