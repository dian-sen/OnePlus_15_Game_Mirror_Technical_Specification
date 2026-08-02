#ifndef TOUCH_INJECTOR_H
#define TOUCH_INJECTOR_H

#include <jni.h>
#include <string>
#include <vector>

/**
 * 触控注入器 - 一加 15 (OnePlus 15) 适配
 *
 * 双策略触控注入：
 * 1. uinput 虚拟设备（首选，vendor=0x1A15，系统原生识别）
 * 2. /dev/input/event* 物理设备直写（回退方案）
 *
 * 支持单击、多点触控、滑动操作
 * 目标延迟 ≤ 3ms
 */
class TouchInjector {
public:
    TouchInjector();
    ~TouchInjector();

    /**
     * 初始化：优先创建 uinput 虚拟设备，失败则回退到物理设备
     * @return true 如果至少一种策略初始化成功
     */
    bool init();

    /**
     * 注入一次点击事件（Down → Up）
     * @param x          屏幕绝对 X 坐标
     * @param y          屏幕绝对 Y 坐标
     * @param slotId     独立 Slot ID
     * @param trackingId 触控跟踪 ID
     * @return true 如果注入成功
     */
    bool injectClick(int x, int y, int slotId, int trackingId);

    /**
     * 注入多点触控（Down 阶段，不 Up）
     * @param x          屏幕绝对 X 坐标
     * @param y          屏幕绝对 Y 坐标
     * @param slotId     Slot ID（0~9）
     * @param trackingId 触控跟踪 ID
     * @return true 如果注入成功
     */
    bool injectMultiTouchDown(int x, int y, int slotId, int trackingId);

    /**
     * 移动触控点（用于滑动操作）
     * @param x     新 X 坐标
     * @param y     新 Y 坐标
     * @param slotId Slot ID
     */
    bool injectMultiTouchMove(int x, int y, int slotId);

    /**
     * 抬起触控点
     * @param slotId Slot ID
     */
    bool injectMultiTouchUp(int slotId);

    /**
     * 注入滑动操作（Down → Move* → Up）
     * @param fromX     起始 X
     * @param fromY     起始 Y
     * @param toX       目标 X
     * @param toY       目标 Y
     * @param steps     滑动步数（越高越平滑，建议 5~20）
     * @param slotId    Slot ID
     * @param trackingId 触控跟踪 ID
     * @return true 如果注入成功
     */
    bool injectSwipe(int fromX, int fromY, int toX, int toY,
                     int steps, int slotId, int trackingId);

    /**
     * 释放资源
     */
    void release();

    bool isReady() const { return fd_ >= 0; }
    bool isUinput() const { return isUinput_; }

private:
    // 发送单个 input_event
    void sendEvent(int type, int code, int value);

    // 扫描 /dev/input/ 目录查找触摸屏设备
    std::string findTouchDevice();

    // 检查设备是否支持多点触控（ABS_MT_POSITION_X/Y + BTN_TOUCH）
    bool isTouchDevice(const std::string& path);

    // 创建 uinput 虚拟触控设备
    bool createUinputDevice();

    // 销毁 uinput 设备
    void destroyUinputDevice();

    int fd_;               // 当前使用的设备文件描述符
    std::string device_;   // 设备路径
    bool isUinput_;        // 是否使用 uinput 虚拟设备
    int uinputFd_;         // uinput 设备 fd（与 fd_ 指向同一设备时相同）
    int maxSlots_;         // 最大触控点数
};

#endif // TOUCH_INJECTOR_H