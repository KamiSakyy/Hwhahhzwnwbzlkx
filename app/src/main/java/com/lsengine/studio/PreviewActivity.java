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
import com.google.android.material.toolbar.MaterialToolbar;
import com.lsengine.engine.lang.BytecodeFile;
import com.lsengine.engine.lang.Chunk;
import com.lsengine.engine.runtime.LsRuntime;
import com.lsengine.studio.ui.LsSurfaceView;

/**
 * Живой предпросмотр: скомпилированный байт-код выполняется прямо здесь,
 * без APK и без перезапуска приложения.
 */
public class PreviewActivity extends AppCompatActivity implements LsRuntime.Host {

    public static final String EXTRA_BYTECODE = "bytecode";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_PROJECT_ID = "project_id";

    private LsSurfaceView surface;
    private LsRuntime runtime;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Chip statsChip;
    private View errorCard;
    private TextView errorText;
    private ToneGenerator tone;
    private int frames;
    private long lastFpsUpdate;
    private byte[] bytecode;

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

        bytecode = getIntent().getByteArrayExtra(EXTRA_BYTECODE);
        if (bytecode == null) {
            finish();
            return;
        }
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
        runtime = new LsRuntime();
        runtime.setSurface(surface);
        runtime.setHost(this);
        runtime.setAppInfo(getIntent().getStringExtra(EXTRA_NAME), "1.0");
        runtime.setLogSink(this::log);
        surface.setRuntime(runtime);
        try {
            Chunk chunk = BytecodeFile.read(bytecode);
            runtime.load(chunk);
            surface.startRendering();
        } catch (Exception e) {
            showError(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void restart() {
        if (runtime != null) {
            runtime.stop();
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
        super.onDestroy();
        if (runtime != null) {
            runtime.stop();
        }
        if (tone != null) {
            tone.release();
        }
    }
}
