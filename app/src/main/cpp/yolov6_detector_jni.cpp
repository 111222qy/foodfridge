#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <string>
#include <vector>
#include <memory>
#include <algorithm>
#include <chrono>
#include <cstring>

// RKNN includes
#include "rknn_api.h"
#include "yolov6.h"
#include "common.h"
#include "image_utils.h"
#include "postprocess.h"

#define LOG_TAG "YOLOv6Detector"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// YOLOv6检测结果结构
struct Detection {
    float x, y, width, height;  // 归一化坐标
    float confidence;
    int classId;
};

// 全局状态变量
static rknn_app_context_t g_detector_ctx = {};
static bool g_detector_initialized = false;

// 识别器相关全局变量（用于 cleanup_recognizer_resources）
static rknn_app_context_t g_recognizer_ctx = {};
static bool g_recognizer_initialized = false;
static rknn_app_context_t g_temp_recognizer_ctx = {};
static bool g_temp_initialized = false;

// MobileNetV2特征库结构
struct FeatureRepository {
    std::vector<std::string> classNames;
    std::vector<std::vector<float>> features;
};
static FeatureRepository g_featureRepo;

// 清理函数声明
void cleanup_recognizer_resources();

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    LOGI("YOLOv6Detector JNI_OnLoad - 食品识别SDK检测模块加载成功");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    LOGI("FoodRecognitionSDK JNI_OnUnload - 食品识别SDK全部模块卸载");
    
    // 清理检测器资源
    if (g_detector_initialized) {
        release_yolov6_model(&g_detector_ctx);
        g_detector_initialized = false;
        LOGI("YOLOv6 detector resources released");
    }
    
    // 清理识别器资源
    cleanup_recognizer_resources();
}

/**
 * YOLOv6检测器初始化
 * Java: private native boolean nativeInit(byte[] modelData);
 */
JNIEXPORT jboolean JNICALL
Java_com_foodres_sdk_internal_YOLOv6Detector_nativeInit(
        JNIEnv *env, jobject thiz, jbyteArray jModelData) {
    (void)thiz;
    try {
        // 如果已经初始化，先释放
        if (g_detector_initialized) {
            release_yolov6_model(&g_detector_ctx);
            g_detector_initialized = false;
        }

        // 获取模型数据
        jsize modelSize = env->GetArrayLength(jModelData);
        jbyte *modelBytes = env->GetByteArrayElements(jModelData, nullptr);
        if (!modelBytes) {
            LOGE("Failed to get model data");
            return JNI_FALSE;
        }

        LOGI("Loading YOLOv6 RKNN model from memory, size: %d", modelSize);

        // 从内存加载 RKNN 模型
        int ret = init_yolov6_model_from_memory((const char*)modelBytes, modelSize, &g_detector_ctx);
        if (ret != 0) {
            LOGE("Failed to load YOLOv6 RKNN model: %d", ret);
            env->ReleaseByteArrayElements(jModelData, modelBytes, JNI_ABORT);
            return JNI_FALSE;
        }

        env->ReleaseByteArrayElements(jModelData, modelBytes, JNI_ABORT);
        g_detector_initialized = true;
        LOGI("YOLOv6 RKNN model loaded successfully");
        return JNI_TRUE;
    } catch (const std::exception &e) {
        LOGE("Exception in YOLOv6Detector nativeInit: %s", e.what());
        return JNI_FALSE;
    }
}

/**
 * YOLOv6检测
 * Java: private native Detection[] nativeDetect(Bitmap bitmap);
 */
JNIEXPORT jobjectArray JNICALL
Java_com_foodres_sdk_internal_YOLOv6Detector_nativeDetect(
        JNIEnv *env, jobject thiz, jobject jBitmap) {
    (void)thiz;
    if (!g_detector_initialized) {
        LOGE("YOLOv6 Detector not initialized");
        return nullptr;
    }

    try {
        // 获取 Bitmap 信息
        AndroidBitmapInfo bitmapInfo;
        if (AndroidBitmap_getInfo(env, jBitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("Failed to get bitmap info");
            return nullptr;
        }

        // 锁定 Bitmap 像素
        void *bitmapPixels;
        if (AndroidBitmap_lockPixels(env, jBitmap, &bitmapPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("Failed to lock bitmap pixels");
            return nullptr;
        }

        // 准备输入图像结构体
        image_buffer_t src_image;
        memset(&src_image, 0, sizeof(image_buffer_t));
        src_image.width = bitmapInfo.width;
        src_image.height = bitmapInfo.height;
        src_image.format = IMAGE_FORMAT_RGBA8888;
        src_image.virt_addr = static_cast<unsigned char*>(bitmapPixels);

        // 执行 RKNN 推理
        object_detect_result_list det_results;
        memset(&det_results, 0, sizeof(object_detect_result_list));
        
        auto startTime = std::chrono::high_resolution_clock::now();
        int ret = inference_yolov6_model(&g_detector_ctx, &src_image, &det_results);
        auto endTime = std::chrono::high_resolution_clock::now();
        auto inferenceTime = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
        
        LOGI("YOLOv6 RKNN inference time: %lld ms", (long long)inferenceTime);
        
        AndroidBitmap_unlockPixels(env, jBitmap);

        if (ret != 0) {
            LOGE("YOLOv6 RKNN inference failed: %d", ret);
            return nullptr;
        }

        LOGI("YOLOv6 Detection count: %d", det_results.count);

        // 转换为 Java 对象数组
        jclass detectionClass = env->FindClass("com/foodres/sdk/internal/YOLOv6Detector$Detection");
        if (!detectionClass) {
            LOGE("Failed to find YOLOv6Detector$Detection class");
            return nullptr;
        }

        jmethodID constructor = env->GetMethodID(detectionClass, "<init>", "()V");
        jfieldID xField = env->GetFieldID(detectionClass, "x", "F");
        jfieldID yField = env->GetFieldID(detectionClass, "y", "F");
        jfieldID widthField = env->GetFieldID(detectionClass, "width", "F");
        jfieldID heightField = env->GetFieldID(detectionClass, "height", "F");
        jfieldID confField = env->GetFieldID(detectionClass, "confidence", "F");
        jfieldID classIdField = env->GetFieldID(detectionClass, "classId", "I");

        // 转换检测结果：从像素坐标转换为归一化坐标
        std::vector<Detection> detections;
        for (int i = 0; i < det_results.count; i++) {
            object_detect_result* det = &det_results.results[i];
            
            // 转换为归一化的 xywh（相对于原始图像）
            float cx = (det->box.left + det->box.right) / 2.0f / bitmapInfo.width;
            float cy = (det->box.top + det->box.bottom) / 2.0f / bitmapInfo.height;
            float nw = (det->box.right - det->box.left) / (float)bitmapInfo.width;
            float nh = (det->box.bottom - det->box.top) / (float)bitmapInfo.height;
            
            Detection d;
            d.x = cx;
            d.y = cy;
            d.width = nw;
            d.height = nh;
            d.confidence = det->prop;
            d.classId = det->cls_id;
            
            detections.push_back(d);
        }

        jobjectArray resultArray = env->NewObjectArray(detections.size(), detectionClass, nullptr);
        for (size_t i = 0; i < detections.size(); i++) {
            jobject detectionObj = env->NewObject(detectionClass, constructor);
            env->SetFloatField(detectionObj, xField, detections[i].x);
            env->SetFloatField(detectionObj, yField, detections[i].y);
            env->SetFloatField(detectionObj, widthField, detections[i].width);
            env->SetFloatField(detectionObj, heightField, detections[i].height);
            env->SetFloatField(detectionObj, confField, detections[i].confidence);
            env->SetIntField(detectionObj, classIdField, detections[i].classId);
            env->SetObjectArrayElement(resultArray, i, detectionObj);
            env->DeleteLocalRef(detectionObj);
        }

        return resultArray;
    } catch (const std::exception &e) {
        LOGE("Exception in YOLOv6Detector nativeDetect: %s", e.what());
        return nullptr;
    }
}

/**
 * YOLOv6释放资源
 * Java: private native void nativeRelease();
 */
JNIEXPORT void JNICALL
Java_com_foodres_sdk_internal_YOLOv6Detector_nativeRelease(
        JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    LOGI("Releasing YOLOv6 RKNN detector");
    if (g_detector_initialized) {
        release_yolov6_model(&g_detector_ctx);
        g_detector_initialized = false;
    }
}

} // extern "C"

// 清理识别器资源函数实现
void cleanup_recognizer_resources() {
    if (g_recognizer_initialized) {
        release_mobilenet_model(&g_recognizer_ctx);
        g_recognizer_initialized = false;
    }
    if (g_temp_initialized) {
        release_mobilenet_model(&g_temp_recognizer_ctx);
        g_temp_initialized = false;
    }
    g_featureRepo.classNames.clear();
    g_featureRepo.features.clear();
}