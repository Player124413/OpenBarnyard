package org.opentoshi.openbarnyard;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.opentoshi.openbarnyard.core.EngineConfig;
import org.opentoshi.openbarnyard.ui.UiKit;

/** Graphics/performance form. Every change is persisted immediately. */
public class SettingsActivity extends Activity {
    private EngineConfig config;
    private boolean loading; // suppresses change listeners while repopulating

    private Spinner presetSpinner, fpsSpinner, msaaSpinner, textureSpinner, shadowSpinner;
    private SeekBar scaleSeek, minScaleSeek, sensSeek;
    private TextView scaleLabel, minScaleLabel, sensLabel;
    private CheckBox dynResBox, vsyncBox, showFpsBox, hapticsBox, invertYBox;

    private static final String[] PRESET_IDS = {
        EngineConfig.PRESET_AUTO, EngineConfig.PRESET_POTATO, EngineConfig.PRESET_BALANCED,
        EngineConfig.PRESET_QUALITY, EngineConfig.PRESET_ULTRA, EngineConfig.PRESET_CUSTOM
    };
    private static final int[] FPS_VALUES = {30, 60, 90, 120, 0};
    private static final int[] MSAA_VALUES = {0, 2, 4};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = Storage.loadConfig(this);

        UiKit.Screen screen = UiKit.screen(this);
        LinearLayout col = screen.col;
        col.addView(UiKit.title(this, R.string.settings_title));

        LinearLayout card = UiKit.card(this);

        card.addView(UiKit.h2(this, getString(R.string.set_preset)));
        presetSpinner = spinner(card.getContext(),
            getResources().getStringArray(R.array.preset_names));
        card.addView(presetSpinner);
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (loading) return;
                String preset = PRESET_IDS[pos];
                if (EngineConfig.PRESET_AUTO.equals(preset)) {
                    EngineConfig auto = Storage.autoConfigForDevice(SettingsActivity.this);
                    copyInto(auto, config);
                } else if (!EngineConfig.PRESET_CUSTOM.equals(preset)) {
                    config.applyPreset(preset);
                } else {
                    config.preset = EngineConfig.PRESET_CUSTOM;
                }
                populate();
                persist();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        scaleLabel = UiKit.body(this, "");
        card.addView(scaleLabel);
        scaleSeek = seek(card, 40, 100, new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (loading || !fromUser) return;
                config.resolutionScale = Math.max(progress, (Integer) s.getTag()) / 100f;
                markCustom();
                updateLabels();
                persist();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        dynResBox = check(card, getString(R.string.set_dynamic_res), new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (loading) return;
                config.dynamicResolution = checked;
                markCustom();
                persist();
            }
        });

        minScaleLabel = UiKit.body(this, "");
        card.addView(minScaleLabel);
        minScaleSeek = seek(card, 30, 100, new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (loading || !fromUser) return;
                config.minResolutionScale = Math.max(progress, (Integer) s.getTag()) / 100f;
                markCustom();
                updateLabels();
                persist();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        card.addView(UiKit.h2(this, getString(R.string.set_fps_limit)));
        fpsSpinner = spinner(card.getContext(), fpsLabels());
        card.addView(fpsSpinner);
        fpsSpinner.setOnItemSelectedListener(simpleSelect(new IntSetter() {
            @Override public void set(int pos) { config.fpsLimit = FPS_VALUES[pos]; }
        }));

        card.addView(UiKit.h2(this, getString(R.string.set_msaa)));
        msaaSpinner = spinner(card.getContext(),
            new String[]{getString(R.string.msaa_off), "2x", "4x"});
        card.addView(msaaSpinner);
        msaaSpinner.setOnItemSelectedListener(simpleSelect(new IntSetter() {
            @Override public void set(int pos) { config.msaa = MSAA_VALUES[pos]; }
        }));

        card.addView(UiKit.h2(this, getString(R.string.set_texture_q)));
        textureSpinner = spinner(card.getContext(), qualityLabels());
        card.addView(textureSpinner);
        textureSpinner.setOnItemSelectedListener(simpleSelect(new IntSetter() {
            @Override public void set(int pos) { config.textureQuality = pos; }
        }));

        card.addView(UiKit.h2(this, getString(R.string.set_shadow_q)));
        shadowSpinner = spinner(card.getContext(), qualityLabels());
        card.addView(shadowSpinner);
        shadowSpinner.setOnItemSelectedListener(simpleSelect(new IntSetter() {
            @Override public void set(int pos) { config.shadowQuality = pos; }
        }));

        vsyncBox = check(card, getString(R.string.set_vsync), new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (loading) return;
                config.vsync = checked; markCustom(); persist();
            }
        });
        showFpsBox = check(card, getString(R.string.set_show_fps), new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (loading) return;
                config.showFps = checked; markCustom(); persist();
            }
        });
        hapticsBox = check(card, getString(R.string.set_haptics), new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (loading) return;
                config.haptics = checked; markCustom(); persist();
            }
        });
        invertYBox = check(card, getString(R.string.set_invert_y), new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (loading) return;
                config.invertCameraY = checked; markCustom(); persist();
            }
        });

        sensLabel = UiKit.body(this, "");
        card.addView(sensLabel);
        sensSeek = seek(card, 30, 250, new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (loading || !fromUser) return;
                config.cameraSensitivity = Math.max(progress, (Integer) s.getTag()) / 100f;
                markCustom();
                updateLabels();
                persist();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        col.addView(card);
        col.addView(UiKit.button(this, getString(R.string.btn_reset_auto), UiKit.COLOR_SKY, new View.OnClickListener() {
            @Override public void onClick(View v) {
                copyInto(Storage.autoConfigForDevice(SettingsActivity.this), config);
                populate();
                persist();
                toastSaved();
            }
        }));
        col.addView(UiKit.button(this, getString(R.string.btn_save), UiKit.COLOR_BARN, new View.OnClickListener() {
            @Override public void onClick(View v) {
                persist();
                toastSaved();
                finish();
            }
        }));

        setContentView(screen.root);
        populate();
    }

    // ------------------------------------------------------------ helpers

    private interface IntSetter { void set(int pos); }

    private AdapterView.OnItemSelectedListener simpleSelect(final IntSetter setter) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (loading) return;
                setter.set(pos);
                markCustom();
                persist();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
    }

    private Spinner spinner(android.content.Context ctx, String[] items) {
        Spinner s = new Spinner(ctx);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        return s;
    }

    private SeekBar seek(LinearLayout parent, int min, int max, SeekBar.OnSeekBarChangeListener l) {
        SeekBar s = new SeekBar(this);
        // SeekBar min is 0 pre-API26 settable via setMin on 26+; use offset-free
        // 0..(max-min) range mapping through progress+min handled by callers
        // storing absolute values: we set max=max and clamp in populate.
        s.setMax(max);
        s.setTag(min);
        s.setOnSeekBarChangeListener(l);
        parent.addView(s);
        return s;
    }

    private CheckBox check(LinearLayout parent, String text, CompoundButton.OnCheckedChangeListener l) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextColor(UiKit.COLOR_TEXT);
        c.setTextSize(14);
        c.setOnCheckedChangeListener(l);
        parent.addView(c);
        return c;
    }

    private String[] fpsLabels() {
        return new String[]{"30 FPS", "60 FPS", "90 FPS", "120 FPS", getString(R.string.fps_unlimited)};
    }

    private String[] qualityLabels() {
        return new String[]{
            getString(R.string.quality_0), getString(R.string.quality_1),
            getString(R.string.quality_2), getString(R.string.quality_3)};
    }

    private void populate() {
        loading = true;
        config.sanitize();
        presetSpinner.setSelection(indexOf(PRESET_IDS, config.preset));
        scaleSeek.setProgress(Math.round(config.resolutionScale * 100f));
        minScaleSeek.setProgress(Math.round(config.minResolutionScale * 100f));
        sensSeek.setProgress(Math.round(config.cameraSensitivity * 100f));
        dynResBox.setChecked(config.dynamicResolution);
        vsyncBox.setChecked(config.vsync);
        showFpsBox.setChecked(config.showFps);
        hapticsBox.setChecked(config.haptics);
        invertYBox.setChecked(config.invertCameraY);
        fpsSpinner.setSelection(indexOf(FPS_VALUES, config.fpsLimit));
        msaaSpinner.setSelection(indexOf(MSAA_VALUES, config.msaa));
        textureSpinner.setSelection(config.textureQuality);
        shadowSpinner.setSelection(config.shadowQuality);
        updateLabels();
        loading = false;
    }

    private void updateLabels() {
        scaleLabel.setText(getString(R.string.set_resolution, Math.round(config.resolutionScale * 100f)));
        minScaleLabel.setText(getString(R.string.set_min_resolution, Math.round(config.minResolutionScale * 100f)));
        sensLabel.setText(getString(R.string.set_cam_sens, config.cameraSensitivity));
    }

    private void markCustom() {
        if (!EngineConfig.PRESET_CUSTOM.equals(config.preset)) {
            config.preset = EngineConfig.PRESET_CUSTOM;
            loading = true;
            presetSpinner.setSelection(PRESET_IDS.length - 1);
            loading = false;
        }
        config.sanitize();
    }

    private void persist() {
        config.sanitize();
        Storage.saveConfig(this, config);
        NativeBridge.applyConfig(config.toJson());
    }

    private void toastSaved() {
        Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show();
    }

    private static void copyInto(EngineConfig from, EngineConfig to) {
        to.resolutionScale = from.resolutionScale;
        to.dynamicResolution = from.dynamicResolution;
        to.minResolutionScale = from.minResolutionScale;
        to.fpsLimit = from.fpsLimit;
        to.vsync = from.vsync;
        to.msaa = from.msaa;
        to.textureQuality = from.textureQuality;
        to.shadowQuality = from.shadowQuality;
        to.showFps = from.showFps;
        to.haptics = from.haptics;
        to.cameraSensitivity = from.cameraSensitivity;
        to.invertCameraY = from.invertCameraY;
        to.preset = from.preset;
        to.sanitize();
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return arr.length - 1;
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 1;
    }
}
