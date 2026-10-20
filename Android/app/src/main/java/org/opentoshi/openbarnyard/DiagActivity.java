package org.opentoshi.openbarnyard;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.StatFs;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.opentoshi.openbarnyard.core.EngineConfig;
import org.opentoshi.openbarnyard.ui.UiKit;

import java.util.Locale;

/** Copy-pasteable support report: device, native core, config, launcher log. */
public class DiagActivity extends Activity {
    private TextView reportView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiKit.Screen screen = UiKit.screen(this);
        LinearLayout col = screen.col;
        col.addView(UiKit.title(this, R.string.diag_title));
        col.addView(UiKit.subtitle(this, getString(R.string.diag_hint)));

        LinearLayout card = UiKit.card(this);
        reportView = UiKit.mono(this, "");
        card.addView(reportView);
        col.addView(card);

        col.addView(UiKit.button(this, getString(R.string.btn_refresh), UiKit.COLOR_SKY, new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        }));
        col.addView(UiKit.button(this, getString(R.string.btn_copy), UiKit.COLOR_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("OpenBarnyard report", reportView.getText()));
                    Toast.makeText(DiagActivity.this, R.string.copied_toast, Toast.LENGTH_SHORT).show();
                }
            }
        }));
        col.addView(UiKit.button(this, getString(R.string.btn_clear_log), UiKit.COLOR_BARN, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Storage.clearLog(DiagActivity.this);
                refresh();
            }
        }));

        setContentView(screen.root);
        // Make sure the native side is up so GL/CPU data is populated.
        if (NativeBridge.isAvailable()) {
            EngineConfig cfg = Storage.loadConfig(this);
            NativeBridge.init(getFilesDir().getAbsolutePath(),
                Storage.gameDataPathForNative(this), cfg.toJson());
        }
        refresh();
    }

    private void refresh() {
        reportView.setText(buildReport());
    }

    private String buildReport() {
        StringBuilder b = new StringBuilder();
        b.append("=== OpenBarnyard Android diagnostics ===\n");
        try {
            b.append("App: ").append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName).append('\n');
        } catch (Exception ignored) { }
        Storage.DeviceInfo d = Storage.deviceInfo(this);
        b.append(String.format(Locale.US,
            "Device: %s\nSoC: %s\nABI: %s (%s) · cores %d · RAM %d MB\nGLES %d.%d · %.0f Hz · API %d\n",
            d.model, d.soc, d.primaryAbi, d.is64Bit ? "64-bit" : "32-bit",
            d.cores, d.totalRamMb, d.glesMajor, d.glesMinor, d.refreshHz, d.sdkInt));
        try {
            StatFs stat = new StatFs(getFilesDir().getAbsolutePath());
            long freeMb = stat.getAvailableBytes() / (1024L * 1024L);
            b.append("Free storage: ").append(freeMb).append(" MB\n");
        } catch (Exception ignored) { }
        b.append("\n--- native core ---\n");
        b.append(NativeBridge.getDiagnostics()).append('\n');
        b.append("\n--- openbarnyard.json ---\n");
        b.append(Storage.loadConfig(this).toJson());
        b.append("--- touch profile ---\n");
        b.append("active: ").append(Storage.activeProfileName(this)).append('\n');
        b.append("profiles: ").append(Storage.profileNames(this).toString()).append('\n');
        b.append("\n--- launcher.log ---\n");
        String log = Storage.dumpLog(this);
        b.append(log.length() > 6000 ? log.substring(log.length() - 6000) : log);
        return b.toString();
    }
}
