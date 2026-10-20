// Host-side unit tests for the platform-free native core. Built and run by
// CI (and locally with any C++17 compiler), see Android/tools/run_host_tests.sh.
#include "core/Config.h"
#include "core/CpuFeatures.h"
#include "core/FramePacer.h"
#include "core/InputState.h"
#include "core/Math3d.h"

#include <cmath>
#include <cstdio>
#include <cstring>

static int g_failures = 0;
#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) {                                                    \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);   \
            ++g_failures;                                                 \
        }                                                                 \
    } while (0)

static bool Near(float a, float b, float eps = 0.0001f) { return std::fabs(a - b) <= eps; }

int main() {
    using namespace ob;

    // ---- Config parsing -------------------------------------------------
    {
        EngineConfig c;
        const bool ok = EngineConfig::Parse(
            "{\"version\":1,\"resolutionScale\":0.75,\"dynamicResolution\":false,"
            "\"minResolutionScale\":0.5,\"fpsLimit\":30,\"vsync\":true,\"msaa\":4,"
            "\"textureQuality\":3,\"shadowQuality\":0,\"showFps\":false,"
            "\"haptics\":false,\"cameraSensitivity\":1.6,\"invertCameraY\":true,"
            "\"preset\":\"quality\",\"unknownKey\":123}", &c);
        CHECK(ok);
        CHECK(c.version == 1);
        CHECK(!c.dynamicResolution);
        CHECK(!c.showFps && !c.haptics && c.invertCameraY);
        CHECK(c.shadowQuality == 0);
        CHECK(Near(c.cameraSensitivity, 1.6f));
        CHECK(Near(c.resolutionScale, 0.75f));
        CHECK(c.fpsLimit == 30);
        CHECK(c.msaa == 4);
        CHECK(c.preset == "quality");
    }
    {
        EngineConfig c; // garbage must keep defaults, never crash
        const bool ok = EngineConfig::Parse("not json at all {{{", &c);
        CHECK(!ok);
        CHECK(Near(c.resolutionScale, 0.85f));
        CHECK(c.fpsLimit == 60);
    }
    {
        EngineConfig c; // out-of-range values are clamped
        EngineConfig::Parse("{\"resolutionScale\":9.5,\"fpsLimit\":-3,\"msaa\":7,"
                            "\"cameraSensitivity\":99,\"textureQuality\":42}", &c);
        CHECK(Near(c.resolutionScale, 1.0f));
        CHECK(c.fpsLimit == 0);
        CHECK(c.msaa == 4);
        CHECK(Near(c.cameraSensitivity, 2.5f));
        CHECK(c.textureQuality == 3);
        CHECK(c.minResolutionScale <= c.resolutionScale);
    }
    {
        EngineConfig c; // null out is rejected
        CHECK(!EngineConfig::Parse("{}", nullptr));
        CHECK(!EngineConfig::LoadFromFile("/nonexistent/openbarnyard.json", &c));
    }

    // ---- InputState -------------------------------------------------------
    {
        InputState in;
        CHECK(!in.IsDown(kCmdJump));
        in.SetButton(kCmdJump, true);
        in.SetButton(kCmdHorn, true); // id 80 → second bitmask word
        CHECK(in.IsDown(kCmdJump));
        CHECK(in.IsDown(kCmdHorn));
        CHECK(!in.IsDown(kCmdInteract));
        in.SetButton(kCmdJump, false);
        CHECK(!in.IsDown(kCmdJump));
        CHECK(in.IsDown(kCmdHorn));
        in.SetButton(0, true);        // invalid ids are ignored
        in.SetButton(9999, true);
        in.SetStick(2.0f, -2.0f);     // axes are clamped
        CHECK(Near(in.StickX(), 1.0f));
        CHECK(Near(in.StickY(), -1.0f));
        in.AddCameraDelta(10.0f, 5.0f);
        in.AddCameraDelta(-4.0f, 2.0f);
        float dx = 0, dy = 0;
        in.ConsumeCameraDelta(&dx, &dy);
        CHECK(Near(dx, 6.0f));
        CHECK(Near(dy, 7.0f));
        in.ConsumeCameraDelta(&dx, &dy); // consumed → zero
        CHECK(Near(dx, 0.0f) && Near(dy, 0.0f));
        in.Reset();
        CHECK(!in.IsDown(kCmdHorn));
    }

    // Command ids must match AInputMap.h exactly.
    CHECK(kCmdJump == 57);
    CHECK(kCmdInteract == 53);
    CHECK(kCmdInventory == 75);
    CHECK(kCmdMenu == 51);
    CHECK(kCmdSpeed == 58);

    // ---- FramePacer / dynamic resolution ----------------------------------
    {
        CHECK(ComputeSleepMicros(0, 0, 0) == 0);          // unlimited
        CHECK(ComputeSleepMicros(0, 0, 60) > 15000);      // ~16.6ms budget
        CHECK(ComputeSleepMicros(0, 16666, 60) == 0);     // budget spent
        CHECK(ComputeSleepMicros(1000, 1000, 30) > 31000);
    }
    {
        DynamicResolution dr;
        dr.Configure(1.0f, 0.5f, 60, true);
        float scale = 1.0f;
        for (int i = 0; i < 600; ++i) scale = dr.Update(1.0f / 30.0f); // stuck at 30fps
        CHECK(scale < 0.99f);          // must scale down…
        CHECK(scale >= 0.5f - 0.001f); // …but never below the floor
        for (int i = 0; i < 1200; ++i) scale = dr.Update(1.0f / 240.0f); // tons of headroom
        CHECK(scale > 0.95f);          // recovers towards user scale
        dr.Configure(0.8f, 0.5f, 60, false); // disabled → pinned
        CHECK(Near(dr.Update(1.0f / 20.0f), 0.8f));
        dr.Reset();
        CHECK(dr.AverageFps() == 0.0f);
        dr.Update(1.0f / 60.0f);
        CHECK(dr.AverageFps() > 59.0f && dr.AverageFps() < 61.0f);
        CHECK(Near(dr.Update(-1.0f), dr.CurrentScale())); // invalid input ignored
        CHECK(Near(dr.Update(10.0f), dr.CurrentScale())); // pause/hitch ignored
    }

    // ---- Math --------------------------------------------------------------
    {
        const Mat4 a = Mat4::Perspective(1.2f, 16.0f / 9.0f, 0.1f, 100.0f);
        const Mat4 b = Mat4::LookAt({1, 2, 3}, {0, 0, 0}, {0, 1, 0});
        const Mat4 s = MultiplyScalar(a, b);
        const Mat4 d = g_mat4Multiply(a, b); // dispatched path must agree
        for (int i = 0; i < 16; ++i) CHECK(Near(s.m[i], d.m[i], 0.001f));

        const Mat4 t = Mat4::Translation(5, -2, 7) * Mat4::Scaling(2, 2, 2);
        const Vec3 p = t.TransformPoint({1, 1, 1});
        CHECK(Near(p.x, 7) && Near(p.y, 0) && Near(p.z, 9));

        const Mat4 id = Mat4::Identity() * b;
        for (int i = 0; i < 16; ++i) CHECK(Near(id.m[i], b.m[i]));

        // NOTE: operator* on host without NEON goes through scalar dispatch,
        // on ARM CI runners it exercises the NEON path against the same checks.
        const Vec3 v = Cross({1, 0, 0}, {0, 1, 0});
        CHECK(Near(v.z, 1.0f));
        CHECK(Near(Dot({1, 2, 3}, {4, 5, 6}), 32.0f));
    }

    // ---- CPU features --------------------------------------------------------
    {
        const CpuFeatures& f = GetCpuFeatures();
        CHECK(f.coreCount >= 1);
        CHECK(std::strlen(f.abi) > 0);
        const std::string desc = DescribeCpu(f);
        CHECK(desc.find(f.abi) != std::string::npos);
        const std::string bench = BenchmarkMat4();
        CHECK(bench.find("Mat4") != std::string::npos);
        std::printf("CPU: %s\n%s\n", desc.c_str(), bench.c_str());
    }

    if (g_failures == 0) std::printf("ALL CORE TESTS PASSED\n");
    else std::printf("%d CORE TEST(S) FAILED\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
