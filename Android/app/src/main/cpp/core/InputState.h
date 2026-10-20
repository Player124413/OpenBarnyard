#pragma once
// OpenBarnyard Android — virtual gamepad state fed by the touch overlay,
// physical gamepads and keyboards. Command ids are NOT invented here: they
// are the engine's own AInputCommand values from
// OpenBarnyard/Source/Input/AInputMap.h, so the touch layer can be wired
// straight into TInputInterface once the TOSHI GL/Android platform lands
// (see Android/PORTING.md). Keep this file in sync with AInputMap.h and with
// GameCommand.java.
#include <atomic>
#include <cstdint>
#include <cstring>

namespace ob {

// Subset of AInputCommand that the touch UI binds to. Values match the game.
enum Command : int {
    kCmdEmpty       = 0,
    kCmdOk          = 1,
    kCmdCancel      = 2,
    kCmdBack        = 4,
    kCmdStart       = 7,
    kCmdSelect      = 8,
    kCmdRestart     = 12,
    kCmdMove        = 15,
    kCmdUp          = 16,
    kCmdDown        = 17,
    kCmdLeft        = 18,
    kCmdRight       = 19,
    kCmdForward     = 22,
    kCmdSuperSpeed  = 25,
    kCmdBackward    = 26,
    kCmdZoomIn      = 30,
    kCmdZoomOut     = 31,
    kCmdLook        = 32,
    kCmdContext1    = 41,
    kCmdContext2    = 42,
    kCmdContext3    = 43,
    kCmdContext4    = 44,
    kCmdMenu        = 51,
    kCmdInteract    = 53,
    kCmdUse         = 54,
    kCmdShoot       = 55,
    kCmdShootAlt    = 56,
    kCmdJump        = 57,
    kCmdSpeed       = 58,
    kCmdBrake       = 59,
    kCmdAccelerator = 60,
    kCmdBell        = 61,
    kCmdTrick1      = 62,
    kCmdTrick2      = 63,
    kCmdSideStep    = 66,
    kCmdSwitch      = 67,
    kCmdReload      = 68,
    kCmdDodge       = 69,
    kCmdInventory   = 75,
    kCmdIcons       = 76,
    kCmdZLock       = 78,
    kCmdHorn        = 80,
    kCmdMax         = 128, // bitmask capacity (all game ids are < 81)
};

// Thread-safe snapshot written by the UI thread (JNI calls) and read by the
// render thread once per frame. Buttons are a lock-free bitmask; axes are
// float pairs exchanged atomically. Edge detection ("just pressed") is done
// on the consumer side by diffing consecutive snapshots.
class InputState {
public:
    InputState() = default;

    void SetButton(int command, bool down) {
        if (command <= 0 || command >= kCmdMax) return;
        const uint64_t bit = 1ull << (command & 63);
        std::atomic<uint64_t>& word = (command < 64) ? m_buttons0 : m_buttons1;
        if (down) word.fetch_or(bit, std::memory_order_relaxed);
        else      word.fetch_and(~bit, std::memory_order_relaxed);
    }

    bool IsDown(int command) const {
        if (command <= 0 || command >= kCmdMax) return false;
        const uint64_t word = (command < 64)
            ? m_buttons0.load(std::memory_order_relaxed)
            : m_buttons1.load(std::memory_order_relaxed);
        return (word & (1ull << (command & 63))) != 0;
    }

    // Left stick, -1..1 on both axes (Y up = forward = +1 after Java flip).
    void SetStick(float x, float y) {
        m_stickX.store(ClampAxis(x), std::memory_order_relaxed);
        m_stickY.store(ClampAxis(y), std::memory_order_relaxed);
    }
    float StickX() const { return m_stickX.load(std::memory_order_relaxed); }
    float StickY() const { return m_stickY.load(std::memory_order_relaxed); }

    // Camera look delta accumulated between frames (pixels). Consume() resets.
    void AddCameraDelta(float dx, float dy) {
        AtomicAdd(m_camDx, dx);
        AtomicAdd(m_camDy, dy);
    }
    void ConsumeCameraDelta(float* dx, float* dy) {
        *dx = m_camDx.exchange(0.0f, std::memory_order_relaxed);
        *dy = m_camDy.exchange(0.0f, std::memory_order_relaxed);
    }

    void Reset() {
        m_buttons0.store(0, std::memory_order_relaxed);
        m_buttons1.store(0, std::memory_order_relaxed);
        SetStick(0.0f, 0.0f);
        m_camDx.store(0.0f, std::memory_order_relaxed);
        m_camDy.store(0.0f, std::memory_order_relaxed);
    }

private:
    static float ClampAxis(float v) { return v < -1.0f ? -1.0f : (v > 1.0f ? 1.0f : v); }
    static void AtomicAdd(std::atomic<float>& target, float delta) {
        float cur = target.load(std::memory_order_relaxed);
        while (!target.compare_exchange_weak(cur, cur + delta, std::memory_order_relaxed)) {
        }
    }

    std::atomic<uint64_t> m_buttons0{0};
    std::atomic<uint64_t> m_buttons1{0};
    std::atomic<float>    m_stickX{0.0f};
    std::atomic<float>    m_stickY{0.0f};
    std::atomic<float>    m_camDx{0.0f};
    std::atomic<float>    m_camDy{0.0f};
};

} // namespace ob
