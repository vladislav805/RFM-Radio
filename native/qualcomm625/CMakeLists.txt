# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

cmake_minimum_required(VERSION 4.1)

project(fmbin)

include_directories(.)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -pthread -ffunction-sections -fdata-sections")
set(CMAKE_C_FLAGS_RELEASE "-Os -flto -fno-unwind-tables -fno-asynchronous-unwind-tables -DNDEBUG")
set(CMAKE_EXE_LINKER_FLAGS_RELEASE "-Wl,--gc-sections -Wl,-s -flto")

set(CMAKE_C_FLAGS_DEBUG "-O0 -g -DDEBUG")

if(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv7-a -mfpu=neon-vfpv4")
    set(CMAKE_SYSTEM_PROCESSOR armv7-a)
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8-a")
    set(CMAKE_SYSTEM_PROCESSOR aarch64)
endif()

add_executable(
    fmbin
    main.c
    ctl_server.c
    fm_wrap.c
    fm_ctl.c
    utils.c
    detector.c
)
