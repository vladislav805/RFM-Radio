cmake_minimum_required(VERSION 3.10)

project(fmbin)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -pthread -ffunction-sections -fdata-sections")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -pthread -ffunction-sections -fdata-sections")
set(CMAKE_C_FLAGS_RELEASE "-Os -flto -fno-unwind-tables -fno-asynchronous-unwind-tables -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-Os -flto -fno-unwind-tables -fno-asynchronous-unwind-tables -DNDEBUG")
set(CMAKE_EXE_LINKER_FLAGS_RELEASE "-Wl,--gc-sections -Wl,-s -flto")
set(CMAKE_C_FLAGS_DEBUG "-O0 -g -DDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O0 -g -DDEBUG")

if(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv7-a -mfpu=neon-vfpv4")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfpu=neon-vfpv4")
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8-a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a")
endif()

add_executable(
    fmbin
    main.cpp
    ctl_server.c
    backend_factory.cpp
    legacy_backend.cpp
    hal_backend.cpp
    hal/fm2_backend.cpp
    legacy/fm_wrap.c
    legacy/fm_ctl.c
    legacy/utils.c
    legacy/detector.c
)

target_include_directories(
    fmbin
    PRIVATE
    .
    legacy
    hal
)

target_link_libraries(fmbin dl)
