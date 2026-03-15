cmake_minimum_required(VERSION 3.22.1)
project("vortex_frontend" LANGUAGES C)

add_library(vortex_frontend SHARED
    vortex_frontend.c
)

target_link_libraries(vortex_frontend
    android
    log
    EGL
    GLESv3
    OpenSLES
    dl
)
