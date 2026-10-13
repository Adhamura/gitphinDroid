#pragma once
/* POSIX stand-in for the furi_hal calls of the Gitphin core (port/port_furi.c). */
#include <furi.h>
#include <toolbox/version.h>

uint32_t furi_hal_rtc_get_timestamp(void); /* Unix time, seconds */
uint32_t furi_hal_random_get(void);

/* Device facts for /api/info. The Android side sets them (port_set_device). */
uint8_t furi_hal_power_get_pct(void);
bool furi_hal_power_is_charging(void);
const char* furi_hal_version_get_name_ptr(void);
const Version* furi_hal_version_get_firmware_version(void);
