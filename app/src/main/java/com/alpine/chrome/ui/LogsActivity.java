package com.alpine.chrome.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.alpine.chrome.R;
import com.alpine.chrome.engine.SessionLog;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.List;

/** Terminal-style viewer for session process output. */
public class LogsActivity extends AppCompatActivity implements SessionLog.Listener {

    private TextView logText;
    private ScrollView scroll;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        logText = findViewById(R.id.log_text);
        scroll = findViewById(R.id.scroll);

        MaterialButton btnClear = findViewById(R.id.btn_clear);
        MaterialButton btnCopy = findViewById(R.id.btn_copy);

        btnClear.setOnClickListener(v -> {
            SessionLog.clear();
            logText.setText("");
        });
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("logs", SessionLog.dump()));
                Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show();
            }
        });

        refreshFromSnapshot();
    }

    private void refreshFromSnapshot() {
        List<String> lines = SessionLog.snapshot();
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append('\n');
        }
        if (sb.length() == 0) {
            sb.append(getString(R.string.logs_empty));
        }
        logText.setText(sb.toString());
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionLog.addListener(this);
        refreshFromSnapshot();
    }

    @Override
    protected void onStop() {
        SessionLog.removeListener(this);
        super.onStop();
    }

    @Override
    public void onLogLine(String line) {
        runOnUiThread(() -> {
            CharSequence cur = logText.getText();
            if (cur != null && cur.toString().equals(getString(R.string.logs_empty))) {
                logText.setText(line + "\n");
            } else {
                logText.append(line + "\n");
            }
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
