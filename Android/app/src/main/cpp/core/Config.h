#pragma once
// OpenBarnyard Android — engine configuration shared between the Java launcher
// and the native core. The launcher serialises user settings to
// "openbarnyard.json" (see EngineConfig.java, which is the writer), the native
// side parses it here. The parser is deliberately tiny and dependency-free: it
// understands exactly the flat key/value schema the launcher emits, tolerates
// unknown keys and never crashes on malformed input (falls back to defaults).
#include <string>

namespace ob {

struct EngineConfig {
    int   version            = 1;
    float resolutionScale    = 0.85f;  // 0.4 .. 1.0 (fraction of display res)
    bool  dynamicResolution  = true;   // auto-adjust scale to hold target FPS
    float minResolutionScale = 0.50f;  // floor for dynamic resolution
    int   fpsLimit           = 60;     // 0 = unlimited
    bool  vsync              = true;
    int   msaa               = 2;      // 0, 2 or 4
    int   textureQuality     = 1;      // 0 low .. 3 ultra
    int   shadowQuality      = 1;      // 0 off .. 3 high
    bool  showFps            = true;
    bool  haptics            = true;
    float cameraSensitivity  = 1.0f;   // 0.3 .. 2.5
    bool  invertCameraY      = false;
    std::string preset       = "balanced";

    // Clamp every field into its documented range. Called after parsing so a
    // hand-edited or corrupted config can never produce insane GPU settings.
    void Sanitize();

    // Parse the flat JSON object emitted by the launcher. Returns true if at
    // least one known key was found. Unknown/missing keys keep defaults.
    static bool Parse(const std::string& json, EngineConfig* out);

    // Load from a file path. Returns false (keeping defaults) if unreadable.
    static bool LoadFromFile(const std::string& path, EngineConfig* out);
};

} // namespace ob
