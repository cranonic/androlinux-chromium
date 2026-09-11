package com.alpine.chrome.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.alpine.chrome.R;
import com.alpine.chrome.engine.ChromiumInstaller;
import com.alpine.chrome.engine.Prefs;
import com.alpine.chrome.engine.RootfsManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/**
 * Install Alpine minirootfs + minimal Chromium packages.
 * Shows only user-facing status + real errors (no backend chatter).
 */
public class SetupActivity extends AppCompatActivity {

    private LinearProgressIndicator progress;
    private TextView status;
    private MaterialCardView errorCard;
    private TextView errorMessage;
    private volatile boolean running;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        errorCard = findViewById(R.id.error_card);
        errorMessage = findViewById(R.id.error_message);
        MaterialButton btnRetry = findViewById(R.id.btn_retry);
        btnRetry.setOnClickListener(v -> startInstall());

        if (Prefs.isSetupDone()) {
            goMain();
            return;
        }
        startInstall();
    }

    private void startInstall() {
        if (running) return;
        running = true;
        errorCard.setVisibility(View.GONE);
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.setup_status_downloading); // "Downloading environment…"

        new Thread(() -> {
            RootfsManager.ProgressListener listener = new RootfsManager.ProgressListener() {
                @Override
                public void onStatus(String userMessage) {
                    runOnUiThread(() -> status.setText(userMessage));
                }

                @Override
                public void onProgress(int percent) {
                    runOnUiThread(() -> {
                        if (percent < 0) {
                            progress.setIndeterminate(true);
                        } else {
                            progress.setIndeterminate(false);
                            progress.setProgressCompat(percent, true);
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    // handled via exception path
                }
            };

            try {
                RootfsManager rootfs = new RootfsManager(SetupActivity.this);
                rootfs.ensureAlpine(listener);
                ChromiumInstaller installer = new ChromiumInstaller(rootfs);
                installer.install(listener);
                Prefs.setSetupDone(true);
                runOnUiThread(this::goMain);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> showError(msg));
            } finally {
                running = false;
            }
        }, "alpine-setup").start();
    }

    private void showError(String msg) {
        progress.setVisibility(View.INVISIBLE);
        errorCard.setVisibility(View.VISIBLE);
        errorMessage.setText(msg);
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
