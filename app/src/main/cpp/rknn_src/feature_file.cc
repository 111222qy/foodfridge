#include "feature_file.h"

#include <stdio.h>
#include <stdlib.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "Float16.h"
#include "rknn_matmul_api.h"
// #include "matmul_utils.h"
#include <queue>

#include <sqlite3.h>
#include <android/log.h>

#include <unistd.h>

pthread_mutex_t db_mutex = PTHREAD_MUTEX_INITIALIZER;

#define LOG_TAG "YOLOv6_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

void normalize_feature(float* feature) {
    float norm = 0.0f;
    for (int i = 0; i < FEATURE_DIM; ++i) {
        norm += feature[i] * feature[i];
    }
    norm = sqrtf(norm);
    
    if (norm > 0) {
        for (int i = 0; i < FEATURE_DIM; ++i) {
            feature[i] /= norm;
        }
    }
}
int save_feature_to_file(const char* category, float* feature, const char* filename) {
    FILE* fp = fopen(filename, "a");  // 追加写入模式
    LOGD("保存特征到文件\n");
    if (fp == NULL) {
        fprintf(stderr, "Error opening file: %s\n", filename);
        LOGE("Error opening file\n");
        return -1;
    }
    
    // 写入中文类别（带长度检查）
    if(strlen(category) >= MAX_CATEGORY_LEN) {
        fprintf(stderr, "Category name too long: %s\n", category);
        LOGE("Category name too long\n");
        fclose(fp);
        return -1;
    }
    LOGD("写入特征\n");
    fprintf(fp, "%s", category);
    // 写入512维特征
    for (int i = 0; i < 512; ++i) {
        normalize_feature(&feature[i]);
        // LOGD("写入特征: %.6f\n", feature[i]);
        fprintf(fp, " %.6f", feature[i]);  // 保留6位小数
    }
    fprintf(fp, "\n");
    LOGD("写入特征完成\n");
    fclose(fp);
    LOGD("关闭文件\n");
    return 0;
}

// 读取特征库
FeatureEntry* load_feature_library(const char* filename, int* entry_count) {
    FILE* fp = fopen(filename, "r");
    if (fp == NULL) {
        fprintf(stderr, "错误：无法打开文件 %s\n", filename);
        LOGE("错误：无法打开文件 %s\n", filename);
        return NULL;
    }

    FeatureEntry* library = NULL;
    char line[8192];  // 增大缓冲区
    *entry_count = 0;
    int line_num = 0;

    while (fgets(line, sizeof(line), fp)) {
        line_num++;
        
        // 删除换行符
        line[strcspn(line, "\r\n")] = '\0';
        
        // 跳过空行
        if (strlen(line) == 0) {
            fprintf(stderr, "警告: 第%d行是空行，已跳过\n", line_num);
            continue;
        }

        // 扩展内存空间
        FeatureEntry* temp = (FeatureEntry*)realloc(library, (*entry_count + 1) * sizeof(FeatureEntry));
        if (temp == NULL) {
            fprintf(stderr, "错误: 第%d行内存分配失败\n", line_num);
            free(library);
            break;
        }
        library = temp;

        // 解析行数据
        char* token = strtok(line, " ");
        if (token == NULL) {
            fprintf(stderr, "错误: 第%d行无法解析类别\n", line_num);
            continue;
        }

        // 读取中文类别
        if (strlen(token) >= MAX_CATEGORY_LEN) {
            fprintf(stderr, "错误: 第%d行类别名称过长(最大%d字符)\n", line_num, MAX_CATEGORY_LEN-1);
            continue;
        }
        strncpy(library[*entry_count].category, token, MAX_CATEGORY_LEN-1);
        library[*entry_count].category[MAX_CATEGORY_LEN-1] = '\0';

        // 读取特征
        int idx = 0;
        while ((token = strtok(NULL, " ")) != NULL) {
            if (idx >= FEATURE_DIM) {
                fprintf(stderr, "警告: 第%d行特征数量超过%d维\n", line_num, FEATURE_DIM);
                break;
            }
            
            char* endptr;
            library[*entry_count].feature[idx] = strtof(token, &endptr);
            
            if (*endptr != '\0') {
                fprintf(stderr, "错误: 第%d行第%d个特征无效: '%s'\n", line_num, idx+1, token);
                idx = -1; // 标记错误
                break;
            }
            idx++;
        }

        // 检查特征数量
        if (idx == -1) continue; // 已有错误
        if (idx != FEATURE_DIM) {
            fprintf(stderr, "错误: 第%d行特征数量不足，应为%d，实际%d\n", 
                   line_num, FEATURE_DIM, idx);
            continue;
        }

        (*entry_count)++;
    }

    fclose(fp);
    printf("成功加载%d条有效记录\n", *entry_count);
    return library;
}

// 计算欧氏距离（保持不变）
float calculate_distance(float* feature1, float* feature2) {
    float sum = 0.0f;
    for (int i = 0; i < FEATURE_DIM; ++i) {
        float diff = feature1[i] - feature2[i];
        sum += diff * diff;
    }
    return sqrtf(sum);
}

// 计算余弦相似度
// float cosine(float* feature1,float* feature2){
//     float sum = 0.0f;
//     for (int i = 0; i < FEATURE_DIM; ++i){
//         float diff = feature1[i] * feature2[i];
//         sum += diff;
//     }
//     return sum;
// }
float cosine_similarity(float* feature1, float* feature2) {
    float dot = 0.0f;
    float norm1 = 0.0f, norm2 = 0.0f;
    
    for (int i = 0; i < FEATURE_DIM; ++i) {
        dot += feature1[i] * feature2[i];
        norm1 += feature1[i] * feature1[i];
        norm2 += feature2[i] * feature2[i];
    }
    
    norm1 = sqrtf(norm1);
    norm2 = sqrtf(norm2);
    
    if (norm1 == 0 || norm2 == 0) return 0;
    return dot / (norm1 * norm2);
}

// 在查找最近邻时填充结构体
// 添加结构体用于记录类别信息
typedef struct {
    char category[MAX_CATEGORY_LEN];
    float max_score;
} CategoryScore;

// 比较函数用于排序
int compare_category_scores(const void* a, const void* b) {
    const CategoryScore* cs_a = (const CategoryScore*)a;
    const CategoryScore* cs_b = (const CategoryScore*)b;
    return (cs_b->max_score > cs_a->max_score) ? 1 : -1;
}

int find_and_store_nearest(float* query_feature, 
                           FeatureEntry* library, 
                           int entry_count,
                           mobilenet_result* out_results,
                           int topk)
{
    // 参数校验
    if (entry_count == 0 || library == NULL || out_results == NULL || topk <= 0) {
        LOGE("数据库为空或参数错误");
        return -1;
    }

    // 记录每个类别的最高分
    CategoryScore* category_scores = NULL;
    int category_count = 0;
    int capacity = 10;

    // 分配初始内存
    category_scores = (CategoryScore*)malloc(capacity * sizeof(CategoryScore));
    if (!category_scores) {
        LOGE("Memory allocation failed");
        return -1;
    }

    // 遍历所有特征条目
    for (int i = 0; i < entry_count; ++i) {
        float similarity = cosine_similarity(query_feature, library[i].feature);
        int found = 0;

        // 查找是否已有该类别
        for (int j = 0; j < category_count; ++j) {
            if (strcmp(category_scores[j].category, library[i].category) == 0) {
                found = 1;
                if (similarity > category_scores[j].max_score) {
                    category_scores[j].max_score = similarity;
                }
                break;
            }
        }

        // 新类别处理
        if (!found) {
            // 扩展内存
            if (category_count >= capacity) {
                capacity *= 2;
                CategoryScore* temp = (CategoryScore*)realloc(category_scores, capacity * sizeof(CategoryScore));
                if (!temp) {
                    LOGE("内存扩展失败");
                    free(category_scores);
                    return -1;
                }
                category_scores = temp;
            }

            // 添加新类别
            strncpy(category_scores[category_count].category, library[i].category, MAX_CATEGORY_LEN-1);
            category_scores[category_count].category[MAX_CATEGORY_LEN-1] = '\0';
            category_scores[category_count].max_score = similarity;
            category_count++;
        }
    }

    // 排序（降序）
    qsort(category_scores, category_count, sizeof(CategoryScore), compare_category_scores);

    // 确定实际返回数量
    int valid_count = (category_count < topk) ? category_count : topk;
    if (valid_count < topk) {
        LOGD("Warning: Only %d unique categories found (requested %d)", valid_count, topk);
    }

    // 填充有效结果
    for (int i = 0; i < valid_count; ++i) {
        if (category_scores[i].max_score < 0.6f) {
            strncpy(out_results[i].cls, "未知类别", MAX_CATEGORY_LEN-1);
        } else {
            strncpy(out_results[i].cls, 
                   category_scores[i].category,
                   MAX_CATEGORY_LEN-1);
        }
        out_results[i].cls[MAX_CATEGORY_LEN-1] = '\0';
        out_results[i].score = category_scores[i].max_score;
        // LOGD("Top%d: %s (%.4f)", i+1, out_results[i].cls, out_results[i].score);
    }

    // 填充剩余位置
    for (int i = valid_count; i < topk; ++i) {
        strncpy(out_results[i].cls, "无有效结果", MAX_CATEGORY_LEN-1);
        out_results[i].cls[MAX_CATEGORY_LEN-1] = '\0';
        out_results[i].score = 0.0f;
    }

    // 释放内存
    free(category_scores);
    return 0;
}

// // 修改后的函数调用示例：
// FeatureEntry* library = load_feature_library("features.db", &entry_count);
// const int TOPK = 5;
// mobilenet_result results[TOPK];
// find_and_store_nearest(query_feature, library, entry_count, results, TOPK);

// // 输出结果
// for(int i=0; i<TOPK; ++i){
//     printf("Top%d: %s (%.4f)\n", i+1, results[i].cls, results[i].score);
// }

// 矩阵相似度计算接口
int calculate_similarity(const float* query_feature,   // 查询特征 [1, FEATURE_DIM]
                        const FeatureEntry* library,  // 特征库 [NUM_ENTRIES]
                        int num_entries,              // 库条目数
                        mobilenet_result* top_results,
                        int topk)// 
{
    int ret = 0;
    rknn_matmul_ctx ctx;
    rknn_matmul_info info;
    rknn_matmul_io_attr io_attr;
    CategoryScore* category_scores = NULL;
    int category_count = 0;
    int capacity = 10;
    int valid_count;

    memset(&io_attr, 0, sizeof(rknn_matmul_io_attr));

    // 参数校验
    if (!query_feature || !library || num_entries <=0 || !top_results) {
        fprintf(stderr, "Invalid input parameters\n");
        return -1;
    }

    // [1] 数据对齐 --------------------------------------------------
    const int aligned_num_entries = ((num_entries + 15) / 16) * 16;
    FeatureEntry* aligned_library = (FeatureEntry*)malloc(aligned_num_entries * sizeof(FeatureEntry));
    memcpy(aligned_library, library, num_entries * sizeof(FeatureEntry));
    
    // 填充无效数据（可根据需要改用其他值）
    for (int i = num_entries; i < aligned_num_entries; ++i) {
        memset(&aligned_library[i], 0, sizeof(FeatureEntry));
        strcpy(aligned_library[i].category, "INVALID");
    }
    // 1. 初始化矩阵计算上下文
    memset(&info, 0, sizeof(rknn_matmul_info));
    info.M = 1;                 // 查询特征数量
    info.K = FEATURE_DIM;       // 特征维度
    info.N = aligned_num_entries;       // 使用对齐后的N
    info.type = RKNN_FLOAT16_MM_FLOAT16_TO_FLOAT32;
    info.B_layout = RKNN_MM_LAYOUT_TP_NORM;          // B矩阵自动转置（库特征需要转置）
    // printf("MatMul Params: M=%d, K=%d, N=%d, type=%d, B_layout=%d\n", 
    //     info.M, info.K, info.N, info.type, info.B_layout);

    if ((ret = rknn_matmul_create(&ctx, &info, &io_attr)) != 0) {
        fprintf(stderr, "rknn_matmul_create failed: %d\n", ret);
        return ret;
    }
    // printf("Memory Info: A.size=%zu, B.size=%zu, C.size=%zu\n",
    //     io_attr.A.size, io_attr.B.size, io_attr.C.size);
    // 2. 分配内存
    rknn_tensor_mem* A = rknn_create_mem(ctx, io_attr.A.size);  // 查询特征
    rknn_tensor_mem* B = rknn_create_mem(ctx, io_attr.B.size);  // 特征库
    rknn_tensor_mem* C = rknn_create_mem(ctx, io_attr.C.size);  // 相似度结果
    // 6. 处理输出结果
    float* similarities = (float*)C->virt_addr;
    std::priority_queue<std::pair<float, int>> pq;

    if (!A || !B || !C) {
        fprintf(stderr, "Memory allocation failed\n");
        ret = -1;
        // goto CLEANUP;
    }

    // 3. 准备输入数据
    // 转换查询特征到FP16（自动归一化）
    float normalized_query[FEATURE_DIM];
    memcpy(normalized_query, query_feature, FEATURE_DIM * sizeof(float));
    normalize_feature(normalized_query);
    rknpu2::float16 *a_fp16 = (rknpu2::float16*)A->virt_addr;
    for (int i = 0; i < FEATURE_DIM; ++i) {
        a_fp16[i] = static_cast<rknpu2::float16>(normalized_query[i]);
        // LOGD("归一化后查询特征示例：[%d]=%.6f", i,normalized_query[i]);
        // printf("归一化后查询特征示例：[%d]=%.6f\n", i,normalized_query[i]);

        // float converted = static_cast<float>(a_fp16[i]);
        // printf("Original: %.6f -> FP16: 0x%04X -> Converted: %.6f\n", 
        //     query_feature[i], 
        //      *reinterpret_cast<uint16_t*>(&a_fp16),
        //     converted);
        // printf("query_feature[%d]: %.6f\n",i, query_feature[i]);
        // printf("a_fp16[%d]: %.6f\n", i,a_fp16[i]);
    }

    // 转换特征库到FP16并转置 [FEATURE_DIM, num_entries]
    rknpu2::float16* b_fp16 = (rknpu2::float16*)B->virt_addr;
    for (int entry = 0; entry < num_entries; ++entry) {
        for (int dim = 0; dim < FEATURE_DIM; ++dim) {
            b_fp16[entry * FEATURE_DIM + dim] = 
            static_cast<rknpu2::float16>(aligned_library[entry].feature[dim]);
        }
    }
    // 4. 设置IO内存
    if ((ret = rknn_matmul_set_io_mem(ctx, A, &io_attr.A)) ||
        (ret = rknn_matmul_set_io_mem(ctx, B, &io_attr.B)) ||
        (ret = rknn_matmul_set_io_mem(ctx, C, &io_attr.C))) 
    {
        fprintf(stderr, "Set IO memory failed: %d\n", ret);
        goto CLEANUP;
    }

    // 5. 执行计算（1 x FEATURE_DIM）*（FEATURE_DIM x num_entries）->（1 x num_entries）
    if ((ret = rknn_matmul_run(ctx)) != 0) {
        fprintf(stderr, "Matrix computation failed: %d\n", ret);
        goto CLEANUP;
    }


    // 使用优先队列获取Top5结果


    // 分配初始内存
    category_scores = (CategoryScore*)malloc(capacity * sizeof(CategoryScore));
    if (!category_scores) {
        LOGE("Memory allocation failed");
        return -1;
    }

    // 遍历所有相似度结果
    for (int i = 0; i < num_entries; ++i) {  // 确保不处理填充数据
        const char* current_category = library[i].category;
        float current_score = similarities[i];
        int found = 0;

        // 修改3：添加类别存在性检查
        for (int j = 0; j < category_count; ++j) {
            if (strcmp(category_scores[j].category, current_category) == 0) {
                found = 1;
                if (current_score > category_scores[j].max_score) {
                    category_scores[j].max_score = current_score;
                }
                break;
            }
        }

        // 修改4：动态扩展内存
        if (!found) {
            if (category_count >= capacity) {
                capacity *= 2;
                CategoryScore* temp = (CategoryScore*)realloc(
                    category_scores, capacity * sizeof(CategoryScore));
                if (!temp) {
                    LOGE("Memory realloc failed");
                    free(category_scores);
                    return -1;
                }
                category_scores = temp;
            }
            strncpy(category_scores[category_count].category, 
                   current_category, MAX_CATEGORY_LEN-1);
            category_scores[category_count].category[MAX_CATEGORY_LEN-1] = '\0';
            category_scores[category_count].max_score = current_score;
            category_count++;
        }
    }

    // 修改5：统一结果填充逻辑
    qsort(category_scores, category_count, sizeof(CategoryScore), compare_category_scores);
    
    valid_count = (category_count < topk) ? category_count : topk;
    if (valid_count < topk) {
        LOGD("Warning: Only %d unique categories found (requested %d)", valid_count, topk);
    }

    for (int i = 0; i < valid_count; ++i) {
        if (category_scores[i].max_score < 0.6f) {  // 保持阈值一致
            strncpy(top_results[i].cls, "未知类别", MAX_CATEGORY_LEN-1);
        } else {
            strncpy(top_results[i].cls, 
                   category_scores[i].category,
                   MAX_CATEGORY_LEN-1);
        }
        top_results[i].cls[MAX_CATEGORY_LEN-1] = '\0';
        top_results[i].score = category_scores[i].max_score;
    }

    // 修改6：统一填充无效结果
    for (int i = valid_count; i < topk; ++i) {
        strncpy(top_results[i].cls, "无有效结果", MAX_CATEGORY_LEN-1);
        top_results[i].cls[MAX_CATEGORY_LEN-1] = '\0';
        top_results[i].score = 0.0f;
    }

    // 修改7：添加调试日志
    // LOGD("有效类别数: %d/%d, 最高相似度: %.4f", 
    //      valid_count, category_count, 
    //      (category_count>0) ? category_scores[0].max_score : 0);


    // 释放内存
    free(category_scores);
    free(aligned_library);

CLEANUP:
    // 7. 资源清理
    if (A) rknn_destroy_mem(ctx, A);
    if (B) rknn_destroy_mem(ctx, B);
    if (C) rknn_destroy_mem(ctx, C);
    rknn_matmul_destroy(ctx);
    
    return ret;
}


// 修改后的数据库操作函数
// ==================== 保存特征到数据库 ====================
int save_feature_to_db(const char* category, float* feature, const char* db_path) {
    sqlite3* db;
    char* err_msg = 0;
    int rc;

    // 打开数据库连接
    // rc = sqlite3_open(db_path, &db);
    // if (rc != SQLITE_OK) {
    //     fprintf(stderr, "无法打开数据库: %s\n", sqlite3_errmsg(db));
    //     return -1;
    // }

    // 修改打开数据库的调用方式
    rc = sqlite3_open_v2(db_path, &db, 
        SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX,
        NULL);


    // 创建表（如果不存在）
    const char* create_table_sql = 
        "CREATE TABLE IF NOT EXISTS features ("
        "id INTEGER PRIMARY KEY AUTOINCREMENT,"
        "category TEXT NOT NULL,"
        "feature BLOB NOT NULL);";
    
    rc = sqlite3_exec(db, create_table_sql, 0, 0, &err_msg);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "创建表失败: %s\n", err_msg);
        sqlite3_free(err_msg);
        sqlite3_close(db);
        return -1;
    }

    // 准备插入语句
    sqlite3_stmt* stmt;
    const char* insert_sql = "INSERT INTO features (category, feature) VALUES (?, ?);";
    
    rc = sqlite3_prepare_v2(db, insert_sql, -1, &stmt, 0);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "准备语句失败: %s\n", sqlite3_errmsg(db));
        sqlite3_close(db);
        
        return -1;
    }

    // 绑定参数
    sqlite3_bind_text(stmt, 1, category, -1, SQLITE_STATIC);
    sqlite3_bind_blob(stmt, 2, feature, FEATURE_DIM * sizeof(float), SQLITE_STATIC);

    // 执行插入
    rc = sqlite3_step(stmt);
    if (rc != SQLITE_DONE) {
        fprintf(stderr, "执行插入失败: %s\n", sqlite3_errmsg(db));
    }

    // 清理资源
    sqlite3_finalize(stmt);
    sqlite3_close(db);
    return 0;
}

// 从数据库删除类别
// feature_file.cc
int delete_categories_from_db(const char* db_path, const char** categories, int count) {
    pthread_mutex_lock(&db_mutex);
    sqlite3* db = nullptr;
    sqlite3_stmt* stmt = nullptr;
    int total_deleted = 0;
    int rc;

    // 参数校验
    if (!db_path || count <= 0 || !categories) {
        LOGE("Invalid parameters");
        pthread_mutex_unlock(&db_mutex);
        return -1;
    }

    // 打开数据库
    if ((rc = sqlite3_open_v2(db_path, &db, 
        SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, NULL)) != SQLITE_OK) 
    {
        LOGE("Database open failed[%d]: %s", rc, sqlite3_errmsg(db));
        pthread_mutex_unlock(&db_mutex);
        return -2;
    }

    // 构建SQL语句
    char sql[512];
    snprintf(sql, sizeof(sql), "DELETE FROM features WHERE category IN (");
    char* ptr = sql + strlen(sql);
    for (int i = 0; i < count; ++i) {
        if (i == count-1) {
            snprintf(ptr, sizeof(sql)-(ptr-sql), "?%s", ")"); 
        } else {
            snprintf(ptr, sizeof(sql)-(ptr-sql), "?%s", ","); 
        }
        ptr += 2;
    }

    // 准备语句
    if ((rc = sqlite3_prepare_v2(db, sql, -1, &stmt, 0)) != SQLITE_OK) {
        LOGE("Prepare failed[%d]: %s", rc, sqlite3_errmsg(db));
        sqlite3_close(db);
        pthread_mutex_unlock(&db_mutex);
        return -3;
    }

    // 绑定参数
    for (int i = 0; i < count; ++i) {
        sqlite3_bind_text(stmt, i+1, categories[i], -1, SQLITE_STATIC);
    }

    // 执行删除
    rc = sqlite3_step(stmt);
    if (rc == SQLITE_DONE) {
        total_deleted = sqlite3_changes(db);
        LOGD("Deleted %d records", total_deleted);
    } else {
        LOGE("Delete failed[%d]: %s", rc, sqlite3_errmsg(db));
    }

    // 清理资源
    sqlite3_finalize(stmt);
    sqlite3_close(db);
    pthread_mutex_unlock(&db_mutex);
    return total_deleted;
}

int delete_all_categories_from_db(const char* db_path) {
    pthread_mutex_lock(&db_mutex);
    sqlite3* db = nullptr;
    int rc;

    // 打开数据库
    if ((rc = sqlite3_open_v2(db_path, &db, 
        SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, NULL)) != SQLITE_OK) 
    {
        LOGE("Database open failed[%d]: %s", rc, sqlite3_errmsg(db));
        pthread_mutex_unlock(&db_mutex);
        return -1;
    }

    // 执行全表删除
    const char* delete_sql = "DELETE FROM features;";
    char* err_msg = nullptr;
    int changes = 0;
    
    if ((rc = sqlite3_exec(db, delete_sql, 0, 0, &err_msg)) == SQLITE_OK) {
        changes = sqlite3_changes(db);
        LOGD("Deleted all records: %d rows", changes);
        
        // 重置自增ID
        const char* reset_sql = "DELETE FROM sqlite_sequence WHERE name='features';";
        sqlite3_exec(db, reset_sql, 0, 0, 0);
    } else {
        LOGE("Delete all failed[%d]: %s", rc, err_msg);
        sqlite3_free(err_msg);
    }

    sqlite3_close(db);
    pthread_mutex_unlock(&db_mutex);
    return changes;
}

// 查询类别
int query_categories_from_db(
    const char* db_path, 
    char*** categories,  // 输出参数：类别字符串数组 
    int* category_count) // 输出参数：类别数量
{
    pthread_mutex_lock(&db_mutex);
    sqlite3* db;
    sqlite3_stmt* stmt;
    int rc;

    *category_count = 0;
    *categories = NULL;

    // 打开数据库
    rc = sqlite3_open_v2(db_path, &db, 
        SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX,
        NULL);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "Database open failed: %s\n", sqlite3_errmsg(db));
        pthread_mutex_unlock(&db_mutex);
        return -1;
    }

    // 准备查询语句（DISTINCT去重）
    const char* select_sql = "SELECT DISTINCT category FROM features;";
    rc = sqlite3_prepare_v2(db, select_sql, -1, &stmt, 0);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "Prepare statement failed: %s\n", sqlite3_errmsg(db));
        sqlite3_close(db);
        pthread_mutex_unlock(&db_mutex);
        return -2;
    }

    // 遍历结果集
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        const char* category = (const char*)sqlite3_column_text(stmt, 0);
        if (!category) continue;

        // 扩展数组
        char** temp = (char**)realloc(*categories, (*category_count + 1) * sizeof(char*));
        if (!temp) {
            fprintf(stderr, "Memory allocation failed\n");
            break;
        }
        *categories = temp;

        // 复制字符串
        (*categories)[*category_count] = strdup(category);
        if (!(*categories)[*category_count]) {
            fprintf(stderr, "String duplication failed\n");
            break;
        }

        (*category_count)++;
    }

    // 清理资源
    sqlite3_finalize(stmt);
    sqlite3_close(db);
    pthread_mutex_unlock(&db_mutex);

    return 0;
}

// 释放查询结果内存的函数
void free_categories(char** categories, int count) {
    if (!categories) return;
    
    for (int i = 0; i < count; ++i) {
        free(categories[i]);
    }
    free(categories);
}

// ==================== 从数据库加载特征库 ====================
FeatureEntry* load_feature_library_from_db(const char* db_path, int* entry_count) {
    pthread_mutex_lock(&db_mutex);
    sqlite3* db;
    sqlite3_stmt* stmt;
    int rc;

    *entry_count = 0;
    
    // 打开数据库
    rc = sqlite3_open(db_path, &db);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "无法打开数据库: %s\n", sqlite3_errmsg(db));
        pthread_mutex_unlock(&db_mutex);

        return NULL;
    }

    // 准备查询语句
    const char* select_sql = "SELECT category, feature FROM features;";
    rc = sqlite3_prepare_v2(db, select_sql, -1, &stmt, 0);
    if (rc != SQLITE_OK) {
        fprintf(stderr, "准备查询失败: %s\n", sqlite3_errmsg(db));
        sqlite3_close(db);
        pthread_mutex_unlock(&db_mutex);
        return NULL;
    }

    // 分配内存
    FeatureEntry* library = NULL;
    int capacity = 100;  // 初始容量
    int count = 0;
    
    library = (FeatureEntry*)malloc(capacity * sizeof(FeatureEntry));
    if (!library) {
        fprintf(stderr, "内存分配失败\n");
        sqlite3_finalize(stmt);
        sqlite3_close(db);
        pthread_mutex_unlock(&db_mutex);
        return NULL;
    }

    // 遍历结果集
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        // 扩展容量
        if (count >= capacity) {
            capacity *= 2;
            FeatureEntry* temp = (FeatureEntry*)realloc(library, capacity * sizeof(FeatureEntry));
            if (!temp) {
                fprintf(stderr, "内存扩展失败\n");
                free(library);
                library = NULL;
                break;
            }
            library = temp;
        }

        // 读取类别
        const char* category = (const char*)sqlite3_column_text(stmt, 0);
        strncpy(library[count].category, category, MAX_CATEGORY_LEN-1);
        library[count].category[MAX_CATEGORY_LEN-1] = '\0';

        // 读取特征
        const void* blob = sqlite3_column_blob(stmt, 1);
        int blob_size = sqlite3_column_bytes(stmt, 1);

        // 新增特征验证代码 --------------------------------------------------
        // if (blob_size == FEATURE_DIM * sizeof(float)) {
        //     float* feature = (float*)blob;
            
        //     // 打印前5个和后5个特征值
        //     LOGD("===== 加载特征验证 =====");
        //     printf("===== 加载特征验证 =====\n");
        //     LOGD("类别：%s", category);
        //     printf("类别：%s\n", category);
        //     for(int i=0; i<512; ++i){
        //         LOGD("特征[%03d]: %.6f", i, feature[i]);
        //         printf("特征[%03d]: %.6f\n", i, feature[i]);
        //     }
        // }

        if (blob_size != FEATURE_DIM * sizeof(float)) {
            fprintf(stderr, "特征维度不匹配，跳过该记录\n");
            continue;
        }

        memcpy(library[count].feature, blob, blob_size);
        count++;
    }

    // 清理资源
    sqlite3_finalize(stmt);
    sqlite3_close(db);

    *entry_count = count;
    printf("成功加载 %d 条特征记录\n", count);
    pthread_mutex_unlock(&db_mutex);
    return library;
}

// ==================== 使用示例修改 ====================
// 原代码修改（mobilenet.cc）：
// 保存时：
// save_feature_to_file(classes, ...) 改为：
// save_feature_to_db(classes, (float*)outputs[0].buf, "features.db");

// 加载时：
// FeatureEntry* library = load_feature_library("features.txt", &entry_count); 改为：
// FeatureEntry* library = load_feature_library_from_db("features.db", &entry_count);

// 函数实现
FeatureEntry* load_feature_library_from_db_whitelist(
    const char* db_path,
    int* entry_count,
    const char** whitelist,
    int whitelist_size)
{
    pthread_mutex_lock(&db_mutex);
    // 所有变量声明提前
    sqlite3* db = NULL;
    sqlite3_stmt* stmt = NULL;
    char* sql = NULL;
    FeatureEntry* library = NULL;
    int capacity = 100;
    int count = 0;
    size_t sql_len = 0;
    char* ptr = NULL;
    int remain = 0;
    int written = 0;
    int rc = SQLITE_OK;

    *entry_count = 0;

    // 参数检查（调整到变量声明之后）
    if (whitelist_size <= 0 || whitelist == NULL) {
        LOGE("Invalid whitelist: size=%d", whitelist_size);
        goto CLEANUP;
    }

    // 打开数据库
    if ((rc = sqlite3_open_v2(db_path, &db, SQLITE_OPEN_READONLY, NULL)) != SQLITE_OK) {
        LOGE("Database open failed[%d]: %s", rc, sqlite3_errmsg(db));
        goto CLEANUP;
    }

    // 构建SQL语句
    sql_len = 64 + (whitelist_size * 4);
    sql = (char*)malloc(sql_len);
    if (!sql) {
        LOGE("Memory allocation failed for SQL");
        goto CLEANUP;
    }

    ptr = sql;
    remain = sql_len;
    
    // 构建基础SQL
    written = snprintf(ptr, remain, 
        "SELECT category, feature FROM features WHERE category IN (");
    if (written < 0 || written >= remain) goto SQL_TOO_LONG;
    ptr += written;
    remain -= written;

    // 添加占位符
    for (int i = 0; i < whitelist_size; ++i) {
        const char* fmt = (i == whitelist_size-1) ? "?)" : "?,";
        written = snprintf(ptr, remain, "%s", fmt);
        if (written < 0 || written >= remain) goto SQL_TOO_LONG;
        ptr += written;
        remain -= written;
    }

    // 准备语句
    if ((rc = sqlite3_prepare_v2(db, sql, -1, &stmt, 0)) != SQLITE_OK) {
        LOGE("Prepare failed[%d]: %s\nSQL: %s", rc, sqlite3_errmsg(db), sql);
        goto CLEANUP;
    }

    // 绑定参数
    for (int i = 0; i < whitelist_size; ++i) {
        if ((rc = sqlite3_bind_text(stmt, i+1, whitelist[i], -1, SQLITE_STATIC)) != SQLITE_OK) {
            LOGE("Bind param %d failed[%d]: %s\n", i, rc, sqlite3_errmsg(db));
            goto CLEANUP;
        }
    }

    // 初始化内存
    library = (FeatureEntry*)malloc(capacity * sizeof(FeatureEntry));
    if (!library) {
        LOGE("Initial memory allocation failed\n");
        goto CLEANUP;
    }

    // 处理结果集
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        // 扩展容量
        if (count >= capacity) {
            capacity *= 2;
            FeatureEntry* temp = (FeatureEntry*)realloc(library, capacity * sizeof(FeatureEntry));
            if (!temp) {
                LOGE("Memory realloc failed at count=%d\n", count);
                break;
            }
            library = temp;
        }

        // 读取类别
        const char* category = (const char*)sqlite3_column_text(stmt, 0);
        strncpy(library[count].category, category, MAX_CATEGORY_LEN-1);
        library[count].category[MAX_CATEGORY_LEN-1] = '\0';

        // 读取特征
        const void* blob = sqlite3_column_blob(stmt, 1);
        int blob_size = sqlite3_column_bytes(stmt, 1);
        
        if (blob_size != FEATURE_DIM * sizeof(float)) {
            LOGE("Skip invalid feature size: %d\n", blob_size);
            continue;
        }

        memcpy(library[count].feature, blob, blob_size);
        count++;
    }

    // 设置返回参数
    *entry_count = count;
    LOGD("Loaded %d features from whitelist\n", count);
    printf("Loaded %d features from whitelist\n", count);

SQL_TOO_LONG:
    if (remain <= 0) {
        LOGE("SQL buffer overflow");
    }

CLEANUP:
    // 资源清理
    if (stmt) sqlite3_finalize(stmt);
    if (db) sqlite3_close(db);
    if (sql) free(sql);
    
    pthread_mutex_unlock(&db_mutex);
    
    // 失败时释放内存
    if (*entry_count == 0 && library) {
        free(library);
        library = NULL;
    }
    
    return library;
}

// // 白名单定义
// const char* whitelist[] = {"宫保鸡丁", "鱼香肉丝", "麻婆豆腐"};
// int whitelist_size = 3;

// // 加载数据
// int entry_count;
// FeatureEntry* library = load_feature_library_from_db_whitelist(
//     db_path, 
//     &entry_count,
//     whitelist,
//     sizeof(whitelist)/sizeof(whitelist[0])
// );

FeatureEntry* load_first_feature_per_whitelist_class(
    const char* db_path,
    int* entry_count,
    const char** whitelist_classes,
    int whitelist_size) 
{
    pthread_mutex_lock(&db_mutex);
    sqlite3* db = NULL;
    sqlite3_stmt* stmt = NULL;
    FeatureEntry* library = NULL;
    int capacity = 10;
    int count = 0;
    int rc = SQLITE_OK;
    // 构建优化SQL（使用GROUP BY获取每个白名单类别的首条记录）
    const char* sql_template = 
        "SELECT category, feature FROM features "
        "WHERE category IN (%s) "
        "GROUP BY category ORDER BY MIN(id);";
    char placeholders[256] = {0};

    // 参数校验
    if (whitelist_size <= 0 || whitelist_classes == NULL) {
        LOGE("无效参数：whitelist_size=%d", whitelist_size);
        goto CLEANUP;
    }

    // 打开数据库
    if ((rc = sqlite3_open_v2(db_path, &db, SQLITE_OPEN_READONLY, NULL)) != SQLITE_OK) {
        LOGE("数据库打开失败[%d]: %s", rc, sqlite3_errmsg(db));
        goto CLEANUP;
    }

    // 构建占位符字符串
    for (int i = 0; i < whitelist_size; ++i) {
        strcat(placeholders, "?");
        if (i != whitelist_size-1) strcat(placeholders, ",");
    }

    // 准备完整SQL
    char sql[512];
    snprintf(sql, sizeof(sql), sql_template, placeholders);

    // 准备语句
    if ((rc = sqlite3_prepare_v2(db, sql, -1, &stmt, 0)) != SQLITE_OK) {
        LOGE("SQL准备失败[%d]: %s\nSQL: %s", rc, sqlite3_errmsg(db), sql);
        goto CLEANUP;
    }

    // 绑定白名单参数
    for (int i = 0; i < whitelist_size; ++i) {
        if ((rc = sqlite3_bind_text(stmt, i+1, whitelist_classes[i], -1, SQLITE_STATIC)) != SQLITE_OK) {
            LOGE("参数绑定失败[%d]: %s", rc, sqlite3_errmsg(db));
            goto CLEANUP;
        }
    }

    // 初始化内存
    library = (FeatureEntry*)malloc(capacity * sizeof(FeatureEntry));
    if (!library) {
        LOGE("初始内存分配失败");
        goto CLEANUP;
    }

    // 处理结果集
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        // 扩展容量
        if (count >= capacity) {
            capacity *= 2;
            FeatureEntry* temp = (FeatureEntry*)realloc(library, capacity * sizeof(FeatureEntry));
            if (!temp) {
                LOGE("内存扩展失败 count=%d", count);
                break;
            }
            library = temp;
        }

        // 读取类别
        const char* category = (const char*)sqlite3_column_text(stmt, 0);
        strncpy(library[count].category, category, MAX_CATEGORY_LEN-1);
        library[count].category[MAX_CATEGORY_LEN-1] = '\0';

        // 读取特征
        const void* blob = sqlite3_column_blob(stmt, 1);
        int blob_size = sqlite3_column_bytes(stmt, 1);
        
        if (blob_size != FEATURE_DIM * sizeof(float)) {
            LOGE("跳过无效特征尺寸: %d", blob_size);
            continue;
        }

        memcpy(library[count].feature, blob, blob_size);
        count++;
    }

    // 设置返回参数
    *entry_count = count;
    LOGD("成功加载白名单中 %d 个类别的首条特征", count);

CLEANUP:
    // 资源清理
    if (stmt) sqlite3_finalize(stmt);
    if (db) sqlite3_close(db);
    pthread_mutex_unlock(&db_mutex);
    
    // 失败时释放内存
    if (*entry_count == 0 && library) {
        free(library);
        library = NULL;
    }
    
    return library;
}