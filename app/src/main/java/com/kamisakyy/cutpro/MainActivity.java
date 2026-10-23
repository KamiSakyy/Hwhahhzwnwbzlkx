package com.kamisakyy.cutpro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Project browser: create, open, rename or delete CutPro projects. */
public class MainActivity extends Activity {

    private LinearLayout projectsContainer;
    private TextView emptyView;
    private TextView countView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectsContainer = findViewById(R.id.projectsContainer);
        emptyView = findViewById(R.id.emptyProjects);
        countView = findViewById(R.id.projectsCount);

        findViewById(R.id.btnNewProject).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Project project = ProjectStore.create(MainActivity.this);
                openEditor(project.id);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        projectsContainer.removeAllViews();
        List<Project> projects = ProjectStore.list(this);
        emptyView.setVisibility(projects.isEmpty() ? View.VISIBLE : View.GONE);
        countView.setText(projects.isEmpty() ? "" : String.valueOf(projects.size()));

        LayoutInflater inflater = LayoutInflater.from(this);
        for (final Project project : projects) {
            View row = inflater.inflate(R.layout.item_project, projectsContainer, false);
            TextView name = row.findViewById(R.id.projectName);
            TextView meta = row.findViewById(R.id.projectMeta);
            name.setText(project.name);
            meta.setText(project.clips.size() + " клип. · " + duration(project.durationMs())
                    + " · " + project.aspectLabel());
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEditor(project.id);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showMenu(project);
                    return true;
                }
            });
            projectsContainer.addView(row);
        }
    }

    private void openEditor(String projectId) {
        Intent intent = new Intent(this, EditorActivity.class);
        intent.putExtra("project_id", projectId);
        startActivity(intent);
    }

    private void showMenu(final Project project) {
        String[] items = {"Открыть", "Переименовать", "Удалить"};
        new AlertDialog.Builder(this)
                .setTitle(project.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            openEditor(project.id);
                        } else if (which == 1) {
                            rename(project);
                        } else {
                            confirmDelete(project);
                        }
                    }
                })
                .show();
    }

    private void rename(final Project project) {
        final EditText input = new EditText(this);
        input.setText(project.name);
        input.setSingleLine(true);
        input.setSelection(project.name.length());
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("Название проекта")
                .setView(input)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String value = input.getText().toString().trim();
                        if (!value.isEmpty()) {
                            project.name = value;
                            ProjectStore.save(MainActivity.this, project);
                            refresh();
                        }
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void confirmDelete(final Project project) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить проект?")
                .setMessage(project.name + "\nВидео в Галерее останется.")
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ProjectStore.delete(MainActivity.this, project);
                        refresh();
                        Toast.makeText(MainActivity.this, "Проект удалён", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String duration(long ms) {
        long totalSeconds = ms / 1000L;
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
