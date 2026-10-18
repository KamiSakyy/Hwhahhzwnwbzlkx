package com.lsengine.runtime;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.lsengine.engine.aot.AotRuntime;
import com.lsengine.engine.bundle.AppBundle;
import com.lsengine.engine.lang.BytecodeFile;
import com.lsengine.engine.lang.Chunk;
import com.lsengine.engine.nativecodec.Codec;
import com.lsengine.engine.runtime.AotHost;
import com.lsengine.engine.runtime.LsRuntime;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Хост для собранных приложений LS: распаковывает зашифрованный контейнер .c
 * из ассетов, загружает байт-код и запускает виртуальную машину.
 *
 * Никакой интерпретации исходников: внутри только скомпилированный байт-код.
 */
public class RuntimeActivity extends Activity implements LsRuntime.Host {

    private static final String TAG = "LsRuntime";
    private static final String BUNDLE_ASSET = "lsapp.c";

    private RuntimeSurfaceView surface;
    private LsRuntime runtime;
    private AotRuntime aotRuntime;
    private AotHost aotHost;
    private ToneGenerator tone;
    private TextView errorView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_runtime);

        surface = findViewById(R.id.runtimeSurface);
        errorView = findViewById(R.id.runtimeError);

        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
        } catch (Exception e) {
            tone = null;
        }

        try {
            byte[] blob = readAsset(BUNDLE_ASSET);
            byte[] plain = Codec.open(blob);
            AppBundle bundle = AppBundle.fromBytes(plain);
            applyOrientation(bundle.orientation);
            Chunk chunk = BytecodeFile.read(bundle.bytecode);
            AotRuntime native_ = AotRuntime.loadLibrary("lsapp");
            if (native_ != null) {
                // В APK лежит машинный код: запускаем его напрямую.
                aotRuntime = native_;
                aotHost = new AotHost(native_);
                aotHost.setSurface(surface);
                aotHost.setHost(this);
                AotRuntime.setPlatform(aotHost);
                surface.setDriver(aotHost);
                aotHost.start();
                Log.i(TAG, "запуск машинного кода (AOT)");
            } else {
                runtime = new LsRuntime();
                runtime.setSurface(surface);
                runtime.setHost(this);
                runtime.setAppInfo(bundle.appName, bundle.versionName);
                runtime.setLogSink(message -> Log.i(TAG, message));
                surface.setDriver(runtime);
                runtime.load(chunk);
                Log.i(TAG, "запуск виртуальной машины");
            }
            surface.startRendering();
        } catch (Exception e) {
            Log.e(TAG, "не удалось запустить приложение", e);
            showError(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void applyOrientation(String orientation) {
        if ("landscape".equals(orientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else if ("portrait".equals(orientation)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }

    private void showError(String message) {
        errorView.setVisibility(View.VISIBLE);
        errorView.setText("Не удалось запустить приложение:\n" + message);
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    @Override
    public void log(String message) {
        Log.i(TAG, message);
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
        surface.startRendering();
    }

    @Override
    protected void onPause() {
        super.onPause();
        surface.stopRendering();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (runtime != null) {
            runtime.stop();
        }
        if (aotRuntime != null) {
            aotRuntime.close();
            aotRuntime = null;
        }
        if (tone != null) {
            tone.release();
        }
    }
}
