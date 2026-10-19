package com.lsengine.studio;

import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.chip.Chip;
import com.google.android.material.appbar.MaterialToolbar;
import com.lsengine.engine.aot.AotRuntime;
import com.lsengine.engine.runtime.AotHost;
import com.lsengine.engine.runtime.LsRuntime;
import com.lsengine.studio.ui.LsSurfaceView;

/**
 * Живой предпросмотр: скомпилированный байт-код выполняется прямо здесь,
 * без APK и без перезапуска приложения.
 */
public class PreviewActivity extends AppCompatActivity implements LsRuntime.Host {

    /** Машинный код программы (ELF), если он собран AOT-компилятором. */
    public static final String EXTRA_AOT_LIBRARY = "aot_library";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_PROJECT_ID = "project_id";

    private LsSurfaceView surface;
    private AotRuntime aotRuntime;
    private AotHost aotHost;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Chip statsChip;
    private View errorCard;
    private TextView errorText;
    private ToneGenerator tone;
    private int frames;
    private long lastFpsUpdate;

    private final Runnable fpsTicker = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (lastFpsUpdate == 0) {
                lastFpsUpdate = now;
            }
            long delta = now - lastFpsUpdate;
            if (delta >= 500) {
                int fps = (int) (frames * 1000L / delta);
                statsChip.setText(fps + " FPS · " + frames + " кадров");
                frames = 0;
                lastFpsUpdate = now;
            }
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        surface = findViewById(R.id.surfaceView);
        statsChip = findViewById(R.id.statsChip);
        errorCard = findViewById(R.id.errorCard);
        errorText = findViewById(R.id.errorText);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getIntent().getStringExtra(EXTRA_NAME));
        toolbar.setNavigationOnClickListener(v -> finish());
        findViewById(R.id.restartButton).setOnClickListener(v -> restart());
        findViewById(R.id.buildButton).setOnClickListener(v -> {
            Intent intent = new Intent(this, EditorActivity.class);
            intent.putExtra(EditorActivity.EXTRA_PROJECT_ID, getIntent().getStringExtra(EXTRA_PROJECT_ID));
            intent.putExtra(EditorActivity.EXTRA_OPEN_BUILD, true);
            startActivity(intent);
            finish();
        });

        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
        } catch (Exception ignored) {
            tone = null;
        }
        startRuntime();
    }

    private void startRuntime() {
        errorCard.setVisibility(View.GONE);
        errorText.setText("");
        byte[] library = getIntent().getByteArrayExtra(EXTRA_AOT_LIBRARY);
        if (library == null || library.length == 0) {
            showError("Не получен машинный код Android ARM64");
            return;
        }
        if (!AotRuntime.isAvailable()) {
            showError("Не загружен нативный рантайм ARM64 (liblsrt.so)");
            return;
        }
        startAot(library);
    }

    /** Запуск машинного кода: без байт-кода и без интерпретатора. */
    private void startAot(byte[] library) {
        AotRuntime loaded = AotRuntime.load(library, getCodeCacheDir());
        if (loaded == null) {
            showError("Не удалось загрузить машинный код Android ARM64: " + AotRuntime.lastError());
            return;
        }
        AotHost host = new AotHost(loaded);
        host.setSurface(surface);
        host.setHost(this);
        AotRuntime.setPlatform(host);
        aotRuntime = loaded;
        aotHost = host;
        surface.setDriver(host);
        try {
            host.start();
            surface.startRendering();
        } catch (Exception e) {
            showError(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void restart() {
        if (aotRuntime != null) {
            aotRuntime.close();
            aotRuntime = null;
        }
        startRuntime();
    }

    @Override
    public void log(String message) {
        runOnUiThread(() -> {
            errorCard.setVisibility(View.VISIBLE);
            errorText.setText(message);
        });
    }

    private void showError(String message) {
        errorCard.setVisibility(View.VISIBLE);
        errorText.setText("✖ " + message);
    }

    @Override
    public void beep(int frequency, int millis) {
        if (tone == null) {
            return;
        }
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, Math.max(30, millis));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(fpsTicker);
        if (surface != null) {
            surface.startRendering();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(fpsTicker);
        if (surface != null) {
            surface.stopRendering();
        }
    }

    @Override
    protected void onDestroy() {
        if (aotRuntime != null) {
            aotRuntime.close();
            aotRuntime = null;
        }
        super.onDestroy();
        if (tone != null) {
            tone.release();
        }
    }
}
