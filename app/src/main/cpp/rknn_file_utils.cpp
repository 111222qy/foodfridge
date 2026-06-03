#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "rknn_file_utils.h"

// Function definition moved to file_utils.c to avoid duplicate symbols

int read_data_from_memory(const char *data, int size, char **out_data)
{
    if (data == NULL || size <= 0) {
        return -1;
    }
    char *out = (char *)malloc(size);
    if (out == NULL) {
        return -1;
    }
    memcpy(out, data, size);
    *out_data = out;
    return size;
}

