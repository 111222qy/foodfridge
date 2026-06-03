#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <string>
#include <vector>
#include <memory>
#include <algorithm>
#include <chrono>
#include <map>
#include <cmath>
#include <mutex>

// RKNN includes
#include "rknn_api.h"
#include "mobilenet.h"
#include "common.h"
#include "image_utils.h"

#define LOG_TAG "MobileNetV2Recognizer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// MobileNetV2识别结果结构
struct Recognition {
    std::string className;
    float confidence;
    int rank;
};

// MobileNetV2特征库结构
struct FeatureRepository {
    std::vector<std::string> classNames;
    std::vector<std::vector<float>> features;
};

// 全局状态变量
static rknn_app_context_t g_recognizer_ctx = {};
static bool g_recognizer_initialized = false;
static FeatureRepository g_featureRepo;
static const int FEATURE_DIM = 512;

// 临时推理器（用于动态加载特征库时提取特征）
static rknn_app_context_t g_temp_recognizer_ctx = {};
static bool g_temp_initialized = false;
static size_t g_tempModelSize = 0;

// 线程同步互斥锁（保护共享全局状态的并发访问）
static std::mutex g_repo_mutex;

// 前向声明
std::vector<float> extractFeature(const image_buffer_t* img, rknn_app_context_t* ctx);
std::vector<Recognition> matchFeatures(const std::vector<float> &feature, int topK);

// RKNN相关函数前向声明（不在mobilenet.h中）
int init_mobilenet_model_from_memory(const char* model_data, int model_size, rknn_app_context_t* app_ctx);

extern "C" {

// 提供清理函数供 JNI_OnUnload 调用
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


/**
 * MobileNetV2识别器初始化
 * Java: public native boolean nativeInit(byte[] modelData, String[] classNames, float[][] features);
 */
JNIEXPORT jboolean JNICALL
Java_com_foodres_sdk_internal_MobileNetV2Recognizer_nativeInit(
        JNIEnv *env, jobject thiz, jbyteArray jModelData,
        jobjectArray jClassNames, jobjectArray jFeatures) {
    (void)thiz;
    try {
        // 如果已经初始化，先释放
        if (g_recognizer_initialized) {
            LOGI("Releasing previous MobileNetV2 recognizer instance");
            if (g_recognizer_ctx.rknn_ctx != 0) {
                release_mobilenet_model(&g_recognizer_ctx);
            }
            g_recognizer_initialized = false;
        }

        // 获取模型数据
        jsize modelSize = env->GetArrayLength(jModelData);
        jbyte *modelBytes = env->GetByteArrayElements(jModelData, nullptr);
        if (!modelBytes) {
            LOGE("Failed to get MobileNetV2 model data");
            return JNI_FALSE;
        }

        LOGI("Loading MobileNetV2 RKNN model from memory, size: %d", modelSize);

        // 从内存加载 RKNN 模型
        int ret = init_mobilenet_model_from_memory((const char*)modelBytes, modelSize, &g_recognizer_ctx);
        if (ret != 0) {
            LOGE("Failed to load MobileNetV2 RKNN model: %d", ret);
            LOGE("RKNN requires Rockchip NPU hardware (e.g., RK3566, RK3588)");
            LOGE("This device does not support RKNN. Please use a Rockchip device or switch to ONNX models.");
            env->ReleaseByteArrayElements(jModelData, modelBytes, JNI_ABORT);
            return JNI_FALSE;
        }

        env->ReleaseByteArrayElements(jModelData, modelBytes, JNI_ABORT);

        // 加载特征库
        jsize numClasses = env->GetArrayLength(jClassNames);
        g_featureRepo.classNames.clear();
        g_featureRepo.features.clear();

        for (jsize i = 0; i < numClasses; i++) {
            jstring className = (jstring)env->GetObjectArrayElement(jClassNames, i);
            const char *classNameStr = env->GetStringUTFChars(className, nullptr);
            g_featureRepo.classNames.push_back(classNameStr);
            env->ReleaseStringUTFChars(className, classNameStr);
            env->DeleteLocalRef(className);

            jfloatArray featureArray = (jfloatArray)env->GetObjectArrayElement(jFeatures, i);
            jsize featureSize = env->GetArrayLength(featureArray);
            jfloat *featureData = env->GetFloatArrayElements(featureArray, nullptr);

            std::vector<float> feature(featureSize);
            for (jsize j = 0; j < featureSize; j++) {
                feature[j] = featureData[j];
            }
            
            // L2 归一化特征向量（特征库的特征向量应该已经归一化，但这里再次确保）
            float norm = 0.0f;
            for (float f : feature) {
                norm += f * f;
            }
            norm = std::sqrt(norm);
            if (norm > 0) {
                for (float &f : feature) {
                    f /= norm;
                }
            }
            
            g_featureRepo.features.push_back(feature);

            env->ReleaseFloatArrayElements(featureArray, featureData, JNI_ABORT);
            env->DeleteLocalRef(featureArray);
        }

        g_recognizer_initialized = true;
        LOGI("MobileNetV2 RKNN model loaded successfully, feature repo size: %zu", g_featureRepo.classNames.size());
        return JNI_TRUE;
    } catch (const std::exception &e) {
        LOGE("Exception in MobileNetV2Recognizer nativeInit: %s", e.what());
        return JNI_FALSE;
    }
}

/**
 * MobileNetV2识别
 * Java: public native Recognition[] nativeRecognize(Bitmap bitmap, int topK);
 */
JNIEXPORT jobjectArray JNICALL
Java_com_foodres_sdk_internal_MobileNetV2Recognizer_nativeRecognize(
        JNIEnv *env, jobject thiz, jobject jBitmap, jint topK) {
    (void)thiz;
    if (!g_recognizer_initialized) {
        LOGE("MobileNetV2 Recognizer not initialized");
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

        // 提取特征
        auto startTime = std::chrono::high_resolution_clock::now();
        std::vector<float> feature = extractFeature(&src_image, &g_recognizer_ctx);
        auto endTime = std::chrono::high_resolution_clock::now();
        auto featureTime = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
        
        LOGI("MobileNetV2 feature extraction time: %lld ms", (long long)featureTime);

        AndroidBitmap_unlockPixels(env, jBitmap);

        if (feature.empty()) {
            LOGE("Failed to extract feature");
            return nullptr;
        }

        // 匹配特征库
        std::vector<Recognition> recognitions = matchFeatures(feature, topK);

        // 转换为 Java 对象数组
        jclass recognitionClass = env->FindClass("com/foodres/sdk/internal/MobileNetV2Recognizer$Recognition");
        if (!recognitionClass) {
            LOGE("Failed to find MobileNetV2Recognizer$Recognition class");
            return nullptr;
        }

        jmethodID constructor = env->GetMethodID(recognitionClass, "<init>", "()V");
        jfieldID classNameField = env->GetFieldID(recognitionClass, "className", "Ljava/lang/String;");
        jfieldID confField = env->GetFieldID(recognitionClass, "confidence", "F");
        jfieldID rankField = env->GetFieldID(recognitionClass, "rank", "I");

        jobjectArray resultArray = env->NewObjectArray(recognitions.size(), recognitionClass, nullptr);
        for (size_t i = 0; i < recognitions.size(); i++) {
            jobject recognitionObj = env->NewObject(recognitionClass, constructor);
            jstring className = env->NewStringUTF(recognitions[i].className.c_str());
            env->SetObjectField(recognitionObj, classNameField, className);
            env->SetFloatField(recognitionObj, confField, recognitions[i].confidence);
            env->SetIntField(recognitionObj, rankField, recognitions[i].rank);
            env->SetObjectArrayElement(resultArray, i, recognitionObj);
            env->DeleteLocalRef(className);
            env->DeleteLocalRef(recognitionObj);
        }

        LOGI("MobileNetV2 Recognition completed, found %zu results", recognitions.size());
        return resultArray;
    } catch (const std::exception &e) {
        LOGE("Exception in MobileNetV2Recognizer nativeRecognize: %s", e.what());
        return nullptr;
    }
}

/**
 * MobileNetV2释放资源
 * Java: public native void nativeRelease();
 */
JNIEXPORT void JNICALL
Java_com_foodres_sdk_internal_MobileNetV2Recognizer_nativeRelease(
        JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    LOGI("Releasing MobileNetV2 RKNN recognizer");
    if (g_recognizer_initialized) {
        release_mobilenet_model(&g_recognizer_ctx);
        g_recognizer_initialized = false;
    }
    g_featureRepo.classNames.clear();
    g_featureRepo.features.clear();
}

/**
 * 动态添加特征到特征库
 * Java: public native boolean nativeAddFeature(String className, float[] feature);
 */
JNIEXPORT jboolean JNICALL
Java_com_foodres_sdk_internal_MobileNetV2Recognizer_nativeAddFeature(
        JNIEnv *env, jobject thiz, jstring jClassName, jfloatArray jFeature) {
    (void)thiz;
    if (!g_recognizer_initialized) {
        LOGE("MobileNetV2 Recognizer not initialized");
        return JNI_FALSE;
    }

    try {
        // 获取类名
        const char *classNameStr = env->GetStringUTFChars(jClassName, nullptr);
        if (!classNameStr) {
            LOGE("Failed to get class name");
            return JNI_FALSE;
        }
        std::string className(classNameStr);
        env->ReleaseStringUTFChars(jClassName, classNameStr);

        // 获取特征向量
        jsize featureSize = env->GetArrayLength(jFeature);
        jfloat *featureData = env->GetFloatArrayElements(jFeature, nullptr);
        if (!featureData) {
            LOGE("Failed to get feature data");
            return JNI_FALSE;
        }

        std::vector<float> feature(featureSize);
        for (jsize i = 0; i < featureSize; i++) {
            feature[i] = featureData[i];
        }
        env->ReleaseFloatArrayElements(jFeature, featureData, JNI_ABORT);

        // L2 归一化特征向量
        float norm = 0.0f;
        for (float f : feature) {
            norm += f * f;
        }
        norm = std::sqrt(norm);
        if (norm > 0) {
            for (float &f : feature) {
                f /= norm;
            }
        }

        // 使用互斥锁保护特征库并发访问
        std::lock_guard<std::mutex> lock(g_repo_mutex);

        // 检查是否已存在该类名，如果存在则更新，否则添加
        bool found = false;
        for (size_t i = 0; i < g_featureRepo.classNames.size(); i++) {
            if (g_featureRepo.classNames[i] == className) {
                // 更新现有特征
                g_featureRepo.features[i] = feature;
                found = true;
                LOGI("Updated feature for class: %s", className.c_str());
                break;
            }
        }

        if (!found) {
            // 添加新特征
            g_featureRepo.classNames.push_back(className);
            g_featureRepo.features.push_back(feature);
            LOGI("Added new feature for class: %s, total classes: %zu", className.c_str(), g_featureRepo.classNames.size());
        }

        return JNI_TRUE;
    } catch (const std::exception &e) {
        LOGE("Exception in MobileNetV2Recognizer nativeAddFeature: %s", e.what());
        return JNI_FALSE;
    }
}

/**
 * 从 Bitmap 提取特征向量（用于动态加载特征库）
 * Java: public static native float[] extractFeatureFromBitmap(Bitmap bitmap, byte[] modelData);
 * 注意：这个方法在 FeatureRepository 内部类中，JNI 函数名需要包含内部类名称
 * 内部类用 $ 表示，在 JNI 函数名中编码为 _00024
 */
JNIEXPORT jfloatArray JNICALL
Java_com_foodres_sdk_internal_MobileNetV2Recognizer_00024FeatureRepository_extractFeatureFromBitmap(
        JNIEnv *env, jclass clazz, jobject jBitmap, jbyteArray jModelData) {
    (void)clazz;
    try {
        // 获取模型数据
        jsize modelSize = env->GetArrayLength(jModelData);
        jbyte *modelBytes = env->GetByteArrayElements(jModelData, nullptr);
        if (!modelBytes) {
            LOGE("Failed to get model data for feature extraction");
            return nullptr;
        }

        // 如果临时推理器未初始化或模型大小不同，重新加载
        bool needReload = !g_temp_initialized || (g_tempModelSize != (size_t)modelSize);

        if (needReload) {
            if (g_temp_initialized) {
                release_mobilenet_model(&g_temp_recognizer_ctx);
                g_temp_initialized = false;
            }

            LOGI("Loading temporary MobileNetV2 RKNN model from memory, size: %d", modelSize);
            int ret = init_mobilenet_model_from_memory((const char*)modelBytes, modelSize, &g_temp_recognizer_ctx);
            if (ret != 0) {
                LOGE("Failed to load temporary MobileNetV2 RKNN model: %d", ret);
                LOGE("RKNN requires Rockchip NPU hardware (e.g., RK3566, RK3588)");
                LOGE("This device does not support RKNN. Please use a Rockchip device or switch to ONNX models.");
                env->ReleaseByteArrayElements(jModelData, modelBytes, JNI_ABORT);
                return nullptr;
            }

            // 验证模型尺寸是否已正确初始化
            if (g_temp_recognizer_ctx.model_width == 0 || 
                g_temp_recognizer_ctx.model_height == 0 || 
                g_temp_recognizer_ctx.model_channel == 0) {
                LOGE("Temporary MobileNetV2 model dimensions not initialized after loading");
                release_mobilenet_model(&g_temp_recognizer_ctx);
                g_temp_initialized = false;
                env->ReleaseByteArrayElements(jModelData, modelBytes, JNI_ABORT);
                return nullptr;
            }

            g_tempModelSize = modelSize;
            g_temp_initialized = true;
            LOGI("Temporary MobileNetV2 RKNN model loaded successfully (width=%d, height=%d, channel=%d)", 
                 g_temp_recognizer_ctx.model_width, 
                 g_temp_recognizer_ctx.model_height, 
                 g_temp_recognizer_ctx.model_channel);
        } else {
            LOGI("Reusing cached temporary MobileNetV2 RKNN model, size: %zu", g_tempModelSize);
        }

        env->ReleaseByteArrayElements(jModelData, modelBytes, JNI_ABORT);

        // 获取 Bitmap 信息
        AndroidBitmapInfo bitmapInfo;
        if (AndroidBitmap_getInfo(env, jBitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("Failed to get bitmap info for feature extraction");
            return nullptr;
        }

        // 锁定 Bitmap 像素
        void *bitmapPixels;
        if (AndroidBitmap_lockPixels(env, jBitmap, &bitmapPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("Failed to lock bitmap pixels for feature extraction");
            return nullptr;
        }

        // 准备输入图像结构体
        image_buffer_t src_image;
        memset(&src_image, 0, sizeof(image_buffer_t));
        src_image.width = bitmapInfo.width;
        src_image.height = bitmapInfo.height;
        src_image.format = IMAGE_FORMAT_RGBA8888;
        src_image.virt_addr = static_cast<unsigned char*>(bitmapPixels);

        // 验证临时推理器已正确初始化
        if (!g_temp_initialized || g_temp_recognizer_ctx.rknn_ctx == 0) {
            LOGE("Temporary MobileNetV2 recognizer not properly initialized");
            AndroidBitmap_unlockPixels(env, jBitmap);
            return nullptr;
        }

        // 提取特征（使用临时推理器）
        std::vector<float> feature = extractFeature(&src_image, &g_temp_recognizer_ctx);

        AndroidBitmap_unlockPixels(env, jBitmap);

        if (feature.empty()) {
            LOGE("Failed to extract feature (empty result)");
            return nullptr;
        }
        
        if (feature.size() != FEATURE_DIM) {
            LOGE("Feature dimension mismatch: expected %d, got %zu", FEATURE_DIM, feature.size());
            return nullptr;
        }

        // 转换为 Java float 数组
        jfloatArray result = env->NewFloatArray(feature.size());
        if (result) {
            env->SetFloatArrayRegion(result, 0, feature.size(), feature.data());
        }

        return result;
    } catch (const std::exception &e) {
        LOGE("Exception in extractFeatureFromBitmap: %s", e.what());
        return nullptr;
    }
}

} // extern "C"

// 提取特征（使用指定的RKNN上下文）
std::vector<float> extractFeature(const image_buffer_t* img, rknn_app_context_t* ctx) {
    if (!ctx || ctx->rknn_ctx == 0) {
        LOGE("RKNN context is null or not initialized");
        return std::vector<float>(FEATURE_DIM, 0.0f);
    }

    // 检查模型尺寸是否已初始化
    if (ctx->model_width == 0 || ctx->model_height == 0 || ctx->model_channel == 0) {
        LOGE("Model dimensions not initialized: width=%d, height=%d, channel=%d", 
             ctx->model_width, ctx->model_height, ctx->model_channel);
        return std::vector<float>(FEATURE_DIM, 0.0f);
    }

    int ret;
    image_buffer_t processed_img;
    rknn_input inputs[1];
    rknn_output outputs[1];

    memset(&processed_img, 0, sizeof(image_buffer_t));
    memset(inputs, 0, sizeof(inputs));
    memset(outputs, 0, sizeof(outputs));

    // Pre Process
    processed_img.width = ctx->model_width;
    processed_img.height = ctx->model_height;
    processed_img.format = IMAGE_FORMAT_RGB888;
    processed_img.size = get_image_size(&processed_img);
    
    if (processed_img.size <= 0) {
        LOGE("Invalid image size calculated: %d (width=%d, height=%d, format=%d)", 
             processed_img.size, processed_img.width, processed_img.height, processed_img.format);
        return std::vector<float>(FEATURE_DIM, 0.0f);
    }
    
    processed_img.virt_addr = (unsigned char*)malloc(processed_img.size);
    if (processed_img.virt_addr == NULL) {
        LOGE("malloc buffer size:%d fail!", processed_img.size);
        return std::vector<float>(FEATURE_DIM, 0.0f);
    }

    ret = convert_image((image_buffer_t*)img, &processed_img, NULL, NULL, 0);
    if (ret < 0) {
        LOGE("convert_image fail! ret=%d", ret);
        free(processed_img.virt_addr);
        return std::vector<float>(FEATURE_DIM, 0.0f);
    }

    // Set Input Data
    inputs[0].index = 0;
    inputs[0].type  = RKNN_TENSOR_UINT8;
    inputs[0].fmt   = RKNN_TENSOR_NHWC;
    inputs[0].size  = ctx->model_width * ctx->model_height * ctx->model_channel;
    inputs[0].buf   = processed_img.virt_addr;

    ret = rknn_inputs_set(ctx->rknn_ctx, 1, inputs);
    if (ret < 0) {
        LOGE("rknn_input_set fail! ret=%d", ret);
        free(processed_img.virt_addr);
        return std::vector<float>(FEATURE_DIM, 0.0f);
    }

    // Run
    ret = rknn_run(ctx->rknn_ctx, nullptr);
    if (ret < 0) {
        LOGE("rknn_run fail! ret=%d", ret);
        free(processed_img.virt_addr);
        return std::vector<float>(FEATURE_DIM, 0.0f);
    }

    // Get Output
    outputs[0].want_float = 1;
    ret = rknn_outputs_get(ctx->rknn_ctx, 1, outputs, NULL);
    if (ret < 0) {
        LOGE("rknn_outputs_get fail! ret=%d", ret);
        free(processed_img.virt_addr);
        return std::vector<float>(FEATURE_DIM, 0.0f);
    }

    // 提取特征向量
    float* feature_data = (float*)outputs[0].buf;
    int feature_size = outputs[0].size / sizeof(float);
    
    std::vector<float> feature(feature_data, feature_data + feature_size);
    
    // L2 归一化
    float norm = 0.0f;
    for (float f : feature) {
        norm += f * f;
    }
    norm = std::sqrt(norm);
    if (norm > 0) {
        for (float &f : feature) {
            f /= norm;
        }
    }

    // 释放资源
    rknn_outputs_release(ctx->rknn_ctx, 1, outputs);
    free(processed_img.virt_addr);

    return feature;
}

// 匹配特征库
std::vector<Recognition> matchFeatures(const std::vector<float> &feature, int topK) {
    // 确保输入特征向量已归一化
    std::vector<float> normalizedFeature = feature;
    float norm = 0.0f;
    for (float f : normalizedFeature) {
        norm += f * f;
    }
    norm = std::sqrt(norm);
    if (norm > 0) {
        for (float &f : normalizedFeature) {
            f /= norm;
        }
    }
    
    // 使用互斥锁保护特征库并发访问
    std::lock_guard<std::mutex> lock(g_repo_mutex);
    
    // 计算与所有特征向量的点积（特征库的特征向量已经在 nativeInit 中归一化）
    std::vector<std::pair<float, int>> similarities;
    for (size_t i = 0; i < g_featureRepo.features.size(); i++) {
        // 点积（因为都已归一化，点积就是余弦相似度）
        float dotProduct = 0.0f;
        for (size_t j = 0; j < normalizedFeature.size() && j < g_featureRepo.features[i].size(); j++) {
            dotProduct += normalizedFeature[j] * g_featureRepo.features[i][j];
        }
        similarities.push_back({dotProduct, i});
    }
    
    // 按相似度降序排序
    std::sort(similarities.begin(), similarities.end(),
              [](const std::pair<float, int> &a, const std::pair<float, int> &b) {
                  return a.first > b.first;
              });
    
    // 取 Top-K
    int k = std::min(topK, (int)similarities.size());
    std::vector<Recognition> recognitions;
    
    for (int i = 0; i < k; i++) {
        Recognition rec;
        rec.className = g_featureRepo.classNames[similarities[i].second];
        rec.confidence = similarities[i].first;  // 余弦相似度（已归一化的点积）
        rec.rank = i + 1;
        recognitions.push_back(rec);
    }
    
    return recognitions;
}