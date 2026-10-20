package org.opentoshi.openbarnyard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.opentoshi.openbarnyard.core.GameCommand;
import org.opentoshi.openbarnyard.core.TouchProfile;
import org.opentoshi.openbarnyard.ui.TouchOverlayView;
import org.opentoshi.openbarnyard.ui.UiKit;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Full-screen WYSIWYG touch-layout editor. Drag to move, tap to select,
 * then use the bottom bar to rebind/resize. Layouts are resolution
 * independent (normalised coordinates) and shareable as .json files.
 */
public class TouchEditorActivity extends Activity implements TouchOverlayView.Listener {
    private static final int REQ_EXPORT = 4301;
    private static final int REQ_IMPORT = 4302;

    private TouchOverlayView overlay;
    private TouchProfile profile;
    private boolean loading;

    private EditText nameEdit;
    private Spinner profileSpinner;
    private Spinner bindingSpinner;
    private SeekBar sizeSeek, opacitySeek;
    private TextView selectedLabel;
    private CheckBox snapBox, dynamicStickBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        profile = Storage.loadActiveProfile(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(0x2A, 0x44, 0x30)); // editor "pasture"

        overlay = new TouchOverlayView(this);
        overlay.setListener(this);
        overlay.setEditorMode(true);
        overlay.setRussian(Storage.isRussian());
        overlay.setProfile(profile);
        root.addView(overlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(buildTopBar(), topLp());
        root.addView(buildBottomBar(), bottomLp());

        setContentView(root);
        rebuildProfileSpinner();
        syncTopBar();
        onSelect(null);
    }

    // ------------------------------------------------------------ top bar

    private View buildTopBar() {
        LinearLayout bar = panelBar();
        TextView title = label(getString(R.string.editor_title));
        bar.addView(title);

        profileSpinner = new Spinner(this);
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (loading) return;
                Object item = parent.getItemAtPosition(pos);
                if (item == null) return;
                String name = item.toString();
                if (name.equals(profile.name)) return;
                Storage.saveProfile(TouchEditorActivity.this, profile); // never lose edits silently
                profile = Storage.loadProfile(TouchEditorActivity.this, name);
                applyProfile();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        bar.addView(profileSpinner);

        nameEdit = new EditText(this);
        nameEdit.setHint(R.string.ed_profile_name_hint);
        nameEdit.setTextColor(Color.WHITE);
        nameEdit.setHintTextColor(Color.rgb(0xAA, 0xB8, 0xAA));
        nameEdit.setSingleLine(true);
        nameEdit.setMinWidth(UiKit.dp(this, 120));
        bar.addView(nameEdit);

        bar.addView(UiKit.smallButton(this, getString(R.string.ed_new), UiKit.COLOR_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { newProfile(); }
        }));
        bar.addView(UiKit.smallButton(this, getString(R.string.ed_save), UiKit.COLOR_BARN, new View.OnClickListener() {
            @Override public void onClick(View v) { save(true); }
        }));
        bar.addView(UiKit.smallButton(this, getString(R.string.ed_delete_profile), UiKit.COLOR_AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) { deleteProfile(); }
        }));

        snapBox = checkWhite(getString(R.string.ed_snap), new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                overlay.setSnapEnabled(checked);
            }
        });
        snapBox.setChecked(true);
        bar.addView(snapBox);

        dynamicStickBox = checkWhite(getString(R.string.ed_dynamic_stick), new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!loading) profile.dynamicStick = checked;
            }
        });
        bar.addView(dynamicStickBox);

        bar.addView(UiKit.smallButton(this, getString(R.string.ed_done), UiKit.COLOR_SKY, new View.OnClickListener() {
            @Override public void onClick(View v) {
                save(false);
                finish();
            }
        }));
        return scrollWrap(bar);
    }

    // ------------------------------------------------------------ bottom bar

    private View buildBottomBar() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.argb(215, 18, 26, 18));
        col.setClickable(true);
        col.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 4), UiKit.dp(this, 8), UiKit.dp(this, 4));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        selectedLabel = label(getString(R.string.ed_selected_none));
        row1.addView(selectedLabel);

        row1.addView(label(getString(R.string.ed_binding)));
        bindingSpinner = new Spinner(this);
        List<GameCommand> bindable = GameCommand.bindable();
        String[] labels = new String[bindable.size()];
        boolean ru = Storage.isRussian();
        for (int i = 0; i < bindable.size(); i++) labels[i] = bindable.get(i).label(ru);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bindingSpinner.setAdapter(adapter);
        bindingSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (loading) return;
                TouchProfile.Element e = overlay.getSelected();
                if (e != null && e.type == TouchProfile.Type.BUTTON) {
                    e.command = GameCommand.bindable().get(pos).id;
                    overlay.invalidate();
                    updateSelectedLabel();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        row1.addView(bindingSpinner);

        row1.addView(label(getString(R.string.ed_size)));
        sizeSeek = new SeekBar(this);
        sizeSeek.setMax(45);
        sizeSeek.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(this, 120), LinearLayout.LayoutParams.WRAP_CONTENT));
        sizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (loading || !fromUser) return;
                TouchProfile.Element e = overlay.getSelected();
                if (e == null) return;
                int v = Math.max(progress, 5);
                if (e.type == TouchProfile.Type.CAMERA) { e.w = v / 50f; e.h = v / 45f; }
                else e.size = v / 100f;
                e.sanitize();
                overlay.invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        row1.addView(sizeSeek);

        row1.addView(label(getString(R.string.ed_opacity)));
        opacitySeek = new SeekBar(this);
        opacitySeek.setMax(100);
        opacitySeek.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(this, 100), LinearLayout.LayoutParams.WRAP_CONTENT));
        opacitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (loading || !fromUser) return;
                TouchProfile.Element e = overlay.getSelected();
                if (e == null) return;
                e.opacity = Math.max(progress, 10) / 100f;
                e.sanitize();
                overlay.invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        row1.addView(opacitySeek);
        col.addView(scrollWrap(row1));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(UiKit.smallButton(this, getString(R.string.ed_add_button), UiKit.COLOR_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { addButton(); }
        }));
        row2.addView(UiKit.smallButton(this, getString(R.string.ed_duplicate), UiKit.COLOR_GREEN, new View.OnClickListener() {
            @Override public void onClick(View v) { duplicateSelected(); }
        }));
        row2.addView(UiKit.smallButton(this, getString(R.string.ed_delete_element), UiKit.COLOR_BARN, new View.OnClickListener() {
            @Override public void onClick(View v) { deleteSelected(); }
        }));
        row2.addView(UiKit.smallButton(this, getString(R.string.ed_mirror), UiKit.COLOR_SKY, new View.OnClickListener() {
            @Override public void onClick(View v) {
                profile.mirrorHorizontally();
                overlay.invalidate();
            }
        }));
        row2.addView(UiKit.smallButton(this, getString(R.string.ed_reset), UiKit.COLOR_AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String name = profile.name;
                profile = TouchProfile.defaultProfile();
                profile.name = name;
                applyProfile();
            }
        }));
        row2.addView(UiKit.smallButton(this, getString(R.string.ed_export), UiKit.COLOR_SKY, new View.OnClickListener() {
            @Override public void onClick(View v) { exportProfile(); }
        }));
        row2.addView(UiKit.smallButton(this, getString(R.string.ed_import), UiKit.COLOR_SKY, new View.OnClickListener() {
            @Override public void onClick(View v) { importProfile(); }
        }));
        col.addView(scrollWrap(row2));
        return col;
    }

    // ------------------------------------------------------------ profile operations

    private void applyProfile() {
        overlay.setProfile(profile);
        overlay.setSelected(null);
        syncTopBar();
        rebuildProfileSpinner();
        onSelect(null);
    }

    private void syncTopBar() {
        loading = true;
        nameEdit.setText(profile.name);
        dynamicStickBox.setChecked(profile.dynamicStick);
        loading = false;
    }

    private void rebuildProfileSpinner() {
        loading = true;
        List<String> names = Storage.profileNames(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);
        int idx = names.indexOf(Storage.sanitizeName(profile.name));
        if (idx < 0) idx = names.indexOf(profile.name);
        if (idx >= 0) profileSpinner.setSelection(idx);
        loading = false;
    }

    private void save(boolean toast) {
        String newName = Storage.sanitizeName(nameEdit.getText().toString());
        if (!newName.equals(profile.name)) {
            Storage.deleteProfile(this, profile.name);
            profile.name = newName;
        }
        Storage.saveProfile(this, profile);
        Storage.setActiveProfileName(this, profile.name);
        Storage.log(this, "Touch profile saved: " + profile.name);
        rebuildProfileSpinner();
        if (toast) Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show();
    }

    private void newProfile() {
        save(false);
        String base = getString(R.string.ed_new_profile);
        String name = base;
        List<String> existing = Storage.profileNames(this);
        int n = 2;
        while (existing.contains(name)) { name = base + " " + n; n++; }
        profile = TouchProfile.defaultProfile();
        profile.name = name;
        Storage.saveProfile(this, profile);
        Storage.setActiveProfileName(this, name);
        applyProfile();
    }

    private void deleteProfile() {
        Storage.deleteProfile(this, profile.name);
        profile = Storage.loadProfile(this, "Default");
        Storage.setActiveProfileName(this, "Default");
        applyProfile();
    }

    private void addButton() {
        String id = "btn" + System.currentTimeMillis() % 100000;
        TouchProfile.Element e = new TouchProfile.Element(id, TouchProfile.Type.BUTTON,
            GameCommand.JUMP.id, 0.5f, 0.5f, 0.15f, 0.6f);
        profile.elements.add(e);
        overlay.setSelected(e);
        overlay.invalidate();
    }

    private void duplicateSelected() {
        TouchProfile.Element e = overlay.getSelected();
        if (e == null) return;
        TouchProfile.Element copy = e.copy();
        copy.id = e.id + "_" + (System.currentTimeMillis() % 10000);
        copy.x = Math.min(1f, e.x + 0.04f);
        copy.y = Math.min(1f, e.y + 0.04f);
        profile.elements.add(copy);
        profile.sanitize();
        overlay.setSelected(copy);
        overlay.invalidate();
    }

    private void deleteSelected() {
        TouchProfile.Element e = overlay.getSelected();
        if (e == null) return;
        profile.elements.remove(e);
        overlay.setSelected(null);
        overlay.invalidate();
    }

    // ------------------------------------------------------------ import / export

    private void exportProfile() {
        save(false);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, profile.name + ".json");
        try {
            startActivityForResult(intent, REQ_EXPORT);
        } catch (Exception e) {
            Storage.log(this, "Export picker unavailable: " + e);
        }
    }

    private void importProfile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Exception e) {
            Storage.log(this, "Import picker unavailable: " + e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            try {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out != null) {
                    Storage.writeText(out, profile.toJson());
                    out.close();
                }
                Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Storage.log(this, "Export failed: " + e);
            }
        } else if (requestCode == REQ_IMPORT) {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                String text = in != null ? Storage.readText(in) : null;
                if (text == null) throw new IllegalStateException("empty");
                TouchProfile imported = TouchProfile.fromJson(text);
                List<String> existing = Storage.profileNames(this);
                String name = imported.name;
                int n = 2;
                while (existing.contains(Storage.sanitizeName(name))) { name = imported.name + " (" + n + ")"; n++; }
                imported.name = name;
                save(false);
                profile = imported;
                Storage.saveProfile(this, profile);
                Storage.setActiveProfileName(this, profile.name);
                applyProfile();
            } catch (Exception e) {
                Storage.log(this, "Import failed: " + e);
                Toast.makeText(this, R.string.ed_import_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------ overlay listener

    @Override public void onButton(int command, boolean down) { /* editor ignores */ }
    @Override public void onStick(float x, float y) { }
    @Override public void onCameraDelta(float dxPx, float dyPx) { }
    @Override public void onLayoutChanged() { /* saved via Save/Done */ }

    @Override
    public void onSelect(TouchProfile.Element e) {
        updateSelectedLabel();
        loading = true;
        if (e != null) {
            if (e.type == TouchProfile.Type.BUTTON) {
                List<GameCommand> bindable = GameCommand.bindable();
                for (int i = 0; i < bindable.size(); i++) {
                    if (bindable.get(i).id == e.command) { bindingSpinner.setSelection(i); break; }
                }
                sizeSeek.setProgress(Math.round(e.size * 100f));
            } else if (e.type == TouchProfile.Type.CAMERA) {
                sizeSeek.setProgress(Math.round(e.w * 50f));
            } else {
                sizeSeek.setProgress(Math.round(e.size * 100f));
            }
            opacitySeek.setProgress(Math.round(e.opacity * 100f));
        }
        boolean isButton = e != null && e.type == TouchProfile.Type.BUTTON;
        bindingSpinner.setEnabled(isButton);
        sizeSeek.setEnabled(e != null);
        opacitySeek.setEnabled(e != null);
        loading = false;
    }

    private void updateSelectedLabel() {
        TouchProfile.Element e = overlay.getSelected();
        if (e == null) {
            selectedLabel.setText(R.string.ed_selected_none);
            return;
        }
        String what;
        if (e.type == TouchProfile.Type.STICK) what = getString(R.string.ed_element_stick);
        else if (e.type == TouchProfile.Type.CAMERA) what = getString(R.string.ed_element_camera);
        else {
            GameCommand c = GameCommand.byId(e.command);
            what = c != null ? c.label(Storage.isRussian()) : ("#" + e.command);
        }
        selectedLabel.setText(getString(R.string.ed_selected_format, what));
    }

    @Override
    public void onBackPressed() {
        save(false);
        super.onBackPressed();
    }

    // ------------------------------------------------------------ small builders

    private LinearLayout panelBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.argb(215, 18, 26, 18));
        bar.setClickable(true);
        bar.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 4), UiKit.dp(this, 8), UiKit.dp(this, 4));
        return bar;
    }

    private View scrollWrap(View inner) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(inner);
        return hs;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setPadding(UiKit.dp(this, 6), 0, UiKit.dp(this, 6), 0);
        return t;
    }

    private CheckBox checkWhite(String text, CompoundButton.OnCheckedChangeListener l) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextColor(Color.WHITE);
        c.setTextSize(12.5f);
        c.setOnCheckedChangeListener(l);
        return c;
    }

    private FrameLayout.LayoutParams topLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        return lp;
    }

    private FrameLayout.LayoutParams bottomLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        return lp;
    }
}
