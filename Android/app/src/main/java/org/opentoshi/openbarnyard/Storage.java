package org.opentoshi.openbarnyard;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.hardware.display.DisplayManager;

import org.opentoshi.openbarnyard.core.EngineConfig;
import org.opentoshi.openbarnyard.core.TouchProfile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** All file / preference access for the launcher, in one auditable place. */
public final class Storage {
    private static final String TAG = "OpenBarnyard";
    private static final String PREFS = "launcher";
    private static final String KEY_ACTIVE_PROFILE = "active_profile";
    private static final String KEY_DATA_URI = "game_data_uri";
    private static final String KEY_DATA_PATH = "game_data_path";
    private static final int MAX_WALK_FILES = 20000;

    private Storage() {}

    // ------------------------------------------------------------ config

    public static File configFile(Context ctx) {
        return new File(ctx.getFilesDir(), "openbarnyard.json");
    }

    public static EngineConfig loadConfig(Context ctx) {
        File f = configFile(ctx);
        String text = readText(f);
        if (text == null) {
            EngineConfig fresh = autoConfigForDevice(ctx);
            saveConfig(ctx, fresh);
            log(ctx, "Created default (auto) engine config");
            return fresh;
        }
        return EngineConfig.fromJson(text);
    }

    public static void saveConfig(Context ctx, EngineConfig cfg) {
        writeText(configFile(ctx), cfg.toJson());
    }

    public static EngineConfig autoConfigForDevice(Context ctx) {
        DeviceInfo d = deviceInfo(ctx);
        return EngineConfig.autoQuality(d.totalRamMb, d.cores, d.glesMajor,
            d.glesMinor, d.is64Bit, d.refreshHz);
    }

    // ------------------------------------------------------- touch profiles

    public static File profilesDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "touch_profiles");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static List<String> profileNames(Context ctx) {
        ensureDefaultProfile(ctx);
        List<String> names = new ArrayList<String>();
        File[] files = profilesDir(ctx).listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".json")) names.add(n.substring(0, n.length() - 5));
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    public static void ensureDefaultProfile(Context ctx) {
        File dir = profilesDir(ctx);
        File def = new File(dir, "Default.json");
        if (!def.exists()) writeText(def, TouchProfile.defaultProfile().toJson());
    }

    public static TouchProfile loadProfile(Context ctx, String name) {
        ensureDefaultProfile(ctx);
        String text = readText(new File(profilesDir(ctx), sanitizeName(name) + ".json"));
        if (text != null) {
            try {
                return TouchProfile.fromJson(text);
            } catch (Exception e) {
                Log.e(TAG, "Corrupt touch profile " + name, e);
                log(ctx, "Corrupt touch profile '" + name + "', using defaults");
            }
        }
        return TouchProfile.defaultProfile();
    }

    public static void saveProfile(Context ctx, TouchProfile p) {
        p.sanitize();
        writeText(new File(profilesDir(ctx), sanitizeName(p.name) + ".json"), p.toJson());
    }

    public static void deleteProfile(Context ctx, String name) {
        File f = new File(profilesDir(ctx), sanitizeName(name) + ".json");
        if (f.exists()) f.delete();
        if (name.equals(activeProfileName(ctx))) setActiveProfileName(ctx, "Default");
        ensureDefaultProfile(ctx);
    }

    public static String activeProfileName(Context ctx) {
        return prefs(ctx).getString(KEY_ACTIVE_PROFILE, "Default");
    }

    public static void setActiveProfileName(Context ctx, String name) {
        prefs(ctx).edit().putString(KEY_ACTIVE_PROFILE, name).apply();
    }

    public static TouchProfile loadActiveProfile(Context ctx) {
        return loadProfile(ctx, activeProfileName(ctx));
    }

    static String sanitizeName(String name) {
        if (name == null || name.trim().length() == 0) return "Profile";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '(' || c == ')') b.append(c);
            else b.append('_');
        }
        String out = b.toString().trim();
        return out.length() == 0 ? "Profile" : out;
    }

    // ------------------------------------------------------------ game data

    /** Default, permission-free drop location (visible over USB/file managers). */
    public static File defaultGameDataDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "GameData");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** User-picked SAF tree URI string, or "" if the default folder is used. */
    public static String gameDataUri(Context ctx) {
        return prefs(ctx).getString(KEY_DATA_URI, "");
    }

    public static void setGameDataUri(Context ctx, String uri) {
        prefs(ctx).edit().putString(KEY_DATA_URI, uri != null ? uri : "").apply();
    }

    /** Effective filesystem path for display/native (SAF mode reports the URI). */
    public static String gameDataPathForNative(Context ctx) {
        String uri = gameDataUri(ctx);
        if (uri.length() > 0) return uri;
        String custom = prefs(ctx).getString(KEY_DATA_PATH, "");
        if (custom.length() > 0) return custom;
        return defaultGameDataDir(ctx).getAbsolutePath();
    }

    public static void setGameDataPath(Context ctx, String path) {
        prefs(ctx).edit().putString(KEY_DATA_PATH, path != null ? path : "").apply();
    }

    /** Recursively lists relative file paths (with '/') under root, capped. */
    public static List<String> walkFiles(File root) {
        List<String> out = new ArrayList<String>();
        if (root != null && root.exists()) walkInto(root, "", out);
        return out;
    }

    private static void walkInto(File dir, String prefix, List<String> out) {
        if (out.size() >= MAX_WALK_FILES) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (out.size() >= MAX_WALK_FILES) return;
            String rel = prefix.length() == 0 ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) walkInto(child, rel, out);
            else out.add(rel);
        }
    }

    // ------------------------------------------------------------ device info

    public static final class DeviceInfo {
        public String model = "";
        public String soc = "";
        public String primaryAbi = "";
        public boolean is64Bit;
        public int cores = 1;
        public long totalRamMb;
        public int glesMajor = 2, glesMinor = 0;
        public float refreshHz = 60f;
        public int sdkInt;
    }

    public static DeviceInfo deviceInfo(Context ctx) {
        DeviceInfo d = new DeviceInfo();
        d.model = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        d.sdkInt = Build.VERSION.SDK_INT;
        if (Build.VERSION.SDK_INT >= 31) {
            try { d.soc = Build.SOC_MODEL; } catch (Throwable ignored) { d.soc = ""; }
        }
        if (d.soc == null || d.soc.length() == 0) d.soc = Build.HARDWARE != null ? Build.HARDWARE : "";
        String[] abis = Build.SUPPORTED_ABIS;
        d.primaryAbi = (abis != null && abis.length > 0) ? abis[0] : "";
        d.is64Bit = Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0
            && d.primaryAbi.contains("64");
        d.cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                d.totalRamMb = mi.totalMem / (1024L * 1024L);
                int gl = am.getDeviceConfigurationInfo().reqGlEsVersion;
                d.glesMajor = (gl >> 16) & 0xffff;
                d.glesMinor = gl & 0xffff;
                if (d.glesMajor == 0) { d.glesMajor = 2; d.glesMinor = 0; }
            }
        } catch (Throwable t) {
            Log.w(TAG, "deviceInfo partially unavailable", t);
        }
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Display disp = dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (disp != null && disp.getRefreshRate() > 0) d.refreshHz = disp.getRefreshRate();
            }
        } catch (Throwable ignored) {
            // refreshHz stays at the 60 fallback
        }
        return d;
    }

    public static boolean isRussian() {
        return Locale.getDefault().getLanguage().startsWith("ru");
    }

    // ------------------------------------------------------------ tiny logger

    public static File logFile(Context ctx) {
        return new File(ctx.getFilesDir(), "launcher.log");
    }

    public static synchronized void log(Context ctx, String message) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        appendText(logFile(ctx), ts + "  " + message + "\n");
        Log.i(TAG, message);
    }

    public static String dumpLog(Context ctx) {
        String text = readText(logFile(ctx));
        return text != null ? text : "";
    }

    public static void clearLog(Context ctx) {
        File f = logFile(ctx);
        if (f.exists()) f.delete();
    }

    // ------------------------------------------------------------ text io

    public static String readText(File f) {
        if (f == null || !f.exists()) return null;
        try {
            return readText(new FileInputStream(f));
        } catch (Exception e) {
            Log.e(TAG, "read failed: " + f, e);
            return null;
        }
    }

    public static String readText(InputStream in) throws java.io.IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) != -1) b.append(buf, 0, n);
        r.close();
        return b.toString();
    }

    public static void writeText(File f, String text) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            // Write-then-rename so a crash mid-write never truncates settings.
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            if (!tmp.renameTo(f)) {
                FileOutputStream direct = new FileOutputStream(f);
                direct.write(text.getBytes(StandardCharsets.UTF_8));
                direct.close();
                tmp.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "write failed: " + f, e);
        }
    }

    public static void writeText(OutputStream out, String text) throws java.io.IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void appendText(File f, String text) {
        try {
            FileOutputStream out = new FileOutputStream(f, true);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception e) {
            Log.e(TAG, "append failed: " + f, e);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
