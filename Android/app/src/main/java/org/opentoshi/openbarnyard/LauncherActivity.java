package org.opentoshi.openbarnyard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.opentoshi.openbarnyard.core.GameDataScanner;
import org.opentoshi.openbarnyard.ui.UiKit;

/** Launcher home: device card, game-data status and the main navigation. */
public class LauncherActivity extends Activity {
    private TextView dataStatus;
    private TextView dataDetails;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Storage.ensureDefaultProfile(this);

        UiKit.Screen screen = UiKit.screen(this);
        LinearLayout col = screen.col;

        col.addView(UiKit.titleText(this, "🐄 " + getString(R.string.app_name)));
        String version = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = " · v" + pi.versionName;
        } catch (Exception ignored) {
            // version label is decorative only
        }
        col.addView(UiKit.subtitle(this, getString(R.string.launcher_subtitle) + version));

        // Play button, front and centre
        col.addView(UiKit.button(this, getString(R.string.btn_play), UiKit.COLOR_BARN, new View.OnClickListener() {
            @Override public void onClick(View v) { play(); }
        }));

        // Device card
        LinearLayout deviceCard = UiKit.card(this);
        deviceCard.addView(UiKit.h2(this, "📱 " + getString(R.string.card_device)));
        deviceCard.addView(UiKit.body(this, deviceSummary()));
        String cpu = NativeBridge.getCpuSummary();
        if (cpu.length() > 0) deviceCard.addView(UiKit.small(this, "Native: " + cpu));
        col.addView(deviceCard);

        // Game data card (filled asynchronously)
        LinearLayout dataCard = UiKit.card(this);
        dataCard.addView(UiKit.h2(this, getString(R.string.card_game_data)));
        dataStatus = UiKit.body(this, getString(R.string.data_status_scanning));
        dataDetails = UiKit.small(this, "");
        dataCard.addView(dataStatus);
        dataCard.addView(dataDetails);
        dataCard.addView(UiKit.small(this, Storage.gameDataPathForNative(this)));
        col.addView(dataCard);

        // Navigation
        col.addView(UiKit.button(this, getString(R.string.btn_touch_editor), UiKit.COLOR_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(LauncherActivity.this, TouchEditorActivity.class)); }
        }));
        col.addView(UiKit.button(this, getString(R.string.btn_settings), UiKit.COLOR_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(LauncherActivity.this, SettingsActivity.class)); }
        }));
        col.addView(UiKit.button(this, getString(R.string.btn_game_data), UiKit.COLOR_SKY, new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(LauncherActivity.this, DataActivity.class)); }
        }));
        col.addView(UiKit.button(this, getString(R.string.btn_diagnostics), UiKit.COLOR_SKY, new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(LauncherActivity.this, DiagActivity.class)); }
        }));
        col.addView(UiKit.button(this, getString(R.string.btn_about), UiKit.COLOR_AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) { showAbout(); }
        }));

        // Engine status / honesty card
        LinearLayout statusCard = UiKit.card(this);
        statusCard.addView(UiKit.body(this, getString(R.string.play_demo_note)));
        col.addView(statusCard);

        LinearLayout quickCard = UiKit.card(this);
        quickCard.addView(UiKit.h2(this, getString(R.string.card_quick_start)));
        quickCard.addView(UiKit.body(this, getString(R.string.quick_start_text)));
        col.addView(quickCard);

        TextView footer = UiKit.small(this, getString(R.string.footer_no_data));
        footer.setTextColor(Color.rgb(0xC9, 0xD6, 0xC4));
        col.addView(footer);

        setContentView(screen.root);
        Storage.log(this, "Launcher opened");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDataStatus();
    }

    private void play() {
        if (!NativeBridge.isAvailable()) {
            Toast.makeText(this, R.string.native_missing_toast, Toast.LENGTH_LONG).show();
            Storage.log(this, "Play pressed but native library unavailable");
            return;
        }
        startActivity(new Intent(this, GameActivity.class));
    }

    private String deviceSummary() {
        Storage.DeviceInfo d = Storage.deviceInfo(this);
        return getString(R.string.device_format, d.model, d.soc, d.primaryAbi,
            d.cores, d.totalRamMb, d.glesMajor, d.glesMinor, d.refreshHz, d.sdkInt);
    }

    private void refreshDataStatus() {
        if (dataStatus == null) return;
        dataStatus.setText(R.string.data_status_scanning);
        new Thread(new Runnable() {
            @Override public void run() {
                final GameDataScanner.Result result = ScanHelper.scan(LauncherActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        dataStatus.setText(verdictText(result.verdict));
                        dataDetails.setText(getString(R.string.data_counts_format,
                            result.totalFiles, result.trbCount, result.ttlCount,
                            result.tklCount, result.bikCount));
                    }
                });
            }
        }, "ob-scan").start();
    }

    static int verdictText(GameDataScanner.Verdict v) {
        switch (v) {
            case EMPTY: return R.string.data_empty;
            case NO_GAME_DATA: return R.string.data_no_game;
            case PARTIAL: return R.string.data_partial;
            case LOOKS_COMPLETE: return R.string.data_complete;
            default: return R.string.data_empty;
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.btn_about))
            .setMessage(getString(R.string.about_text))
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }
}
