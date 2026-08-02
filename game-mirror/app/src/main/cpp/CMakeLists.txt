cmake_minimum_required(VERSION 3.22.1)
project("touch_injector")

# 编译选项
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 触控注入库
add_library(touch_injector SHARED
        touch_injector.cpp
)

# 链接系统库
target_link_libraries(touch_injector
        android
        log
)

# 编译优化：O2 + 零异常（减少运行时开销）
target_compile_options(touch_injector PRIVATE
        -O2
        -fno-exceptions
        -fno-rtti
        -Wall
        -Wextra
)