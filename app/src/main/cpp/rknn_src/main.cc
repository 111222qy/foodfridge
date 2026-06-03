// Copyright (c) 2023 by Rockchip Electronics Co., Ltd. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/*-------------------------------------------
                Includes
-------------------------------------------*/
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "yolov6.h"
#include "image_utils.h"
#include "file_utils.h"
#include "image_drawing.h"
#include "crop_image.h"
#include "feature_file.h"
#include "time.h"



/*-------------------------------------------
                Main Function
-------------------------------------------*/
int main(int argc, char **argv)
{
    const char* yolo_model_path;
    const char* mobilenet_model_path;
    const char* image_path;
    const char* classes;
    // printf("argc:%d\n",argc);
    if (argc == 4) {
        yolo_model_path = argv[1];
        mobilenet_model_path = argv[2];
        image_path = argv[3];
        classes = nullptr;
        printf("%s <yolo_model> <mobilenet_model> <image_path> <label>\n", argv[0]);

    } else {
            yolo_model_path = argv[1];
            mobilenet_model_path = argv[2];
            image_path = argv[3];
            classes = argv[4];
            printf("%s <yolo_model> <mobilenet_model> <image_path> <label>\n", argv[0]);
    }

    // const char* yolo_model_path = argv[1];
    // const char* mobilenet_model_path = argv[2];
    // const char* image_path = argv[3];

    // --------------------- 初始化模型 ---------------------
    rknn_app_context_t yolo_ctx, mobilenet_ctx;
    memset(&yolo_ctx, 0, sizeof(rknn_app_context_t));
    memset(&mobilenet_ctx, 0, sizeof(rknn_app_context_t));

    init_post_process();

    // 初始化YOLOv6
    if (init_yolov6_model(yolo_model_path, &yolo_ctx) != 0) {
        printf("Init YOLO model failed\n");
        return -1;
    }

    // 初始化MobileNet
    if (init_mobilenet_model(mobilenet_model_path, &mobilenet_ctx) != 0) {
        printf("Init MobileNet model failed\n");
        // release_yolov6_model(&yolo_ctx);
        return -1;
    }

    // --------------------- 读取输入图像 ---------------------
    image_buffer_t src_image;
    object_detect_result_list* od_results = (object_detect_result_list*)malloc(sizeof(object_detect_result_list));
    memset(od_results, 0, sizeof(object_detect_result_list));
    memset(&src_image, 0, sizeof(image_buffer_t));
    if (read_image(image_path, &src_image) != 0) {
        printf("Read image failed\n");
        // goto EXIT;
    }

    // --------------------- YOLO检测 ---------------------
    // object_detect_result_list od_results;
    // memset(od_results, 0, sizeof(object_detect_result_list));
    printf("src_image:%d\n",src_image.format);
    if (inference_yolov6_model(&yolo_ctx, &src_image, od_results) != 0) {
        printf("YOLO inference failed\n");
        // goto EXIT;
    }

    printf("处理检测结果\n");
    printf("%d\n",od_results->count);
    struct timeval total_start, total_end;
    double det_time;
    gettimeofday(&total_start, NULL);  // 记录开始时间
    int test_registration = 0;
    if(test_registration == 1){
        mobilenet_result rec_results[1];
        const char* target_class = "排骨";
        const char* db_path = "feature.db";
        printf("开始注册\n");
        int ret = dish_registration(&mobilenet_ctx, &src_image, rec_results, target_class, db_path);
        printf("注册成功\n");
    }
    // --------------------- 处理检测结果 ---------------------
    for (int i = 0; i < od_results->count; i++) {
        object_detect_result* det = &od_results->results[i];
        printf("cls_id:%d,i:%d\n",det->cls_id,i);
        if (det->cls_id == 1) continue;
        printf("%s (%d %d %d %d) %.3f\n", coco_cls_to_name(det->cls_id),
        det->box.left, det->box.top,
        det->box.right, det->box.bottom,
        det->prop);
        // 1. 裁剪检测区域
        image_buffer_t crop_img;
        clock_t start, end,start1, end1;
        double cpu_time_used,gpu_time_used;
        // start = clock();  // 记录开始时间
        // process_detections(&src_image, det, &crop_img);
        // end = clock();  // 记录结束时间
        // gpu_time_used = ((double)(end - start)) / CLOCKS_PER_SEC;  // 转换为秒
        // printf("crop硬件裁剪时间: %f 秒\n", gpu_time_used);
        memset(&crop_img, 0, sizeof(image_buffer_t)); // 强制初始化
        int crop_mode = 0;
        if (crop_mode == 0){
            start1 = clock();
            if (crop_image(&src_image, &crop_img,
            det->box.left, det->box.top,
            det->box.right - det->box.left,
            det->box.bottom - det->box.top) != 0){
                printf("Crop failed\n");
                continue;
            }
            end1 = clock();
            cpu_time_used = ((double)(end1 - start1)) / CLOCKS_PER_SEC;
            printf("裁剪耗时: %f ms\n", cpu_time_used);
            
        }else{
            process_detections(&src_image, det, &crop_img);
            // stbi_write_png("/storage/emulated/0/sample_weight/model/food.png", TARGET_SIZE, TARGET_SIZE, channels,
            //     src_image->virt_addr, TARGET_SIZE * bpp);
        }
        // 2. MobileNet分类
        const int TOPK = 5;
        int ret = 0;
        mobilenet_result rec_results[TOPK];
        const char* db_path = "feature.db";
        int test_getfeature = 1;
        if (test_getfeature == 1){
            ret = inference_mobilenet_model(&mobilenet_ctx, &crop_img, rec_results, TOPK, classes,db_path);
            if (ret == 0) {  
                for(int i = 0; i < TOPK; i++){
                    printf("识别结果 -> Top%d: %s (%.2f%%)\n",
                        // coco_cls_to_name(det->cls_id),
                        i+1,
                        rec_results[i].cls,
                        rec_results[i].score*100);
                }
            }else if (ret == 2){
                printf("注册成功\n");
            }
        }else if (test_getfeature == 2){
            printf("验证类别\n");
            const char* target_class = "番茄炒蛋";
            ret = similarity_calculation(&mobilenet_ctx, &crop_img,rec_results, target_class,db_path);
            if (ret == 0) {  
                printf("分数：(%.2f%%)\n",
                    rec_results[i].score*100);
                
            }
        }

        int test_delete = 0;
        if (test_delete == 1){
            const char* foods[] = {"番茄炒蛋","紫菜蛋花汤"};
            int food_count = sizeof(foods)/sizeof(foods[0]);
            int ret = delete_categories_from_db(db_path, foods,food_count);
            if (ret >= 0) {
                printf("[TEST] 批量删除成功，删除%d条记录\n",ret);
            }else {
                printf("[TEST] 批量删除失败,错误码：%d\n",ret);
            }
        }else if (test_delete == 2)
        {
            int ret_all = delete_all_categories_from_db(db_path);
            if (ret_all >= 0) {
                printf("[TEST] 清空数据库成功，删除%d条记录\n",ret_all);
            }else{
                printf("[TEST] 清空数据库失败，错误码：%d\n",ret_all);
            }
        }
        


        int query_class = 1;
        // 调用查询函数
        if (query_class == 1){
            char** categories = NULL;
            int category_count = 0;
            printf("查询数据库中类别:\n");
            int ret = query_categories_from_db(db_path, &categories, &category_count);
            printf("开始查询");
            if (ret == 0 && category_count > 0) {
                printf("Found %d categories:\n", category_count);
                for (int i = 0; i < category_count; ++i) {
                    printf("[%d] %s\n", i+1, categories[i]);
                }
                free_categories(categories, category_count); // 必须调用释放内存
            } else {
                printf("No categories found or error occurred\n");
                return -1;
            }
        }
            // // 3. 绘制结果
            // char text[256];
            // sprintf(text, "%s|%s %.1f%%",
            //     coco_cls_to_name(det->cls_id),
            //     results[0].cls,
            //     results[0].score*100);
            // draw_rectangle(&src_image,
            //             det->box.left, det->box.top,
            //             det->box.right - det->box.left,
            //             det->box.bottom - det->box.top,
            //             COLOR_BLUE, 3);
            // draw_text(&src_image, text,
            //         det->box.left, det->box.top - 25,
            //         COLOR_RED, 12);

        // 4. 释放裁剪图像
        release_image(&crop_img);
        
    }
    gettimeofday(&total_end, NULL); // 记录结束时间
    det_time = (total_end.tv_sec - total_start.tv_sec) + (total_end.tv_usec - total_start.tv_usec) / 1000000.0; // 转换为秒
    printf("检测识别总耗时: %f ms\n", det_time*1000);
    // --------------------- 保存结果 ---------------------
    // write_image("result.png", &src_image);

EXIT:
    // --------------------- 资源释放 ---------------------
    release_yolov6_model(&yolo_ctx);
    release_mobilenet_model(&mobilenet_ctx);
    release_image(&src_image); // 统一释放函数处理DMA/普通内存
    return 0;
}
