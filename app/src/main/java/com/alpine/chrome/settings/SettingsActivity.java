package com.alpine.chrome.settings;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.alpine.chrome.BuildConfig;
import com.alpine.chrome.R;
import com.alpine.chrome.engine.Prefs;
import com.alpine.chrome.engine.RootfsManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

public class SettingsActivity extends AppCompatActivity {

    private static final int[][] RES_PRESETS = {
            {720, 1280},   // portrait phone
            {800, 1280},
            {900, 1600},
            {1080, 1920},
            {1280, 720},   // landscape desktop
            {1024, 768},
            {1366, 768},
            {1920, 1080},
    };

    private static final String[] RES_LABELS = {
            "720×1280 portrait (fills phone upright)",
            "800×1280 portrait",
            "900×1600 portrait",
            "1080×1920 portrait HD",
            "1280×720 landscape (desktop)",
            "1024×768 landscape",
            "1366×768 landscape",
            "1920×1080 landscape HD",
    };

    private static final String[] VIEW_MODES = {
            Prefs.VIEW_FIT, Prefs.VIEW_FILL, Prefs.VIEW_REMOTE
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        Slider scale = findViewById(R.id.slider_display_scale);
        scale.setValue(Prefs.getDisplayScale());
        scale.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) Prefs.setDisplayScale(value);
        });

        Spinner viewMode = findViewById(R.id.spinner_view_mode);
        String[] viewLabels = {
                getString(R.string.settings_view_fit),
                getString(R.string.settings_view_fill),
                getString(R.string.settings_view_remote),
        };
        viewMode.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, viewLabels));
        String curView = Prefs.getViewMode();
        for (int i = 0; i < VIEW_MODES.length; i++) {
            if (VIEW_MODES[i].equals(curView)) {
                viewMode.setSelection(i);
                break;
            }
        }
        viewMode.setOnItemSelectedListener(new SimpleItemSelected() {
            @Override
            public void onSelected(int pos) {
                Prefs.setViewMode(VIEW_MODES[pos]);
            }
        });

        MaterialSwitch hideStatus = findViewById(R.id.switch_hide_status);
        hideStatus.setChecked(Prefs.isHideStatusBar());
        hideStatus.setOnCheckedChangeListener((b, c) -> Prefs.setHideStatusBar(c));

        MaterialSwitch showPtr = findViewById(R.id.switch_show_pointer);
        showPtr.setChecked(Prefs.isShowPointer());
        showPtr.setOnCheckedChangeListener((b, c) -> Prefs.setShowPointer(c));

        Spinner res = findViewById(R.id.spinner_resolution);
        res.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, RES_LABELS));
        int w = Prefs.getVncWidth();
        int h = Prefs.getVncHeight();
        int sel = 4; // default 1280x720
        for (int i = 0; i < RES_PRESETS.length; i++) {
            if (RES_PRESETS[i][0] == w && RES_PRESETS[i][1] == h) {
                sel = i;
                break;
            }
        }
        res.setSelection(sel);
        TextView resLabel = findViewById(R.id.resolution_label);
        resLabel.setText(w + " × " + h);
        res.setOnItemSelectedListener(new SimpleItemSelected() {
            @Override
            public void onSelected(int pos) {
                Prefs.setVncWidth(RES_PRESETS[pos][0]);
                Prefs.setVncHeight(RES_PRESETS[pos][1]);
                resLabel.setText(RES_PRESETS[pos][0] + " × " + RES_PRESETS[pos][1]);
            }
        });

        MaterialSwitch touch = findViewById(R.id.switch_touch_mode);
        touch.setChecked(Prefs.isTouchMode());
        touch.setOnCheckedChangeListener((b, c) -> Prefs.setTouchMode(c));

        MaterialSwitch mouse = findViewById(R.id.switch_mouse_support);
        mouse.setChecked(Prefs.isMouseSupport());
        mouse.setOnCheckedChangeListener((b, c) -> Prefs.setMouseSupport(c));

        MaterialSwitch twoKb = findViewById(R.id.switch_two_finger_kb);
        twoKb.setChecked(Prefs.isTwoFingerKeyboard());
        twoKb.setOnCheckedChangeListener((b, c) -> Prefs.setTwoFingerKeyboard(c));

        MaterialSwitch twoScroll = findViewById(R.id.switch_two_finger_scroll);
        twoScroll.setChecked(Prefs.isTwoFingerScroll());
        twoScroll.setOnCheckedChangeListener((b, c) -> Prefs.setTwoFingerScroll(c));

        Slider quality = findViewById(R.id.slider_vnc_quality);
        quality.setValue(Prefs.getVncQuality());
        quality.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) Prefs.setVncQuality(Math.round(value));
        });

        MaterialSwitch perf = findViewById(R.id.switch_perf_mode);
        perf.setChecked(Prefs.isPerformanceMode());
        perf.setOnCheckedChangeListener((b, c) -> Prefs.setPerformanceMode(c));

        MaterialSwitch tryGpu = findViewById(R.id.switch_try_gpu);
        tryGpu.setChecked(Prefs.isTryGpu());
        tryGpu.setOnCheckedChangeListener((b, c) -> Prefs.setTryGpu(c));

        MaterialSwitch sd = findViewById(R.id.switch_sdcard);
        sd.setChecked(Prefs.isSdCardEnabled());
        sd.setOnCheckedChangeListener((b, c) -> Prefs.setSdCardEnabled(c));

        findViewById(R.id.btn_open_sdcard).setOnClickListener(v -> {
            Prefs.setPendingOpenUrl("file:///sdcard/");
            Toast.makeText(this, R.string.settings_open_queued, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_open_files).setOnClickListener(v -> {
            Prefs.setPendingOpenUrl("file:///root/");
            Toast.makeText(this, R.string.settings_open_queued, Toast.LENGTH_SHORT).show();
        });

        MaterialSwitch logsFab = findViewById(R.id.switch_logs_fab);
        logsFab.setChecked(Prefs.isLogsFabEnabled());
        logsFab.setOnCheckedChangeListener((b, c) -> Prefs.setLogsFabEnabled(c));

        MaterialButton reset = findViewById(R.id.btn_reset_env);
        reset.setOnClickListener(v -> confirmReset());

        TextView about = findViewById(R.id.about_text);
        about.setText(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME
                + "\nLightweight Chromium preview");
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_reset_env)
                .setMessage(R.string.settings_reset_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    new RootfsManager(this).resetEnvironment();
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Minimal AdapterView listener without noise. */
    private abstract static class SimpleItemSelected implements android.widget.AdapterView.OnItemSelectedListener {
        public abstract void onSelected(int pos);

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
            onSelected(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
