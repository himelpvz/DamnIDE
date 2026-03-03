cmake_minimum_required(VERSION 3.22.1)
project(terminal_native LANGUAGES CXX)

add_library(
    terminal_native
    SHARED
    terminal_jni.cpp
    runtime.cpp
    pty.cpp
    process.cpp
)

find_library(log-lib log)

target_link_libraries(
    terminal_native
    ${log-lib}
)
