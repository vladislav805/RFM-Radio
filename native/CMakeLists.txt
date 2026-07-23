cmake_minimum_required(VERSION 3.10)

project(fmbin)

option(RFM_NATIVE_TESTS "Build host-side native tests" OFF)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
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

if(RFM_NATIVE_TESTS)
    find_package(GTest REQUIRED)
    enable_testing()
    add_executable(
        rfm_native_tests
        tests/rds_parser_test.cpp
        tests/frequency_format_test.cpp
        tests/ctl_json_test.cpp
        tests/startup_config_test.cpp
        tests/region_profile_test.cpp
        tests/legacy_scan_state_test.cpp
        rds_parser.cpp
        ctl_json.cpp
        startup_config.cpp
        region_profile.cpp
        legacy/scan_state.cpp
    )
    target_include_directories(rfm_native_tests PRIVATE .)
    target_link_libraries(rfm_native_tests GTest::gtest_main)
    include(GoogleTest)
    gtest_discover_tests(rfm_native_tests)
    return()
endif()

add_executable(
    fmbin
    main.cpp
    ctl_server.cpp
    ctl_json.cpp
    startup_config.cpp
    region_profile.cpp
    rds_parser.cpp
    backend_factory.cpp
    legacy_backend.cpp
    hal_backend.cpp
    hal/fm2_backend.cpp
    hal/utils.cpp
    legacy/fm_wrap.cpp
    legacy/fm_ctl.cpp
    legacy/scan_state.cpp
    legacy/utils.cpp
    legacy/detector.cpp
)

target_include_directories(
    fmbin
    PRIVATE
    .
    legacy
    hal
)

target_link_libraries(fmbin dl)
