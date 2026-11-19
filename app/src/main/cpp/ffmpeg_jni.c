// Kotlin 和 FFmpeg 之间的桥：这里每个 JNI 函数对应 FFmpegBridge.kt 里的一个 external fun。
#include <jni.h>
#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>

#define MAX_NAMES 512

// 把 C 字符串数组转成 Kotlin 的 Array<String>。
static jobjectArray to_string_array(JNIEnv *env, const char **names, int count) {
  jclass string_class = (*env)->FindClass(env, "java/lang/String");
  jobjectArray array = (*env)->NewObjectArray(env, count, string_class, NULL);
  if (array == NULL) return NULL;
  for (int i = 0; i < count; i++) {
    jstring name = (*env)->NewStringUTF(env, names[i]);
    (*env)->SetObjectArrayElement(env, array, i, name);
    (*env)->DeleteLocalRef(env, name);
  }
  return array;
}

// 对应 FFmpegBridge.versionInfo()：返回编进来的 FFmpeg 版本号。
JNIEXPORT jstring JNICALL
Java_com_example_music_ffmpeg_FFmpegBridge_versionInfo(JNIEnv *env, jobject thiz) {
  return (*env)->NewStringUTF(env, av_version_info());
}

// 对应 FFmpegBridge.libraryVersions()：6 个库各调用一次，顺序要和 Kotlin 侧的 libraryNames 一致。
// 每个库都被调用到，也就验证了 6 个 .so 都能加载、符号都能解析。
JNIEXPORT jintArray JNICALL
Java_com_example_music_ffmpeg_FFmpegBridge_libraryVersions(JNIEnv *env, jobject thiz) {
  const jint versions[] = {
      (jint) avutil_version(),  (jint) avcodec_version(), (jint) avformat_version(),
      (jint) avfilter_version(), (jint) swscale_version(), (jint) swresample_version(),
  };
  const jsize count = sizeof(versions) / sizeof(versions[0]);
  jintArray array = (*env)->NewIntArray(env, count);
  if (array != NULL) (*env)->SetIntArrayRegion(env, array, 0, count, versions);
  return array;
}

// 对应 FFmpegBridge.listComponents(kind)：列出某一类已启用的组件名。
// kind 的取值要和 Kotlin 侧的 FFmpegComponent.id 一致。
JNIEXPORT jobjectArray JNICALL
Java_com_example_music_ffmpeg_FFmpegBridge_listComponents(JNIEnv *env, jobject thiz, jint kind) {
  const char *names[MAX_NAMES];
  int count = 0;
  void *it = NULL;

  switch (kind) {
    case 0:    // 解码器
    case 1: {  // 编码器
      const AVCodec *codec;
      while (count < MAX_NAMES && (codec = av_codec_iterate(&it)) != NULL) {
        int wanted = kind == 0 ? av_codec_is_decoder(codec) : av_codec_is_encoder(codec);
        if (wanted) names[count++] = codec->name;
      }
      break;
    }
    case 2: {  // 解封装
      const AVInputFormat *format;
      while (count < MAX_NAMES && (format = av_demuxer_iterate(&it)) != NULL) {
        names[count++] = format->name;
      }
      break;
    }
    case 3: {  // 封装
      const AVOutputFormat *format;
      while (count < MAX_NAMES && (format = av_muxer_iterate(&it)) != NULL) {
        names[count++] = format->name;
      }
      break;
    }
    case 4: {  // 滤镜
      const AVFilter *filter;
      while (count < MAX_NAMES && (filter = av_filter_iterate(&it)) != NULL) {
        names[count++] = filter->name;
      }
      break;
    }
    case 5: {  // 输入协议
      const char *protocol;
      while (count < MAX_NAMES && (protocol = avio_enum_protocols(&it, 0)) != NULL) {
        names[count++] = protocol;
      }
      break;
    }
    default:
      break;
  }
  return to_string_array(env, names, count);
}

// 对应 FFmpegBridge.errorString(code)：把 FFmpeg 的负数错误码转成可读的文字。
JNIEXPORT jstring JNICALL
Java_com_example_music_ffmpeg_FFmpegBridge_errorString(JNIEnv *env, jobject thiz, jint code) {
  char message[AV_ERROR_MAX_STRING_SIZE];
  av_strerror(code, message, sizeof(message));
  return (*env)->NewStringUTF(env, message);
}
