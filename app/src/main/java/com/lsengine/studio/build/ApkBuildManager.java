package com.lsengine.studio.build;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.lsengine.engine.apk.ApkBuilder;
import com.lsengine.engine.aot.AotModule;
import com.lsengine.engine.bundle.AppBundle;
import com.lsengine.engine.lang.BytecodeFile;
import com.lsengine.engine.lang.Chunk;
import com.lsengine.engine.lang.Compiler;
import com.lsengine.engine.nativecodec.Codec;
import com.lsengine.engine.project.ProjectStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Офлайн-сборщик пользовательского APK.
 * Компиляция, шифрование контейнера и перепаковка выполняются в фоне,
 * без сетевых запросов и внешних build-инструментов.
 */
public final class ApkBuildManager {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ls-offline-apk-builder");
        thread.setDaemon(true);
        return thread;
    });

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ApkBuildManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public interface Callback {
        void onStage(String text);
        void onResult(Result result);
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        public final File file;

        private Result(boolean success, String message, File file) {
            this.success = success;
            this.message = message;
            this.file = file;
        }
    }

    public void build(ProjectStore.Project project, ApkBuilder.Options options, Callback callback) {
        if (project == null || options == null || callback == null) {
            throw new IllegalArgumentException("project, options и callback обязательны");
        }
        WORKER.execute(() -> {
            try {
                notifyStage(callback, "Компиляция исходника LS…");
                if (project.files.isEmpty()) {
                    throw new IllegalStateException("В проекте нет исходных файлов");
                }
                String entryFile = project.mainFile();
                String source = project.files.get(entryFile);
                if (source == null || source.trim().isEmpty()) {
                    throw new IllegalStateException("Главный файл проекта пуст: " + entryFile);
                }
                Chunk chunk = new Compiler().compile(source);
                byte[] bytecode = BytecodeFile.write(chunk);

                notifyStage(callback, "Подготовка зашифрованного контейнера .c…");
                if (!Codec.isNativeAvailable()) {
                    throw new IllegalStateException(
                            "Нативный кодек не загружен; безопасная сборка APK недоступна");
                }
                AppBundle bundle = createBundle(project, options, entryFile, bytecode);
                byte[] encryptedBundle = Codec.seal(bundle.toBytes());

                notifyStage(callback, "Генерация машинного кода x86-64 и ARM64…");
                options.nativeLibs.put("lib/x86_64/liblsapp.so", AotModule.compileX64(chunk));
                options.nativeLibs.put("lib/arm64-v8a/liblsapp.so", AotModule.compileArm64(chunk));

                notifyStage(callback, "Загрузка runtime shell…");
                byte[] shell = readAsset("runtime/shell.apk");

                notifyStage(callback, "Перепаковка и подпись APK офлайн…");
                byte[] apk = ApkBuilder.build(shell, encryptedBundle, options);
                File output = outputFile(options);
                writeAtomically(output, apk);

                long sizeMb = (output.length() + 1024L * 1024L - 1) / (1024L * 1024L);
                Result result = new Result(true,
                        "APK собран и подписан офлайн (" + sizeMb + " МБ)", output);
                notifyResult(callback, result);
            } catch (Throwable error) {
                String message = error.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = error.getClass().getSimpleName();
                }
                notifyResult(callback, new Result(false, message, null));
            }
        });
    }

    private AppBundle createBundle(ProjectStore.Project project, ApkBuilder.Options options,
                                   String entryFile, byte[] bytecode) {
        AppBundle bundle = new AppBundle();
        bundle.appName = options.appName;
        bundle.packageName = options.packageName;
        bundle.versionName = options.versionName;
        bundle.versionCode = options.versionCode;
        bundle.orientation = options.orientation;
        bundle.description = options.description;
        bundle.entryFile = entryFile;
        bundle.bytecode = bytecode;
        for (Map.Entry<String, String> entry : project.files.entrySet()) {
            String name = safeFileName(entry.getKey());
            bundle.files.put(name, entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return bundle;
    }

    private byte[] readAsset(String path) throws Exception {
        try (InputStream input = appContext.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private File outputFile(ApkBuilder.Options options) throws Exception {
        File downloads = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null) {
            downloads = new File(appContext.getFilesDir(), "Download");
        }
        File directory = new File(downloads, "LS Studio");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Не удалось создать папку для APK");
        }
        String packageName = options.packageName == null ? "ls-app"
                : options.packageName.replaceAll("[^A-Za-z0-9._-]", "_");
        String version = options.versionName == null ? "1.0.0"
                : options.versionName.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(directory, packageName + "-" + version + ".apk");
    }

    private static String safeFileName(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Недопустимое имя файла проекта");
        }
        return name;
    }

    private static void writeAtomically(File target, byte[] bytes) throws Exception {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IllegalStateException("Не удалось заменить предыдущую сборку");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IllegalStateException("Не удалось сохранить собранный APK");
        }
    }

    private void notifyStage(Callback callback, String text) {
        mainHandler.post(() -> callback.onStage(text));
    }

    private void notifyResult(Callback callback, Result result) {
        mainHandler.post(() -> callback.onResult(result));
    }
}
