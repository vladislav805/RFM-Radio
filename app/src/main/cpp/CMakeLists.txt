cmake_minimum_required(VERSION 3.16)

project(rfm_app_native)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections")

set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/lame)

if(NOT EXISTS "${LAME_DIR}/include/lame.h")
    message(FATAL_ERROR "LAME submodule is missing. Run: git submodule update --init --recursive")
endif()

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
    ${LAME_DIR}
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

target_link_libraries(mp3encoder m)
if(ANDROID)
    target_link_libraries(mp3encoder log)
else()
    # Also allow host builds of the same JNI encoder for MP3 round-trip checks.
    find_package(JNI REQUIRED)
    target_include_directories(mp3encoder PRIVATE ${JNI_INCLUDE_DIRS})
endif()

set(OPUS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/opus)
set(OPUSENC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libopusenc)
if(NOT EXISTS "${OPUS_DIR}/CMakeLists.txt" OR NOT EXISTS "${OPUSENC_DIR}/include/opusenc.h")
    message(FATAL_ERROR "Opus submodules are missing. Run: git submodule update --init --recursive")
endif()

add_subdirectory(${OPUS_DIR} opus EXCLUDE_FROM_ALL)
set_target_properties(opus PROPERTIES POSITION_INDEPENDENT_CODE ON)
add_library(opusencoder SHARED
    opus_encoder_jni.c
    ${OPUSENC_DIR}/src/ogg_packer.c
    ${OPUSENC_DIR}/src/opus_header.c
    ${OPUSENC_DIR}/src/opusenc.c
    ${OPUSENC_DIR}/src/resample.c
)
target_include_directories(opusencoder PRIVATE ${OPUSENC_DIR}/include)
target_compile_definitions(opusencoder PRIVATE
    OPE_EXPORT= OUTSIDE_SPEEX RANDOM_PREFIX=libopusenc FLOATING_POINT
    PACKAGE_NAME="libopusenc" PACKAGE_VERSION="0.3"
)
if(ANDROID)
    target_compile_options(opus PRIVATE -Os)
    target_compile_options(opusencoder PRIVATE -Os)
endif()
set_target_properties(opusencoder PROPERTIES C_VISIBILITY_PRESET hidden)
# Keep only the codec functions reachable from JNI in the packaged library.
target_link_options(opusencoder PRIVATE "-Wl,--exclude-libs,ALL")
target_link_libraries(opusencoder PRIVATE opus m)
if(NOT ANDROID)
    target_include_directories(opusencoder PRIVATE ${JNI_INCLUDE_DIRS})
endif()
