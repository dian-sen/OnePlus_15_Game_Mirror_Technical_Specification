#include "touch_injector.h"

#include <linux/input.h>
#include <linux/uinput.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <dirent.h>
#include <android/log.h>

#define LOG_TAG "TouchInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

constexpr const char* INPUT_DEV_DIR = "/dev/input";

// ========================================================================
// JNI 导出函数
// ========================================================================

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_gamemirror_touch_TouchRedirector_nativeInit(JNIEnv* env, jobject thiz) {
    auto* injector = new TouchInjector();
    if (injector->init()) {
        LOGI("Native touch injector initialized successfully");
        return reinterpret_cast<jlong>(injector);
    }
    LOGE("Native touch injector initialization failed");
    delete injector;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_example_gamemirror_touch_TouchRedirector_nativeInjectTouch(
        JNIEnv* env, jobject thiz, jlong handle,
        jint x, jint y, jint slotId, jint trackingId) {
    auto* injector = reinterpret_cast<TouchInjector*>(handle);
    if (injector && injector->isReady()) {
        injector->injectClick(x, y, slotId, trackingId);
    }
}

JNIEXPORT void JNICALL
Java_com_example_gamemirror_touch_TouchRedirector_nativeRelease(
        JNIEnv* env, jobject thiz, jlong handle) {
    auto* injector = reinterpret_cast<TouchInjector*>(handle);
    if (injector) {
        injector->release();
        delete injector;
    }
}

} // extern "C"

// ========================================================================
// TouchInjector 实现
// ========================================================================

TouchInjector::TouchInjector() : fd_(-1) {}

TouchInjector::~TouchInjector() {
    release();
}

bool TouchInjector::init() {
    device_ = findTouchDevice();
    if (device_.empty()) {
        LOGE("No touch device found in %s", INPUT_DEV_DIR);
        return false;
    }

    fd_ = open(device_.c_str(), O_WRONLY | O_NONBLOCK);
    if (fd_ < 0) {
        LOGE("Failed to open touch device %s: %s", device_.c_str(), strerror(errno));
        return false;
    }

    LOGI("Touch device opened: %s (fd=%d)", device_.c_str(), fd_);
    return true;
}

bool TouchInjector::injectClick(int x, int y, int slotId, int trackingId) {
    if (fd_ < 0) {
        return false;
    }

    // 1. 分配独立 Slot（避免干扰游戏方向盘摇杆）
    sendEvent(EV_ABS, ABS_MT_SLOT, slotId);
    sendEvent(EV_ABS, ABS_MT_TRACKING_ID, trackingId);
    sendEvent(EV_KEY, BTN_TOUCH, 1);

    // 2. 写入映射后的 A 区域坐标
    sendEvent(EV_ABS, ABS_MT_POSITION_X, x);
    sendEvent(EV_ABS, ABS_MT_POSITION_Y, y);
    sendEvent(EV_SYN, SYN_REPORT, 0);

    // 3. 抬起 (Up)
    sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
    sendEvent(EV_KEY, BTN_TOUCH, 0);
    sendEvent(EV_SYN, SYN_REPORT, 0);

    return true;
}

void TouchInjector::release() {
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
        LOGI("Touch device released: %s", device_.c_str());
    }
}

void TouchInjector::sendEvent(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    write(fd_, &ev, sizeof(ev));
}

// ========================================================================
// 触摸屏设备发现
// ========================================================================

std::string TouchInjector::findTouchDevice() {
    DIR* dir = opendir(INPUT_DEV_DIR);
    if (!dir) {
        LOGE("Cannot open %s", INPUT_DEV_DIR);
        return "";
    }

    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        // 只处理 event* 设备节点
        if (strncmp(entry->d_name, "event", 5) != 0) {
            continue;
        }
        std::string path = std::string(INPUT_DEV_DIR) + "/" + entry->d_name;
        if (isTouchDevice(path)) {
            closedir(dir);
            return path;
        }
    }

    closedir(dir);
    return "";
}

bool TouchInjector::isTouchDevice(const std::string& path) {
    int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        return false;
    }

    // 检查设备能力位：支持 ABS_MT_POSITION_X 且支持 EV_KEY + BTN_TOUCH
    unsigned long absBits[EV_CNT / (sizeof(unsigned long) * 8) + 1];
    memset(absBits, 0, sizeof(absBits));

    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absBits)), absBits) < 0) {
        close(fd);
        return false;
    }

    // 检查是否支持多点触控绝对坐标
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(absBits)), absBits) < 0) {
        close(fd);
        return false;
    }

    bool hasTouchKey = (absBits[BTN_TOUCH / (sizeof(unsigned long) * 8)]
            & (1UL << (BTN_TOUCH % (sizeof(unsigned long) * 8)))) != 0;

    close(fd);
    return hasTouchKey;
}