#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <string>
#include <vector>
#include <algorithm>
#include <cstring>

#include "rknn_api.h"
#include "yolov6.h"
#include "common.h"
#include "image_utils.h"

#define LOG_TAG "FasterRcnnDetector"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct FrcnnContext {
    rknn_app_context_t app_ctx;
    bool initialized = false;
    int out_boxes = 0;
    int out_scores = 1;
    int out_classes = 2;
    int out_num = 3;
};

static FrcnnContext g_frcnn_ctx = {};

static void dump_tensor_attr(rknn_tensor_attr *attr) {
    LOGD("tensor index=%d name=%s dims=[%d,%d,%d,%d] type=%d qnt_type=%d zp=%d scale=%f", 
        attr->index, attr->name, attr->dims[0], attr->dims[1], attr->dims[2], attr->dims[3],
        attr->type, attr->qnt_type, attr->zp, attr->scale);
}

static int init_frcnn_model_from_memory(const char *model_data, int model_size, FrcnnContext *ctx) {
    if (!model_data || model_size <= 0 || !ctx) {
        return -1;
    }

    rknn_context rknn_ctx = 0;
    int ret = rknn_init(&rknn_ctx, (void *)model_data, model_size, 0, NULL);
    if (ret != RKNN_SUCC) {
        LOGE("rknn_init failed: %d", ret);
        return -1;
    }

    rknn_input_output_num io_num;
    ret = rknn_query(rknn_ctx, RKNN_QUERY_IN_OUT_NUM, &io_num, sizeof(io_num));
    if (ret != RKNN_SUCC) {
        LOGE("rknn_query io num failed: %d", ret);
        rknn_destroy(rknn_ctx);
        return -1;
    }

    std::vector<rknn_tensor_attr> input_attrs(io_num.n_input);
    for (int i = 0; i < io_num.n_input; i++) {
        input_attrs[i].index = i;
        ret = rknn_query(rknn_ctx, RKNN_QUERY_INPUT_ATTR, &input_attrs[i], sizeof(rknn_tensor_attr));
        if (ret != RKNN_SUCC) {
            LOGE("rknn_query input attr failed: %d", ret);
            rknn_destroy(rknn_ctx);
            return -1;
        }
        dump_tensor_attr(&input_attrs[i]);
    }

    std::vector<rknn_tensor_attr> output_attrs(io_num.n_output);
    for (int i = 0; i < io_num.n_output; i++) {
        output_attrs[i].index = i;
        ret = rknn_query(rknn_ctx, RKNN_QUERY_OUTPUT_ATTR, &output_attrs[i], sizeof(rknn_tensor_attr));
        if (ret != RKNN_SUCC) {
            LOGE("rknn_query output attr failed: %d", ret);
            rknn_destroy(rknn_ctx);
            return -1;
        }
        dump_tensor_attr(&output_attrs[i]);
    }

    ctx->app_ctx.rknn_ctx = rknn_ctx;
    ctx->app_ctx.io_num = io_num;
    ctx->app_ctx.input_attrs = (rknn_tensor_attr *)malloc(io_num.n_input * sizeof(rknn_tensor_attr));
    ctx->app_ctx.output_attrs = (rknn_tensor_attr *)malloc(io_num.n_output * sizeof(rknn_tensor_attr));
    memcpy(ctx->app_ctx.input_attrs, input_attrs.data(), io_num.n_input * sizeof(rknn_tensor_attr));
    memcpy(ctx->app_ctx.output_attrs, output_attrs.data(), io_num.n_output * sizeof(rknn_tensor_attr));

    if (input_attrs[0].fmt == RKNN_TENSOR_NCHW) {
        ctx->app_ctx.model_channel = input_attrs[0].dims[1];
        ctx->app_ctx.model_height = input_attrs[0].dims[2];
        ctx->app_ctx.model_width = input_attrs[0].dims[3];
    } else {
        ctx->app_ctx.model_height = input_attrs[0].dims[1];
        ctx->app_ctx.model_width = input_attrs[0].dims[2];
        ctx->app_ctx.model_channel = input_attrs[0].dims[3];
    }

    ctx->out_boxes = 0;
    ctx->out_scores = 1;
    ctx->out_classes = 2;
    ctx->out_num = 3;
    for (int i = 0; i < io_num.n_output; i++) {
        const char *name = output_attrs[i].name;
        if (!name) continue;
        if (strcmp(name, "boxes") == 0) ctx->out_boxes = i;
        if (strcmp(name, "scores") == 0) ctx->out_scores = i;
        if (strcmp(name, "classes") == 0) ctx->out_classes = i;
        if (strcmp(name, "num_detections") == 0) ctx->out_num = i;
    }

    return 0;
}

static void release_frcnn_model(FrcnnContext *ctx) {
    if (!ctx) return;
    if (ctx->app_ctx.input_attrs) {
        free(ctx->app_ctx.input_attrs);
        ctx->app_ctx.input_attrs = NULL;
    }
    if (ctx->app_ctx.output_attrs) {
        free(ctx->app_ctx.output_attrs);
        ctx->app_ctx.output_attrs = NULL;
    }
    if (ctx->app_ctx.rknn_ctx) {
        rknn_destroy(ctx->app_ctx.rknn_ctx);
        ctx->app_ctx.rknn_ctx = 0;
    }
}

static void reorder_hwc_to_chw(const unsigned char *src, unsigned char *dst, int width, int height, int channels) {
    int hw = width * height;
    for (int c = 0; c < channels; c++) {
        for (int i = 0; i < hw; i++) {
            dst[c * hw + i] = src[i * channels + c];
        }
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_foodres_sdk_internal_FasterRcnnDetector_nativeInit(
        JNIEnv *env, jobject thiz, jbyteArray jModelData) {
    (void)thiz;
    if (g_frcnn_ctx.initialized) {
        release_frcnn_model(&g_frcnn_ctx);
        g_frcnn_ctx.initialized = false;
    }

    jsize modelSize = env->GetArrayLength(jModelData);
    jbyte *modelBytes = env->GetByteArrayElements(jModelData, nullptr);
    if (!modelBytes) {
        LOGE("Failed to get model data");
        return JNI_FALSE;
    }

    int ret = init_frcnn_model_from_memory((const char *)modelBytes, modelSize, &g_frcnn_ctx);
    env->ReleaseByteArrayElements(jModelData, modelBytes, JNI_ABORT);
    if (ret != 0) {
        LOGE("Failed to init Faster R-CNN model");
        return JNI_FALSE;
    }

    g_frcnn_ctx.initialized = true;
    LOGD("Faster R-CNN model initialized");
    return JNI_TRUE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_foodres_sdk_internal_FasterRcnnDetector_nativeDetect(
        JNIEnv *env, jobject thiz, jobject jBitmap) {
    (void)thiz;
    if (!g_frcnn_ctx.initialized) {
        LOGE("Faster R-CNN not initialized");
        return nullptr;
    }

    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, jBitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to get bitmap info");
        return nullptr;
    }

    void *bitmapPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jBitmap, &bitmapPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock bitmap pixels");
        return nullptr;
    }

    image_buffer_t src_image;
    memset(&src_image, 0, sizeof(image_buffer_t));
    src_image.width = bitmapInfo.width;
    src_image.height = bitmapInfo.height;
    src_image.format = IMAGE_FORMAT_RGBA8888;
    src_image.virt_addr = static_cast<unsigned char *>(bitmapPixels);

    image_buffer_t dst_image;
    memset(&dst_image, 0, sizeof(image_buffer_t));
    dst_image.width = g_frcnn_ctx.app_ctx.model_width;
    dst_image.height = g_frcnn_ctx.app_ctx.model_height;
    dst_image.format = IMAGE_FORMAT_RGB888;
    dst_image.size = get_image_size(&dst_image);
    dst_image.virt_addr = (unsigned char *)malloc(dst_image.size);
    if (!dst_image.virt_addr) {
        AndroidBitmap_unlockPixels(env, jBitmap);
        LOGE("Failed to alloc input buffer");
        return nullptr;
    }

    int ret = convert_image(&src_image, &dst_image, NULL, NULL, 0);
    AndroidBitmap_unlockPixels(env, jBitmap);
    if (ret < 0) {
        free(dst_image.virt_addr);
        LOGE("convert_image failed: %d", ret);
        return nullptr;
    }

    unsigned char *input_buf = dst_image.virt_addr;
    std::vector<unsigned char> chw_buffer;
    if (g_frcnn_ctx.app_ctx.input_attrs[0].fmt == RKNN_TENSOR_NCHW) {
        chw_buffer.resize(dst_image.size);
        reorder_hwc_to_chw(dst_image.virt_addr, chw_buffer.data(),
            dst_image.width, dst_image.height, g_frcnn_ctx.app_ctx.model_channel);
        input_buf = chw_buffer.data();
    }

    std::vector<int8_t> int8_buffer;
    if (g_frcnn_ctx.app_ctx.input_attrs[0].type == RKNN_TENSOR_INT8) {
        int input_size = dst_image.width * dst_image.height * g_frcnn_ctx.app_ctx.model_channel;
        int8_buffer.resize(input_size);
        int zp = g_frcnn_ctx.app_ctx.input_attrs[0].zp;
        for (int i = 0; i < input_size; i++) {
            int8_buffer[i] = static_cast<int8_t>(static_cast<int>(input_buf[i]) - zp);
        }
        input_buf = reinterpret_cast<unsigned char *>(int8_buffer.data());
    }

    rknn_input input;
    memset(&input, 0, sizeof(input));
    input.index = 0;
    input.type = g_frcnn_ctx.app_ctx.input_attrs[0].type;
    input.fmt = g_frcnn_ctx.app_ctx.input_attrs[0].fmt;
    input.size = dst_image.width * dst_image.height * g_frcnn_ctx.app_ctx.model_channel;
    input.buf = input_buf;

    ret = rknn_inputs_set(g_frcnn_ctx.app_ctx.rknn_ctx, 1, &input);
    if (ret != RKNN_SUCC) {
        free(dst_image.virt_addr);
        LOGE("rknn_inputs_set failed: %d", ret);
        return nullptr;
    }

    ret = rknn_run(g_frcnn_ctx.app_ctx.rknn_ctx, nullptr);
    if (ret != RKNN_SUCC) {
        free(dst_image.virt_addr);
        LOGE("rknn_run failed: %d", ret);
        return nullptr;
    }

    std::vector<rknn_output> outputs(g_frcnn_ctx.app_ctx.io_num.n_output);
    for (int i = 0; i < g_frcnn_ctx.app_ctx.io_num.n_output; i++) {
        outputs[i].index = i;
        outputs[i].want_float = (g_frcnn_ctx.app_ctx.output_attrs[i].type == RKNN_TENSOR_FLOAT32);
    }

    ret = rknn_outputs_get(g_frcnn_ctx.app_ctx.rknn_ctx, outputs.size(), outputs.data(), NULL);
    if (ret != RKNN_SUCC) {
        free(dst_image.virt_addr);
        LOGE("rknn_outputs_get failed: %d", ret);
        return nullptr;
    }

    float *boxes = reinterpret_cast<float *>(outputs[g_frcnn_ctx.out_boxes].buf);
    float *scores = reinterpret_cast<float *>(outputs[g_frcnn_ctx.out_scores].buf);
    int *classes = reinterpret_cast<int *>(outputs[g_frcnn_ctx.out_classes].buf);
    int num = 0;
    if (outputs[g_frcnn_ctx.out_num].buf) {
        num = *reinterpret_cast<int *>(outputs[g_frcnn_ctx.out_num].buf);
    }

    int max_det = 100;
    if (num > 0 && num < max_det) {
        max_det = num;
    }

    float max_box_value = 0.0f;
    for (int i = 0; i < max_det * 4; i++) {
        max_box_value = std::max(max_box_value, boxes[i]);
    }
    bool normalized = max_box_value <= 1.5f;

    jclass detectionClass = env->FindClass("com/foodres/sdk/internal/FasterRcnnDetector$Detection");
    if (!detectionClass) {
        rknn_outputs_release(g_frcnn_ctx.app_ctx.rknn_ctx, outputs.size(), outputs.data());
        free(dst_image.virt_addr);
        LOGE("Detection class not found");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(detectionClass, "<init>", "()V");
    jfieldID xField = env->GetFieldID(detectionClass, "x", "F");
    jfieldID yField = env->GetFieldID(detectionClass, "y", "F");
    jfieldID widthField = env->GetFieldID(detectionClass, "width", "F");
    jfieldID heightField = env->GetFieldID(detectionClass, "height", "F");
    jfieldID confField = env->GetFieldID(detectionClass, "confidence", "F");
    jfieldID classIdField = env->GetFieldID(detectionClass, "classId", "I");

    float scale_x = (float)bitmapInfo.width / (float)g_frcnn_ctx.app_ctx.model_width;
    float scale_y = (float)bitmapInfo.height / (float)g_frcnn_ctx.app_ctx.model_height;

    struct TempDet {
        float cx;
        float cy;
        float w;
        float h;
        float score;
        int class_id;
    };
    std::vector<TempDet> temp_dets;
    temp_dets.reserve(max_det);

    for (int idx = 0; idx < max_det; idx++) {
        if (scores[idx] <= 0.0f) {
            continue;
        }
        float x1 = boxes[idx * 4 + 0];
        float y1 = boxes[idx * 4 + 1];
        float x2 = boxes[idx * 4 + 2];
        float y2 = boxes[idx * 4 + 3];

        if (normalized) {
            x1 *= g_frcnn_ctx.app_ctx.model_width;
            y1 *= g_frcnn_ctx.app_ctx.model_height;
            x2 *= g_frcnn_ctx.app_ctx.model_width;
            y2 *= g_frcnn_ctx.app_ctx.model_height;
        }

        x1 *= scale_x;
        y1 *= scale_y;
        x2 *= scale_x;
        y2 *= scale_y;

        if (x2 <= x1 || y2 <= y1) {
            continue;
        }

        float cx = (x1 + x2) * 0.5f / bitmapInfo.width;
        float cy = (y1 + y2) * 0.5f / bitmapInfo.height;
        float w = (x2 - x1) / bitmapInfo.width;
        float h = (y2 - y1) / bitmapInfo.height;

        TempDet det;
        det.cx = cx;
        det.cy = cy;
        det.w = w;
        det.h = h;
        det.score = scores[idx];
        det.class_id = classes[idx];
        temp_dets.push_back(det);
    }

    jobjectArray resultArray = env->NewObjectArray(temp_dets.size(), detectionClass, nullptr);
    for (size_t i = 0; i < temp_dets.size(); i++) {
        jobject detectionObj = env->NewObject(detectionClass, constructor);
        env->SetFloatField(detectionObj, xField, temp_dets[i].cx);
        env->SetFloatField(detectionObj, yField, temp_dets[i].cy);
        env->SetFloatField(detectionObj, widthField, temp_dets[i].w);
        env->SetFloatField(detectionObj, heightField, temp_dets[i].h);
        env->SetFloatField(detectionObj, confField, temp_dets[i].score);
        env->SetIntField(detectionObj, classIdField, temp_dets[i].class_id);
        env->SetObjectArrayElement(resultArray, i, detectionObj);
        env->DeleteLocalRef(detectionObj);
    }

    rknn_outputs_release(g_frcnn_ctx.app_ctx.rknn_ctx, outputs.size(), outputs.data());
    free(dst_image.virt_addr);
    return resultArray;
}

JNIEXPORT void JNICALL
Java_com_foodres_sdk_internal_FasterRcnnDetector_nativeRelease(
        JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (g_frcnn_ctx.initialized) {
        release_frcnn_model(&g_frcnn_ctx);
        g_frcnn_ctx.initialized = false;
    }
}

} // extern "C"
