package com.alpine.chrome.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
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
import com.alpine.chrome.settings.SettingsActivity;
import com.google.android.material.card.MaterialCardView;

/**
 * Main Chromium preview host.
 * Touch scrolling / long-press handled by WebView (noVNC or local bridge).
 * Session service is started only while this activity is in foreground.
 */
public class MainActivity extends AppCompatActivity {

    private WebView preview;
    private View loadingOverlay;
    private MaterialCardView errorBanner;
    private TextView errorBannerText;
    private boolean sessionStarted;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        // Long-press launcher shortcut → Settings only
        if (handleSettingsShortcut(getIntent())) {
            return;
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
        errorBanner = findViewById(R.id.error_banner);
        errorBannerText = findViewById(R.id.error_banner_text);

        setupWebView();
        setupBackHandler();
        // Loading overlay with Chromium logo is visible until preview is ready
        loadingOverlay.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSettingsShortcut(intent);
    }

    private boolean handleSettingsShortcut(Intent intent) {
        if (intent != null && "com.alpine.chrome.OPEN_SETTINGS".equals(intent.getAction())) {
            startActivity(new Intent(this, SettingsActivity.class));
            // If cold-started via shortcut and not set up, still allow settings after
            if (!Prefs.isOnboardingDone() || !Prefs.isSetupDone()) {
                // fall through to normal routing on next launch; settings can still open
            }
            return false; // keep MainActivity for back stack parent
        }
        return false;
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
        // Full touch / scroll behaviour
        preview.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        preview.setLongClickable(true);
        preview.setWebChromeClient(new WebChromeClient());
        preview.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loadingOverlay.setVisibility(View.GONE);
                preview.setVisibility(View.VISIBLE);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // Only surface real load errors
                showError(description != null ? description : getString(R.string.browser_error_generic));
            }
        });

        // Apply user display scale
        float scale = Prefs.getDisplayScale();
        preview.setInitialScale((int) (scale * 100));

        // Placeholder local page until VNC/noVNC endpoint is live.
        // Production: load http://127.0.0.1:<novnc-port>/vnc.html?autoconnect=true&resize=scale
        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'/>"
                + "<style>html,body{margin:0;height:100%;background:#0f1115;color:#e8eaed;font-family:sans-serif;display:flex;align-items:center;justify-content:center;text-align:center;padding:24px}"
                + "h1{font-size:1.25rem;font-weight:500}p{color:#9aa0a6;line-height:1.4}</style></head>"
                + "<body><div><h1>Chromium session starting</h1>"
                + "<p>Touch scrolling and long-press are enabled. "
                + "When the Alpine display bridge is ready this view connects automatically.</p></div></body></html>";
        preview.loadDataWithBaseURL("https://alpine.chrome.local/", html, "text/html", "UTF-8", null);
    }

    private void showError(String msg) {
        errorBanner.setVisibility(View.VISIBLE);
        errorBannerText.setText(msg);
    }

    private void startSession() {
        if (sessionStarted) return;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.isSetupDone()) {
            startSession();
        }
    }

    @Override
    protected void onStop() {
        // Do not keep Chromium running all day in the background
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

