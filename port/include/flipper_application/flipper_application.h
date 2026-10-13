#pragma once
#include <stdint.h>
/* The plugins are linked into the one library (port/port_plugin.c); only the descriptor type stays. */
typedef struct {
    const char* appid;
    uint32_t ep_api_version;
    const void* entry_point;
} FlipperAppPluginDescriptor;
