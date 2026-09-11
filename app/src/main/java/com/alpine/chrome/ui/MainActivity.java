package com.alpine.chrome.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;

import com.alpine.chrome.R;
import com.alpine.chrome.engine.ChromeSessionService;
import com.alpine.chrome.engine.Prefs;
import com.alpine.chrome.engine.SessionEvents;
import com.alpine.chrome.engine.SessionLog;
import com.alpine.chrome.settings.SettingsActivity;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

/**
 * Main Chromium preview host.
 * WebView loads noVNC against the local WebSocket→VNC bridge.
 */
public class MainActivity extends AppCompatActivity implements SessionEvents.Listener {

    private WebView preview;
    private View loadingOverlay;
    private TextView loadingText;
    private MaterialCardView errorBanner;
    private TextView errorBannerText;
    private FloatingActionButton fabLogs;
    private boolean sessionStarted;
    private boolean previewConnected;
    private boolean keepSessionForChild;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        if (handleSettingsShortcut(getIntent())) {
            // still set up UI after
        }

        if (!Prefs.isOnboardingDone()) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        if (!Prefs.isSetupDone()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        preview = findViewById(R.id.preview);
        loadingOverlay = findViewById(R.id.loading_overlay);
        loadingText = findViewById(R.id.loading_text);
        errorBanner = findViewById(R.id.error_banner);
        errorBannerText = findViewById(R.id.error_banner_text);
        fabLogs = findViewById(R.id.fab_logs);

        fabLogs.setOnClickListener(v -> {
            keepSessionForChild = true;
            startActivity(new Intent(this, LogsActivity.class));
        });
        updateFabVisibility();

        setupWebView();
        setupBackHandler();
        loadingOverlay.setVisibility(View.VISIBLE);
        loadingText.setText(R.string.browser_loading);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSettingsShortcut(intent);
    }

    private boolean handleSettingsShortcut(Intent intent) {
        if (intent != null && "com.alpine.chrome.OPEN_SETTINGS".equals(intent.getAction())) {
            keepSessionForChild = true;
            Intent s = new Intent(this, SettingsActivity.class);
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(s);
            return true;
        }
        return false;
    }

    private void updateFabVisibility() {
        if (fabLogs != null) {
            fabLogs.setVisibility(Prefs.isLogsFabEnabled() ? View.VISIBLE : View.GONE);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = preview.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        preview.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        preview.setLongClickable(true);
        preview.setWebChromeClient(new WebChromeClient());
        preview.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Keep overlay until noVNC signals connect; still hide after page load
                // if already connected
                if (previewConnected) {
                    loadingOverlay.setVisibility(View.GONE);
                    preview.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    String desc = error != null && error.getDescription() != null
                            ? error.getDescription().toString()
                            : getString(R.string.browser_error_generic);
                    showError(desc);
                    SessionLog.e("WebView", desc);
                }
            }
        });

        float scale = Prefs.getDisplayScale();
        preview.setInitialScale((int) (scale * 100));
    }

    /** Load noVNC viewer pointed at local WS bridge. */
    private void connectPreview(int wsPort) {
        if (previewConnected) return;
        previewConnected = true;
        SessionLog.i("Main", "Connecting preview to ws://127.0.0.1:" + wsPort);

        // Give x11vnc a moment after port open; chromium paints a bit later
        handler.postDelayed(() -> loadNoVnc(wsPort), 1500);
    }

    private void loadNoVnc(int wsPort) {
        String html = "<!DOCTYPE html><html><head>"
                + "<meta charset='utf-8'/>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'/>"
                + "<style>"
                + "html,body{margin:0;height:100%;background:#0f1115;overflow:hidden}"
                + "#screen{position:fixed;inset:0;background:#000}"
                + "#status{position:fixed;left:0;right:0;bottom:0;padding:12px;text-align:center;"
                + "color:#9aa0a6;font:14px sans-serif;background:rgba(0,0,0,.65);z-index:5}"
                + "</style>"
                + "</head><body>"
                + "<div id='screen'></div>"
                + "<div id='status'>Loading viewer…</div>"
                + "<script type='module'>\n"
                + "const statusEl = document.getElementById('status');\n"
                + "const setStatus = (t) => { statusEl.textContent = t; statusEl.style.display = t ? 'block' : 'none'; };\n"
                + "let rfb = null;\n"
                + "async function connect() {\n"
                + "  try {\n"
                + "    setStatus('Loading noVNC…');\n"
                + "    const RFB = (await import('https://cdn.jsdelivr.net/npm/@novnc/novnc@1.5.0/lib/rfb.js')).default;\n"
                + "    setStatus('Connecting to display…');\n"
                + "    if (rfb) { try { rfb.disconnect(); } catch(e){} }\n"
                + "    rfb = new RFB(document.getElementById('screen'), 'ws://127.0.0.1:" + wsPort + "');\n"
                + "    rfb.scaleViewport = true;\n"
                + "    rfb.resizeSession = false;\n"
                + "    rfb.clipViewport = false;\n"
                + "    rfb.background = '#000';\n"
                + "    rfb.showDotCursor = true;\n"
                + "    rfb.addEventListener('connect', () => setStatus(''));\n"
                + "    rfb.addEventListener('disconnect', (e) => {\n"
                + "      setStatus(e.detail && e.detail.clean ? 'Disconnected' : 'Reconnecting…');\n"
                + "      setTimeout(connect, 2000);\n"
                + "    });\n"
                + "    rfb.addEventListener('credentialsrequired', () => {\n"
                + "      rfb.sendCredentials({ password: '' });\n"
                + "    });\n"
                + "  } catch (err) {\n"
                + "    setStatus('Viewer error: ' + err);\n"
                + "    setTimeout(connect, 3000);\n"
                + "  }\n"
                + "}\n"
                + "connect();\n"
                + "</script></body></html>";

        preview.setVisibility(View.VISIBLE);
        loadingOverlay.setVisibility(View.GONE);
        preview.loadDataWithBaseURL(
                "https://local.chromium.preview/",
                html,
                "text/html",
                "UTF-8",
                null
        );
    }

    private void showError(String msg) {
        errorBanner.setVisibility(View.VISIBLE);
        errorBannerText.setText(msg);
    }

    private void startSession() {
        if (sessionStarted) return;
        SessionLog.i("Main", "Requesting session start");
        Intent i = new Intent(this, ChromeSessionService.class);
        i.setAction(ChromeSessionService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, i);
        } else {
            startService(i);
        }
        sessionStarted = true;
    }

    private void stopSession() {
        if (!sessionStarted) return;
        Intent i = new Intent(this, ChromeSessionService.class);
        i.setAction(ChromeSessionService.ACTION_STOP);
        startService(i);
        sessionStarted = false;
        previewConnected = false;
    }

    @Override
    public void onStatus(String status) {
        handler.post(() -> {
            if (loadingText != null && loadingOverlay.getVisibility() == View.VISIBLE) {
                loadingText.setText(status);
            }
        });
    }

    @Override
    public void onReady(int wsPort) {
        handler.post(() -> connectPreview(wsPort));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFabVisibility();
        SessionEvents.addListener(this);
        if (Prefs.isSetupDone()) {
            startSession();
        }
    }

    @Override
    protected void onStop() {
        SessionEvents.removeListener(this);
        if (keepSessionForChild) {
            keepSessionForChild = false;
            // Leaving for Logs/Settings — keep Chromium running
            super.onStop();
            return;
        }
        stopSession();
        super.onStop();
    }

    private void setupBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (preview != null && preview.canGoBack()) {
                    preview.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }
}
