package com.alpine.chrome.settings;

import android.os.Bundle;
import android.widget.TextView;

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

/**
 * Android-style settings only (display scale, touch, mouse).
 * Opened exclusively via long-press app shortcut → Settings.
 */
public class SettingsActivity extends AppCompatActivity {

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

        MaterialSwitch touch = findViewById(R.id.switch_touch_mode);
        touch.setChecked(Prefs.isTouchMode());
        touch.setOnCheckedChangeListener((b, c) -> Prefs.setTouchMode(c));

        MaterialSwitch mouse = findViewById(R.id.switch_mouse_support);
        mouse.setChecked(Prefs.isMouseSupport());
        mouse.setOnCheckedChangeListener((b, c) -> Prefs.setMouseSupport(c));

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
}
