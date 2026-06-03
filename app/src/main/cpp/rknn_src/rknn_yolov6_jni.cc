#include <android/log.h>
#include <android/bitmap.h>
#include <jni.h>
#include <sys/sysinfo.h>
#include <sys/time.h>
#include "yolov6.h"
#include "image_drawing.h"
#include "crop_image.h"
#include "image_utils.h"
#include "file_utils.h"
#include "image_drawing.h"
#include "feature_file.h"
#include <pthread.h>
#include <malloc.h>
#include <stb_image_write.h>


extern "C" {

static rknn_app_context_t yolo_ctx;
static rknn_app_context_t mobilenet_ctx;

#define LOG_TAG "YOLOv6_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

void log_memory(const char* tag){
    struct mallinfo mi = mallinfo();
    LOGD("[%s] 内存使用: %d KB", tag, mi.uordblks / 1024);
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnLoad");
    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnUnload");
}

JNIEXPORT jboolean JNICALL 
Java_com_dongbei_weight_algorithm_Algorithm_initModels(
    JNIEnv* env,
    jclass clazz,
    jstring j_yolo_model_path,
    jstring j_mobilenet_model_path) 
{
    LOGD("Init models");
    log_memory("模型初始化开始");
    if (env->ExceptionCheck()){
        env->ExceptionClear();
        LOGE("JNI exception pending before init");
        return JNI_FALSE;
    }

    // 获取字符串指针时增加空指针检查
    const char* yolo_model_path = env->GetStringUTFChars(j_yolo_model_path, nullptr);
    if (yolo_model_path == nullptr) {
        LOGE("Get yolo path failed");
        return JNI_FALSE;
    }
    
    const char* mobilenet_model_path = env->GetStringUTFChars(j_mobilenet_model_path, nullptr);
    if (mobilenet_model_path == nullptr) {
        LOGE("Get mobilenet path failed");
        env->ReleaseStringUTFChars(j_yolo_model_path, yolo_model_path); // 释放已获取的yolo路径
        return JNI_FALSE;
    }

    // YOLO初始化
    int yolo_ret = init_yolov6_model(yolo_model_path, &yolo_ctx);
    if (yolo_ret != 0) {
        LOGE("Init YOLOv6 failed: %d", yolo_ret);
        env->ReleaseStringUTFChars(j_yolo_model_path, yolo_model_path);
        env->ReleaseStringUTFChars(j_mobilenet_model_path, mobilenet_model_path);
        return JNI_FALSE;
    }

    // MobileNet初始化
    int mobilenet_ret = init_mobilenet_model(mobilenet_model_path, &mobilenet_ctx);
    if (mobilenet_ret != 0) {
        LOGE("Init MobileNet failed: %d", mobilenet_ret);
        env->ReleaseStringUTFChars(j_yolo_model_path, yolo_model_path);
        env->ReleaseStringUTFChars(j_mobilenet_model_path, mobilenet_model_path);
        release_yolov6_model(&yolo_ctx); // 仅释放已初始化的YOLO模型
        return JNI_FALSE;
    }

    // // 成功时释放字符串引用
    env->ReleaseStringUTFChars(j_yolo_model_path, yolo_model_path);
    env->ReleaseStringUTFChars(j_mobilenet_model_path, mobilenet_model_path);
    
    LOGD("Init models success");
    log_memory("模型初始化结束");
    return JNI_TRUE; 
}
// 检测识别接口
JNIEXPORT jboolean JNICALL
Java_com_dongbei_weight_algorithm_Algorithm_detect(
    JNIEnv* env,
    jclass clazz,
    jobject j_bitmap,
    jstring j_label,
    jstring j_db_path,
    jint TOPK,
    jobject j_result_list)
{

    log_memory("检测开始");
    AndroidBitmapInfo bitmap_info;
    if (AndroidBitmap_getInfo(env, j_bitmap, &bitmap_info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Get bitmap info failed");
        return JNI_FALSE;
    }

    void* pixel_buffer;
    if (AndroidBitmap_lockPixels(env, j_bitmap, &pixel_buffer) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Lock pixels failed");
        return JNI_FALSE;
    }

    int ret = 0;
    // 准备输入图像结构体
    image_buffer_t src_image;
    memset(&src_image, 0, sizeof(image_buffer_t));
    src_image.width = bitmap_info.width;
    src_image.height = bitmap_info.height;
    src_image.format = IMAGE_FORMAT_RGBA8888;
    src_image.virt_addr = static_cast<unsigned char*>(pixel_buffer);

    // 执行YOLOv6推理
    object_detect_result_list* det_results = (object_detect_result_list*)malloc(sizeof(object_detect_result_list));
    memset(det_results, 0, sizeof(object_detect_result_list));
    struct timeval yolo_start, yolo_end;
    double yolov6_time_used = 0;
    gettimeofday(&yolo_start, NULL);  // 记录开始时间
    log_memory("检测模型开始");
    ret = inference_yolov6_model(&yolo_ctx, &src_image, det_results);
    log_memory("检测模型结束");
    gettimeofday(&yolo_end, NULL); // 记录结束时间
    yolov6_time_used = (yolo_end.tv_sec - yolo_start.tv_sec) + (yolo_end.tv_usec - yolo_start.tv_usec) / 1000000.0;
    LOGD("检测耗时: %f ms", yolov6_time_used * 1000);
    if (ret != 0) {
        LOGE("YOLOv6 inference failed: %d", ret);
        AndroidBitmap_unlockPixels(env, j_bitmap);
        return JNI_FALSE;
    }

    // 获取Java类和方法 ================================
    jclass inferInfoClass = env->FindClass("com/dongbei/weight/algorithm/InferInfo");
    jclass detectInfoClass = env->FindClass("com/dongbei/weight/algorithm/DetectInfo");
    jclass classifyInfoClass = env->FindClass("com/dongbei/weight/algorithm/ClassifyInfo");
    jclass arrayListClass = env->FindClass("java/util/ArrayList");

    // 异常检查
    if (!inferInfoClass || !detectInfoClass || !classifyInfoClass || !arrayListClass) {
        LOGE("Java class not found");
        AndroidBitmap_unlockPixels(env, j_bitmap);
        return JNI_FALSE;
    }

    // 获取构造函数和方法ID
    jmethodID inferInfoConstructor = env->GetMethodID(inferInfoClass, "<init>", "()V");
    jmethodID detectInfoConstructor = env->GetMethodID(detectInfoClass, "<init>", "()V");
    jmethodID classifyInfoConstructor = env->GetMethodID(classifyInfoClass, "<init>", "()V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");
    jmethodID arrayListConstructor = env->GetMethodID(arrayListClass, "<init>", "()V");

    // 获取字段ID
    jfieldID inferInfoBox = env->GetFieldID(inferInfoClass, "box", "Lcom/dongbei/weight/algorithm/DetectInfo;");
    jfieldID inferInfoClassify = env->GetFieldID(inferInfoClass, "classifyInfos", "Ljava/util/ArrayList;");
    
    jfieldID detectX1 = env->GetFieldID(detectInfoClass, "x1", "I");
    jfieldID detectY1 = env->GetFieldID(detectInfoClass, "y1", "I");
    jfieldID detectX2 = env->GetFieldID(detectInfoClass, "x2", "I");
    jfieldID detectY2 = env->GetFieldID(detectInfoClass, "y2", "I");
    jfieldID detectC = env->GetFieldID(detectInfoClass, "c", "I");
    
    jfieldID classifyName = env->GetFieldID(classifyInfoClass, "className", "Ljava/lang/String;");
    jfieldID classifyScore = env->GetFieldID(classifyInfoClass, "score", "F");

    const char* classes = nullptr;
    if (j_label != nullptr){
        classes = env->GetStringUTFChars(j_label, 0);
    }
    
    const char* db_path = env->GetStringUTFChars(j_db_path, nullptr);

    // 处理检测结果 ====================================
    struct timeval rec_start, rec_end;
    double mobilenet_time = 0;
    gettimeofday(&rec_start, NULL);
    for (int i = 0; i < det_results->count; i++) {
        object_detect_result* det = &det_results->results[i];
        LOGD("%s @ (%d %d %d %d) %.3f\n", coco_cls_to_name(det->cls_id),
            det->box.left, det->box.top,
            det->box.right, det->box.bottom,
            det->prop);
        if (det->cls_id != 1){

            // 创建InferInfo对象
            jobject inferInfoObj = env->NewObject(inferInfoClass, inferInfoConstructor);
            
            // 填充DetectInfo ==============================
            jobject detectInfoObj = env->NewObject(detectInfoClass, detectInfoConstructor);
            env->SetIntField(detectInfoObj, detectX1, det->box.left);
            env->SetIntField(detectInfoObj, detectY1, det->box.top);
            env->SetIntField(detectInfoObj, detectX2, det->box.right);
            env->SetIntField(detectInfoObj, detectY2, det->box.bottom);
            env->SetIntField(detectInfoObj, detectC, det->cls_id);
            env->SetObjectField(inferInfoObj, inferInfoBox, detectInfoObj);

            // 创建分类结果列表 =============================
            jobject classifyList = env->NewObject(arrayListClass, arrayListConstructor);
            // int bpp = get_bpp_from_format(RK_FORMAT_RGB_888);
            // stbi_write_png("/storage/emulated/0/sample_weight/model/food3.png", src_image.width, src_image.height, 3,
            //     src_image.virt_addr, src_image.width * bpp);
            // 执行MobileNet分类
            image_buffer_t crop_img;
            memset(&crop_img, 0, sizeof(image_buffer_t));
            LOGD("YOLOv6 输出图像格式: %d", src_image.format);
            // process_detections(&src_image, det, &crop_img);
            struct timeval crop_start, crop_end;
            double crop_time = 0;
            gettimeofday(&crop_start, NULL);
            image_buffer_t dst_img;
            int rets = 0;
            memset(&dst_img, 0, sizeof(image_buffer_t));
            dst_img.width = src_image.width;
            dst_img.height = src_image.height;
            dst_img.format = IMAGE_FORMAT_RGB888;
            dst_img.size = get_image_size(&dst_img);
            dst_img.virt_addr = (unsigned char*)malloc(dst_img.size);
            if (dst_img.virt_addr == NULL) {
                printf("malloc buffer size:%d fail!\n", dst_img.size);
            }
            
            // rets = convert_image(&src_image, &dst_img, NULL, NULL, 0);
            // if (rets < 0) {
            //     printf("convert_image fail! ret=%d\n", rets);
            // }
            // if (crop_image(&dst_img, &crop_img,
            //     det->box.left, det->box.top,
            //     det->box.right - det->box.left,
            //     det->box.bottom - det->box.top) != 0){
            //         LOGE("裁剪失败\n");
            //         env->DeleteLocalRef(detectInfoObj); // 添加清理
            //         continue;
            //     }
            
            // process_detections(&src_image, det, &crop_img);
            log_memory("裁剪开始");
            if (crop_RGBA_image(&src_image, &crop_img,
                det->box.left, det->box.top,
                det->box.right - det->box.left,
                det->box.bottom - det->box.top) != 0){
                    LOGE("裁剪失败\n");
                    env->DeleteLocalRef(detectInfoObj); // 添加清理
                    if (crop_img.virt_addr) release_image(&crop_img);
                    continue;
                }
            log_memory("裁剪结束");

            if (dst_img.virt_addr !=nullptr){
                release_image(&dst_img);
            }
            gettimeofday(&crop_end, NULL);
            crop_time = (crop_end.tv_sec - crop_start.tv_sec) + (crop_end.tv_usec - crop_start.tv_usec) / 1000000.0;
            LOGD("裁剪耗时: %f ms", crop_time * 1000);
            
            mobilenet_result rec_results[TOPK];
            // int format = (crop_img.format == IMAGE_FORMAT_RGBA8888) ? 
            // RK_FORMAT_RGBA_8888 : RK_FORMAT_RGB_888; // 动态获取格式
            // int bpp = get_bpp_from_format(format);
            // int channels = (format == RK_FORMAT_RGBA_8888) ? 4 : 3;
            // stbi_write_png("/storage/emulated/0/sample_weight/model/food2.png", crop_img.width, crop_img.height, 3,
            //     crop_img.virt_addr, crop_img.width * bpp);
            struct timeval mobilenet_start, mobilenet_end;
            double infer_mobilenet_time = 0;
            gettimeofday(&mobilenet_start, NULL);
            log_memory("识别模型开始");
            ret = inference_mobilenet_model(&mobilenet_ctx, &crop_img, rec_results, TOPK, classes, db_path);
            log_memory("识别模型结束");
            gettimeofday(&mobilenet_end, NULL);
            infer_mobilenet_time = (mobilenet_end.tv_sec - mobilenet_start.tv_sec) + (mobilenet_end.tv_usec - mobilenet_start.tv_usec) / 1000000.0;
            LOGD("单个框识别: %f ms", infer_mobilenet_time * 1000);
            // ret = similarity_calculation(&mobilenet_ctx, &crop_img, rec_results,classes,db_path);
            if (ret == 0) {
                LOGD("%d (%d %d %d %d) %.3f\n",det->cls_id,det->box.left, det->box.top,det->box.right, det->box.bottom,det->prop);
                for(int j = 0; j < TOPK; j++) {
                    // 创建ClassifyInfo对象
                    const char* cls_name = rec_results[j].cls;
                    LOGD("识别结果 -> Top[%d]: %s (%.2f%%)\n", j+1,rec_results[j].cls, rec_results[j].score*100);
                    jobject classifyInfoObj = env->NewObject(classifyInfoClass, classifyInfoConstructor);
                    jstring j_cls_name = env->NewStringUTF(cls_name);
                    
                    // 设置分类字段
                    env->SetObjectField(classifyInfoObj, classifyName, j_cls_name);
                    env->SetFloatField(classifyInfoObj, classifyScore, rec_results[j].score);
                    
                    // 添加到列表
                    env->CallBooleanMethod(classifyList, arrayListAdd, classifyInfoObj);
                    
                    // 释放局部引用
                    env->DeleteLocalRef(j_cls_name);
                    env->DeleteLocalRef(classifyInfoObj);
                }
            }else if (ret == 2)
            {
                LOGD("注册成功");
            }else{
                LOGE("识别失败");
            }

            // env->ReleaseStringUTFChars(j_db_path, db_path);
            if (crop_img.virt_addr != nullptr){
                release_image(&crop_img);
            }
            // 关联分类列表到InferInfo
            env->SetObjectField(inferInfoObj, inferInfoClassify, classifyList);
            
            // 添加主结果列表
            env->CallBooleanMethod(j_result_list, arrayListAdd, inferInfoObj);

            // 释放局部引用
            env->DeleteLocalRef(detectInfoObj);
            env->DeleteLocalRef(classifyList);
            env->DeleteLocalRef(inferInfoObj);
        }
    }
    gettimeofday(&rec_end, NULL);
    mobilenet_time = (rec_end.tv_sec - rec_start.tv_sec) + (rec_end.tv_usec - rec_start.tv_usec) / 1000000.0;
    LOGD("全部检测框识别耗时: %.3f ms", mobilenet_time * 1000);
    // 资源释放 ========================================
    struct timeval result_start, result_end;
    double release_time = 0;
    gettimeofday(&result_start, NULL);
    AndroidBitmap_unlockPixels(env, j_bitmap);
    if (j_label != nullptr && classes != nullptr){
        env->ReleaseStringUTFChars(j_label, classes);
    }

    // 释放类引用
    free(det_results);
    env->ReleaseStringUTFChars(j_db_path, db_path);
    env->DeleteLocalRef(inferInfoClass);
    env->DeleteLocalRef(detectInfoClass);
    env->DeleteLocalRef(classifyInfoClass);
    env->DeleteLocalRef(arrayListClass);
    gettimeofday(&result_end, NULL);
    release_time = (result_end.tv_sec - result_start.tv_sec) + (result_end.tv_usec - result_start.tv_usec) / 1000000.0;
    LOGD("释放资源耗时: %.3f ms", release_time * 1000);
    log_memory("检测结束");
    return (ret != -1) ? JNI_TRUE : JNI_FALSE;
}

// 子图特征对比
JNIEXPORT jfloat JNICALL
Java_com_dongbei_weight_algorithm_Algorithm_similarityCalculation(
    JNIEnv* env,
    jclass clazz,
    jobject j_crop_bitmap,
    jstring j_class,
    jstring j_db_path)  // 新增结果列表参数
{
    log_memory("子图特征对比开始");
    // 参数校验
    if (j_class == nullptr) {
        LOGE("Invalid result list");
        return JNI_FALSE;
    }

    AndroidBitmapInfo bitmap_info;
    if (AndroidBitmap_getInfo(env, j_crop_bitmap, &bitmap_info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Get crop bitmap info failed");
        return JNI_FALSE;
    }

    void* pixel_buffer;
    if (AndroidBitmap_lockPixels(env, j_crop_bitmap, &pixel_buffer) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Lock crop pixels failed");
        return JNI_FALSE;
    }

    image_buffer_t crop_image; 
    crop_image.width = bitmap_info.width;
    crop_image.height = bitmap_info.height;
    crop_image.format = IMAGE_FORMAT_RGBA8888;
    crop_image.virt_addr = static_cast<unsigned char*>(pixel_buffer);

    // 获取特征维度
    mobilenet_result rec_results[1];
    const char* target_class = env->GetStringUTFChars(j_class, 0);
    const char* db_path = env->GetStringUTFChars(j_db_path, nullptr);
    // 获取特征
    int ret = similarity_calculation(&mobilenet_ctx, &crop_image, rec_results,target_class,db_path);
    AndroidBitmap_unlockPixels(env, j_crop_bitmap);
    // 释放资源
    jfloat result = 0.0f;
    if (ret != -1) {
        result = rec_results[0].score;
    }
    
    if (target_class) env->ReleaseStringUTFChars(j_class, target_class);
    if (db_path) env->ReleaseStringUTFChars(j_db_path, db_path);
    
    log_memory("子图特征对比结束");
    return result;
}

// 删除指定类别
JNIEXPORT jboolean JNICALL
Java_com_dongbei_weight_algorithm_Algorithm_deleteClasses(
    JNIEnv* env,
    jclass clazz,
    jstring j_db_path,
    jobjectArray j_categories)
{
    log_memory("删除类别开始");
    const char* db_path = env->GetStringUTFChars(j_db_path, nullptr);
    if (!db_path) {
        LOGE("Invalid database path");
        return JNI_FALSE;
    }

    int result = 0;
    
    // 处理两种情况
    if (j_categories == nullptr) {
        // 清空数据库
        LOGD("清空数据库");
        result = delete_all_categories_from_db(db_path);
    } else {
        // 批量删除
        LOGD("批量删除数据库");
        jsize count = env->GetArrayLength(j_categories);
        const char** categories = new const char*[count];
        
        for (jsize i = 0; i < count; ++i) {
            jstring j_category = (jstring)env->GetObjectArrayElement(j_categories, i);
            categories[i] = env->GetStringUTFChars(j_category, nullptr);
            env->DeleteLocalRef(j_category);
        }

        result = delete_categories_from_db(db_path, categories, count);

        // 释放资源
        for (jsize i = 0; i < count; ++i) {
            env->ReleaseStringUTFChars(
                (jstring)env->GetObjectArrayElement(j_categories, i), 
                categories[i]
            );
        }
        delete[] categories;
    }

    env->ReleaseStringUTFChars(j_db_path, db_path);
    log_memory("删除类别结束");
    return (result >= 0) ? JNI_TRUE : JNI_FALSE;
}

// 查询类别列表接口
JNIEXPORT jboolean JNICALL
Java_com_dongbei_weight_algorithm_Algorithm_queryClasses(
    JNIEnv* env,
    jclass clazz,
    jstring j_db_path,
    jobject j_result_list)
{
    log_memory("查询类别开始");
    LOGD("数据库查询");
    // 参数校验
    if (j_db_path == NULL) {
        LOGE("数据库地址为空");
        return JNI_TRUE;
    }

    if (j_result_list == nullptr) {
        LOGE("Invalid result list");
        return JNI_FALSE;
    }
    // 数据库查询
    const char* db_path = env->GetStringUTFChars(j_db_path, nullptr);
    if (db_path == NULL) {
        LOGE("数据库地址获取失败");
        return JNI_FALSE; 
    }
    char** categories = NULL;
    int count = 0;
    LOGD("查询数据库路径：%s",db_path);
    int ret = query_categories_from_db(db_path, &categories, &count);

    if (ret != 0) {
        if (categories) free_categories(categories, count);
        return JNI_FALSE;
    }
    if(count == 0){
        if (categories) free_categories(categories, count);
        LOGE("数据库为空");
        return JNI_TRUE;
    }

    // 使用缓存的方法ID
    static jmethodID arrayList_add = nullptr;
    if (arrayList_add == nullptr) {
        jclass clazz = env->FindClass("java/util/ArrayList");
        arrayList_add = env->GetMethodID(clazz, "add", "(Ljava/lang/Object;)Z");
        env->DeleteLocalRef(clazz);
    }

    // 批量添加结果
    for (int i = 0; i < count; ++i) {
        const char* cls_name = categories[i];

        jstring str = env->NewStringUTF(cls_name);
        env->CallBooleanMethod(j_result_list, arrayList_add, str);
        env->DeleteLocalRef(str);
        
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("Exception occurred while adding item %d", i);
            break;
        }
    }

    free_categories(categories, count);
    env->ReleaseStringUTFChars(j_db_path, db_path);

    log_memory("查询类别结束");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_dongbei_weight_algorithm_Algorithm_releaseModels(
    JNIEnv* env,
    jclass clazz) {
    log_memory("释放模型开始");

    if (release_yolov6_model(&yolo_ctx) != 0) {
        LOGE("Release YOLOv6 model failed");
        return JNI_FALSE;
    }

    if (release_mobilenet_model(&mobilenet_ctx) != 0) {
        LOGE("Release MobileNet model failed");
        return JNI_FALSE;
    }
    log_memory("释放模型结束");

    return JNI_TRUE;
}

// 设置白名单
JNIEXPORT jboolean JNICALL  // 修改返回类型为jboolean
Java_com_dongbei_weight_algorithm_Algorithm_setWhitelist(
    JNIEnv* env,
    jclass clazz,
    jobjectArray j_whitelist) 
{
    log_memory("白名单开始");

    pthread_mutex_lock(&whitelist_mutex);
    // 释放旧数据
    if(g_whitelist.whitelist != nullptr) {
        for(int i=0; i<g_whitelist.count; ++i){
            if(g_whitelist.whitelist[i]) {
                free(g_whitelist.whitelist[i]);
                g_whitelist.whitelist[i] = nullptr; // 置空指针
            }
        }
        free(g_whitelist.whitelist);
        g_whitelist.whitelist = nullptr;
        g_whitelist.count = 0;
    }

    jboolean result = JNI_FALSE;  // 默认返回失败)
    // 转换新数据
    if (j_whitelist == nullptr){
        LOGD("清空白名单");
        result = JNI_TRUE;  // 清空白名单成功
    } else {
        jsize len = env->GetArrayLength(j_whitelist);
        
        // 分配内存失败检查
        g_whitelist.whitelist = (char**)calloc(len, sizeof(char*)); 
        if (!g_whitelist.whitelist) {
            LOGE("Memory allocation failed for whitelist");
            pthread_mutex_unlock(&whitelist_mutex);
            g_whitelist.count = 0; // 确保count清零
            return JNI_FALSE;
        }
        g_whitelist.count = len;
        if (g_whitelist.count == 0) {
            LOGD("白名单数组为空");
            g_whitelist.count = -1;
            pthread_mutex_unlock(&whitelist_mutex);
            return JNI_TRUE; 
        }
        for(int i=0; i<len; ++i){
            jstring str = (jstring)env->GetObjectArrayElement(j_whitelist, i);
            if (str == nullptr) {
                LOGE("Null string at index %d", i);
                continue;  // 跳过无效条目
            }
            
            const char* utf_str = env->GetStringUTFChars(str, nullptr);
            if (!utf_str) {
                LOGE("GetStringUTFChars failed at index %d", i);
                env->DeleteLocalRef(str);
                continue;  // 跳过无效条目
            }
            
            char* dup_str = strdup(utf_str);
            if (!dup_str) {
                LOGE("strdup failed at index %d", i);
                env->ReleaseStringUTFChars(str, utf_str);
                env->DeleteLocalRef(str);
                continue;
            }
            g_whitelist.whitelist[i] = dup_str;
            if (!g_whitelist.whitelist[i]) {
                LOGE("String duplication failed at index %d", i);
                env->ReleaseStringUTFChars(str, utf_str);
                env->DeleteLocalRef(str);
                continue;  // 跳过无效条目
            }
            
            env->ReleaseStringUTFChars(str, utf_str);
            env->DeleteLocalRef(str);
        }
        result = JNI_TRUE;  // 至少部分成功
    }

    pthread_mutex_unlock(&whitelist_mutex);
    LOGD("Whitelist updated with %d items", g_whitelist.count);
    log_memory("白名单结束");

    return result;
}

// 新增注册接口
JNIEXPORT jboolean JNICALL
Java_com_dongbei_weight_algorithm_Algorithm_register(
    JNIEnv* env,
    jclass clazz,
    jobject j_crop_bitmap,
    jstring j_class,
    jstring j_db_path)
{
    log_memory("注册开始");

    // 参数校验
    if (j_class == nullptr || j_db_path == nullptr) {
        LOGE("Invalid parameters");
        return JNI_FALSE;
    }

    AndroidBitmapInfo bitmap_info;
    if (AndroidBitmap_getInfo(env, j_crop_bitmap, &bitmap_info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Get crop bitmap info failed");
        return JNI_FALSE;
    }

    void* pixel_buffer;
    if (AndroidBitmap_lockPixels(env, j_crop_bitmap, &pixel_buffer) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Lock crop pixels failed");
        return JNI_FALSE;
    }

    // 准备图像结构体
    image_buffer_t crop_image;
    memset(&crop_image, 0, sizeof(image_buffer_t));
    crop_image.width = bitmap_info.width;
    crop_image.height = bitmap_info.height;
    crop_image.format = IMAGE_FORMAT_RGBA8888;
    crop_image.virt_addr = static_cast<unsigned char*>(pixel_buffer);

    // 执行MobileNet推理
    mobilenet_result rec_results[1];
    const char* target_class = env->GetStringUTFChars(j_class, 0);
    const char* db_path = env->GetStringUTFChars(j_db_path, nullptr);
    
    // 直接调用特征保存逻辑
    int ret = dish_registration(&mobilenet_ctx, &crop_image, rec_results, target_class, db_path);

    AndroidBitmap_unlockPixels(env, j_crop_bitmap);
    env->ReleaseStringUTFChars(j_class, target_class);
    env->ReleaseStringUTFChars(j_db_path, db_path);
    log_memory("注册结束");

    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}
}