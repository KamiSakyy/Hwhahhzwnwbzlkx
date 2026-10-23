package com.kamisakyy.webrecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Web browser + screen recorder in one window.
 * The recorder writes only what happens inside this app window into the video.
 */
public class MainActivity extends Activity implements RecorderService.Listener {

    private static final int REQ_PROJECTION = 1001;
    private static final int REQ_PERMISSIONS = 1002;
    private static final int REQ_FOLDER = 1003;

    private static final String HOME_URL = "file:///android_asset/home.html";
    private static final String PREF_LAST_URL = "last_url";
    private static final String PREF_INTRO = "intro_shown";

    private WebView webView;
    private EditText urlBar;
    private ProgressBar progress;
    private Spinner qualitySpinner;
    private CheckBox micCheck;
    private Button recordBtn;
    private Button btnGallery;
    private Button btnFolder;
    private Button btnOpen;
    private TextView statusText;
    private FrameLayout fullscreenContainer;
    private TextView btnBack;
    private TextView btnForward;
    private View toolbar;
    private View bottomBar;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean pendingStart;
    private boolean pendingGallerySave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(RecorderService.PREFS, MODE_PRIVATE);

        setContentView(R.layout.activity_main);
        bindViews();
        setupToolbar();
        setupQualitySpinner();
        setupRecorderPanel();
        setupWebView();

        String lastUrl = prefs.getString(PREF_LAST_URL, null);
        if (savedInstanceState == null && lastUrl != null && lastUrl.startsWith("http")) {
            webView.loadUrl(lastUrl);
            urlBar.setText(lastUrl);
        } else {
            webView.loadUrl(HOME_URL);
        }

        maybeAskPermissions();
        maybeShowIntro();
    }

    private void bindViews() {
        webView = findViewById(R.id.webView);
        urlBar = findViewById(R.id.urlBar);
        progress = findViewById(R.id.progress);
        qualitySpinner = findViewById(R.id.qualitySpinner);
        micCheck = findViewById(R.id.micCheck);
        recordBtn = findViewById(R.id.recordBtn);
        btnGallery = findViewById(R.id.btnGallery);
        btnFolder = findViewById(R.id.btnFolder);
        btnOpen = findViewById(R.id.btnOpen);
        statusText = findViewById(R.id.statusText);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        btnBack = findViewById(R.id.btnBack);
        btnForward = findViewById(R.id.btnForward);
        toolbar = findViewById(R.id.toolbar);
        bottomBar = findViewById(R.id.bottomBar);
    }

    // ------------------------------------------------------------------
    // Browser
    // ------------------------------------------------------------------

    private void setupToolbar() {
        findViewById(R.id.btnGo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigate(urlBar.getText().toString());
            }
        });
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView.canGoBack()) {
                    webView.goBack();
                }
            }
        });
        btnForward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView.canGoForward()) {
                    webView.goForward();
                }
            }
        });
        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_NEXT) {
                    navigate(urlBar.getText().toString());
                    return true;
                }
                return false;
            }
        });
        updateNavButtons();
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }
        String ua = s.getUserAgentString();
        if (ua != null && ua.contains("; wv")) {
            s.setUserAgentString(ua.replace("; wv", ""));
        }

        webView.setBackgroundColor(Color.BLACK);
        webView.setKeepScreenOn(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(0);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (!urlBar.hasFocus()) {
                    urlBar.setText(url.startsWith("file://") ? "" : url);
                }
                if (url.startsWith("http")) {
                    prefs.edit().putString(PREF_LAST_URL, url).apply();
                }
                updateNavButtons();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showFullscreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                hideFullscreen();
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable t) {
                toast("Скачивание не поддерживается");
            }
        });

        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
    }

    private boolean handleUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.US);
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("file")
                || scheme.equals("about") || scheme.equals("data") || scheme.equals("javascript")) {
            return false;
        }
        if (scheme.equals("intent")) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                startActivity(intent);
            } catch (Throwable t) {
                toast("Приложение не найдено");
            }
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Throwable t) {
            toast("Не удалось открыть ссылку");
        }
        return true;
    }

    private void navigate(String input) {
        String url = normalizeUrl(input);
        if (url == null) {
            return;
        }
        urlBar.clearFocus();
        hideKeyboard();
        webView.loadUrl(url);
        prefs.edit().putString(PREF_LAST_URL, url).apply();
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file://")) {
            return s;
        }
        if (s.matches("^[\\w.-]+\\.[a-zA-Z]{2,}(/.*)?$")) {
            return "https://" + s;
        }
        return "https://m.youtube.com/results?search_query=" + Uri.encode(s);
    }

    private void updateNavButtons() {
        float backAlpha = webView.canGoBack() ? 1f : 0.3f;
        float forwardAlpha = webView.canGoForward() ? 1f : 0.3f;
        btnBack.setAlpha(backAlpha);
        btnForward.setAlpha(forwardAlpha);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Fullscreen video inside the app
    // ------------------------------------------------------------------

    private void showFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = callback;
        fullscreenContainer.removeAllViews();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        fullscreenContainer.addView(view, lp);
        fullscreenContainer.setVisibility(View.VISIBLE);
        fullscreenContainer.setKeepScreenOn(true);
        toolbar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        enterImmersive();
    }

    private void hideFullscreen() {
        if (customView == null) {
            return;
        }
        fullscreenContainer.removeAllViews();
        fullscreenContainer.setVisibility(View.GONE);
        fullscreenContainer.setKeepScreenOn(false);
        customView = null;
        WebChromeClient.CustomViewCallback callback = customViewCallback;
        customViewCallback = null;
        if (callback != null) {
            try {
                callback.onCustomViewHidden();
            } catch (Throwable ignored) {
            }
        }
        toolbar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        exitImmersive();
    }

    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void exitImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && customView != null) {
            enterImmersive();
        }
    }

    // ------------------------------------------------------------------
    // Recorder panel
    // ------------------------------------------------------------------

    private void setupQualitySpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, RecorderService.QUALITY_NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);
        int saved = prefs.getInt(RecorderService.PREF_QUALITY, 1);
        if (saved >= 0 && saved < RecorderService.QUALITY_NAMES.length) {
            qualitySpinner.setSelection(saved);
        }
        qualitySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                prefs.edit().putInt(RecorderService.PREF_QUALITY, position).apply();
                if (!RecorderService.isRecording()) {
                    updateIdleStatus();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void setupRecorderPanel() {
        recordBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (RecorderService.isRecording()) {
                    RecorderService.requestStop(MainActivity.this);
                    setStatus("Остановка и сохранение…");
                } else {
                    startRecordingFlow();
                }
            }
        });

        btnGallery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveToGallery(true);
            }
        });

        btnFolder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File f = lastFile();
                if (f == null) {
                    toast("Нет записи. Сначала запишите видео.");
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(intent, REQ_FOLDER);
                } catch (Throwable t) {
                    toast("Проводник недоступен на этом устройстве");
                }
            }
        });

        btnOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLast();
            }
        });

        micCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) {
                toast("Без микрофона запишется звук приложения (Android 10+)");
            }
        });

        updateRecorderButtons();
    }

    private void startRecordingFlow() {
        if (micCheck.isChecked()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMISSIONS);
            return;
        }
        launchProjection();
    }

    private void launchProjection() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            toast("Запись экрана недоступна на этом устройстве");
            return;
        }
        try {
            Intent intent = mpm.createScreenCaptureIntent();
            startActivityForResult(intent, REQ_PROJECTION);
            if (!prefs.getBoolean("projection_hint", false)) {
                prefs.edit().putBoolean("projection_hint", true).apply();
                toast("Совет: выберите «Одно приложение» → Web Recorder, чтобы записывать только это приложение");
            }
        } catch (Throwable t) {
            toast("Не удалось открыть системное окно записи");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, RecorderService.class);
                service.setAction(RecorderService.ACTION_START);
                service.putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode);
                service.putExtra(RecorderService.EXTRA_RESULT_DATA, data);
                service.putExtra(RecorderService.EXTRA_QUALITY,
                        qualitySpinner.getSelectedItemPosition());
                service.putExtra(RecorderService.EXTRA_MIC, micCheck.isChecked());
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(service);
                    } else {
                        startService(service);
                    }
                    setStatus("Запуск записи…");
                } catch (Throwable t) {
                    toast("Не удалось запустить запись: " + t.getMessage());
                }
            } else {
                toast("Запись отменена");
            }
        } else if (requestCode == REQ_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Throwable ignored) {
                }
                saveToFolder(tree);
            }
        }
    }

    private void saveToGallery(boolean manual) {
        final File file = lastFile();
        if (file == null) {
            toast("Нет записи. Сначала запишите видео.");
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingGallerySave = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_PERMISSIONS);
            return;
        }
        pendingGallerySave = false;
        setStatus("Сохранение в Галерею…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Uri uri = RecorderStore.saveToGallery(MainActivity.this, file, file.getName());
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (uri != null) {
                            prefs.edit().putString(RecorderService.PREF_LAST_URI, uri.toString()).apply();
                            toast("Сохранено в Галерею / Movies/" + RecorderStore.ALBUM);
                            updateIdleStatus();
                            updateRecorderButtons();
                        } else {
                            toast("Не удалось сохранить видео");
                            updateIdleStatus();
                        }
                    }
                });
            }
        }, "manual-save").start();
    }

    private void saveToFolder(Uri tree) {
        final File file = lastFile();
        if (file == null) {
            toast("Нет записи. Сначала запишите видео.");
            return;
        }
        setStatus("Сохранение в выбранную папку…");
        final String name = file.getName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Uri saved = RecorderStore.saveToTree(MainActivity.this, file, tree, name);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (saved != null) {
                            toast("Файл сохранён в выбранную папку");
                            setStatus("Сохранено в папку проводника: " + name);
                        } else {
                            toast("Не удалось сохранить в выбранную папку");
                            updateIdleStatus();
                        }
                    }
                });
            }
        }, "folder-save").start();
    }

    private void openLast() {
        String uriString = prefs.getString(RecorderService.PREF_LAST_URI, null);
        if (uriString == null) {
            if (lastFile() != null) {
                toast("Видео ещё сохраняется в Галерею…");
            } else {
                toast("Нет сохранённого видео");
            }
            return;
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(uriString), "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
        } catch (Throwable t) {
            toast("Не удалось открыть видео");
        }
    }

    private File lastFile() {
        String path = prefs.getString(RecorderService.PREF_LAST_FILE, null);
        if (path == null) {
            return null;
        }
        File f = new File(path);
        return f.exists() && f.length() > 0 ? f : null;
    }

    private void updateRecorderButtons() {
        boolean hasFile = lastFile() != null;
        boolean hasSaved = prefs.getString(RecorderService.PREF_LAST_URI, null) != null;
        btnGallery.setEnabled(hasFile);
        btnFolder.setEnabled(hasFile);
        btnOpen.setEnabled(hasSaved);
        btnGallery.setAlpha(hasFile ? 1f : 0.45f);
        btnFolder.setAlpha(hasFile ? 1f : 0.45f);
        btnOpen.setAlpha(hasSaved ? 1f : 0.45f);
    }

    private void updateIdleStatus() {
        if (RecorderService.isRecording()) {
            return;
        }
        String q = RecorderService.QUALITY_NAMES[qualitySpinner.getSelectedItemPosition()];
        statusText.setTextColor(getColor(R.color.text_dim));
        if (prefs.getString(RecorderService.PREF_LAST_URI, null) != null) {
            setStatus("Готово • качество " + q + " • последнее видео в Галерее");
        } else {
            setStatus("Готово • качество " + q);
        }
        updateRecorderButtons();
    }

    private void setStatus(String text) {
        statusText.setText(text);
    }

    // ------------------------------------------------------------------
    // RecorderService.Listener
    // ------------------------------------------------------------------

    @Override
    public void onRecorderState(final boolean recording, final int seconds, final long bytes) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (recording) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    recordBtn.setText("■ Стоп");
                    recordBtn.setBackgroundResource(R.drawable.bg_stop);
                    recordBtn.setTextColor(getColor(R.color.text));
                    statusText.setTextColor(getColor(R.color.accent));
                    statusText.setText("● REC " + formatTime(seconds) + " • "
                            + RecorderService.QUALITY_NAMES[qualitySpinner.getSelectedItemPosition()]
                            + " • " + formatSize(bytes));
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    recordBtn.setText("● Запись");
                    recordBtn.setBackgroundResource(R.drawable.bg_record);
                    recordBtn.setTextColor(Color.WHITE);
                    updateIdleStatus();
                }
            }
        });
    }

    @Override
    public void onRecorderSaved(final Uri uri, final String label) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                toast("Сохранено: " + label);
                updateIdleStatus();
                updateRecorderButtons();
            }
        });
    }

    @Override
    public void onRecorderError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                toast(message);
                statusText.setTextColor(getColor(R.color.accent));
                setStatus("Ошибка: " + message);
                updateRecorderButtons();
            }
        });
    }

    // ------------------------------------------------------------------
    // Lifecycle / permissions
    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        RecorderService.setListener(this);
        refreshRecorderUi();
    }

    @Override
    protected void onStop() {
        RecorderService.setListener(null);
        super.onStop();
    }

    private void refreshRecorderUi() {
        boolean rec = RecorderService.isRecording();
        File f = lastFile();
        long bytes = f != null ? f.length() : 0;
        onRecorderState(rec, RecorderService.getElapsedSeconds(), bytes);
        updateRecorderButtons();
    }

    private void maybeAskPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("asked_notif", false)) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
            prefs.edit().putBoolean("asked_notif", true).apply();
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("asked_storage", false)) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            prefs.edit().putBoolean("asked_storage", true).apply();
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) {
            return;
        }
        boolean audioGranted = true;
        boolean storageGranted = true;
        for (int i = 0; i < permissions.length; i++) {
            int result = i < grantResults.length ? grantResults[i] : PackageManager.PERMISSION_DENIED;
            if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])) {
                audioGranted = result == PackageManager.PERMISSION_GRANTED;
            } else if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])) {
                storageGranted = result == PackageManager.PERMISSION_GRANTED;
            }
        }
        if (pendingStart) {
            pendingStart = false;
            if (audioGranted) {
                launchProjection();
            } else {
                micCheck.setChecked(false);
                toast("Микрофон запрещён — запись пойдёт без него");
                launchProjection();
            }
        }
        if (pendingGallerySave) {
            pendingGallerySave = false;
            if (storageGranted) {
                saveToGallery(true);
            } else {
                toast("Нет разрешения на запись в память");
            }
        }
    }

    private void maybeShowIntro() {
        if (prefs.getBoolean(PREF_INTRO, false)) {
            return;
        }
        prefs.edit().putBoolean(PREF_INTRO, true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Web Recorder")
                .setMessage("1. Откройте любое видео в браузере приложения.\n\n"
                        + "2. Нажмите «● Запись» и разрешите показ экрана. На Android 14+ "
                        + "выберите «Одно приложение» → Web Recorder.\n\n"
                        + "3. Переведите плеер в полноэкранный режим — запись сохранит это "
                        + "состояние, даже если свернуть приложение.\n\n"
                        + "4. После остановки видео автоматически попадает в Галерею "
                        + "(Movies/WebRecorder). Кнопка «В папку…» сохранит его в любую "
                        + "папку через проводник.\n\n"
                        + "Качество записи выбирается в списке 480p / 720p / 1080p / Экран.")
                .setPositiveButton("Понятно", null)
                .show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static String formatTime(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 МБ";
        }
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1) {
            return String.format(Locale.US, "%.0f КБ", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f МБ", mb);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideFullscreen();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        RecorderService.setListener(null);
        if (webView != null) {
            try {
                webView.loadUrl("about:blank");
                webView.stopLoading();
                webView.setWebChromeClient(null);
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
        super.onDestroy();
    }
}
