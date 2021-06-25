# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

cmake_minimum_required(VERSION 3.4.1)

project(fmbin)

include_directories(.)

set(CMAKE_C_FLAGS "${CMAKE_CXX_FLAGS} -pthread")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS}")
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -DDEBUG")

add_executable(fmbin main.c ctl_server.c fm_wrap.c fm_ctl.c fm_ctl.h utils.c utils.h detector.c detector.h) # alsa.c asound.h
