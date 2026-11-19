# 把预编译好的 FFmpeg 动态库接进 App，并编出我们自己的 JNI 桥接库 libffmpeg_bridge.so。
# 所有 .so 都在 app/libs/<abi>/ 下：
#   - FFmpeg 的 6 个库由 ffmpeg/build_android.sh 编译后同步进来；
#   - libffmpeg_bridge.so 由本文件编译，直接输出到同一个目录。
# 头文件在 app/src/main/cpp/include/，同样由 ffmpeg/build_android.sh 同步。
cmake_minimum_required(VERSION 3.22.1)
project(ffmpeg_bridge C)

# ---------- ① 当前 ABI 对应的库目录和头文件目录 ----------
# Gradle 会为每个 ABI 各跑一遍本文件，ANDROID_ABI 分别是 arm64-v8a / armeabi-v7a。
get_filename_component(APP_LIBS_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/${ANDROID_ABI}" ABSOLUTE)
set(FFMPEG_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")
if(NOT EXISTS "${APP_LIBS_DIR}/libavcodec.so" OR
   NOT EXISTS "${FFMPEG_INCLUDE_DIR}/libavcodec/avcodec.h")
  message(FATAL_ERROR
      "找不到 ${ANDROID_ABI} 的 FFmpeg 库或头文件：${APP_LIBS_DIR}\n"
      "请先运行 ffmpeg/build_android.sh，它会编译并同步到 app/libs")
endif()

# ---------- ② 把 6 个预编译的 .so 声明成 CMake 能引用的目标 ----------
# IMPORTED 的意思是"这个库已经编好了，不需要 CMake 来编"，只要告诉它文件在哪。
# 被链接到的导入库会由 Gradle 自动打进 APK，所以不要再把它们放进 jniLibs。
set(FFMPEG_LIBS avformat avcodec avfilter swscale swresample avutil)
foreach(LIB ${FFMPEG_LIBS})
  add_library(${LIB} SHARED IMPORTED)
  set_target_properties(${LIB} PROPERTIES
      IMPORTED_LOCATION "${APP_LIBS_DIR}/lib${LIB}.so")
endforeach()

# ---------- ③ 合成一个名为 ffmpeg 的接口目标 ----------
# 谁链接 ffmpeg，谁就自动得到头文件路径和这 6 个库，不用每次重复写。
add_library(ffmpeg INTERFACE)
target_include_directories(ffmpeg INTERFACE "${FFMPEG_INCLUDE_DIR}")
target_link_libraries(ffmpeg INTERFACE ${FFMPEG_LIBS})

# ---------- ④ 我们自己的 JNI 桥接库 ----------
# 以后新增 .c 文件就加在这里。log 是 Android 的日志库，m 是数学库。
add_library(ffmpeg_bridge SHARED ffmpeg_jni.c video_decoder.c)
target_link_libraries(ffmpeg_bridge PRIVATE ffmpeg log m)
# 生成的 libffmpeg_bridge.so 直接输出到 app/libs/<abi>/，而不是留在 build 目录里。
# 注意：debug 和 release 构建写的是同一个文件，后编的会覆盖先编的。
set_target_properties(ffmpeg_bridge PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${APP_LIBS_DIR}")
