#pragma once
// OpenBarnyard Android — frame pacing + dynamic resolution controller.
//
// The controller watches an exponential moving average of GPU frame time and
// nudges the internal render scale up/down so weak GPUs (Mali-400 class,
// 32-bit phones) hold a stable frame rate while strong ones (Adreno 7xx,
// Apple-class Android flagships) render at full quality. Hysteresis and a
// minimum interval between changes prevent visible "breathing".
#include <cstdint>

namespace ob {

class DynamicResolution {
public:
    // targetFps<=0 means "use 60 as the internal target for scaling only".
    void Configure(float userScale, float minScale, int targetFps, bool enabled);
    void Reset();

    // Feed one CPU-measured frame duration (seconds, present-to-present).
    // Returns the render scale to use for the NEXT frame.
    float Update(float frameSeconds);

    float CurrentScale() const { return m_scale; }
    float AverageFps() const { return m_avgSeconds > 0.0f ? 1.0f / m_avgSeconds : 0.0f; }

private:
    float m_userScale = 0.85f;
    float m_minScale  = 0.50f;
    float m_scale     = 0.85f;
    float m_targetSec = 1.0f / 60.0f;
    float m_avgSeconds = 0.0f;
    float m_sinceChange = 0.0f;
    bool  m_enabled = true;
};

// Computes how long the render thread should sleep to honour an FPS cap
// when vsync alone is not enough (e.g. 30 FPS cap on a 120 Hz panel).
// Pure arithmetic — unit tested on the host.
int64_t ComputeSleepMicros(int64_t frameStartMicros, int64_t nowMicros, int fpsLimit);

} // namespace ob
