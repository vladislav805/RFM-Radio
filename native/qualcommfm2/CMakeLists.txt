cmake_minimum_required(VERSION 3.10)

project(fmbin_fm2)

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
    fmbin_fm2
    main.cpp
    ctl_server.c
    fm2_backend.cpp
)

target_link_libraries(fmbin_fm2 dl)
