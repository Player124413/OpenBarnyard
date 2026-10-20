package org.opentoshi.openbarnyard.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A complete, user-editable on-screen control layout.
 *
 * <p>Coordinates are <b>normalised</b>: element centres are fractions of the
 * screen size (0..1), a button/stick {@code size} is its diameter as a
 * fraction of screen <i>height</i> (so buttons stay round on any aspect
 * ratio). A profile designed on a 16:9 phone therefore looks right on a
 * 21:9 phone or a 4:3 tablet without re-editing.
 *
 * <p>Profiles are stored as human-readable JSON — users can share them as
 * files, and the format is versioned ({@link #FORMAT_VERSION}).
 */
public final class TouchProfile {
    public static final int FORMAT_VERSION = 1;

    public enum Type { STICK, BUTTON, CAMERA }

    public static final class Element {
        public String id;
        public Type type;
        /** AInputCommand id for BUTTONs; 0 otherwise. */
        public int command;
        public float x;        // centre, 0..1 of screen width
        public float y;        // centre, 0..1 of screen height
        public float size;     // STICK/BUTTON diameter, fraction of height
        public float w;        // CAMERA area width, fraction of screen width
        public float h;        // CAMERA area height, fraction of screen height
        public float opacity;  // 0.1..1 (CAMERA is drawn only in the editor)
        /** Optional label override; empty → use the command's short label. */
        public String label = "";

        public Element(String id, Type type, int command, float x, float y, float size, float opacity) {
            this.id = id;
            this.type = type;
            this.command = command;
            this.x = x;
            this.y = y;
            this.size = size;
            this.opacity = opacity;
            this.w = 0.5f;
            this.h = 0.8f;
        }

        public Element copy() {
            Element e = new Element(id, type, command, x, y, size, opacity);
            e.w = w; e.h = h; e.label = label;
            return e;
        }

        public String displayLabel() {
            if (label != null && label.length() > 0) return label;
            if (type == Type.STICK) return "";
            if (type == Type.CAMERA) return "";
            GameCommand c = GameCommand.byId(command);
            return c != null ? c.shortLabel : "?";
        }

        /** Clamp every field into a sane on-screen range. */
        public void sanitize() {
            x = clamp(x, 0.0f, 1.0f);
            y = clamp(y, 0.0f, 1.0f);
            size = clamp(size, 0.05f, 0.45f);
            w = clamp(w, 0.1f, 1.0f);
            h = clamp(h, 0.1f, 1.0f);
            opacity = clamp(opacity, 0.1f, 1.0f);
            if (label == null) label = "";
            if (id == null || id.length() == 0) id = "el";
        }
    }

    public String name;
    public boolean leftHanded;
    /** Stick starts where the thumb lands instead of at a fixed centre. */
    public boolean dynamicStick = true;
    public final List<Element> elements = new ArrayList<Element>();

    public TouchProfile(String name) {
        this.name = name != null ? name : "Profile";
    }

    public Element findById(String id) {
        for (Element e : elements) if (e.id.equals(id)) return e;
        return null;
    }

    public TouchProfile copy(String newName) {
        TouchProfile p = new TouchProfile(newName);
        p.leftHanded = leftHanded;
        p.dynamicStick = dynamicStick;
        for (Element e : elements) p.elements.add(e.copy());
        return p;
    }

    /** Horizontal mirror — instant right-handed ⇄ left-handed conversion. */
    public void mirrorHorizontally() {
        for (Element e : elements) e.x = 1.0f - e.x;
        leftHanded = !leftHanded;
    }

    public void sanitize() {
        if (name == null || name.trim().length() == 0) name = "Profile";
        // De-duplicate ids so editor lookups stay unambiguous.
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            e.sanitize();
            for (int j = 0; j < i; j++) {
                if (elements.get(j).id.equals(e.id)) e.id = e.id + "_" + i;
            }
        }
    }

    // ------------------------------------------------------------ defaults

    /** Sensible out-of-the-box layout modelled on the PC/console scheme. */
    public static TouchProfile defaultProfile() {
        TouchProfile p = new TouchProfile("Default");
        p.elements.add(new Element("camera", Type.CAMERA, 0, 0.74f, 0.44f, 0.3f, 0.25f));
        Element cam = p.elements.get(0);
        cam.w = 0.52f; cam.h = 0.88f;

        p.elements.add(new Element("stick", Type.STICK, 0, 0.13f, 0.68f, 0.30f, 0.55f));
        p.elements.add(btn("jump", GameCommand.JUMP, 0.905f, 0.70f, 0.20f));
        p.elements.add(btn("interact", GameCommand.INTERACT, 0.795f, 0.56f, 0.155f));
        p.elements.add(btn("use", GameCommand.USE, 0.93f, 0.44f, 0.145f));
        p.elements.add(btn("run", GameCommand.SPEED, 0.775f, 0.83f, 0.135f));
        p.elements.add(btn("context", GameCommand.CONTEXT1, 0.845f, 0.30f, 0.125f));
        p.elements.add(btn("menu", GameCommand.MENU, 0.962f, 0.095f, 0.10f));
        p.elements.add(btn("inventory", GameCommand.INVENTORY, 0.885f, 0.095f, 0.10f));
        p.elements.add(btn("map", GameCommand.ICONS, 0.808f, 0.095f, 0.10f));
        p.sanitize();
        return p;
    }

    private static Element btn(String id, GameCommand c, float x, float y, float size) {
        return new Element(id, Type.BUTTON, c.id, x, y, size, 0.6f);
    }

    // ------------------------------------------------------------ JSON I/O

    public String toJson() {
        StringBuilder b = new StringBuilder(512);
        b.append("{\n");
        b.append("  \"version\": ").append(FORMAT_VERSION).append(",\n");
        b.append("  \"name\": \"").append(Json.escape(name)).append("\",\n");
        b.append("  \"leftHanded\": ").append(leftHanded).append(",\n");
        b.append("  \"dynamicStick\": ").append(dynamicStick).append(",\n");
        b.append("  \"elements\": [\n");
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            b.append("    {\"id\": \"").append(Json.escape(e.id)).append("\"");
            b.append(", \"type\": \"").append(e.type.name().toLowerCase(Locale.US)).append("\"");
            b.append(", \"command\": ").append(e.command);
            b.append(", \"x\": ").append(Json.num(e.x));
            b.append(", \"y\": ").append(Json.num(e.y));
            b.append(", \"size\": ").append(Json.num(e.size));
            b.append(", \"w\": ").append(Json.num(e.w));
            b.append(", \"h\": ").append(Json.num(e.h));
            b.append(", \"opacity\": ").append(Json.num(e.opacity));
            b.append(", \"label\": \"").append(Json.escape(e.label)).append("\"}");
            if (i + 1 < elements.size()) b.append(',');
            b.append('\n');
        }
        b.append("  ]\n}\n");
        return b.toString();
    }

    /** @throws Json.ParseException on malformed input (callers fall back to defaults). */
    public static TouchProfile fromJson(String text) throws Json.ParseException {
        Map<String, Object> root = Json.parseObject(text);
        // Format version: unknown-newer versions are read best-effort (all
        // v1 fields are optional), so a newer launcher's profile still loads.
        int formatVersion = Json.getInt(root, "version", FORMAT_VERSION);
        if (formatVersion < 1) throw new Json.ParseException("bad profile version");
        TouchProfile p = new TouchProfile(Json.getString(root, "name", "Profile"));
        p.leftHanded = Json.getBool(root, "leftHanded", false);
        p.dynamicStick = Json.getBool(root, "dynamicStick", true);
        List<Object> arr = Json.getArray(root, "elements");
        if (arr != null) {
            for (Object o : arr) {
                Map<String, Object> m = Json.getObject(o);
                if (m == null) continue;
                String typeStr = Json.getString(m, "type", "button");
                Type type = Type.BUTTON;
                if ("stick".equalsIgnoreCase(typeStr)) type = Type.STICK;
                else if ("camera".equalsIgnoreCase(typeStr)) type = Type.CAMERA;
                Element e = new Element(
                    Json.getString(m, "id", "el"),
                    type,
                    Json.getInt(m, "command", 0),
                    (float) Json.getDouble(m, "x", 0.5),
                    (float) Json.getDouble(m, "y", 0.5),
                    (float) Json.getDouble(m, "size", 0.15),
                    (float) Json.getDouble(m, "opacity", 0.6));
                e.w = (float) Json.getDouble(m, "w", 0.5);
                e.h = (float) Json.getDouble(m, "h", 0.8);
                e.label = Json.getString(m, "label", "");
                p.elements.add(e);
            }
        }
        p.sanitize();
        return p;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
