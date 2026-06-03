#include "common.h"
#include "im2d.hpp"
#include "RgaUtils.h"
#include "yolov6.h"

void process_detections(const image_buffer_t* img, object_detect_result *det_result,image_buffer_t* dst);
int crop_jpeg(const char *input_filename, const char *output_filename,
    int x, int y, int width, int height);
int crop_image(const image_buffer_t* src, image_buffer_t* dst,
              int x, int y, int w, int h);
void release_image(image_buffer_t* img);
int crop_RGBA_image(const image_buffer_t* src, image_buffer_t* dst,
              int rx, int ry, int rw, int rh);
