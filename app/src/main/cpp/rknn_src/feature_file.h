#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "yolov6.h"
#include <math.h>
#include <pthread.h>
#define FEATURE_DIM 512       //根据实际情况调整特征维度
#define MAX_CATEGORY_LEN 32  // 假设中文类别最大长度（UTF-8编码）

extern pthread_mutex_t db_mutex; //声明全局互斥锁


typedef struct {
    char category[MAX_CATEGORY_LEN];
    float feature[FEATURE_DIM];
} FeatureEntry;

int save_feature_to_db(const char* category, float* feature, const char* db_path);
FeatureEntry* load_feature_library_from_db(const char* db_path, int* entry_count);

int save_feature_to_file(const char* category, float* feature, const char* filename);
FeatureEntry* load_feature_library(const char* filename, int* entry_count);
float calculate_distance(float* feature1, float* feature2);
int find_and_store_nearest(float* query_feature, FeatureEntry* library, int entry_count,mobilenet_result* out_result,int topk);
int calculate_similarity(const float* query_feature,const FeatureEntry* library,int num_entries,mobilenet_result* top_results,int topk);
int query_categories_from_db(const char* db_path, char*** categories,int* category_count);
void free_categories(char** categories, int count);
FeatureEntry* load_feature_library_from_db_whitelist(
    const char* db_path,
    int* entry_count,
    const char** whitelist,
    int whitelist_size
);
FeatureEntry* load_first_feature_per_whitelist_class(
    const char* db_path,
    int* entry_count,
    const char** whitelist_classes,
    int whitelist_size);
// 批量删除函数
int delete_categories_from_db(const char* db_path, const char** categories, int count);
// 清空数据库函数
int delete_all_categories_from_db(const char* db_path);