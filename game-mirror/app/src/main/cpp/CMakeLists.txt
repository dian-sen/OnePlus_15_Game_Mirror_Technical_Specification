cmake_minimum_required(VERSION 3.22.1)
project("touch_injector")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(touch_injector SHARED
        touch_injector.cpp
)

target_link_libraries(touch_injector
        android
        log
)

target_compile_options(touch_injector PRIVATE
        -O2
        -fno-exceptions
        -fno-rtti
        -Wall
        -Wextra
)