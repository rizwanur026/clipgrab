// ClipGrab AI Enhance: Real-ESRGAN (ncnn + Vulkan) frame upscaler for the Java video pipeline.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <string>
#include <vector>

#include "gpu.h"
#include "net.h"

#define TAG "ClipEnhance"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static ncnn::Net* g_net = nullptr;
static bool g_gpu_ready = false;
static int g_scale = 2;
static int g_tile = 256;
static const int PREPAD = 10;
static std::string g_in_name = "data";
static std::string g_out_name = "output";
static std::string g_gpu_name;

static void release_all() {
    delete g_net;
    g_net = nullptr;
    if (g_gpu_ready) {
        ncnn::destroy_gpu_instance();
        g_gpu_ready = false;
    }
}

extern "C" {

// 0 = ok, -1 = no Vulkan GPU, -2 = model failed to load
JNIEXPORT jint JNICALL Java_com_clipgrab_app_Enhancer_nInit(JNIEnv* env, jclass, jstring jparam, jstring jbin,
                                                             jint scale, jint tile) {
    release_all();
    ncnn::create_gpu_instance();
    g_gpu_ready = true;
    if (ncnn::get_gpu_count() <= 0) {
        LOGE("no Vulkan GPU");
        release_all();
        return -1;
    }
    g_gpu_name = ncnn::get_gpu_info(0).device_name();
    g_scale = scale;
    g_tile = std::max(64, (int)tile);

    g_net = new ncnn::Net();
    g_net->opt.use_vulkan_compute = true;
    g_net->opt.use_fp16_packed = true;
    g_net->opt.use_fp16_storage = true;
    g_net->opt.use_fp16_arithmetic = false;
    g_net->opt.use_int8_packed = false;
    g_net->opt.use_int8_storage = false;
    g_net->opt.use_int8_arithmetic = false;
    g_net->opt.num_threads = 4;
    g_net->set_vulkan_device(0);

    const char* param = env->GetStringUTFChars(jparam, nullptr);
    const char* bin = env->GetStringUTFChars(jbin, nullptr);
    int r1 = g_net->load_param(param);
    int r2 = r1 == 0 ? g_net->load_model(bin) : -1;
    env->ReleaseStringUTFChars(jparam, param);
    env->ReleaseStringUTFChars(jbin, bin);
    if (r1 != 0 || r2 != 0) {
        LOGE("model load failed %d %d", r1, r2);
        release_all();
        return -2;
    }
    const std::vector<const char*>& in = g_net->input_names();
    const std::vector<const char*>& out = g_net->output_names();
    if (!in.empty()) g_in_name = in[0];
    if (!out.empty()) g_out_name = out[0];
    LOGI("ready: gpu=%s scale=%d tile=%d in=%s out=%s", g_gpu_name.c_str(), g_scale, g_tile, g_in_name.c_str(),
         g_out_name.c_str());
    return 0;
}

JNIEXPORT jstring JNICALL Java_com_clipgrab_app_Enhancer_nGpu(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_gpu_name.c_str());
}

JNIEXPORT void JNICALL Java_com_clipgrab_app_Enhancer_nSetTile(JNIEnv*, jclass, jint tile) {
    g_tile = std::max(64, (int)tile);
}

JNIEXPORT void JNICALL Java_com_clipgrab_app_Enhancer_nRelease(JNIEnv*, jclass) { release_all(); }

// in: w*h ARGB ints. out: (w*scale)*(h*scale) ARGB ints. 0 = ok, <0 = error
JNIEXPORT jint JNICALL Java_com_clipgrab_app_Enhancer_nUpscale(JNIEnv* env, jclass, jintArray jin, jint w, jint h,
                                                                jintArray jout) {
    if (!g_net) return -10;
    const int s = g_scale;
    const int W = w * s, H = h * s;
    jint* src = env->GetIntArrayElements(jin, nullptr);
    jint* dst = env->GetIntArrayElements(jout, nullptr);
    if (!src || !dst) {
        if (src) env->ReleaseIntArrayElements(jin, src, JNI_ABORT);
        if (dst) env->ReleaseIntArrayElements(jout, dst, JNI_ABORT);
        return -11;
    }
    int rc = 0;
    const int tile = g_tile;
    std::vector<unsigned char> buf;
    const float norm[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};

    for (int y0 = 0; y0 < h && rc == 0; y0 += tile) {
        const int y1 = std::min(y0 + tile, h);
        for (int x0 = 0; x0 < w && rc == 0; x0 += tile) {
            const int x1 = std::min(x0 + tile, w);
            const int tw = (x1 - x0) + 2 * PREPAD;
            const int th = (y1 - y0) + 2 * PREPAD;
            buf.resize((size_t)tw * th * 3);
            // tile + padding; pixels outside the picture repeat the nearest edge pixel
            for (int yy = 0; yy < th; yy++) {
                int sy = std::min(std::max(y0 - PREPAD + yy, 0), h - 1);
                const jint* row = src + (size_t)sy * w;
                unsigned char* d = buf.data() + (size_t)yy * tw * 3;
                for (int xx = 0; xx < tw; xx++) {
                    int sx = std::min(std::max(x0 - PREPAD + xx, 0), w - 1);
                    unsigned int p = (unsigned int)row[sx];
                    d[0] = (p >> 16) & 0xff;
                    d[1] = (p >> 8) & 0xff;
                    d[2] = p & 0xff;
                    d += 3;
                }
            }
            ncnn::Mat in = ncnn::Mat::from_pixels(buf.data(), ncnn::Mat::PIXEL_RGB, tw, th);
            in.substract_mean_normalize(0, norm);

            ncnn::Mat out;
            {
                ncnn::Extractor ex = g_net->create_extractor();
                ex.input(g_in_name.c_str(), in);
                int r = ex.extract(g_out_name.c_str(), out);
                if (r != 0 || out.w != tw * s || out.h != th * s || out.c != 3) {
                    LOGE("extract failed r=%d out=%dx%dx%d expected %dx%d", r, out.w, out.h, out.c, tw * s, th * s);
                    rc = -12;
                    break;
                }
            }
            // copy only the tile itself (without the padding) into the big picture
            const int ox = PREPAD * s, oy = PREPAD * s;
            const int cw = (x1 - x0) * s, ch = (y1 - y0) * s;
            const float* pr = out.channel(0);
            const float* pg = out.channel(1);
            const float* pb = out.channel(2);
            for (int yy = 0; yy < ch; yy++) {
                const size_t o = (size_t)(oy + yy) * out.w + ox;
                jint* d = dst + (size_t)(y0 * s + yy) * W + (x0 * s);
                for (int xx = 0; xx < cw; xx++) {
                    int r = (int)(pr[o + xx] * 255.f + 0.5f);
                    int g = (int)(pg[o + xx] * 255.f + 0.5f);
                    int b = (int)(pb[o + xx] * 255.f + 0.5f);
                    r = r < 0 ? 0 : (r > 255 ? 255 : r);
                    g = g < 0 ? 0 : (g > 255 ? 255 : g);
                    b = b < 0 ? 0 : (b > 255 ? 255 : b);
                    d[xx] = (jint)(0xff000000u | ((unsigned)r << 16) | ((unsigned)g << 8) | (unsigned)b);
                }
            }
        }
    }
    env->ReleaseIntArrayElements(jin, src, JNI_ABORT);
    env->ReleaseIntArrayElements(jout, dst, 0);
    return rc;
}

}  // extern "C"
