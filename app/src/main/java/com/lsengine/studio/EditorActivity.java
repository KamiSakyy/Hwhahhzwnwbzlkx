package com.lsengine.studio;

import android.content.Intent;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.lsengine.engine.apk.ApkBuilder;
import com.lsengine.engine.aot.AotModule;
import com.lsengine.engine.lang.BytecodeFile;
import com.lsengine.engine.lang.Chunk;
import com.lsengine.engine.lang.Compiler;
import com.lsengine.engine.lang.LsError;
import com.lsengine.engine.project.ProjectStore;
import com.lsengine.studio.build.ApkBuildManager;
import com.lsengine.studio.ui.LsCodeEditor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Редактор проекта: код, файлы, живой запуск и офлайн-сборка APK.
 */
public class EditorActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_ID = "project_id";
    public static final String EXTRA_OPEN_BUILD = "open_build";

    private ProjectStore store;
    private ProjectStore.Project project;
    private LsCodeEditor editor;
    private ChipGroup fileChips;
    private TextView consoleText;
    private TextView compileStatus;
    private String currentFile;
    private boolean dirty;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        store = new ProjectStore(getFilesDir());
        String id = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        project = id == null ? null : store.load(id);
        if (project == null) {
            finish();
            return;
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(project.name);
        toolbar.setSubtitle(project.packageName);
        toolbar.setNavigationOnClickListener(v -> {
            save();
            finish();
        });
        toolbar.inflateMenu(R.menu.menu_editor);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

        editor = findViewById(R.id.codeEditor);
        fileChips = findViewById(R.id.fileChips);
        consoleText = findViewById(R.id.consoleText);
        compileStatus = findViewById(R.id.compileStatus);

        findViewById(R.id.clearConsole).setOnClickListener(v -> {
            consoleText.setText("");
            compileStatus.setText("");
        });
        findViewById(R.id.liveButton).setOnClickListener(v -> runLive());
        findViewById(R.id.buildButton).setOnClickListener(v -> showBuildDialog());

        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                dirty = true;
                if (currentFile != null) {
                    project.files.put(currentFile, s.toString());
                }
            }
        });

        buildChips();
        selectFile(project.mainFile());

        if (getIntent().getBooleanExtra(EXTRA_OPEN_BUILD, false)) {
            showBuildDialog();
        }
    }

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save) {
            save();
            log("Сохранено.");
            return true;
        }
        if (id == R.id.action_templates) {
            showTemplatePicker();
            return true;
        }
        if (id == R.id.action_console) {
            View card = findViewById(R.id.consoleCard);
            card.setVisibility(card.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return true;
        }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return false;
    }

    // ---------------- файлы ----------------

    private void buildChips() {
        fileChips.removeAllViews();
        List<String> names = new ArrayList<>(project.files.keySet());
        for (String name : names) {
            Chip chip = new Chip(this);
            chip.setText(name);
            chip.setCheckable(true);
            chip.setTextSize(12f);
            chip.setOnClickListener(v -> selectFile(name));
            fileChips.addView(chip);
        }
        Chip add = new Chip(this);
        add.setText("+");
        add.setCheckable(false);
        add.setChipIconVisible(false);
        add.setOnClickListener(v -> addFile());
        fileChips.addView(add);
    }

    private void selectFile(String name) {
        if (name == null || !project.files.containsKey(name)) {
            return;
        }
        if (currentFile != null) {
            project.files.put(currentFile, editor.getText() == null ? "" : editor.getText().toString());
        }
        currentFile = name;
        editor.setText(project.files.get(name));
        editor.setSelection(0);
        for (int i = 0; i < fileChips.getChildCount(); i++) {
            View child = fileChips.getChildAt(i);
            if (child instanceof Chip) {
                Chip chip = (Chip) child;
                if (name.equals(chip.getText().toString())) {
                    chip.setChecked(true);
                }
            }
        }
    }

    private void addFile() {
        final EditText input = new EditText(this);
        input.setHint("main2.ls");
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.new_file)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = input.getText() == null ? "" : input.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    if (!name.endsWith(".ls")) {
                        name = name + ".ls";
                    }
                    if (project.files.containsKey(name)) {
                        log("Файл уже существует: " + name);
                        return;
                    }
                    project.files.put(name, "-- " + name + "\n");
                    save();
                    buildChips();
                    selectFile(name);
                })
                .show();
    }

    // ---------------- живой запуск ----------------

    private void runLive() {
        save();
        consoleText.setText("");
        compileStatus.setText("Компиляция…");
        final String source = project.files.get(project.mainFile());
        try {
            Compiler compiler = new Compiler();
            Chunk chunk = compiler.compile(source == null ? "" : source);
            byte[] bytecode = BytecodeFile.write(chunk);
            compileStatus.setText("Готово");
            log("Байт-код собран: " + bytecode.length + " байт");
            byte[] library = null;
            try {
                long started = System.currentTimeMillis();
                library = AotModule.compile(chunk, isArm64Device());
                log("Машинный код собран: " + library.length + " байт за "
                        + (System.currentTimeMillis() - started) + " мс");
            } catch (Throwable error) {
                log("AOT недоступен, используется виртуальная машина");
            }
            Intent intent = new Intent(this, PreviewActivity.class);
            intent.putExtra(PreviewActivity.EXTRA_BYTECODE, bytecode);
            if (library != null) {
                intent.putExtra(PreviewActivity.EXTRA_AOT_LIBRARY, library);
            }
            intent.putExtra(PreviewActivity.EXTRA_NAME, project.name);
            intent.putExtra(PreviewActivity.EXTRA_PROJECT_ID, project.id);
            startActivity(intent);
        } catch (LsError e) {
            compileStatus.setText("Ошибка");
            log("✖ " + e.pretty());
        } catch (Exception e) {
            compileStatus.setText("Ошибка");
            log("✖ " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /** Требуется ли машинный код для AArch64 (arm64-v8a). */
    private boolean isArm64Device() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) {
            return false;
        }
        return abis[0].startsWith("arm64");
    }

    // ---------------- сборка APK ----------------

    private void showBuildDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_build, null);
        TextInputEditText nameInput = view.findViewById(R.id.appNameInput);
        TextInputEditText packageInput = view.findViewById(R.id.packageInput);
        TextInputEditText versionInput = view.findViewById(R.id.versionInput);
        RadioGroup orientation = view.findViewById(R.id.orientationGroup);
        nameInput.setText(project.name);
        packageInput.setText(project.packageName);
        versionInput.setText(project.versionName);
        orientation.check(R.id.orientationPortrait);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.build_dialog_title)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.build_action, (dialog, which) -> {
                    ApkBuilder.Options options = new ApkBuilder.Options();
                    options.appName = text(nameInput, project.name);
                    options.packageName = text(packageInput, project.packageName);
                    options.versionName = text(versionInput, "1.0.0");
                    options.orientation = orientation.getCheckedRadioButtonId() == R.id.orientationLandscape
                            ? "landscape" : "portrait";
                    options.description = "Приложение создано в LS Studio";
                    startBuild(options);
                })
                .show();
    }

    private void startBuild(ApkBuilder.Options options) {
        View progress = LayoutInflater.from(this).inflate(R.layout.dialog_build_progress, null);
        final TextView stage = progress.findViewById(R.id.progressStage);
        final android.widget.ProgressBar bar = progress.findViewById(R.id.progressBar);
        stage.setText("Подготовка…");
        bar.setIndeterminate(true);
        final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.build_dialog_title)
                .setView(progress)
                .setCancelable(false)
                .create();
        dialog.show();

        new ApkBuildManager(this).build(project, options, new ApkBuildManager.Callback() {
            @Override
            public void onStage(String text) {
                runOnUiThread(() -> stage.setText(text));
            }

            @Override
            public void onResult(ApkBuildManager.Result result) {
                dialog.dismiss();
                if (result.success) {
                    log("✔ " + result.message);
                    showBuildResult(result);
                } else {
                    log("✖ " + result.message);
                    new MaterialAlertDialogBuilder(EditorActivity.this)
                            .setTitle(R.string.build_failed)
                            .setMessage(result.message)
                            .setPositiveButton(R.string.close, null)
                            .show();
                }
            }
        });
    }

    private void showBuildResult(ApkBuildManager.Result result) {
        StringBuilder message = new StringBuilder();
        message.append(result.message).append("\n\n");
        if (result.file != null) {
            message.append("Файл:\n").append(result.file.getAbsolutePath());
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.build_success)
                .setMessage(message.toString())
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.install, (d, w) -> installApk(result))
                .show();
    }

    private void installApk(ApkBuildManager.Result result) {
        if (result.file == null) {
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", result.file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.install)
                    .setMessage("Откройте папку «Загрузки/LS Studio» и установите APK вручную.")
                    .setPositiveButton(R.string.close, null)
                    .show();
        }
    }

    // ---------------- вспомогательное ----------------

    private void showTemplatePicker() {
        String[] names = {"Пустое приложение", "Арканоид", "Кликер", "Анимация", "Счётчик"};
        String[] sources = {
                com.lsengine.studio.data.Templates.EMPTY,
                com.lsengine.studio.data.Templates.GAME,
                com.lsengine.studio.data.Templates.CLICKER,
                com.lsengine.studio.data.Templates.ANIMATION,
                com.lsengine.studio.data.Templates.COUNTER};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.templates)
                .setItems(names, (dialog, which) -> {
                    editor.setText(sources[which]);
                    editor.setSelection(0);
                    dirty = true;
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void log(String message) {
        if (consoleText.getText().length() > 0) {
            consoleText.append("\n");
        }
        consoleText.append(message);
    }

    private static String text(TextInputEditText input, String fallback) {
        CharSequence value = input.getText();
        if (value == null || value.toString().trim().isEmpty()) {
            return fallback;
        }
        return value.toString().trim();
    }

    private void save() {
        if (project == null) {
            return;
        }
        if (currentFile != null && editor.getText() != null) {
            project.files.put(currentFile, editor.getText().toString());
        }
        try {
            store.save(project);
            dirty = false;
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }
}
