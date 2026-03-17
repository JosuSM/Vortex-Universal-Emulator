cmake_minimum_required(VERSION 3.22.1)
project("vortex_frontend" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# C++ frontend (with Vulkan display layer)
add_library(vortex_frontend SHARED
    vortex_frontend.cpp
    vortex_vulkan.cpp
)
target_link_libraries(vortex_frontend
    android
    log
    EGL
    GLESv3
    vulkan
    dl
)

# Pure C frontend (alternative for C-based cores)
add_library(vortex_frontend_c SHARED
    vortex_frontend_c.c
)
target_link_libraries(vortex_frontend_c
    android
    log
    EGL
    GLESv3
    dl
)
