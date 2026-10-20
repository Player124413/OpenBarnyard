package org.opentoshi.openbarnyard;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.opentoshi.openbarnyard.core.GameDataScanner;
import org.opentoshi.openbarnyard.ui.UiKit;

import java.io.File;

/** Game-data location picker + installation verification. */
public class DataActivity extends Activity {
    private static final int REQ_PICK_TREE = 4201;

    private TextView pathView;
    private TextView statusView;
    private TextView detailsView;
    private LinearLayout hintsBox;
    private Button subfolderButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiKit.Screen screen = UiKit.screen(this);
        LinearLayout col = screen.col;
        col.addView(UiKit.title(this, R.string.data_title));

        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.body(this, getString(R.string.data_instructions)));
        card.addView(UiKit.spacer(this, 8));
        card.addView(UiKit.small(this, getString(R.string.data_current_path)));
        pathView = UiKit.mono(this, "");
        card.addView(pathView);
        col.addView(card);

        LinearLayout statusCard = UiKit.card(this);
        statusView = UiKit.body(this, getString(R.string.data_status_scanning));
        detailsView = UiKit.small(this, "");
        hintsBox = new LinearLayout(this);
        hintsBox.setOrientation(LinearLayout.VERTICAL);
        statusCard.addView(statusView);
        statusCard.addView(detailsView);
        statusCard.addView(hintsBox);
        col.addView(statusCard);

        subfolderButton = UiKit.button(this, "", UiKit.COLOR_AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String suggestion = (String) v.getTag();
                if (suggestion == null || suggestion.length() == 0) return;
                // Only meaningful for the filesystem mode.
                String base = Storage.gameDataPathForNative(DataActivity.this);
                Storage.setGameDataPath(DataActivity.this,
                    new File(base, suggestion).getAbsolutePath());
                Storage.setGameDataUri(DataActivity.this, "");
                refresh();
            }
        });
        subfolderButton.setVisibility(View.GONE);
        col.addView(subfolderButton);

        col.addView(UiKit.button(this, getString(R.string.btn_pick_folder), UiKit.COLOR_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { pickFolder(); }
        }));
        col.addView(UiKit.button(this, getString(R.string.btn_default_folder), UiKit.COLOR_SKY, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Storage.setGameDataUri(DataActivity.this, "");
                Storage.setGameDataPath(DataActivity.this, "");
                Storage.defaultGameDataDir(DataActivity.this); // ensure it exists
                refresh();
            }
        }));
        col.addView(UiKit.button(this, getString(R.string.btn_rescan), UiKit.COLOR_SKY, new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        }));

        LinearLayout noteCard = UiKit.card(this);
        noteCard.addView(UiKit.small(this, getString(R.string.data_engine_note)));
        col.addView(noteCard);

        setContentView(screen.root);
        refresh();
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_TREE);
        } catch (Exception e) {
            Storage.log(this, "No folder picker available: " + e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_TREE || resultCode != RESULT_OK || data == null) return;
        Uri treeUri = data.getData();
        if (treeUri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            Storage.log(this, "Persisting folder permission failed: " + e);
        }
        Storage.setGameDataUri(this, treeUri.toString());
        Storage.log(this, "Game data folder set via SAF");
        refresh();
    }

    private void refresh() {
        pathView.setText(displayPath());
        statusView.setText(R.string.data_status_scanning);
        detailsView.setText("");
        hintsBox.removeAllViews();
        subfolderButton.setVisibility(View.GONE);
        new Thread(new Runnable() {
            @Override public void run() {
                final GameDataScanner.Result result = ScanHelper.scan(DataActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        showResult(result);
                    }
                });
            }
        }, "ob-scan").start();
    }

    private String displayPath() {
        String uri = Storage.gameDataUri(this);
        if (uri.length() > 0) {
            String decoded = Uri.decode(uri);
            int colon = decoded.lastIndexOf(':');
            return colon >= 0 ? decoded.substring(colon + 1) : decoded;
        }
        return Storage.gameDataPathForNative(this);
    }

    private void showResult(GameDataScanner.Result r) {
        statusView.setText(LauncherActivity.verdictText(r.verdict));
        detailsView.setText(getString(R.string.data_counts_format,
            r.totalFiles, r.trbCount, r.ttlCount, r.tklCount, r.bikCount));
        hintsBox.removeAllViews();
        for (GameDataScanner.Hint hint : r.hints) {
            String text = null;
            switch (hint) {
                case MISSING_DATA_DIR: text = getString(R.string.hint_missing_data_dir); break;
                case FEW_TRB_FILES: text = getString(R.string.hint_few_trb); break;
                case MISSING_TEXTURE_LIBS: text = getString(R.string.hint_missing_ttl); break;
                case HAS_PC_EXECUTABLE: text = getString(R.string.hint_has_exe); break;
                case NESTED_INSTALL:
                    text = getString(R.string.hint_nested, r.suggestedSubPath);
                    if (Storage.gameDataUri(this).length() == 0) {
                        subfolderButton.setText(getString(R.string.btn_use_subfolder, r.suggestedSubPath));
                        subfolderButton.setTag(r.suggestedSubPath);
                        subfolderButton.setVisibility(View.VISIBLE);
                    }
                    break;
            }
            if (text != null) {
                TextView line = UiKit.small(this, "• " + text);
                hintsBox.addView(line);
            }
        }
        Storage.log(this, "Scan: " + r.verdict + " trb=" + r.trbCount + " files=" + r.totalFiles);
    }
}
