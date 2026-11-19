#!/usr/bin/env bash
# 从零开始，一条命令编出 Android 用的 FFmpeg 动态库（.so）。
#
# 流程：
#   ① 检查环境       git、make 是否可用，ABI 参数是否正确
#   ② 准备源码       ffmpeg/src 不存在就按 FFMPEG_TAG 下载；存在则核对版本
#   ③ 逐个 ABI 构建   清理旧文件 → 配置 → 编译 → 安装
#   ④ 校验并汇总     每个 .so 是否存在、架构是否正确、各自大小
#   ⑤ 同步到 app     .so 拷到 app/libs/<abi>/，头文件拷到 app/src/main/cpp/include/
#
# 用法：ffmpeg/build_android.sh              编全部 ABI
#       ffmpeg/build_android.sh arm64-v8a    只编指定的 ABI
# 详细输出不打印到屏幕，写在 ffmpeg/build/<abi>/build.log 里；出错时会显示日志末尾。
set -euo pipefail

FFMPEG_TAG="n8.1.3"                                   # 升级 FFmpeg 时改这一行
FFMPEG_REPO="https://github.com/FFmpeg/FFmpeg.git"
ALL_ABIS=(arm64-v8a armeabi-v7a)
LIBS=(libavcodec libavfilter libavformat libavutil libswresample libswscale)

ROOT="$(cd "$(dirname "$0")" && pwd)"                 # .../music_demo/ffmpeg
SRC="$ROOT/src"
JOBS="$(sysctl -n hw.ncpu)"                           # 并行编译用的核数
PROJECT="$(cd "$ROOT/.." && pwd)"                     # .../music_demo
APP_LIBS="$PROJECT/app/libs"                          # .so 同步到这里：app/libs/<abi>/
APP_INCLUDE="$PROJECT/app/src/main/cpp/include"       # 头文件同步到这里

if [ $# -gt 0 ]; then ABIS=("$@"); else ABIS=("${ALL_ABIS[@]}"); fi

# 执行一步并计时；输出写进日志，失败时显示日志末尾后退出
run_step() {   # run_step <步骤名> <日志文件> <命令...>
  local title="$1" log="$2" start=$SECONDS
  shift 2
  printf '  %s…… ' "$title"
  if "$@" >> "$log" 2>&1; then
    echo "完成（$((SECONDS - start)) 秒）"
  else
    echo "失败。日志最后 30 行："
    tail -n 30 "$log"
    echo "完整日志：$log"
    exit 1
  fi
}

# ---------- ① 检查环境 ----------
echo "① 检查环境"
for TOOL in git make file; do
  command -v "$TOOL" > /dev/null || { echo "  找不到命令：$TOOL" >&2; exit 1; }
done
for ABI in "${ABIS[@]}"; do
  case "$ABI" in
    arm64-v8a|armeabi-v7a) ;;
    *) echo "  不支持的 ABI：$ABI（可选：${ALL_ABIS[*]}）" >&2; exit 1 ;;
  esac
done
[ -d "$PROJECT/app" ] || { echo "  找不到 app 模块：$PROJECT/app" >&2; exit 1; }
echo "  要构建的 ABI：${ABIS[*]}"

# ---------- ② 准备源码 ----------
echo "② 准备源码（$FFMPEG_TAG）"
if [ ! -f "$SRC/configure" ]; then
  [ ! -e "$SRC" ] || { echo "  $SRC 已存在但不是 FFmpeg 源码，请先处理它" >&2; exit 1; }
  echo "  正在下载到 $SRC ……"
  git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$FFMPEG_TAG" "$FFMPEG_REPO" "$SRC"
fi
HAVE="n$(cat "$SRC/RELEASE")"
if [ "$HAVE" != "$FFMPEG_TAG" ]; then
  echo "  源码版本是 $HAVE，但脚本要求 $FFMPEG_TAG。" >&2
  echo "  确认不需要保留后删掉 $SRC 再重跑，脚本会重新下载。" >&2
  exit 1
fi
if [ -f "$SRC/config.h" ]; then
  echo "  源码目录里有旧的构建结果，先清理"
  make -C "$SRC" distclean > /dev/null
fi
echo "  源码就绪：$HAVE"

# ---------- ③ 逐个 ABI 构建 ----------
for ABI in "${ABIS[@]}"; do
  echo "③ 构建 $ABI"
  BUILD="$ROOT/build/$ABI"
  LOG="$BUILD/build.log"
  # 全部重来：避免旧的中间文件、旧产物和新配置对不上
  rm -rf "$BUILD" "$ROOT/out/$ABI"
  mkdir -p "$BUILD"
  run_step "配置" "$LOG" "$ROOT/configure_android.sh" "$ABI"
  run_step "编译" "$LOG" make -C "$BUILD" -j"$JOBS"
  run_step "安装" "$LOG" make -C "$BUILD" install
done

# ---------- ④ 校验并汇总 ----------
echo "④ 校验产物"
for ABI in "${ABIS[@]}"; do
  case "$ABI" in
    arm64-v8a)   WANT='64-bit.*aarch64' ;;
    armeabi-v7a) WANT='32-bit.*ARM' ;;
  esac
  DIR="$ROOT/out/$ABI/lib"
  for LIB in "${LIBS[@]}"; do
    [ -f "$DIR/$LIB.so" ] || { echo "  [$ABI] 缺少 $LIB.so" >&2; exit 1; }
    file "$DIR/$LIB.so" | grep -Eq "$WANT" || { echo "  [$ABI] $LIB.so 的架构不对" >&2; exit 1; }
  done
  TOTAL="$(cat "$DIR"/*.so | wc -c | awk '{printf "%.1f MB", $1 / 1048576}')"
  echo "  [$ABI] ${#LIBS[@]} 个 .so 架构正确，合计 $TOTAL"
  (cd "$DIR" && ls -lh *.so | awk '{printf "      %-20s %s\n", $NF, $5}')
done

# ---------- ⑤ 同步到 app 模块 ----------
# App 的 CMake 只认 app/libs 和 app/src/main/cpp/include，不直接引用 ffmpeg/out。
echo "⑤ 同步到 app 模块"
for ABI in "${ABIS[@]}"; do
  mkdir -p "$APP_LIBS/$ABI"
  for LIB in "${LIBS[@]}"; do
    cp -f "$ROOT/out/$ABI/lib/$LIB.so" "$APP_LIBS/$ABI/"
  done
  echo "  $APP_LIBS/$ABI"
done
# 头文件与 ABI 无关，取其中一个 ABI 的即可；只替换 FFmpeg 自己的那几个子目录
mkdir -p "$APP_INCLUDE"
for LIB in "${LIBS[@]}"; do
  rm -rf "$APP_INCLUDE/$LIB"
  cp -R "$ROOT/out/${ABIS[0]}/include/$LIB" "$APP_INCLUDE/$LIB"
done
echo "  $APP_INCLUDE"

echo
echo "全部完成（共 $SECONDS 秒）。"
