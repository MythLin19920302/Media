// FFmpeg 视频解码器：C 这一层只做两件事——解封装（avformat）和解码（avcodec），输出 YUV。
// 什么时候显示、要不要丢帧、怎么画到屏幕上，都交给 Kotlin（见 player/ 目录），思路和 Media3 的软件解码扩展一样。
// Kotlin 侧见 ffmpeg/FFmpegVideoDecoder.kt。
#include <jni.h>
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/display.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>

// nativeDecodeNextFrame 的返回值：没有更多帧了（和 FFmpegVideoDecoder.END_OF_STREAM 一致）
#define END_OF_STREAM INT64_MIN

typedef struct Decoder {
  AVFormatContext *fmt;
  AVCodecContext *dec;
  AVStream *stream;
  int video_index;
  int width, height;          // 打开时的画面尺寸；输出给 Kotlin 的 YUV 永远是这个尺寸
  AVPacket *pkt;
  AVFrame *frame;
  AVFrame *converted;         // 解码器输出不是 8 位 yuv420p 时，先转到这里
  struct SwsContext *sws;
  bool draining;              // 文件读完了，正在让解码器吐出缓存里的最后几帧
  bool reported_first_frame;
} Decoder;

static void throw_io(JNIEnv *env, const char *what, int err) {
  char message[256];
  char reason[AV_ERROR_MAX_STRING_SIZE];
  av_strerror(err, reason, sizeof(reason));
  snprintf(message, sizeof(message), "%s: %s (%d)", what, reason, err);
  jclass cls = (*env)->FindClass(env, "java/io/IOException");
  if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}

static void close_decoder(Decoder *d) {
  if (d == NULL) return;
  sws_freeContext(d->sws);
  av_frame_free(&d->converted);
  av_frame_free(&d->frame);
  av_packet_free(&d->pkt);
  avcodec_free_context(&d->dec);
  avformat_close_input(&d->fmt);
  av_free(d);
}

// 视频显示前要顺时针转的角度：0 / 90 / 180 / 270。
// 算法同 fftools/cmdutils.c 的 get_rotation：av_display_rotation_get 给的是逆时针角度，取反再归一化。
static int stream_rotation(const AVStream *stream) {
  const AVPacketSideData *sd = av_packet_side_data_get(stream->codecpar->coded_side_data,
                                                       stream->codecpar->nb_coded_side_data,
                                                       AV_PKT_DATA_DISPLAYMATRIX);
  if (sd == NULL) return 0;
  double theta = -round(av_display_rotation_get((const int32_t *) sd->data));
  theta -= 360 * floor(theta / 360 + 0.9 / 360);
  return (int) (lround(theta / 90) * 90 % 360);
}

// 打开 fd 指向的视频，找到视频流并打开软件解码器。成功返回句柄；失败抛 IOException 并返回 0。
// 打开后会回调 Kotlin 的 onStreamInfo，把视频流的参数传过去。
JNIEXPORT jlong JNICALL
Java_com_example_music_ffmpeg_FFmpegVideoDecoder_nativeOpen(JNIEnv *env, jobject thiz, jint fd) {
  Decoder *d = av_mallocz(sizeof(Decoder));
  AVDictionary *options = NULL;
  const AVCodec *codec = NULL;
  char fd_text[16];
  int ret;

  if (d == NULL) {
    throw_io(env, "out of memory", AVERROR(ENOMEM));
    return 0;
  }
  d->pkt = av_packet_alloc();
  d->frame = av_frame_alloc();
  if (d->pkt == NULL || d->frame == NULL) {
    ret = AVERROR(ENOMEM);
    goto fail;
  }

  // ① 解封装：用 FFmpeg 的 fd 协议读 Kotlin 传来的文件描述符。URL 必须写成 "fd:"，fd 通过选项传
  snprintf(fd_text, sizeof(fd_text), "%d", fd);
  av_dict_set(&options, "fd", fd_text, 0);
  ret = avformat_open_input(&d->fmt, "fd:", NULL, &options);
  av_dict_free(&options);
  if (ret < 0) goto fail;
  if ((ret = avformat_find_stream_info(d->fmt, NULL)) < 0) goto fail;
  if ((ret = av_find_best_stream(d->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0)) < 0) goto fail;
  d->video_index = ret;
  d->stream = d->fmt->streams[d->video_index];
  d->width = d->stream->codecpar->width;
  d->height = d->stream->codecpar->height;

  // ② 打开软件解码器；thread_count = 0 表示按 CPU 核数自动多线程解码
  d->dec = avcodec_alloc_context3(codec);
  if (d->dec == NULL) {
    ret = AVERROR(ENOMEM);
    goto fail;
  }
  if ((ret = avcodec_parameters_to_context(d->dec, d->stream->codecpar)) < 0) goto fail;
  d->dec->thread_count = 0;
  if ((ret = avcodec_open2(d->dec, codec, NULL)) < 0) goto fail;

  // 把视频流的参数告诉 Kotlin：渲染需要知道尺寸、旋转和 YUV 用的是哪套系数
  {
    const AVCodecParameters *par = d->stream->codecpar;
    AVRational rate = d->stream->avg_frame_rate;
    double fps = rate.num > 0 && rate.den > 0 ? av_q2d(rate) : 0;
    const char *space_name = av_color_space_name(par->color_space);
    jclass cls = (*env)->GetObjectClass(env, thiz);
    jmethodID on_stream_info = (*env)->GetMethodID(env, cls, "onStreamInfo",
                                                   "(Ljava/lang/String;IIIDJILjava/lang/String;I)V");
    (*env)->DeleteLocalRef(env, cls);
    if (on_stream_info == NULL) goto fail_with_java_exception;
    jstring j_codec = (*env)->NewStringUTF(env, codec->name);
    jstring j_space = (*env)->NewStringUTF(env, space_name != NULL ? space_name : "unknown");
    (*env)->CallVoidMethod(env, thiz, on_stream_info, j_codec, d->width, d->height,
                           stream_rotation(d->stream), fps,
                           (jlong) (d->fmt->duration != AV_NOPTS_VALUE ? d->fmt->duration : -1),
                           (jint) par->color_space, j_space, (jint) par->color_range);
    (*env)->DeleteLocalRef(env, j_codec);
    (*env)->DeleteLocalRef(env, j_space);
    if ((*env)->ExceptionCheck(env)) goto fail_with_java_exception;
  }
  return (jlong) (intptr_t) d;

fail:
  throw_io(env, "open video failed", ret);
fail_with_java_exception:
  close_decoder(d);
  return 0;
}

// 把一帧拷进 Kotlin 的三个 direct ByteBuffer：Y、U、V 三个平面，每个平面紧密排列（去掉每行的对齐填充）。
// 解码器输出的不是 8 位 yuv420p（比如 10 位视频的 yuv420p10le），或者尺寸变了，先用 swscale 转一下。
static int copy_to_buffers(Decoder *d, const AVFrame *src, uint8_t *dst[3]) {
  const AVFrame *f = src;
  bool plain = (src->format == AV_PIX_FMT_YUV420P || src->format == AV_PIX_FMT_YUVJ420P) &&
               src->width == d->width && src->height == d->height;
  if (!plain) {
    if (d->converted == NULL) {
      d->converted = av_frame_alloc();
      if (d->converted == NULL) return AVERROR(ENOMEM);
      d->converted->format = AV_PIX_FMT_YUV420P;
      d->converted->width = d->width;
      d->converted->height = d->height;
      int ret = av_frame_get_buffer(d->converted, 0);
      if (ret < 0) return ret;
    }
    d->sws = sws_getCachedContext(d->sws, src->width, src->height, src->format,
                                  d->width, d->height, AV_PIX_FMT_YUV420P,
                                  SWS_BILINEAR, NULL, NULL, NULL);
    if (d->sws == NULL) return AVERROR(ENOSYS);
    sws_scale(d->sws, (const uint8_t *const *) src->data, src->linesize, 0, src->height,
              d->converted->data, d->converted->linesize);
    f = d->converted;
  }
  int chroma_w = (d->width + 1) / 2;
  int chroma_h = (d->height + 1) / 2;
  // 源数据每行 linesize 字节（可能大于宽度），目标每行正好是宽度，所以必须逐行拷
  av_image_copy_plane(dst[0], d->width, f->data[0], f->linesize[0], d->width, d->height);
  av_image_copy_plane(dst[1], chroma_w, f->data[1], f->linesize[1], chroma_w, chroma_h);
  av_image_copy_plane(dst[2], chroma_w, f->data[2], f->linesize[2], chroma_w, chroma_h);
  return 0;
}

// 第一帧解出来时回调 Kotlin 的 onFirstFrame：解码器实际输出的像素格式和每行字节数
static void report_first_frame(JNIEnv *env, jobject thiz, const AVFrame *frame) {
  jclass cls = (*env)->GetObjectClass(env, thiz);
  jmethodID on_first_frame = (*env)->GetMethodID(env, cls, "onFirstFrame", "(Ljava/lang/String;III)V");
  (*env)->DeleteLocalRef(env, cls);
  if (on_first_frame == NULL) return;
  const char *name = av_get_pix_fmt_name(frame->format);
  jstring j_name = (*env)->NewStringUTF(env, name != NULL ? name : "unknown");
  (*env)->CallVoidMethod(env, thiz, on_first_frame, j_name,
                         frame->linesize[0], frame->linesize[1], frame->linesize[2]);
  (*env)->DeleteLocalRef(env, j_name);
}

// 解出下一帧，写进 y / u / v 三个 direct ByteBuffer。
// 返回这一帧的显示时间（微秒，按视频流自己的时间轴）；没有更多帧时返回 END_OF_STREAM；出错抛 IOException。
JNIEXPORT jlong JNICALL
Java_com_example_music_ffmpeg_FFmpegVideoDecoder_nativeDecodeNextFrame(JNIEnv *env, jobject thiz, jlong handle,
                                                                      jobject y, jobject u, jobject v) {
  Decoder *d = (Decoder *) (intptr_t) handle;
  int64_t chroma_size = (int64_t) ((d->width + 1) / 2) * ((d->height + 1) / 2);
  uint8_t *dst[3] = {
      (*env)->GetDirectBufferAddress(env, y),
      (*env)->GetDirectBufferAddress(env, u),
      (*env)->GetDirectBufferAddress(env, v),
  };
  if (dst[0] == NULL || dst[1] == NULL || dst[2] == NULL ||
      (*env)->GetDirectBufferCapacity(env, y) < (int64_t) d->width * d->height ||
      (*env)->GetDirectBufferCapacity(env, u) < chroma_size ||
      (*env)->GetDirectBufferCapacity(env, v) < chroma_size) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, "YUV buffers must be direct and large enough");
    return END_OF_STREAM;
  }

  for (;;) {
    // ③ 先问解码器要一帧；它说"还要更多数据"（EAGAIN）时，再读一个视频包喂进去
    int ret = avcodec_receive_frame(d->dec, d->frame);
    if (ret == 0) {
      if (!d->reported_first_frame) {
        d->reported_first_frame = true;
        report_first_frame(env, thiz, d->frame);
        if ((*env)->ExceptionCheck(env)) return END_OF_STREAM;
      }
      ret = copy_to_buffers(d, d->frame, dst);
      int64_t pts = d->frame->best_effort_timestamp;
      av_frame_unref(d->frame);
      if (ret < 0) {
        throw_io(env, "convert frame failed", ret);
        return END_OF_STREAM;
      }
      return pts == AV_NOPTS_VALUE ? 0 : av_rescale_q(pts, d->stream->time_base, AV_TIME_BASE_Q);
    }
    if (ret == AVERROR_EOF) return END_OF_STREAM;
    if (ret != AVERROR(EAGAIN)) {
      throw_io(env, "decode failed", ret);
      return END_OF_STREAM;
    }
    if (d->draining) return END_OF_STREAM;  // 防御：已经送过空包，不会再有新数据

    ret = av_read_frame(d->fmt, d->pkt);
    if (ret < 0) {
      // 文件读完了：送一个空包，让解码器把内部缓存的最后几帧吐出来
      d->draining = true;
      avcodec_send_packet(d->dec, NULL);
      continue;
    }
    if (d->pkt->stream_index != d->video_index) {  // 音频包直接丢掉
      av_packet_unref(d->pkt);
      continue;
    }
    ret = avcodec_send_packet(d->dec, d->pkt);
    av_packet_unref(d->pkt);
    if (ret < 0 && ret != AVERROR_INVALIDDATA) {  // 个别损坏的包跳过，其他错误报出去
      throw_io(env, "send packet failed", ret);
      return END_OF_STREAM;
    }
  }
}

JNIEXPORT void JNICALL
Java_com_example_music_ffmpeg_FFmpegVideoDecoder_nativeClose(JNIEnv *env, jobject thiz, jlong handle) {
  close_decoder((Decoder *) (intptr_t) handle);
}
