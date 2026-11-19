#!/usr/bin/env bash
# 为 Android 配置 FFmpeg（只做 configure，不编译），产出动态库 .so。
# 用法：ffmpeg/configure_android.sh <arm64-v8a|armeabi-v7a>
# 之后再执行：make -C ffmpeg/build/<abi> -j10 && make -C ffmpeg/build/<abi> install
# 想一步到位（下载源码 + 编全部 ABI + 校验）用同目录的 build_android.sh，它会调用本脚本。
set -euo pipefail

# 每个 ABI 不同的只有三样：CPU 架构、指令集版本、编译器前缀
ABI="${1:-}"
case "$ABI" in
  arm64-v8a)   ARCH=aarch64; CPU=armv8-a; TRIPLE=aarch64-linux-android ;;
  armeabi-v7a) ARCH=arm;     CPU=armv7-a; TRIPLE=armv7a-linux-androideabi ;;
  *) echo "用法：$0 <arm64-v8a|armeabi-v7a>" >&2; exit 1 ;;
esac

ROOT="$(cd "$(dirname "$0")" && pwd)"   # .../music_demo/ffmpeg
SRC="$ROOT/src"                          # FFmpeg 源码（git clone 到这里）
BUILD="$ROOT/build/$ABI"                 # 每个 ABI 一个独立的构建目录，互不干扰
OUT="$ROOT/out/$ABI"                     # make install 的目标目录

API=24                                   # 对应 app 的 minSdk
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.2.12479018}"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"   # M 芯片 Mac 也是这个目录名

[ -x "$TC/bin/$TRIPLE$API-clang" ] || { echo "找不到 NDK 编译器：$TC/bin/$TRIPLE$API-clang" >&2; exit 1; }
[ -f "$SRC/configure" ] || { echo "找不到 FFmpeg 源码：$SRC（先 git clone）" >&2; exit 1; }
if [ -f "$SRC/config.h" ]; then
  echo "源码目录里有上一次的构建结果，先清掉：make -C $SRC distclean" >&2; exit 1
fi

ARGS=(
  # ① 产物装到哪里
  --prefix="$OUT"

  # ② 交叉编译：目标是 Android，编出来的测试程序只编不跑
  --enable-cross-compile --target-os=android --arch="$ARCH" --cpu="$CPU"

  # ③ 工具链：NDK 的 clang，以及能读写 ELF 的 ar/nm/ranlib/strip
  --cc="$TC/bin/$TRIPLE$API-clang"
  --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm"
  --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip"

  # ④ 头文件和系统库去 Android 那一套里找（Bionic libc），而不是 Mac 的
  --sysroot="$TC/sysroot"

  # ⑤ 产物形态：动态库 .so；16KB 页对齐是 Android 15+ 64 位设备的要求（32 位用不到，加了无害）
  --enable-shared --disable-static --enable-pic
  --extra-ldflags="-Wl,-z,max-page-size=16384"
  --disable-programs --disable-doc --disable-avdevice

  # ⑥ Android 相关能力：JNI、MediaCodec 硬编解码、zlib（PNG 解码要用）
  --enable-jni --enable-mediacodec --enable-zlib

  # ⑦ 裁剪：先全关，再只开用得到的组件
  --disable-everything
  --enable-protocol=file,fd
  --enable-demuxer=mov,mp3,flac,wav,ogg,aac,matroska,concat,image2
  --enable-decoder=h264,hevc,aac,mp3,flac,vorbis,opus,png
  --enable-parser=h264,hevc,aac,mpegaudio,flac,vorbis,opus,png
  --enable-encoder=h264_mediacodec,aac
  --enable-muxer=mp4
  --enable-bsf=h264_mp4toannexb,hevc_mp4toannexb,aac_adtstoasc,extract_extradata
  --enable-filter=scale,crop,overlay,format,setsar,fps,transpose,hflip,vflip,aresample,aformat,null,anull
)

mkdir -p "$BUILD" && cd "$BUILD"
"$SRC/configure" "${ARGS[@]}"
