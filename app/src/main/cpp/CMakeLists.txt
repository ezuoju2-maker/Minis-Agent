cmake_minimum_required(VERSION 3.22.1)
project(minis_native CXX C)

add_library(pty_bridge SHARED pty_bridge.c)

add_library(minis_crash_handler SHARED crash_handler.cpp)

add_library(jieba_jni SHARED jieba_jni.cpp)
target_include_directories(jieba_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/cppjieba/include
)

find_library(log-lib log)
target_link_libraries(pty_bridge ${log-lib})
target_link_libraries(minis_crash_handler ${log-lib})
target_link_libraries(jieba_jni ${log-lib})

# [破甲] 宿主告警通道：JNI 侧只读自身进程状态，不上报、不改内存
add_library(frailhost SHARED frailhost.cpp)
target_link_libraries(frailhost ${log-lib})
