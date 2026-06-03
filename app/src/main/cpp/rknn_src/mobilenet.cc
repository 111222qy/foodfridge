#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <vector>

#include "yolov6.h"
#include "common.h"
#include "file_utils.h"
#include "image_utils.h"
// #include "feature_file.h"  // 需要 SQLite3，暂时移除
#include "time.h"
#include <android/log.h>
// #include <stb_image_write.h>  // 暂时移除，未找到头文件
#include <pthread.h>
// #include "crop_image.h"  // 需要 im2d.hpp，暂时移除

#include <sys/time.h>
#include <unistd.h>

WhitelistConfig g_whitelist = {nullptr, 0};
pthread_mutex_t whitelist_mutex = PTHREAD_MUTEX_INITIALIZER;

#define LOG_TAG "YOLOv6_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
static void dump_tensor_attr(rknn_tensor_attr* attr)
{
    printf("  index=%d, name=%s, n_dims=%d, dims=[%d, %d, %d, %d], n_elems=%d, size=%d, fmt=%s, type=%s, qnt_type=%s, "
            "zp=%d, scale=%f\n",
            attr->index, attr->name, attr->n_dims, attr->dims[0], attr->dims[1], attr->dims[2], attr->dims[3],
            attr->n_elems, attr->size, get_format_string(attr->fmt), get_type_string(attr->type),
            get_qnt_type_string(attr->qnt_type), attr->zp, attr->scale);
}

int init_mobilenet_model(const char* model_path, rknn_app_context_t* app_ctx)
{
    int ret;
    int model_len = 0;
    char* model;
    rknn_context ctx = 0;

    // Load RKNN Model
    model_len = read_data_from_file(model_path, &model);
    if (model == NULL) {
        printf("load_model fail!\n");
        return -1;
    }

    ret = rknn_init(&ctx, model, model_len, 0, NULL);
    free(model);
    if (ret < 0) {
        printf("rknn_init fail! ret=%d\n", ret);
        return -1;
    }

    // Get Model Input Output Number
    rknn_input_output_num io_num;
    ret = rknn_query(ctx, RKNN_QUERY_IN_OUT_NUM, &io_num, sizeof(io_num));
    if (ret != RKNN_SUCC) {
        printf("rknn_query fail! ret=%d\n", ret);
        return -1;
    }
    printf("model input num: %d, output num: %d\n", io_num.n_input, io_num.n_output);

    // Get Model Input Info
    printf("input tensors:\n");
    std::vector<rknn_tensor_attr> input_attrs(io_num.n_input);
    // memset(input_attrs, 0, sizeof(input_attrs)); // vector不需要memset
    for (int i = 0; i < io_num.n_input; i++) {
        input_attrs[i].index = i;
        ret = rknn_query(ctx, RKNN_QUERY_INPUT_ATTR, &(input_attrs[i]), sizeof(rknn_tensor_attr));
        if (ret != RKNN_SUCC) {
            printf("rknn_query fail! ret=%d\n", ret);
            return -1;
        }
        dump_tensor_attr(&(input_attrs[i]));
    }

    // Get Model Output Info
    printf("output tensors:\n");
    std::vector<rknn_tensor_attr> output_attrs(io_num.n_output);
    // memset(output_attrs, 0, sizeof(output_attrs)); // vector不需要memset
    for (int i = 0; i < io_num.n_output; i++) {
        output_attrs[i].index = i;
        ret = rknn_query(ctx, RKNN_QUERY_OUTPUT_ATTR, &(output_attrs[i]), sizeof(rknn_tensor_attr));
        if (ret != RKNN_SUCC) {
            printf("rknn_query fail! ret=%d\n", ret);
            return -1;
        }
        dump_tensor_attr(&(output_attrs[i]));
    }

    // Set to context
    app_ctx->rknn_ctx = ctx;
    app_ctx->io_num = io_num;
    app_ctx->input_attrs = (rknn_tensor_attr*)malloc(io_num.n_input * sizeof(rknn_tensor_attr));
    memcpy(app_ctx->input_attrs, input_attrs.data(), io_num.n_input * sizeof(rknn_tensor_attr));
    app_ctx->output_attrs = (rknn_tensor_attr*)malloc(io_num.n_output * sizeof(rknn_tensor_attr));
    memcpy(app_ctx->output_attrs, output_attrs.data(), io_num.n_output * sizeof(rknn_tensor_attr));

    if (input_attrs[0].fmt == RKNN_TENSOR_NCHW) {
        printf("model is NCHW input fmt\n");
        app_ctx->model_channel = input_attrs[0].dims[1];
        app_ctx->model_height  = input_attrs[0].dims[2];
        app_ctx->model_width   = input_attrs[0].dims[3];
    } else {
        printf("model is NHWC input fmt\n");
        app_ctx->model_height  = input_attrs[0].dims[1];
        app_ctx->model_width   = input_attrs[0].dims[2];
        app_ctx->model_channel = input_attrs[0].dims[3];
    }
    printf("model input height=%d, width=%d, channel=%d\n",
        app_ctx->model_height, app_ctx->model_width, app_ctx->model_channel);

    return 0;
}

int init_mobilenet_model_from_memory(const char* model_data, int model_size, rknn_app_context_t* app_ctx)
{
    int ret;
    rknn_context ctx = 0;
    app_ctx->input_attrs = NULL;  // 显式初始化
    app_ctx->output_attrs = NULL; // 显式初始化

    if (model_data == NULL || model_size <= 0) {
        printf("Invalid model data\n");
        return -1;
    }

    // Load RKNN Model from memory
    ret = rknn_init(&ctx, (void*)model_data, model_size, 0, NULL);
    if (ret < 0) {
        printf("rknn_init fail! ret=%d\n", ret);
        return -1;
    }

    // Get Model Input Output Number
    rknn_input_output_num io_num;
    ret = rknn_query(ctx, RKNN_QUERY_IN_OUT_NUM, &io_num, sizeof(io_num));
    if (ret != RKNN_SUCC) {
        printf("rknn_query fail! ret=%d\n", ret);
        if (ctx) rknn_destroy(ctx);
        return -1;
    }
    printf("model input num: %d, output num: %d\n", io_num.n_input, io_num.n_output);

    // Get Model Input Info
    printf("input tensors:\n");
    std::vector<rknn_tensor_attr> input_attrs(io_num.n_input);
    // memset(input_attrs, 0, sizeof(input_attrs)); // vector不需要memset
    for (int i = 0; i < io_num.n_input; i++) {
        input_attrs[i].index = i;
        ret = rknn_query(ctx, RKNN_QUERY_INPUT_ATTR, &(input_attrs[i]), sizeof(rknn_tensor_attr));
        if (ret != RKNN_SUCC) {
            printf("rknn_query fail! ret=%d\n", ret);
            if (ctx) rknn_destroy(ctx);
            return -1;
        }
        dump_tensor_attr(&(input_attrs[i]));
    }

    // Get Model Output Info
    printf("output tensors:\n");
    std::vector<rknn_tensor_attr> output_attrs(io_num.n_output);
    // memset(output_attrs, 0, sizeof(output_attrs)); // vector不需要memset
    for (int i = 0; i < io_num.n_output; i++) {
        output_attrs[i].index = i;
        ret = rknn_query(ctx, RKNN_QUERY_OUTPUT_ATTR, &(output_attrs[i]), sizeof(rknn_tensor_attr));
        if (ret != RKNN_SUCC) {
            printf("rknn_query fail! ret=%d\n", ret);
            if (ctx) rknn_destroy(ctx);
            return -1;
        }
        dump_tensor_attr(&(output_attrs[i]));
    }

    // Set to context
    app_ctx->rknn_ctx = ctx;
    app_ctx->io_num = io_num;
    app_ctx->input_attrs = (rknn_tensor_attr*)malloc(io_num.n_input * sizeof(rknn_tensor_attr));
    if (!app_ctx->input_attrs) {
        printf("malloc input_attrs fail!\n");
        if (ctx) rknn_destroy(ctx);
        return -1;
    }
    memcpy(app_ctx->input_attrs, input_attrs.data(), io_num.n_input * sizeof(rknn_tensor_attr));
    
    app_ctx->output_attrs = (rknn_tensor_attr*)malloc(io_num.n_output * sizeof(rknn_tensor_attr));
    if (!app_ctx->output_attrs) {
        printf("malloc output_attrs fail!\n");
        if (app_ctx->input_attrs) free(app_ctx->input_attrs);
        app_ctx->input_attrs = NULL;
        if (ctx) rknn_destroy(ctx);
        return -1;
    }
    memcpy(app_ctx->output_attrs, output_attrs.data(), io_num.n_output * sizeof(rknn_tensor_attr));

    if (input_attrs[0].fmt == RKNN_TENSOR_NCHW) {
        printf("model is NCHW input fmt\n");
        app_ctx->model_channel = input_attrs[0].dims[1];
        app_ctx->model_height  = input_attrs[0].dims[2];
        app_ctx->model_width   = input_attrs[0].dims[3];
    } else {
        printf("model is NHWC input fmt\n");
        app_ctx->model_height  = input_attrs[0].dims[1];
        app_ctx->model_width   = input_attrs[0].dims[2];
        app_ctx->model_channel = input_attrs[0].dims[3];
    }
    printf("model input height=%d, width=%d, channel=%d\n",
        app_ctx->model_height, app_ctx->model_width, app_ctx->model_channel);

    return 0;
}

int release_mobilenet_model(rknn_app_context_t* app_ctx)
{
    if (app_ctx->input_attrs != NULL) {
        free(app_ctx->input_attrs);
        app_ctx->input_attrs = NULL;
    }
    if (app_ctx->output_attrs != NULL) {
        free(app_ctx->output_attrs);
        app_ctx->output_attrs = NULL;
    }
    if (app_ctx->rknn_ctx != 0) {
        rknn_destroy(app_ctx->rknn_ctx);
        app_ctx->rknn_ctx = 0;
    }
    return 0;
}

int inference_mobilenet_model(rknn_app_context_t* app_ctx, image_buffer_t* src_img, mobilenet_result* out_result, int topk, const char* classes, const char* db_path)
{
    LOGD("Input parameters:");
    LOGD("app_ctx=%p, src_img=%p, out_result=%p", app_ctx, src_img, out_result);
    LOGD("topk=%d, classes=%s, db_path=%s", 
        topk, 
        classes ? classes : "null", 
        db_path ? db_path : "null");

    printf("Input parameters:\n");
    printf("app_ctx=%p, src_img=%p, out_result=%p\n", app_ctx, src_img, out_result);
    printf("topk=%d, classes=%s, db_path=%s\n", 
       topk, 
       classes ? classes : "null", 
       db_path ? db_path : "null");
    int ret;

    image_buffer_t img;
    rknn_input inputs[1];
    rknn_output outputs[1];
    // FeatureEntry* library = nullptr;  // 需要 feature_file.h，暂时禁用
    void* library = nullptr;  // 临时替代

    memset(&img, 0, sizeof(image_buffer_t));
    memset(inputs, 0, sizeof(inputs));
    memset(outputs, 0, sizeof(outputs));

    // Pre Process
    img.width = app_ctx->model_width;
    img.height = app_ctx->model_height;
    img.format = IMAGE_FORMAT_RGB888;
    img.size = get_image_size(&img);
    img.virt_addr = (unsigned char*)malloc(img.size);
    if (img.virt_addr == NULL) {
        printf("malloc buffer size:%d fail!\n", img.size);
        if (img.virt_addr != NULL) {
            free(img.virt_addr);
        }
        return -1;
    }
    LOGD("mobilenet_format:%d",src_img->format);
    ret = convert_image(src_img, &img, NULL, NULL, 0);
    if (ret < 0) {
        printf("convert_image fail! ret=%d\n", ret);
        LOGE("convert_image fail! ret=%d\n", ret);
        if (img.virt_addr != NULL) {
            free(img.virt_addr);
        }
        return -1;
    }

    // Set Input Data
    inputs[0].index = 0;
    inputs[0].type  = RKNN_TENSOR_UINT8;
    inputs[0].fmt   = RKNN_TENSOR_NHWC;
    inputs[0].size  = app_ctx->model_width * app_ctx->model_height * app_ctx->model_channel;
    inputs[0].buf   = img.virt_addr;

    ret = rknn_inputs_set(app_ctx->rknn_ctx, 1, inputs);
    if (ret < 0) {
        printf("rknn_input_set fail! ret=%d\n", ret);
        LOGE("rknn_input_set fail! ret=%d\n", ret);
        if (img.virt_addr != NULL) {
            free(img.virt_addr);
        }
        return -1;
    }

    // Run
    ret = rknn_run(app_ctx->rknn_ctx, nullptr);
    if (ret < 0) {
        printf("rknn_run fail! ret=%d\n", ret);
        LOGE("rknn_run fail! ret=%d\n", ret);
        if (img.virt_addr != NULL) {
            free(img.virt_addr);
        }
        return -1;
    }

    // Get Output
    outputs[0].want_float = 1;
    ret = rknn_outputs_get(app_ctx->rknn_ctx, 1, outputs, NULL);
    if (ret < 0) {
        printf("rknn_outputs_get fail! ret=%d\n", ret);
        LOGE("rknn_outputs_get fail! ret=%d\n", ret);
        if (img.virt_addr != NULL) {
            free(img.virt_addr);
        }
        return -1;
        // goto out;
    }

    // 数据库功能需要 feature_file.h，暂时禁用
    if (classes != nullptr){
        // pthread_mutex_lock(&db_mutex);  // 需要 feature_file.h
        printf("开启菜品注册功能（数据库功能已禁用）\n");
        LOGD("开启菜品注册功能（数据库功能已禁用）");
        // ret = save_feature_to_db(classes, (float*)outputs[0].buf, db_path);  // 需要 feature_file.h
        ret = -1;  // 暂时返回错误
        // pthread_mutex_unlock(&db_mutex);  // 需要 feature_file.h
        if (img.virt_addr != NULL) {
            free(img.virt_addr);
        }
        rknn_outputs_release(app_ctx->rknn_ctx, 1, outputs);
        return 2;
    }else{
        // 白名单加载逻辑 - 数据库功能已禁用
        pthread_mutex_lock(&whitelist_mutex);
        if(g_whitelist.count != 0 ){
            LOGD("启用白名单（数据库功能已禁用）");
            // library = load_feature_library_from_db_whitelist(...);  // 需要 feature_file.h
            library = nullptr;  // 暂时返回 nullptr
            if (library == nullptr){
                LOGD("白名单特征加载失败（数据库功能已禁用）!");
                if (img.virt_addr != NULL) {
                    free(img.virt_addr);
                }
                rknn_outputs_release(app_ctx->rknn_ctx, 1, outputs);    
                pthread_mutex_unlock(&whitelist_mutex);
                return -1;
            }
        } else {
            LOGD("未启用白名单（数据库功能已禁用）");
            printf("未启用白名单\n");
            printf("db_path:%s\n",db_path);
            // library = load_feature_library_from_db(db_path, &entry_count);  // 需要 feature_file.h
            library = nullptr;  // 暂时返回 nullptr
        }
        pthread_mutex_unlock(&whitelist_mutex);
        LOGD("特征加载!");

        if(library == nullptr){
            LOGE("特征加载失败（数据库功能已禁用）!");
            if (img.virt_addr != NULL) {
                free(img.virt_addr);
            }
            rknn_outputs_release(app_ctx->rknn_ctx, 1, outputs); 
            return -1;
        }
    
        // 数据库功能已禁用，特征对比功能暂时不可用
        ret = -1;  // 暂时返回错误
        LOGE("特征对比失败（数据库功能已禁用）\n");
                if (library) free(library); 
                goto out;
    }
    

out:
    if (img.virt_addr != NULL) {
        free(img.virt_addr);
    }
    rknn_outputs_release(app_ctx->rknn_ctx, 1, outputs);

    return ret;
}

int similarity_calculation(rknn_app_context_t* app_ctx, image_buffer_t* src_img, mobilenet_result* out_result,const char* target_class, const char* db_path){
    (void)out_result;
    (void)db_path;
    int ret;
    image_buffer_t img;
    rknn_input inputs[1];
    rknn_output outputs[1];
    // FeatureEntry* library = nullptr;  // 需要 feature_file.h，暂时禁用
    void* library = nullptr;  // 临时替代
    memset(&img, 0, sizeof(image_buffer_t));
    memset(inputs, 0, sizeof(inputs));
    memset(outputs, 0, sizeof(outputs));

    // Pre Process
    img.width = app_ctx->model_width;
    img.height = app_ctx->model_height;
    img.format = IMAGE_FORMAT_RGB888;
    img.size = get_image_size(&img);
    img.virt_addr = (unsigned char*)malloc(img.size);
    if (img.virt_addr == NULL) {
        printf("malloc buffer size:%d fail!\n", img.size);
        if (img.virt_addr != NULL) {
            free(img.virt_addr);
        }
        return -1;
    }
    LOGD("format:%d",src_img->format);
    ret = convert_image(src_img, &img, NULL, NULL, 0);
    if (ret < 0) {
        printf("convert_image fail! ret=%d\n", ret);
        if (img.virt_addr != NULL) {
            free(img.virt_addr);
        }
        return -1;
    }

    // Set Input Data
    inputs[0].index = 0;
    inputs[0].type  = RKNN_TENSOR_UINT8;
    inputs[0].fmt   = RKNN_TENSOR_NHWC;
    inputs[0].size  = app_ctx->model_width * app_ctx->model_height * app_ctx->model_channel;
    inputs[0].buf   = img.virt_addr;

    ret = rknn_inputs_set(app_ctx->rknn_ctx, 1, inputs);
    if (ret < 0) {
        printf("rknn_input_set fail! ret=%d\n", ret);
        if (img.virt_addr != NULL) {
            free(img.virt_addr);
        }
        return -1;
    }

    // Run
    ret = rknn_run(app_ctx->rknn_ctx, nullptr);
    if (ret < 0) {
        printf("rknn_run fail! ret=%d\n", ret);
        if (img.virt_addr != NULL) {
            free(img.virt_addr);
        }
        return -1;
    }

    // Get Output
    outputs[0].want_float = 1;
    ret = rknn_outputs_get(app_ctx->rknn_ctx, 1, outputs, NULL);
    if (ret < 0) {
        printf("rknn_outputs_get fail! ret=%d\n", ret);
        if (img.virt_addr != NULL) {
            free(img.virt_addr);
        }
        return -1;
        // goto out;
    }
    
    // for(int i = 0; i < 10; i++){
    //     LOGD("特征[%d]: %.6f\n", i, feature[i]);
    //     printf("特征[%d]: %.6f\n", i, feature[i]);
    // }
    // 修改后的白名单加载逻辑
    pthread_mutex_lock(&whitelist_mutex);
    (void)target_class;
    // library = load_first_feature_per_whitelist_class(...);  // 需要 feature_file.h
    library = nullptr;  // 暂时返回 nullptr
    if(library == nullptr){
        LOGE("特征加载失败!\n");
        if (img.virt_addr != NULL) {
            free(img.virt_addr);
        }
        pthread_mutex_unlock(&whitelist_mutex);
        return -1;
    }
    pthread_mutex_unlock(&whitelist_mutex);
    
    // 数据库功能已禁用，特征对比功能暂时不可用
    ret = -1;  // 暂时返回错误
        if (library) free(library);
        if (ret < 0) {
        LOGE("特征对比失败（数据库功能已禁用）\n");
            // return -1;
            goto out;
    }
    
    
out:
    if (img.virt_addr != NULL) {
        free(img.virt_addr);
    }
    rknn_outputs_release(app_ctx->rknn_ctx, 1, outputs); // 添加释放
    return ret;
}

int dish_registration(rknn_app_context_t* app_ctx, image_buffer_t* src_img, mobilenet_result* out_result, const char* classes, const char* db_path)
{
    (void)out_result;
    (void)db_path;
    int ret;
    image_buffer_t img;
    rknn_input inputs[1];
    rknn_output outputs[1];
    // letterbox_t letter_box;
    // int bg_color = 114;

    memset(&img, 0, sizeof(image_buffer_t));
    memset(inputs, 0, sizeof(inputs));
    memset(outputs, 0, sizeof(outputs));
    // memset(&letter_box, 0, sizeof(letterbox_t));


    LOGD("图像预处理");

    // Pre Process
    img.width = app_ctx->model_width;
    img.height = app_ctx->model_height;
    img.format = IMAGE_FORMAT_RGB888;
    img.size = get_image_size(&img);
    img.virt_addr = (unsigned char*)malloc(img.size);
    if (img.virt_addr == NULL) {
        printf("malloc buffer size:%d fail!\n", img.size);
        LOGE("malloc buffer size:%d fail!\n", img.size);
        // return -1;
        ret = -1;
        goto out;
    }
    LOGD("mobilenet_format:%d",src_img->format);
    printf("format:%d",src_img->format);
    ret = convert_image(src_img, &img, NULL, NULL, 0);
    LOGD("[DEBUG] 输入图像尺寸: %dx%d\n", src_img->width, src_img->height);
    printf("[DEBUG] 输入图像尺寸: %dx%d\n", src_img->width, src_img->height);
    // ret = convert_image_with_letterbox(src_img, &img, &letter_box, bg_color);
    LOGD("[DEBUG] 缩放后尺寸: %dx%d, ret=%d\n", img.width, img.height, ret);
    printf("[DEBUG] 缩放后尺寸: %dx%d, ret=%d\n", img.width, img.height, ret);
    if (ret < 0) {
        printf("缩放失败! 错误码: %d\n", ret);
        LOGE("缩放失败! 错误码: %d\n", ret);
        // return -1;
        goto out;

    }
    // if (ret < 0)
    // {
    //     printf("convert_image_with_letterbox fail! ret=%d\n", ret);
    //     LOGD("convert_image_with_letterbox fail! ret=%d\n", ret);
    //     return -1;
    // }
    // if (ret < 0) {
    //     printf("convert_image fail! ret=%d\n", ret);
    //     LOGD("convert_image fail! ret=%d\n", ret);
    //     return -1;
    // }
    LOGD("设置图像输入");

    // Set Input Data
    inputs[0].index = 0;
    inputs[0].type  = RKNN_TENSOR_UINT8;
    inputs[0].fmt   = RKNN_TENSOR_NHWC;
    inputs[0].size  = app_ctx->model_width * app_ctx->model_height * app_ctx->model_channel;
    inputs[0].buf   = img.virt_addr;

    ret = rknn_inputs_set(app_ctx->rknn_ctx, 1, inputs);
    if (ret < 0) {
        printf("rknn_input_set fail! ret=%d\n", ret);
        // return -1;
        goto out;

    }

    // Run
    ret = rknn_run(app_ctx->rknn_ctx, nullptr);
    if (ret < 0) {
        printf("rknn_run fail! ret=%d\n", ret);
        // return -1;
        goto out;

    }

    // Get Output
    outputs[0].want_float = 1;
    ret = rknn_outputs_get(app_ctx->rknn_ctx, 1, outputs, NULL);
    if (ret < 0) {
        printf("rknn_outputs_get fail! ret=%d\n", ret);
        // return -1;
        goto out;
    }
    LOGD("获取结果");

    if (classes!=nullptr){
        // pthread_mutex_lock(&db_mutex);  // 需要 feature_file.h
        printf("开启菜品注册功能（数据库功能已禁用）\n");
        LOGD("开启菜品注册功能（数据库功能已禁用）");
        // ret = save_feature_to_db(classes, (float*)outputs[0].buf, db_path);  // 需要 feature_file.h
        ret = -1;  // 暂时返回错误
        // pthread_mutex_unlock(&db_mutex);  // 需要 feature_file.h
        if(ret < 0){
            LOGE("注册失败\n");
            // return -1;
            goto out;
        }
        // LOGD("save_feature_to_db:%d\n",ret);
        // goto out;
    }else{
        LOGE("未上传菜品类别");
        // return -1;
        ret = -1;
        goto out;
    }
    

out:
    if (img.virt_addr != NULL) {
        free(img.virt_addr);
    }
    rknn_outputs_release(app_ctx->rknn_ctx, 1, outputs); // 添加释放
    return ret;
}