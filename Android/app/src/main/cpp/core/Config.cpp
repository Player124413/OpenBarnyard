#include "Config.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>

namespace ob {
namespace {

float ClampF(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
int   ClampI(int v, int lo, int hi)       { return v < lo ? lo : (v > hi ? hi : v); }

// Find `"key" :` in a flat JSON object; returns position just past ':' or npos.
size_t FindValue(const std::string& json, const char* key) {
    const std::string needle = std::string("\"") + key + "\"";
    size_t pos = json.find(needle);
    if (pos == std::string::npos) return pos;
    pos = json.find(':', pos + needle.size());
    if (pos == std::string::npos) return pos;
    ++pos;
    while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos]))) ++pos;
    return pos;
}

bool ReadFloat(const std::string& json, const char* key, float* out) {
    const size_t pos = FindValue(json, key);
    if (pos == std::string::npos || pos >= json.size()) return false;
    char* end = nullptr;
    const double v = std::strtod(json.c_str() + pos, &end);
    if (end == json.c_str() + pos) return false;
    *out = static_cast<float>(v);
    return true;
}

bool ReadInt(const std::string& json, const char* key, int* out) {
    float f;
    if (!ReadFloat(json, key, &f)) return false;
    *out = static_cast<int>(f);
    return true;
}

bool ReadBool(const std::string& json, const char* key, bool* out) {
    const size_t pos = FindValue(json, key);
    if (pos == std::string::npos) return false;
    if (json.compare(pos, 4, "true") == 0)  { *out = true;  return true; }
    if (json.compare(pos, 5, "false") == 0) { *out = false; return true; }
    return false;
}

bool ReadString(const std::string& json, const char* key, std::string* out) {
    const size_t pos = FindValue(json, key);
    if (pos == std::string::npos || pos >= json.size() || json[pos] != '"') return false;
    const size_t end = json.find('"', pos + 1);
    if (end == std::string::npos) return false;
    *out = json.substr(pos + 1, end - pos - 1);
    return true;
}

} // namespace

void EngineConfig::Sanitize() {
    resolutionScale    = ClampF(resolutionScale, 0.4f, 1.0f);
    minResolutionScale = ClampF(minResolutionScale, 0.3f, resolutionScale);
    fpsLimit           = (fpsLimit < 0) ? 0 : ClampI(fpsLimit, 0, 240);
    msaa               = (msaa >= 4) ? 4 : (msaa >= 2 ? 2 : 0);
    textureQuality     = ClampI(textureQuality, 0, 3);
    shadowQuality      = ClampI(shadowQuality, 0, 3);
    cameraSensitivity  = ClampF(cameraSensitivity, 0.3f, 2.5f);
    if (preset != "potato" && preset != "balanced" && preset != "quality" &&
        preset != "ultra" && preset != "auto" && preset != "custom") {
        preset = "custom";
    }
}

bool EngineConfig::Parse(const std::string& json, EngineConfig* out) {
    if (out == nullptr) return false;
    bool any = false;
    any |= ReadInt(json, "version", &out->version);
    any |= ReadFloat(json, "resolutionScale", &out->resolutionScale);
    any |= ReadBool(json, "dynamicResolution", &out->dynamicResolution);
    any |= ReadFloat(json, "minResolutionScale", &out->minResolutionScale);
    any |= ReadInt(json, "fpsLimit", &out->fpsLimit);
    any |= ReadBool(json, "vsync", &out->vsync);
    any |= ReadInt(json, "msaa", &out->msaa);
    any |= ReadInt(json, "textureQuality", &out->textureQuality);
    any |= ReadInt(json, "shadowQuality", &out->shadowQuality);
    any |= ReadBool(json, "showFps", &out->showFps);
    any |= ReadBool(json, "haptics", &out->haptics);
    any |= ReadFloat(json, "cameraSensitivity", &out->cameraSensitivity);
    any |= ReadBool(json, "invertCameraY", &out->invertCameraY);
    any |= ReadString(json, "preset", &out->preset);
    out->Sanitize();
    return any;
}

bool EngineConfig::LoadFromFile(const std::string& path, EngineConfig* out) {
    if (out == nullptr) return false;
    FILE* f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) return false;
    std::string data;
    char buf[4096];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) data.append(buf, n);
    std::fclose(f);
    if (data.empty() || data.size() > (1u << 20)) return false; // sanity cap 1 MiB
    return Parse(data, out);
}

} // namespace ob
