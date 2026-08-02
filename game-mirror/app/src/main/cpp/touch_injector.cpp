#include "touch_injector.h"

#include <linux/input.h>
#include <linux/uinput.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <android/log.h>

#define LOG_TAG "TouchInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

constexpr const char* INPUT_DEV_DIR = "/dev/input";
constexpr const char* UINPUT_DEV = "/dev/uinput";

// 一加 15 虚拟设备标识
constexpr int VENDOR_OPLUS = 0x1A15;
constexpr int PRODUCT_TOUCH_MIRROR = 0xFA01;
constexpr int VERSION_TOUCH_MIRROR = 1;

// ========================================================================
// JNI 导出函数
// ========================================================================

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_gamemirror_touch_TouchRedirector_nativeInit(JNIEnv* env, jobject thiz) {
    auto* injector = new TouchInjector();
    if (injector->init()) {
        LOGI("Native touch injector initialized (%s)",
             injector->isUinput() ? "uinput" : "physical");
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
Java_com_example_gamemirror_touch_TouchRedirector_nativeInjectSwipe(
        JNIEnv* env, jobject thiz, jlong handle,
        jint fromX, jint fromY, jint toX, jint toY,
        jint steps, jint slotId, jint trackingId) {
    auto* injector = reinterpret_cast<TouchInjector*>(handle);
    if (injector && injector->isReady()) {
        injector->injectSwipe(fromX, fromY, toX, toY, steps, slotId, trackingId);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_gamemirror_touch_TouchRedirector_nativeIsUinput(
        JNIEnv* env, jobject thiz, jlong handle) {
    auto* injector = reinterpret_cast<TouchInjector*>(handle);
    return (injector && injector->isUinput()) ? JNI_TRUE : JNI_FALSE;
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

TouchInjector::TouchInjector()
    : fd_(-1), isUinput_(false), uinputFd_(-1), maxSlots_(10), activeSlots_(0) {}

TouchInjector::~TouchInjector() {
    release();
}

bool TouchInjector::init() {
    // 策略 1：尝试创建 uinput 虚拟设备（首选）
    if (createUinputDevice()) {
        isUinput_ = true;
        LOGI("uinput virtual touch device created (vendor=0x%04X)", VENDOR_OPLUS);
        return true;
    }

    // 策略 2：回退到物理设备直写
    LOGW("uinput unavailable, falling back to physical device");
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

    isUinput_ = false;
    LOGI("Physical touch device opened: %s (fd=%d)", device_.c_str(), fd_);
    return true;
}

// ========================================================================
// 单击注入
// ========================================================================

bool TouchInjector::injectClick(int x, int y, int slotId, int trackingId) {
    if (fd_ < 0) return false;

    sendEvent(EV_ABS, ABS_MT_SLOT, slotId);
    sendEvent(EV_ABS, ABS_MT_TRACKING_ID, trackingId);
    sendEvent(EV_KEY, BTN_TOUCH, 1);
    sendEvent(EV_ABS, ABS_MT_POSITION_X, x);
    sendEvent(EV_ABS, ABS_MT_POSITION_Y, y);
    sendEvent(EV_SYN, SYN_REPORT, 0);

    // Up
    sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
    sendEvent(EV_KEY, BTN_TOUCH, 0);
    sendEvent(EV_SYN, SYN_REPORT, 0);

    return true;
}

// ========================================================================
// 多点触控注入
// ========================================================================

bool TouchInjector::injectMultiTouchDown(int x, int y, int slotId, int trackingId) {
    if (fd_ < 0) return false;

    sendEvent(EV_ABS, ABS_MT_SLOT, slotId);
    sendEvent(EV_ABS, ABS_MT_TRACKING_ID, trackingId);
    sendEvent(EV_KEY, BTN_TOUCH, 1);
    sendEvent(EV_ABS, ABS_MT_POSITION_X, x);
    sendEvent(EV_ABS, ABS_MT_POSITION_Y, y);
    sendEvent(EV_SYN, SYN_REPORT, 0);

    activeSlots_++;
    return true;
}

bool TouchInjector::injectMultiTouchMove(int x, int y, int slotId) {
    if (fd_ < 0) return false;

    sendEvent(EV_ABS, ABS_MT_SLOT, slotId);
    sendEvent(EV_ABS, ABS_MT_POSITION_X, x);
    sendEvent(EV_ABS, ABS_MT_POSITION_Y, y);
    sendEvent(EV_SYN, SYN_REPORT, 0);

    return true;
}

bool TouchInjector::injectMultiTouchUp(int slotId) {
    if (fd_ < 0) return false;

    sendEvent(EV_ABS, ABS_MT_SLOT, slotId);
    sendEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);

    activeSlots_--;
    // 仅当所有 slot 都抬起时才发送 BTN_TOUCH=0，避免影响其他活跃触点
    if (activeSlots_ <= 0) {
        activeSlots_ = 0;
        sendEvent(EV_KEY, BTN_TOUCH, 0);
    }
    sendEvent(EV_SYN, SYN_REPORT, 0);

    return true;
}

// ========================================================================
// 滑动注入
// ========================================================================

bool TouchInjector::injectSwipe(int fromX, int fromY, int toX, int toY,
                                int steps, int slotId, int trackingId) {
    if (fd_ < 0) return false;
    if (steps < 1) steps = 1;

    // Down
    if (!injectMultiTouchDown(fromX, fromY, slotId, trackingId)) {
        return false;
    }

    // 微延迟确保 Down 事件被处理
    usleep(2000);

    // Move 步骤
    float stepX = (float)(toX - fromX) / steps;
    float stepY = (float)(toY - fromY) / steps;

    for (int i = 1; i <= steps; i++) {
        int curX = fromX + (int)(stepX * i);
        int curY = fromY + (int)(stepY * i);
        if (!injectMultiTouchMove(curX, curY, slotId)) {
            injectMultiTouchUp(slotId);
            return false;
        }
        if (i < steps) {
            usleep(1500); // 步间延迟 ~1.5ms
        }
    }

    // Up
    usleep(2000);
    return injectMultiTouchUp(slotId);
}

// ========================================================================
// 资源释放
// ========================================================================

void TouchInjector::release() {
    if (isUinput_) {
        destroyUinputDevice();
    } else if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
        LOGI("Physical touch device released: %s", device_.c_str());
    }
    isUinput_ = false;
}

void TouchInjector::sendEvent(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;

    // 非阻塞写入重试逻辑：处理部分写入和 EAGAIN
    ssize_t written = 0;
    ssize_t total = sizeof(ev);
    char* buf = reinterpret_cast<char*>(&ev);
    int retries = 0;
    const int MAX_RETRIES = 3;

    while (written < total && retries < MAX_RETRIES) {
        ssize_t n = write(fd_, buf + written, total - written);
        if (n > 0) {
            written += n;
        } else if (n == 0) {
            LOGE("sendEvent: write returned 0 (fd closed?)");
            break;
        } else {
            // n < 0: error
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                retries++;
                usleep(500); // 等待 0.5ms 后重试
                continue;
            }
            LOGE("sendEvent: write failed: %s", strerror(errno));
            break;
        }
    }
}

// ========================================================================
// uinput 虚拟设备创建
// ========================================================================

bool TouchInjector::createUinputDevice() {
    uinputFd_ = open(UINPUT_DEV, O_WRONLY | O_NONBLOCK);
    if (uinputFd_ < 0) {
        LOGW("Cannot open %s: %s (root required)", UINPUT_DEV, strerror(errno));
        return false;
    }

    // 设置设备能力位
    ioctl(uinputFd_, UI_SET_EVBIT, EV_KEY);
    ioctl(uinputFd_, UI_SET_EVBIT, EV_SYN);
    ioctl(uinputFd_, UI_SET_EVBIT, EV_ABS);

    ioctl(uinputFd_, UI_SET_KEYBIT, BTN_TOUCH);

    ioctl(uinputFd_, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(uinputFd_, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(uinputFd_, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(uinputFd_, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(uinputFd_, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
    ioctl(uinputFd_, UI_SET_ABSBIT, ABS_MT_PRESSURE);

    // 设置 ABS 参数范围（一加 15 屏幕分辨率 1280x2800）
    struct uinput_abs_setup absSetup;
    memset(&absSetup, 0, sizeof(absSetup));

    absSetup.code = ABS_MT_POSITION_X;
    absSetup.absinfo.maximum = 4096;
    absSetup.absinfo.resolution = 1;
    ioctl(uinputFd_, UI_ABS_SETUP, &absSetup);

    absSetup.code = ABS_MT_POSITION_Y;
    absSetup.absinfo.maximum = 4096;
    absSetup.absinfo.resolution = 1;
    ioctl(uinputFd_, UI_ABS_SETUP, &absSetup);

    absSetup.code = ABS_MT_SLOT;
    absSetup.absinfo.maximum = maxSlots_ - 1;
    ioctl(uinputFd_, UI_ABS_SETUP, &absSetup);

    absSetup.code = ABS_MT_TRACKING_ID;
    absSetup.absinfo.maximum = 65535;
    ioctl(uinputFd_, UI_ABS_SETUP, &absSetup);

    absSetup.code = ABS_MT_PRESSURE;
    absSetup.absinfo.maximum = 255;
    ioctl(uinputFd_, UI_ABS_SETUP, &absSetup);

    absSetup.code = ABS_MT_TOUCH_MAJOR;
    absSetup.absinfo.maximum = 255;
    ioctl(uinputFd_, UI_ABS_SETUP, &absSetup);

    // 设备身份信息
    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    snprintf(usetup.name, UINPUT_MAX_NAME_SIZE, "OPLUS Touch Mirror");
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor = VENDOR_OPLUS;
    usetup.id.product = PRODUCT_TOUCH_MIRROR;
    usetup.id.version = VERSION_TOUCH_MIRROR;

    // 创建设备
    if (ioctl(uinputFd_, UI_DEV_SETUP, &usetup) < 0) {
        LOGE("UI_DEV_SETUP failed: %s", strerror(errno));
        close(uinputFd_);
        uinputFd_ = -1;
        return false;
    }

    if (ioctl(uinputFd_, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: %s", strerror(errno));
        close(uinputFd_);
        uinputFd_ = -1;
        return false;
    }

    fd_ = uinputFd_;
    return true;
}

void TouchInjector::destroyUinputDevice() {
    if (uinputFd_ >= 0) {
        ioctl(uinputFd_, UI_DEV_DESTROY);
        close(uinputFd_);
        uinputFd_ = -1;
        fd_ = -1;
        LOGI("uinput device destroyed");
    }
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

    std::string bestDevice;
    struct dirent* entry;

    while ((entry = readdir(dir)) != nullptr) {
        if (strncmp(entry->d_name, "event", 5) != 0) {
            continue;
        }
        std::string path = std::string(INPUT_DEV_DIR) + "/" + entry->d_name;
        if (!isTouchDevice(path)) {
            continue;
        }

        // 优先匹配名称包含 "touchscreen" 或 "synaptics" 的设备
        char name[256] = {0};
        int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK);
        if (fd >= 0) {
            if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0) {
                if (strstr(name, "touchscreen") || strstr(name, "synaptics")
                        || strstr(name, "fts") || strstr(name, "goodix")) {
                    close(fd);
                    closedir(dir);
                    LOGI("Found primary touchscreen: %s (%s)", path.c_str(), name);
                    return path;
                }
            }
            close(fd);
        }

        // 记录第一个匹配设备作为回退
        if (bestDevice.empty()) {
            bestDevice = path;
        }
    }

    closedir(dir);
    if (!bestDevice.empty()) {
        LOGI("Using fallback touch device: %s", bestDevice.c_str());
    }
    return bestDevice;
}

bool TouchInjector::isTouchDevice(const std::string& path) {
    int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        return false;
    }

    // 检查 ABS 能力位
    unsigned long absBits[KEY_CNT / (sizeof(unsigned long) * 8) + 1];
    memset(absBits, 0, sizeof(absBits));

    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absBits)), absBits) < 0) {
        close(fd);
        return false;
    }

    bool hasAbsMtX = (absBits[ABS_MT_POSITION_X / (sizeof(unsigned long) * 8)]
            & (1UL << (ABS_MT_POSITION_X % (sizeof(unsigned long) * 8)))) != 0;
    bool hasAbsMtY = (absBits[ABS_MT_POSITION_Y / (sizeof(unsigned long) * 8)]
            & (1UL << (ABS_MT_POSITION_Y % (sizeof(unsigned long) * 8)))) != 0;

    if (!hasAbsMtX || !hasAbsMtY) {
        close(fd);
        return false;
    }

    // 检查 KEY 能力位
    unsigned long keyBits[KEY_CNT / (sizeof(unsigned long) * 8) + 1];
    memset(keyBits, 0, sizeof(keyBits));

    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keyBits)), keyBits) < 0) {
        close(fd);
        return false;
    }

    bool hasTouchKey = (keyBits[BTN_TOUCH / (sizeof(unsigned long) * 8)]
            & (1UL << (BTN_TOUCH % (sizeof(unsigned long) * 8)))) != 0;

    close(fd);
    return hasTouchKey;
}