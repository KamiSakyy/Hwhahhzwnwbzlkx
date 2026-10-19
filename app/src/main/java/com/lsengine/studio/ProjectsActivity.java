package com.lsengine.studio;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.lsengine.engine.project.ProjectStore;
import com.lsengine.studio.data.Templates;
import com.lsengine.studio.ui.ProjectAdapter;

import java.util.List;

/**
 * Стартовый экран: список проектов, создание новых, быстрый запуск сборки APK.
 */
public class ProjectsActivity extends AppCompatActivity implements ProjectAdapter.Listener {

    private ProjectStore store;
    private ProjectAdapter adapter;
    private ExtendedFloatingActionButton fab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_projects);
        applyInsets();

        store = new ProjectStore(getFilesDir());
        fab = findViewById(R.id.newProjectFab);
        fab.setOnClickListener(v -> showNewProjectDialog());

        RecyclerView list = findViewById(R.id.projectList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProjectAdapter(this);
        list.setAdapter(adapter);

        findViewById(R.id.toolbar).setOnClickListener(v -> {
        });
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
    }

    private void applyInsets() {
        View root = findViewById(R.id.appBar);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, 0);
            return insets;
        });
        View fabView = findViewById(R.id.newProjectFab);
        ViewCompat.setOnApplyWindowInsetsListener(fabView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams lp =
                    (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) v.getLayoutParams();
            lp.bottomMargin = bars.bottom + (int) (20 * getResources().getDisplayMetrics().density);
            v.setLayoutParams(lp);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<ProjectStore.Project> projects = store.list();
        adapter.submit(projects);
        RecyclerView list = findViewById(R.id.projectList);
        View empty = findViewById(R.id.emptyState);
        if (projects.isEmpty()) {
            list.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
        } else {
            list.setVisibility(View.VISIBLE);
            empty.setVisibility(View.GONE);
        }
    }

    // ---------------- ProjectAdapter.Listener ----------------

    @Override
    public void onOpen(ProjectStore.Project project) {
        Intent intent = new Intent(this, EditorActivity.class);
        intent.putExtra(EditorActivity.EXTRA_PROJECT_ID, project.id);
        startActivity(intent);
    }

    @Override
    public void onBuild(ProjectStore.Project project) {
        Intent intent = new Intent(this, EditorActivity.class);
        intent.putExtra(EditorActivity.EXTRA_PROJECT_ID, project.id);
        intent.putExtra(EditorActivity.EXTRA_OPEN_BUILD, true);
        startActivity(intent);
    }

    @Override
    public void onMenu(ProjectStore.Project project, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.open_project);
        menu.getMenu().add(0, 2, 1, R.string.build_apk);
        menu.getMenu().add(0, 3, 2, R.string.delete_project);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                onOpen(project);
            } else if (item.getItemId() == 2) {
                onBuild(project);
            } else if (item.getItemId() == 3) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.delete_project)
                        .setMessage(project.name)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete_project, (d, w) -> {
                            store.delete(project.id);
                            refresh();
                        })
                        .show();
            }
            return true;
        });
        menu.show();
    }

    // ---------------- создание проекта ----------------

    private void showNewProjectDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_new_project, null);
        TextInputEditText nameInput = view.findViewById(R.id.nameInput);
        RadioGroup templates = view.findViewById(R.id.templateGroup);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.new_project)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.new_project, (dialog, which) -> {
                    String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = "Новый проект";
                    }
                    String source = pickTemplate(templates);
                    ProjectStore.Project project = new ProjectStore.Project();
                    project.name = name;
                    project.packageName = ProjectStore.generatePackageName(name);
                    project.files.put("main.ls", source);
                    try {
                        store.save(project);
                    } catch (Exception e) {
                        android.widget.Toast.makeText(this, "Не удалось сохранить проект", android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    refresh();
                    onOpen(project);
                })
                .show();
    }

    private String pickTemplate(RadioGroup group) {
        int id = group.getCheckedRadioButtonId();
        if (id == R.id.templateGame) {
            return Templates.GAME;
        }
        if (id == R.id.templateClicker) {
            return Templates.CLICKER;
        }
        if (id == R.id.templateAnimation) {
            return Templates.ANIMATION;
        }
        if (id == R.id.templateCounter) {
            return Templates.COUNTER;
        }
        return Templates.EMPTY;
    }
}
