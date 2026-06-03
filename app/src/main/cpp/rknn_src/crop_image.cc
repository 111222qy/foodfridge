#include "stdio.h"
#include "stdlib.h"
#include "time.h"
#include "yolov6.h"
#include "crop_image.h"
#include "image_utils.h"
#include "im2d_version.h"
#include "im2d_type.h"
#include "im2d_single.h"
#include "im2d_common.h"
#include "im2d_buffer.h"
#include "jpeglib.h"
#include "stb_image.h"
#include "stb_image_write.h"
#include <unistd.h>

#include <android/log.h>

#define CLAMP(val, min, max) ((val) < (min) ? (min) : ((val) > (max) ? (max) : (val)))

#define LOG_TAG "YOLOv6_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * 初始化RGA缓冲区
 */
// static int init_rga_buffer(const image_buffer_t* src, rga_buffer_t* rga_buf, rga_buffer_handle_t* handle) {
//     if (!src || !src->virt_addr) {
//         return -1;
//     }

//     // 动态获取RGA格式
//     int rga_format = RK_FORMAT_UNKNOWN;
//     switch(src->format) {
//         case IMAGE_FORMAT_RGBA8888: 
//             rga_format = RK_FORMAT_RGBA_8888;
//             break;
//         case IMAGE_FORMAT_RGB888: 
//             rga_format = RK_FORMAT_RGB_888;
//             break;
//         default:
//             return -1;
//     }

//     // 计算对齐后的尺寸
//     int aligned_width = (src->width + 3) & ~3; // 4字节对齐
//     size_t buffer_size = aligned_width * src->height * get_bpp_from_format(rga_format);
    
//     // 使用memalign分配对齐内存
//     void* aligned_addr = memalign(16, buffer_size);
//     if (!aligned_addr) {
//         return -1;
//     }
//     memcpy(aligned_addr, src->virt_addr, buffer_size);

//     // 导入对齐后的缓冲区
//     *handle = importbuffer_virtualaddr(aligned_addr, buffer_size);
//     if (!*handle) {
//         free(aligned_addr);
//         return -1;
//     }

//     *rga_buf = wrapbuffer_handle(*handle, src->width, src->height, rga_format);
//     return 0;
// }

void process_detections(const image_buffer_t* img, object_detect_result *det_result, image_buffer_t* dst) {
    if (det_result->cls_id == 1) return;

    // 初始化解码参数
    image_buffer_t dst_img;
    int rets = 0;
    memset(&dst_img, 0, sizeof(image_buffer_t));

    // Pre Process
    dst_img.width = img->width;
    dst_img.height = img->height;
    dst_img.format = IMAGE_FORMAT_RGB888;
    dst_img.size = get_image_size(&dst_img);
    dst_img.virt_addr = (unsigned char*)malloc(dst_img.size);
    if (dst_img.virt_addr == NULL) {
        printf("malloc buffer size:%d fail!\n", dst_img.size);
    }
    rets = convert_image((image_buffer_t*)img, &dst_img, NULL, NULL, 0);
    if (rets < 0) {
        printf("convert_image fail! ret=%d\n", rets);
    }
    const int TARGET_SIZE = 224;
    int format = (dst_img.format == IMAGE_FORMAT_RGBA8888) ? 
                RK_FORMAT_RGBA_8888 : RK_FORMAT_RGB_888; // 动态获取格式
    
    int bpp = get_bpp_from_format(format);
    int resize_size = TARGET_SIZE * TARGET_SIZE * bpp;
    char* resize_buf = (char*)memalign(16, resize_size); // 内存对齐

    int x1 = det_result->box.left;
    int y1 = det_result->box.top;
    int x2 = det_result->box.right;
    int y2 = det_result->box.bottom;
    int channels = (format == RK_FORMAT_RGBA_8888) ? 4 : 3;

    // 参数有效性检查（增加对齐后范围校验）
    if (x2 <= x1 || y2 <= y1) return;
    if (x1 < 0 || y1 < 0 || x2 > dst_img.width || y2 > dst_img.height) return;

    rga_buffer_t src_rga, crop_rga, resize_rga;
    rga_buffer_handle_t src_handle = 0, crop_handle = 0, resize_handle = 0;
    int ret = IM_STATUS_SUCCESS;

    /***** 源图像处理 *****/
    size_t src_buffer_size = dst_img.width * dst_img.height * bpp;
    src_handle = importbuffer_virtualaddr(dst_img.virt_addr, src_buffer_size);
    if (!src_handle) {
        printf("Import source buffer failed\n");
        return;
    }
    src_rga = wrapbuffer_handle(src_handle, dst_img.width, dst_img.height, format);

    /***** 裁剪处理 *****/
    int aligned_width = (x2-x1 + 3) & ~3; // 4字节对齐
    int crop_height = y2 - y1;
    size_t crop_size = aligned_width * crop_height * bpp;
    char* crop_buf = (char*)memalign(16, crop_size); // 对齐内存分配

    // 创建裁剪区域结构体
    im_rect crop_rect = {
        .x = x1,
        .y = y1,
        .width = x2 - x1,    // 原始裁剪宽度
        .height = crop_height // 原始裁剪高度
    };

    // 导入裁剪缓冲区
    crop_handle = importbuffer_virtualaddr(crop_buf, crop_size);
    if (!crop_handle) {
        printf("Import crop buffer failed\n");
        goto CLEANUP;
    }
    crop_rga = wrapbuffer_handle(crop_handle, aligned_width, crop_height, format);

    ret = imcheck(src_rga, crop_rga, {}, {});
    if (IM_STATUS_NOERROR != ret) {
        printf("%d, check error! %s", __LINE__, imStrError((IM_STATUS)ret));
    }

    // 执行裁剪操作
    if ((ret = imcrop(src_rga, crop_rga, crop_rect)) != IM_STATUS_SUCCESS) {
        printf("Crop failed: %s\n", imStrError(ret));
        goto CLEANUP;
    }

    // 保存裁剪结果（使用实际像素宽度）
    
    // stbi_write_png("rga_crop.png", x2-x1, crop_height, channels, 
    //               crop_buf, aligned_width * bpp); // 注意stride参数

    /***** 缩放处理 *****/
    resize_handle = importbuffer_virtualaddr(resize_buf, resize_size);
    if (!resize_handle) {
        printf("Import resize buffer failed\n");
        goto CLEANUP;
    }
    resize_rga = wrapbuffer_handle(resize_handle, TARGET_SIZE, TARGET_SIZE, format);

    ret = imcheck(crop_rga, resize_rga, {}, {});
    if (IM_STATUS_NOERROR != ret) {
        printf("%d, check error! %s", __LINE__, imStrError((IM_STATUS)ret));
    }
    // 执行缩放操作
    if ((ret = imresize(crop_rga, resize_rga)) != IM_STATUS_SUCCESS) {
        printf("Resize failed: %s\n", imStrError(ret));
        goto CLEANUP;
    }

    /***** 结果输出 *****/
    // 分配目标内存（确保16字节对齐）
    dst->virt_addr = (unsigned char*)memalign(16, resize_size);
    dst->width = TARGET_SIZE;
    dst->height = TARGET_SIZE;
    dst->format = IMAGE_FORMAT_RGB888;

    // 拷贝数据并保存结果
    memcpy(dst->virt_addr, resize_buf, resize_size);
    // stbi_write_png("/storage/emulated/0/sample_weight/model/food.png", TARGET_SIZE, TARGET_SIZE, channels,
    //               dst->virt_addr, TARGET_SIZE * bpp);

CLEANUP:
    // 逆序释放资源
    if (resize_handle) {
        releasebuffer_handle(resize_handle);
        free(resize_buf);
    }
    if (crop_handle) {
        releasebuffer_handle(crop_handle);
        free(crop_buf);
    }
    if (src_handle) {
        releasebuffer_handle(src_handle);
    }
}

int crop_jpeg(const char *input_filename, const char *output_filename,
              int x, int y, int width, int height) {
    // 声明并初始化JPEG解压缩结构体
    struct jpeg_decompress_struct cinfo;
    struct jpeg_error_mgr jerr;
    cinfo.err = jpeg_std_error(&jerr);
    jpeg_create_decompress(&cinfo);

    // 打开输入文件
    FILE *infile = fopen(input_filename, "rb");
    if (!infile) {
        fprintf(stderr, "无法打开输入文件: %s\n", input_filename);
        return -1;
    }

    // 指定输入文件
    jpeg_stdio_src(&cinfo, infile);

    // 读取JPEG文件头
    jpeg_read_header(&cinfo, TRUE);

    // 开始解压缩
    jpeg_start_decompress(&cinfo);

    // 验证裁剪参数是否合法
    if (x + width > cinfo.output_width ||
        y + height > cinfo.output_height) {
        fprintf(stderr, "裁剪参数超出图像范围\n");
        fclose(infile);
        jpeg_destroy_decompress(&cinfo);
        return -1;
    }

    // 为输出图像分配内存（每行像素）
    JSAMPARRAY buffer = (*cinfo.mem->alloc_sarray)
        ((j_common_ptr)&cinfo, JPOOL_IMAGE, width * cinfo.output_components, 1);

    // 初始化JPEG压缩结构体
    struct jpeg_compress_struct cinfo_out;
    struct jpeg_error_mgr jerr_out;
    cinfo_out.err = jpeg_std_error(&jerr_out);
    jpeg_create_compress(&cinfo_out);

    // 打开输出文件
    FILE *outfile = fopen(output_filename, "wb");
    if (!outfile) {
        fprintf(stderr, "无法创建输出文件: %s\n", output_filename);
        fclose(infile);
        jpeg_destroy_decompress(&cinfo);
        return -1;
    }

    // 设置输出文件
    jpeg_stdio_dest(&cinfo_out, outfile);

    // 设置压缩参数（与输入图像相同）
    cinfo_out.image_width = width;
    cinfo_out.image_height = height;
    cinfo_out.input_components = cinfo.output_components;
    cinfo_out.in_color_space = cinfo.out_color_space;

    // 设置默认参数
    jpeg_set_defaults(&cinfo_out);
    jpeg_set_quality(&cinfo_out, 100, TRUE); // 设置压缩质量（0-100）

    // 开始压缩
    jpeg_start_compress(&cinfo_out, TRUE);

    // 跳过前y行
    for (int i = 0; i < y; i++) {
        jpeg_read_scanlines(&cinfo, buffer, 1);
    }

    // 处理裁剪区域
    while (cinfo_out.next_scanline < cinfo_out.image_height) {
        // 读取当前扫描线
        jpeg_read_scanlines(&cinfo, buffer, 1);
        
        // 跳过左侧x列的像素
        JSAMPROW row_ptr = buffer[0] + x * cinfo.output_components;
        
        // 写入裁剪后的扫描线
        jpeg_write_scanlines(&cinfo_out, &row_ptr, 1);
    }

    // 完成压缩和解压缩
    jpeg_finish_compress(&cinfo_out);
    jpeg_finish_decompress(&cinfo);

    // 清理资源
    jpeg_destroy_compress(&cinfo_out);
    jpeg_destroy_decompress(&cinfo);
    fclose(infile);
    fclose(outfile);

    return 0;
}

int crop_image(const image_buffer_t* src, image_buffer_t* dst,
              int rx, int ry, int rw, int rh) {
    // 参数校验
    *dst = {
            .width = 224,   // 用户设置的目标宽度
            .height = 224,  // 用户设置的目标高度
            .format = IMAGE_FORMAT_RGB888,
            .virt_addr = (unsigned char *)malloc(224*224*sizeof(IMAGE_FORMAT_RGB888)) // 必须预先分配内存
        };
    if (!src || !dst || !src->virt_addr || !dst->virt_addr) {
        LOGE("Invalid buffers");
        printf("Invalid buffers\n");
        return -1;
    }
    if (src->format != IMAGE_FORMAT_RGB888 || dst->format != IMAGE_FORMAT_RGB888) {
        printf("Only RGB888 format supported\n");
        LOGE("Only RGB888 format supported\n");
        return -1;
    }
    if (rx < 0 || ry < 0 || rw <= 0 || rh <= 0 ||
        rx + rw > src->width || ry + rh > src->height) {
        printf("Invalid crop region\n");
        LOGE("Invalid crop region\n");
        return -1;
    }
    if (dst->width <= 0 || dst->height <= 0) {
        LOGE("Invalid output dimensions");
        printf("Invalid output dimensions\n");
        return -1;
    }

    const int channels = 3;
    unsigned char* src_data = src->virt_addr;
    unsigned char* dst_data = dst->virt_addr;

    // 计算缩放比例（裁剪区域->目标尺寸）
    float scale_w = (float)rw / dst->width;
    float scale_h = (float)rh / dst->height;

    // 双线性插值处理
    for (int dy = 0; dy < dst->height; dy++) {
        for (int dx = 0; dx < dst->width; dx++) {
            // 计算对应源图坐标（在裁剪区域内）
            float sx = rx + dx * scale_w;
            float sy = ry + dy * scale_h;

            // 边界保护
            int x0 = (int)sx;
            int y0 = (int)sy;
            x0 = (x0 < rx) ? rx : (x0 >= rx + rw - 1) ? rx + rw - 2 : x0;
            y0 = (y0 < ry) ? ry : (y0 >= ry + rh - 1) ? ry + rh - 2 : y0;

            int x1 = x0 + 1;
            int y1 = y0 + 1;

            // 计算插值权重
            float wx = sx - x0;
            float wy = sy - y0;

            // 获取四个采样点
            unsigned char* p00 = &src_data[(y0 * src->width + x0) * channels];
            unsigned char* p01 = &src_data[(y0 * src->width + x1) * channels];
            unsigned char* p10 = &src_data[(y1 * src->width + x0) * channels];
            unsigned char* p11 = &src_data[(y1 * src->width + x1) * channels];

            // 计算每个通道
            for (int c = 0; c < channels; c++) {
                float val = 
                    p00[c] * (1 - wx) * (1 - wy) +
                    p01[c] * wx * (1 - wy) +
                    p10[c] * (1 - wx) * wy +
                    p11[c] * wx * wy;
                
                dst_data[(dy * dst->width + dx) * channels + c] = 
                    (unsigned char)(val > 255 ? 255 : (val < 0 ? 0 : val));
            }
        }
    }
    
    // char save_path[256];
    // struct timeval tv;
    // gettimeofday(&tv, NULL);
    // pid_t pid = getpid();
    // // 生成唯一文件名
    // snprintf(save_path, sizeof(save_path), 
    //         "/storage/emulated/0/sample_weight/model/images/preproc_%d_%ld%06ld.png",  // 保存路径可修改
    //         pid, tv.tv_sec, tv.tv_usec);
    // if (stbi_write_png(save_path, dst->width, dst->height, 3, 
    //     dst->virt_addr, dst->width*3)!=1){
    //         LOGD("图片保存失败");
    //     }
    return 0;
}

int crop_RGBA_image(const image_buffer_t* src, image_buffer_t* dst,
              int rx, int ry, int rw, int rh) {
    // 参数校验
    *dst = {
            .width = 224,   // 用户设置的目标宽度
            .height = 224,  // 用户设置的目标高度
            .format = IMAGE_FORMAT_RGB888,
            .virt_addr = (unsigned char *)malloc(224*224*3) // 必须预先分配内存
        };
    if (!src || !dst || !src->virt_addr || !dst->virt_addr) {
        LOGE("Invalid buffers");
        printf("Invalid buffers\n");
        if (dst->virt_addr) free(dst->virt_addr);
        return -1;
    }
    if (src->format != IMAGE_FORMAT_RGBA8888 || dst->format != IMAGE_FORMAT_RGB888) {
        printf("Only RGB888 format supported\n");
        LOGE("Only RGB888 format supported\n");
        if (dst->virt_addr) free(dst->virt_addr);
        return -1;
    }
    if (rx < 0 || ry < 0 || rw <= 0 || rh <= 0 ||
        rx + rw > src->width || ry + rh > src->height) {
        printf("Invalid crop region\n");
        LOGE("Invalid crop region\n");
        if (dst->virt_addr) free(dst->virt_addr);
        return -1;
    }
    if (dst->width <= 0 || dst->height <= 0) {
        LOGE("Invalid output dimensions");
        printf("Invalid output dimensions\n");
        if (dst->virt_addr) free(dst->virt_addr);
        return -1;
    }

    const int src_channels = 4; // RGBA
    const int dst_channels = 3; // RGB
    unsigned char* src_data = src->virt_addr;
    unsigned char* dst_data = dst->virt_addr;

    float scale_w = (float)rw / dst->width;
    float scale_h = (float)rh / dst->height;

    for (int dy = 0; dy < dst->height; dy++) {
        for (int dx = 0; dx < dst->width; dx++) {
            // 计算源图坐标
            float sx = rx + dx * scale_w;
            float sy = ry + dy * scale_h;

            // 边界保护
            int x0 = (int)(sx < rx ? rx : (sx > rx + rw - 2 ? rx + rw - 2 : sx));
            int y0 = (int)(sy < ry ? ry : (sy > ry + rh - 2 ? ry + rh - 2 : sy));
            int x1 = x0 + 1;
            int y1 = y0 + 1;

            // 计算插值权重
            float wx = sx - x0;
            float wy = sy - y0;

            // 获取四个采样点
            unsigned char* p00 = &src_data[(y0 * src->width + x0) * src_channels];
            unsigned char* p01 = &src_data[(y0 * src->width + x1) * src_channels];
            unsigned char* p10 = &src_data[(y1 * src->width + x0) * src_channels];
            unsigned char* p11 = &src_data[(y1 * src->width + x1) * src_channels];

            // 处理每个RGB通道
            for (int c = 0; c < dst_channels; c++) {
                float val = 
                    p00[c] * (1 - wx) * (1 - wy) +
                    p01[c] * wx * (1 - wy) +
                    p10[c] * (1 - wx) * wy +
                    p11[c] * wx * wy;
                
                // 使用CLAMP宏
                dst_data[(dy * dst->width + dx) * dst_channels + c] = (unsigned char)CLAMP(val, 0.0f, 255.0f);
            }
        }
    }
    
    // char save_path[256];
    // struct timeval tv;
    // gettimeofday(&tv, NULL);
    // pid_t pid = getpid();
    // // 生成唯一文件名
    // snprintf(save_path, sizeof(save_path), 
    //         "/storage/emulated/0/sample_weight/model/images/preproc_%d_%ld%06ld.png",  // 保存路径可修改
    //         pid, tv.tv_sec, tv.tv_usec);
    // if (stbi_write_png(save_path, dst->width, dst->height, 3, 
    //     dst->virt_addr, dst->width*3)!=1){
    //         LOGD("图片保存失败");
    //     }
    return 0;
}


// 配套内存释放函数

void release_image(image_buffer_t* img)
{
    if (img->virt_addr) {
        free(img->virt_addr);
        memset(img, 0, sizeof(image_buffer_t));
    }
}

