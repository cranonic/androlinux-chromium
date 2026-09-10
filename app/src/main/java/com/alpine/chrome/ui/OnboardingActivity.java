package com.alpine.chrome.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.alpine.chrome.R;
import com.alpine.chrome.engine.Prefs;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * First-run flow: welcome → permissions (storage + notifications toggles) → details → setup.
 * Circular Next FAB bottom-right, Chromium logo slightly above center.
 */
public class OnboardingActivity extends AppCompatActivity {

    private int page = 0; // 0 welcome, 1 perms, 2 details
    private TextView title;
    private TextView body;
    private View permCard;
    private View dot0, dot1, dot2;
    private MaterialSwitch switchStorage;
    private MaterialSwitch switchNotif;

    private final ActivityResultLauncher<String> notifPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                switchNotif.setChecked(granted);
            });

    private final ActivityResultLauncher<String[]> storagePermission =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean ok = true;
                for (Boolean b : result.values()) {
                    if (b == null || !b) ok = false;
                }
                switchStorage.setChecked(ok || hasStorageAccess());
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        title = findViewById(R.id.title);
        body = findViewById(R.id.body);
        permCard = findViewById(R.id.perm_card);
        dot0 = findViewById(R.id.dot0);
        dot1 = findViewById(R.id.dot1);
        dot2 = findViewById(R.id.dot2);
        switchStorage = findViewById(R.id.switch_storage);
        switchNotif = findViewById(R.id.switch_notification);
        FloatingActionButton btnNext = findViewById(R.id.btn_next);

        switchStorage.setChecked(hasStorageAccess());
        switchNotif.setChecked(hasNotifAccess());

        switchStorage.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !hasStorageAccess()) {
                requestStorage();
            }
        });
        switchNotif.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !hasNotifAccess()) {
                requestNotif();
            }
        });

        btnNext.setOnClickListener(v -> nextPage());
        showPage(0);
    }

    private void nextPage() {
        if (page < 2) {
            showPage(page + 1);
        } else {
            Prefs.setOnboardingDone(true);
            startActivity(new Intent(this, SetupActivity.class));
            finish();
        }
    }

    private void showPage(int p) {
        page = p;
        permCard.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        switch (p) {
            case 0:
                title.setText(R.string.onboarding_welcome_title);
                body.setText(R.string.onboarding_welcome_body);
                break;
            case 1:
                title.setText(R.string.onboarding_perm_title);
                body.setText(R.string.onboarding_perm_body);
                break;
            case 2:
                title.setText(R.string.onboarding_details_title);
                body.setText(R.string.onboarding_details_body);
                break;
        }
        setDot(dot0, p == 0);
        setDot(dot1, p == 1);
        setDot(dot2, p == 2);
    }

    private void setDot(View dot, boolean active) {
        dot.setBackgroundResource(active ? R.drawable.dot_active : R.drawable.dot_inactive);
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotifAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(i);
            }
        } else {
            storagePermission.launch(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            });
        }
    }

    private void requestNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (switchStorage != null) switchStorage.setChecked(hasStorageAccess());
        if (switchNotif != null) switchNotif.setChecked(hasNotifAccess());
    }
}
