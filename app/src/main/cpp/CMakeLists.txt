cmake_minimum_required(VERSION 3.10)

project(rfm_app_native)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections")

set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/lame)
set(LAME_SOURCES
    ${LAME_DIR}/libmp3lame/bitstream.c
    ${LAME_DIR}/libmp3lame/encoder.c
    ${LAME_DIR}/libmp3lame/fft.c
    ${LAME_DIR}/libmp3lame/gain_analysis.c
    ${LAME_DIR}/libmp3lame/id3tag.c
    ${LAME_DIR}/libmp3lame/lame.c
    ${LAME_DIR}/libmp3lame/mpglib_interface.c
    ${LAME_DIR}/libmp3lame/newmdct.c
    ${LAME_DIR}/libmp3lame/presets.c
    ${LAME_DIR}/libmp3lame/psymodel.c
    ${LAME_DIR}/libmp3lame/quantize.c
    ${LAME_DIR}/libmp3lame/quantize_pvt.c
    ${LAME_DIR}/libmp3lame/reservoir.c
    ${LAME_DIR}/libmp3lame/set_get.c
    ${LAME_DIR}/libmp3lame/tables.c
    ${LAME_DIR}/libmp3lame/takehiro.c
    ${LAME_DIR}/libmp3lame/util.c
    ${LAME_DIR}/libmp3lame/VbrTag.c
    ${LAME_DIR}/libmp3lame/vbrquantize.c
    ${LAME_DIR}/libmp3lame/version.c
)

add_library(mp3encoder SHARED
    lame_encoder_jni.c
    ${LAME_SOURCES}
)

target_include_directories(mp3encoder PRIVATE
    ${LAME_DIR}/include
    ${LAME_DIR}/libmp3lame
)

target_compile_definitions(mp3encoder PRIVATE
    STDC_HEADERS=1
    HAVE_STDINT_H=1
    HAVE_INTTYPES_H=1
    HAVE_ERRNO_H=1
    HAVE_STRING_H=1
    HAVE_LIMITS_H=1
    ieee754_float32_t=float
)

target_compile_options(mp3encoder PRIVATE
    -Wno-absolute-value
    -Wno-shift-negative-value
    -Wno-tautological-pointer-compare
)

target_link_libraries(mp3encoder log m)
