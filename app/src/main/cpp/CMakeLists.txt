cmake_minimum_required(VERSION 3.10.2)
project("foodrecognition-sdk")

# 设置 C++ 标准库为 shared 版本（确保包含 libc++_shared.so）
if(NOT DEFINED ANDROID_STL)
    set(ANDROID_STL c++_shared)
endif()

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 添加编译选项（禁用严格的类型比较警告）
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -Wno-sign-compare")

# 包含目录
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/rknn_include/include
    ${CMAKE_CURRENT_SOURCE_DIR}/rknn_src
    ${CMAKE_CURRENT_SOURCE_DIR}/rknn_utils
)

# RKNN 库路径
set(RKNN_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
get_filename_component(RKNN_LIB_DIR ${RKNN_LIB_DIR} ABSOLUTE)
set(RKNN_LIB_FILE ${RKNN_LIB_DIR}/librknnrt.so)

if(EXISTS ${RKNN_LIB_FILE})
    message(STATUS "Found RKNN library: ${RKNN_LIB_FILE}")
else()
    message(WARNING "RKNN library not found at ${RKNN_LIB_FILE}")
    message(WARNING "Please ensure librknnrt.so is in jniLibs/${ANDROID_ABI}/")
endif()

message(STATUS "RKNN lib dir: ${RKNN_LIB_DIR}")
message(STATUS "RKNN lib file: ${RKNN_LIB_FILE}")

# SQLite3 库（Android 系统自带）
find_library(SQLITE3_LIB sqlite3)
if(SQLITE3_LIB)
    message(STATUS "Found SQLite3 library: ${SQLITE3_LIB}")
    set(USE_SQLITE3 TRUE)
else()
    message(WARNING "SQLite3 library not found, feature_file.cc may fail to compile")
    message(WARNING "If compilation fails, consider removing feature_file.cc from SOURCES")
    set(USE_SQLITE3 FALSE)
endif()

# 源文件
set(SOURCES
    yolov6_detector_jni.cpp
    mobilenetv2_recognizer_jni.cpp
    rknn_common.h
    rknn_file_utils.cpp
    rknn_file_utils.h
    rknn_src/rknpu2/yolov6.cc
    rknn_src/mobilenet.cc
    rknn_src/postprocess.cc
    rknn_utils/file_utils.c
    rknn_utils/image_utils.c
)

# 创建共享库
add_library(
    foodrecognition-sdk
    SHARED
    ${SOURCES}
)

# 链接库
target_link_libraries(
    foodrecognition-sdk
    # C++ 标准库（必需的）
    c++_shared
    # Android log（用于打印调试信息）
    log
    # JNI
    jnigraphics
    # 原子操作库
    atomic
    # 数学库
    m
)

# 条件性链接 RKNN Runtime 库（只在找到库文件时链接）
if(EXISTS ${RKNN_LIB_FILE})
    target_link_libraries(foodrecognition-sdk ${RKNN_LIB_FILE})
    message(STATUS "Linking RKNN library: ${RKNN_LIB_FILE}")
else()
    message(WARNING "Skipping RKNN library link (not found): ${RKNN_LIB_FILE}")
    message(WARNING "Food recognition functionality may be limited without RKNN library")
endif()

# 条件性链接 SQLite3（如果找到）
if(USE_SQLITE3)
    target_link_libraries(foodrecognition-sdk ${SQLITE3_LIB})
    message(STATUS "Linking SQLite3 library: ${SQLITE3_LIB}")
else()
    message(STATUS "Skipping SQLite3 library (not found)")
    message(STATUS "Note: feature_file.cc may not work without SQLite3")
endif()