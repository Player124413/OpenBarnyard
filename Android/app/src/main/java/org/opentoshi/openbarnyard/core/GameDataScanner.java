package org.opentoshi.openbarnyard.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Heuristic check of a user-supplied Barnyard (PC) installation folder.
 *
 * <p>The engine loads its content from a {@code Data/} tree: model/state
 * libraries are {@code .trb} (Toshi Resource Bundle, see
 * {@code AAssetLoader}: {@code Data/<lib>.trb}), texture libraries are
 * {@code .ttl}, keyframe libraries {@code .tkl}. This scanner only ever
 * looks at relative path names (the Android layer does the walking), which
 * keeps it pure and unit-testable. It never blocks playing;it grades:
 * empty → suspicious/partial → looks like a real installation.
 */
public final class GameDataScanner {
    private GameDataScanner() {}

    public enum Verdict { EMPTY, NO_GAME_DATA, PARTIAL, LOOKS_COMPLETE }

    public enum Hint {
        MISSING_DATA_DIR,     // no Data/ tree at the chosen root
        FEW_TRB_FILES,        // some .trb but far fewer than a real install
        MISSING_TEXTURE_LIBS, // no .ttl anywhere
        HAS_PC_EXECUTABLE,    // Barnyard.exe present (good sign, unused on Android)
        NESTED_INSTALL        // data found one level deeper than the chosen root
    }

    public static final class Result {
        public Verdict verdict = Verdict.EMPTY;
        public int totalFiles;
        public int trbCount;
        public int ttlCount;
        public int tklCount;
        public int bikCount;
        public boolean hasDataDir;
        public boolean hasExe;
        /** Suggested root if the real install sits in a sub-folder, else "". */
        public String suggestedSubPath = "";
        public final List<Hint> hints = new ArrayList<Hint>();
    }

    /** A retail PC install ships hundreds of .trb libraries. */
    static final int TRB_THRESHOLD_COMPLETE = 100;
    static final int TRB_THRESHOLD_PARTIAL = 5;

    /**
     * @param relativePaths paths relative to the chosen root, using '/'
     *                      (either separator and any case are accepted).
     */
    public static Result analyse(List<String> relativePaths) {
        Result r = new Result();
        if (relativePaths == null || relativePaths.isEmpty()) {
            r.verdict = Verdict.EMPTY;
            r.hints.add(Hint.MISSING_DATA_DIR);
            return r;
        }
        int nestedTrb = 0;
        String nestedPrefix = "";
        for (String raw : relativePaths) {
            if (raw == null) continue;
            String path = raw.replace('\\', '/').toLowerCase(Locale.US);
            if (path.endsWith("/")) continue; // directory entries carry no info
            r.totalFiles++;
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (path.startsWith("data/") || path.contains("/data/")) r.hasDataDir = true;
            if (name.endsWith(".trb")) r.trbCount++;
            else if (name.endsWith(".ttl")) r.ttlCount++;
            else if (name.endsWith(".tkl")) r.tklCount++;
            else if (name.endsWith(".bik")) r.bikCount++;
            if (name.equals("barnyard.exe") || name.equals("barnyard_launcher.exe")) r.hasExe = true;

            // Detect "user picked the parent of the install folder":
            // e.g. chosen root contains "Barnyard/Data/..." — count .trb that
            // live below "<something>/data/" but not below plain "data/".
            int dataIdx = path.indexOf("/data/");
            if (dataIdx > 0 && name.endsWith(".trb")) {
                nestedTrb++;
                if (nestedPrefix.length() == 0) nestedPrefix = raw.substring(0, dataIdx);
            }
        }

        if (!r.hasDataDir) r.hints.add(Hint.MISSING_DATA_DIR);
        if (r.ttlCount == 0 && r.trbCount > 0) r.hints.add(Hint.MISSING_TEXTURE_LIBS);
        if (r.hasExe) r.hints.add(Hint.HAS_PC_EXECUTABLE);
        if (!pathStartsWithData(relativePaths) && nestedTrb >= TRB_THRESHOLD_PARTIAL) {
            r.suggestedSubPath = nestedPrefix;
            r.hints.add(Hint.NESTED_INSTALL);
        }

        if (r.trbCount == 0) {
            r.verdict = Verdict.NO_GAME_DATA;
        } else if (r.trbCount >= TRB_THRESHOLD_COMPLETE && r.hasDataDir) {
            r.verdict = Verdict.LOOKS_COMPLETE;
        } else if (r.trbCount >= TRB_THRESHOLD_PARTIAL) {
            r.verdict = Verdict.PARTIAL;
            r.hints.add(Hint.FEW_TRB_FILES);
        } else {
            r.verdict = Verdict.PARTIAL;
            r.hints.add(Hint.FEW_TRB_FILES);
        }
        return r;
    }

    private static boolean pathStartsWithData(List<String> relativePaths) {
        for (String raw : relativePaths) {
            if (raw == null) continue;
            String p = raw.replace('\\', '/').toLowerCase(Locale.US);
            if (p.startsWith("data/")) return true;
        }
        return false;
    }
}
