package org.opentoshi.openbarnyard.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plain-JVM tests for the launcher core (no JUnit dependency — CI runs this
 * with javac/java directly). Exit code 0 = all green.
 */
public final class CoreTests {
    private static int failures = 0;

    private static void check(boolean cond, String name) {
        if (!cond) {
            System.out.println("FAIL: " + name);
            failures++;
        }
    }

    private static void eqF(float a, float b, String name) {
        check(Math.abs(a - b) < 0.0001f, name + " (" + a + " != " + b + ")");
    }

    public static void main(String[] args) throws Exception {
        testJson();
        testGameCommand();
        testTouchProfile();
        testEngineConfig();
        testScanner();
        if (failures == 0) System.out.println("ALL JAVA CORE TESTS PASSED");
        else System.out.println(failures + " JAVA CORE TEST(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void testJson() throws Exception {
        Object v = Json.parse("{\"a\":1,\"b\":[true,null,\"x\\n\\u0436\"],\"c\":-2.5e2}");
        check(v instanceof java.util.Map, "json object");
        java.util.Map<String, Object> m = Json.parseObject("{\"a\":1}");
        check(Json.getInt(m, "a", 0) == 1, "json getInt");
        check(Json.getInt(m, "zz", 7) == 7, "json default");
        check(Json.num(0.85).equals("0.85"), "num 0.85 -> " + Json.num(0.85));
        check(Json.num(1.0).equals("1"), "num 1 -> " + Json.num(1.0));
        check(Json.num(0.5).equals("0.5"), "num 0.5");
        boolean threw = false;
        try { Json.parse("{bad json"); } catch (Json.ParseException e) { threw = true; }
        check(threw, "malformed json throws");
        threw = false;
        try { Json.parseObject("[1,2]"); } catch (Json.ParseException e) { threw = true; }
        check(threw, "parseObject rejects array");
        check(Json.escape("a\"b\\c").equals("a\\\"b\\\\c"), "escape");
    }

    private static void testGameCommand() {
        // Ids must match AInputMap.h exactly.
        check(GameCommand.JUMP.id == 57, "jump id");
        check(GameCommand.INTERACT.id == 53, "interact id");
        check(GameCommand.SPEED.id == 58, "speed id");
        check(GameCommand.MENU.id == 51, "menu id");
        check(GameCommand.INVENTORY.id == 75, "inventory id");
        check(GameCommand.HORN.id == 80, "horn id");
        check(GameCommand.byId(57) == GameCommand.JUMP, "byId");
        check(GameCommand.byId(9999) == null, "byId unknown");
        check(GameCommand.bindable().size() >= 20, "bindable list");
        // No duplicate ids.
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        for (GameCommand c : GameCommand.values()) check(seen.add(c.id), "unique id " + c.id);
        check(GameCommand.JUMP.label(true).length() > 0, "ru label");
    }

    private static void testTouchProfile() throws Exception {
        TouchProfile p = TouchProfile.defaultProfile();
        check(p.elements.size() >= 9, "default element count");
        check(p.findById("stick") != null, "default has stick");
        check(p.findById("jump") != null, "default has jump");
        check(p.findById("jump").command == 57, "jump bound");

        // JSON round trip must be lossless.
        String json = p.toJson();
        TouchProfile q = TouchProfile.fromJson(json);
        check(q.elements.size() == p.elements.size(), "round trip count");
        check(q.name.equals(p.name), "round trip name");
        TouchProfile.Element a = p.findById("jump"), b = q.findById("jump");
        eqF(a.x, b.x, "round trip x");
        eqF(a.size, b.size, "round trip size");
        check(a.command == b.command, "round trip command");
        check(b.displayLabel().equals("A"), "jump short label: " + b.displayLabel());

        // Mirroring twice returns to the original geometry.
        TouchProfile m = p.copy("M");
        float before = m.findById("jump").x;
        m.mirrorHorizontally();
        eqF(m.findById("jump").x, 1.0f - before, "mirror");
        check(m.leftHanded, "mirror flips handedness");
        m.mirrorHorizontally();
        eqF(m.findById("jump").x, before, "mirror twice");

        // Garbage in → clamped, never a crash.
        TouchProfile bad = TouchProfile.fromJson(
            "{\"name\":\"\",\"elements\":[{\"id\":\"x\",\"type\":\"button\",\"command\":57,"
            + "\"x\":5,\"y\":-2,\"size\":9,\"opacity\":0}]}");
        TouchProfile.Element e = bad.findById("x");
        check(e.x >= 0 && e.x <= 1, "clamp x");
        check(e.size <= 0.45f, "clamp size");
        check(e.opacity >= 0.1f, "clamp opacity");
        check(bad.name.length() > 0, "empty name replaced");

        boolean threw = false;
        try { TouchProfile.fromJson("{{{"); } catch (Json.ParseException ex) { threw = true; }
        check(threw, "malformed profile throws");
    }

    private static void testEngineConfig() {
        EngineConfig potato = new EngineConfig();
        potato.applyPreset(EngineConfig.PRESET_POTATO);
        check(potato.fpsLimit == 30 && potato.msaa == 0, "potato preset");
        eqF(potato.resolutionScale, 0.6f, "potato scale");

        EngineConfig ultra = new EngineConfig();
        ultra.applyPreset(EngineConfig.PRESET_ULTRA);
        eqF(ultra.resolutionScale, 1.0f, "ultra scale");
        check(ultra.textureQuality == 3, "ultra textures");

        // Auto: weak 32-bit 1.5GB GLES2 phone → potato-ish settings.
        EngineConfig weak = EngineConfig.autoQuality(1500, 4, 2, 0, false, 60);
        check(weak.fpsLimit <= 60, "weak capped");
        check(weak.resolutionScale <= 0.85f, "weak scale modest: " + weak.resolutionScale);
        check(weak.msaa == 0, "weak no msaa");
        check(EngineConfig.PRESET_AUTO.equals(weak.preset), "weak preset marker");

        // Auto: flagship 12GB, GLES 3.2, 120Hz → ultra with 120 cap.
        EngineConfig strong = EngineConfig.autoQuality(12000, 8, 3, 2, true, 120);
        check(strong.fpsLimit == 120, "strong 120hz: " + strong.fpsLimit);
        eqF(strong.resolutionScale, 1.0f, "strong full res");

        // Midrange stays sane.
        EngineConfig mid = EngineConfig.autoQuality(4000, 8, 3, 1, true, 90);
        check(mid.resolutionScale >= 0.85f, "mid decent: " + mid.resolutionScale);

        // JSON round trip + contract keys for the native parser.
        String json = strong.toJson();
        String[] keys = {"resolutionScale", "dynamicResolution", "minResolutionScale",
            "fpsLimit", "vsync", "msaa", "textureQuality", "shadowQuality", "showFps",
            "haptics", "cameraSensitivity", "invertCameraY", "preset"};
        for (String k : keys) check(json.contains("\"" + k + "\""), "json has " + k);
        EngineConfig back = EngineConfig.fromJson(json);
        eqF(back.resolutionScale, strong.resolutionScale, "cfg round trip scale");
        check(back.fpsLimit == strong.fpsLimit, "cfg round trip fps");
        EngineConfig garbage = EngineConfig.fromJson("###");
        eqF(garbage.resolutionScale, 0.85f, "garbage config -> defaults");
    }

    private static void testScanner() {
        List<String> empty = new ArrayList<String>();
        check(GameDataScanner.analyse(empty).verdict == GameDataScanner.Verdict.EMPTY, "empty verdict");
        check(GameDataScanner.analyse(null).verdict == GameDataScanner.Verdict.EMPTY, "null verdict");

        List<String> photos = Arrays.asList("DCIM/a.jpg", "music/song.mp3");
        check(GameDataScanner.analyse(photos).verdict == GameDataScanner.Verdict.NO_GAME_DATA, "photos verdict");

        List<String> full = new ArrayList<String>();
        full.add("Barnyard.exe");
        for (int i = 0; i < 150; i++) full.add("Data/lib" + i + ".trb");
        for (int i = 0; i < 40; i++) full.add("Data/textures/t" + i + ".ttl");
        GameDataScanner.Result rf = GameDataScanner.analyse(full);
        check(rf.verdict == GameDataScanner.Verdict.LOOKS_COMPLETE, "full install verdict");
        check(rf.trbCount == 150 && rf.ttlCount == 40, "counts");
        check(rf.hints.contains(GameDataScanner.Hint.HAS_PC_EXECUTABLE), "exe hint");

        List<String> partial = Arrays.asList("Data/a.trb", "Data/b.trb", "Data/c.trb");
        GameDataScanner.Result rp = GameDataScanner.analyse(partial);
        check(rp.verdict == GameDataScanner.Verdict.PARTIAL, "partial verdict");
        check(rp.hints.contains(GameDataScanner.Hint.MISSING_TEXTURE_LIBS), "ttl hint");

        // Windows separators + case insensitivity + nested install detection.
        List<String> nested = new ArrayList<String>();
        for (int i = 0; i < 120; i++) nested.add("Barnyard\\DATA\\L" + i + ".TRB");
        GameDataScanner.Result rn = GameDataScanner.analyse(nested);
        check(rn.trbCount == 120, "nested counts trb");
        check(rn.hints.contains(GameDataScanner.Hint.NESTED_INSTALL), "nested hint");
        check(rn.suggestedSubPath.length() > 0, "nested suggestion: " + rn.suggestedSubPath);
    }
}
