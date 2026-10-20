package org.opentoshi.openbarnyard.core;

import java.util.Map;

/**
 * Graphics/performance configuration. The launcher owns this object, writes
 * it to {@code openbarnyard.json}; the native core parses the same schema
 * (cpp/core/Config.cpp). Field names in JSON are part of the contract —
 * change both sides together.
 */
public final class EngineConfig {
    public static final String PRESET_POTATO   = "potato";
    public static final String PRESET_BALANCED = "balanced";
    public static final String PRESET_QUALITY  = "quality";
    public static final String PRESET_ULTRA    = "ultra";
    public static final String PRESET_AUTO     = "auto";
    public static final String PRESET_CUSTOM   = "custom";

    public float resolutionScale = 0.85f;
    public boolean dynamicResolution = true;
    public float minResolutionScale = 0.50f;
    public int fpsLimit = 60;              // 0 = unlimited
    public boolean vsync = true;
    public int msaa = 2;                   // 0 / 2 / 4
    public int textureQuality = 1;         // 0..3
    public int shadowQuality = 1;          // 0..3
    public boolean showFps = true;
    public boolean haptics = true;
    public float cameraSensitivity = 1.0f;
    public boolean invertCameraY = false;
    public String preset = PRESET_BALANCED;

    public void sanitize() {
        resolutionScale = clamp(resolutionScale, 0.4f, 1.0f);
        minResolutionScale = clamp(minResolutionScale, 0.3f, resolutionScale);
        if (fpsLimit < 0 || fpsLimit > 240) fpsLimit = 60;
        msaa = msaa >= 4 ? 4 : (msaa >= 2 ? 2 : 0);
        textureQuality = clamp(textureQuality, 0, 3);
        shadowQuality = clamp(shadowQuality, 0, 3);
        cameraSensitivity = clamp(cameraSensitivity, 0.3f, 2.5f);
        if (preset == null) preset = PRESET_CUSTOM;
    }

    /** Overwrites the performance fields from a named preset (not auto/custom). */
    public void applyPreset(String p) {
        if (p == null) return;
        preset = p;
        if (PRESET_POTATO.equals(p)) {
            resolutionScale = 0.60f; minResolutionScale = 0.40f;
            dynamicResolution = true; fpsLimit = 30; msaa = 0;
            textureQuality = 0; shadowQuality = 0;
        } else if (PRESET_BALANCED.equals(p)) {
            resolutionScale = 0.85f; minResolutionScale = 0.50f;
            dynamicResolution = true; fpsLimit = 60; msaa = 2;
            textureQuality = 1; shadowQuality = 1;
        } else if (PRESET_QUALITY.equals(p)) {
            resolutionScale = 0.95f; minResolutionScale = 0.65f;
            dynamicResolution = true; fpsLimit = 60; msaa = 4;
            textureQuality = 2; shadowQuality = 2;
        } else if (PRESET_ULTRA.equals(p)) {
            resolutionScale = 1.0f; minResolutionScale = 0.75f;
            dynamicResolution = true; fpsLimit = 120; msaa = 4;
            textureQuality = 3; shadowQuality = 3;
        }
        sanitize();
    }

    /**
     * "Авто" heuristic: picks a preset from the device's RAM, core count,
     * GLES level and bitness, then looks at the display refresh so a
     * 120 Hz flagship is not capped at 60 while a 1 GB 32-bit phone gets
     * the potato treatment instead of a slideshow.
     */
    public static EngineConfig autoQuality(long totalRamMb, int cores,
                                           int glesMajor, int glesMinor,
                                           boolean is64Bit, float refreshHz) {
        int score = 0;
        if (totalRamMb >= 8000) score += 5;
        else if (totalRamMb >= 6000) score += 4;
        else if (totalRamMb >= 4000) score += 3;
        else if (totalRamMb >= 3000) score += 2;
        else if (totalRamMb >= 2000) score += 1;
        if (cores >= 8) score += 1;
        if (glesMajor >= 3 && glesMinor >= 2) score += 1;
        else if (glesMajor < 3) score -= 2;
        if (!is64Bit) score -= 1;

        EngineConfig c = new EngineConfig();
        String chosen;
        if (score <= 1) chosen = PRESET_POTATO;
        else if (score <= 3) chosen = PRESET_BALANCED;
        else if (score <= 5) chosen = PRESET_QUALITY;
        else chosen = PRESET_ULTRA;
        c.applyPreset(chosen);
        c.preset = PRESET_AUTO;

        // Respect high-refresh panels on capable devices.
        if (refreshHz >= 119 && (PRESET_QUALITY.equals(chosen) || PRESET_ULTRA.equals(chosen))) {
            c.fpsLimit = 120;
        } else if (refreshHz >= 89 && PRESET_ULTRA.equals(chosen)) {
            c.fpsLimit = 90;
        }
        c.sanitize();
        return c;
    }

    // ------------------------------------------------------------ JSON I/O

    public String toJson() {
        sanitize();
        StringBuilder b = new StringBuilder(384);
        b.append("{\n");
        b.append("  \"version\": 1,\n");
        b.append("  \"resolutionScale\": ").append(Json.num(resolutionScale)).append(",\n");
        b.append("  \"dynamicResolution\": ").append(dynamicResolution).append(",\n");
        b.append("  \"minResolutionScale\": ").append(Json.num(minResolutionScale)).append(",\n");
        b.append("  \"fpsLimit\": ").append(fpsLimit).append(",\n");
        b.append("  \"vsync\": ").append(vsync).append(",\n");
        b.append("  \"msaa\": ").append(msaa).append(",\n");
        b.append("  \"textureQuality\": ").append(textureQuality).append(",\n");
        b.append("  \"shadowQuality\": ").append(shadowQuality).append(",\n");
        b.append("  \"showFps\": ").append(showFps).append(",\n");
        b.append("  \"haptics\": ").append(haptics).append(",\n");
        b.append("  \"cameraSensitivity\": ").append(Json.num(cameraSensitivity)).append(",\n");
        b.append("  \"invertCameraY\": ").append(invertCameraY).append(",\n");
        b.append("  \"preset\": \"").append(Json.escape(preset)).append("\"\n");
        b.append("}\n");
        return b.toString();
    }

    public static EngineConfig fromJson(String text) {
        EngineConfig c = new EngineConfig();
        if (text == null) return c;
        try {
            Map<String, Object> m = Json.parseObject(text);
            c.resolutionScale = (float) Json.getDouble(m, "resolutionScale", c.resolutionScale);
            c.dynamicResolution = Json.getBool(m, "dynamicResolution", c.dynamicResolution);
            c.minResolutionScale = (float) Json.getDouble(m, "minResolutionScale", c.minResolutionScale);
            c.fpsLimit = Json.getInt(m, "fpsLimit", c.fpsLimit);
            c.vsync = Json.getBool(m, "vsync", c.vsync);
            c.msaa = Json.getInt(m, "msaa", c.msaa);
            c.textureQuality = Json.getInt(m, "textureQuality", c.textureQuality);
            c.shadowQuality = Json.getInt(m, "shadowQuality", c.shadowQuality);
            c.showFps = Json.getBool(m, "showFps", c.showFps);
            c.haptics = Json.getBool(m, "haptics", c.haptics);
            c.cameraSensitivity = (float) Json.getDouble(m, "cameraSensitivity", c.cameraSensitivity);
            c.invertCameraY = Json.getBool(m, "invertCameraY", c.invertCameraY);
            c.preset = Json.getString(m, "preset", c.preset);
        } catch (Json.ParseException ignored) {
            // Corrupted config → defaults; the launcher rewrites the file.
        }
        c.sanitize();
        return c;
    }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
