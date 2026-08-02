#ifndef TOUCH_INJECTOR_H
#define TOUCH_INJECTOR_H

#include <jni.h>
#include <string>
#include <vector>

/**
 * 触控注入器 - 一加 15 (OnePlus 15) 适配
 *
 * 直接操作 /dev/input/event* 设备节点
 * 通过 Linux Input Subsystem 实现硬件级触控模拟
 * 目标延迟 ≤ 3ms
 */
class TouchInjector {
public:
    TouchInjector();
    ~TouchInjector();

    /**
     * 初始化：扫描并打开触摸屏设备节点
     * @return true 如果成功找到并打开触摸设备
     */
    bool init();

    /**
     * 注入一次点击事件到屏幕指定坐标
     * @param x          屏幕绝对 X 坐标
     * @param y          屏幕绝对 Y 坐标
     * @param slotId     独立 Slot ID（避免干扰游戏主操作）
     * @param trackingId 触控跟踪 ID
     * @return true 如果注入成功
     */
    bool injectClick(int x, int y, int slotId, int trackingId);

    /**
     * 释放资源
     */
    void release();

    bool isReady() const { return fd_ >= 0; }

private:
    // 发送单个 input_event
    void sendEvent(int type, int code, int value);

    // 扫描 /dev/input/ 目录查找触摸屏设备
    std::string findTouchDevice();

    // 检查设备是否支持 ABS_MT_POSITION_X/Y（触摸屏设备判断）
    bool isTouchDevice(const std::string& path);

    int fd_;             // 触摸屏设备文件描述符
    std::string device_; // 设备路径
};

#endif // TOUCH_INJECTOR_H