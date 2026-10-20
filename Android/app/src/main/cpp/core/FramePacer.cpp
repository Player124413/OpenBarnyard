#include "FramePacer.h"

namespace ob {
namespace {
float ClampF(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
constexpr float kStepDown      = 0.08f;  // react fast when too slow…
constexpr float kStepUp        = 0.04f;  // …recover slowly to avoid oscillation
constexpr float kChangeEverySec = 0.40f; // min seconds between scale changes
constexpr float kHeadroom      = 0.90f;  // aim at 90% of the frame budget
} // namespace

void DynamicResolution::Configure(float userScale, float minScale, int targetFps, bool enabled) {
    m_userScale = ClampF(userScale, 0.4f, 1.0f);
    m_minScale  = ClampF(minScale, 0.3f, m_userScale);
    const int fps = (targetFps > 0) ? targetFps : 60;
    m_targetSec = 1.0f / static_cast<float>(fps);
    m_enabled   = enabled;
    if (!m_enabled) m_scale = m_userScale;
    else m_scale = ClampF(m_scale, m_minScale, m_userScale);
}

void DynamicResolution::Reset() {
    m_avgSeconds  = 0.0f;
    m_sinceChange = 0.0f;
    m_scale       = m_userScale;
}

float DynamicResolution::Update(float frameSeconds) {
    if (frameSeconds <= 0.0f || frameSeconds > 0.5f) return m_scale; // ignore hitches/pauses
    if (m_avgSeconds <= 0.0f) m_avgSeconds = frameSeconds;
    else m_avgSeconds += (frameSeconds - m_avgSeconds) * 0.05f; // EMA, ~20-frame window

    if (!m_enabled) { m_scale = m_userScale; return m_scale; }

    m_sinceChange += frameSeconds;
    if (m_sinceChange < kChangeEverySec) return m_scale;

    const float budget = m_targetSec * kHeadroom;
    if (m_avgSeconds > m_targetSec && m_scale > m_minScale) {
        m_scale = ClampF(m_scale - kStepDown, m_minScale, m_userScale);
        m_sinceChange = 0.0f;
    } else if (m_avgSeconds < budget && m_scale < m_userScale) {
        m_scale = ClampF(m_scale + kStepUp, m_minScale, m_userScale);
        m_sinceChange = 0.0f;
    }
    return m_scale;
}

int64_t ComputeSleepMicros(int64_t frameStartMicros, int64_t nowMicros, int fpsLimit) {
    if (fpsLimit <= 0) return 0;
    const int64_t budget = 1000000ll / fpsLimit;
    const int64_t spent  = nowMicros - frameStartMicros;
    const int64_t remain = budget - spent;
    // Sleep a little less than the remainder; the vsync swap absorbs the rest
    // and this avoids oversleeping a whole refresh on coarse timers.
    if (remain <= 1500) return 0;
    return remain - 1000;
}

} // namespace ob
