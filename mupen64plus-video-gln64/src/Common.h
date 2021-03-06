#ifndef __COMMON_H__
#define __COMMON_H__

//#define PROFILE_GBI

#define LOG_NONE	0
#define LOG_ERROR   1
#define LOG_MINIMAL	2
#define LOG_WARNING 3
#define LOG_VERBOSE 4

#define LOG_LEVEL LOG_WARNING

#if LOG_LEVEL>0

#include <android/log.h>

#define LOG(A, ...) \
    if (A <= LOG_LEVEL) \
    { \
        __android_log_print(ANDROID_LOG_DEBUG, "gln64", __VA_ARGS__); \
    }

#else

#define LOG(A, ...)

#endif

#endif
