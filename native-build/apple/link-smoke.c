#include <libavcodec/avcodec.h>
#include <libavdevice/avdevice.h>
#include <libavfilter/avfilter.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>

int main(void) {
    const AVCodec *encoder = avcodec_find_encoder_by_name("h264_videotoolbox");
    return encoder == NULL ||
        avcodec_version() == 0 || avdevice_version() == 0 ||
        avfilter_version() == 0 || avformat_version() == 0 ||
        avutil_version() == 0 || swresample_version() == 0 ||
        swscale_version() == 0;
}
