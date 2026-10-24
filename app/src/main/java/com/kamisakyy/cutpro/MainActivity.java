package com.kamisakyy.cutpro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Project library: two-column grid with real thumbnails, like a mobile editor home screen. */
public class MainActivity extends Activity {

    private LinearLayout projectsContainer;
    private TextView emptyView;
    private TextView countView;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Map<String, Bitmap> thumbs = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectsContainer = findViewById(R.id.projectsContainer);
        emptyView = findViewById(R.id.emptyProjects);
        countView = findViewById(R.id.projectsCount);
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
        int position = 0;
        boolean first = true;
        while (first || position < projects.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(10);
            row.setLayoutParams(rowParams);

            int cells = 0;
            if (first) {
                View tile = inflater.inflate(R.layout.item_new_project, row, false);
                tile.setForeground(getDrawable(R.drawable.ripple_rect));
                tile.setLayoutParams(cellParams(cells, false));
                tile.setOnClickListener(v -> newProject());
                row.addView(tile);
                cells++;
                first = false;
            }
            while (cells < 2 && position < projects.size()) {
                final Project project = projects.get(position++);
                View card = inflater.inflate(R.layout.item_project, row, false);
                card.setForeground(getDrawable(R.drawable.ripple_rect));
                card.setLayoutParams(cellParams(cells, false));
                bindProject(card, project);
                row.addView(card);
                cells++;
            }
            while (cells < 2) {
                View spacer = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                lp.rightMargin = dp(10);
                spacer.setLayoutParams(lp);
                row.addView(spacer);
                cells++;
            }
            projectsContainer.addView(row);
        }
    }

    private LinearLayout.LayoutParams cellParams(int indexInRow, boolean last) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = last ? 0 : dp(10);
        return lp;
    }

    private void bindProject(View card, final Project project) {
        TextView name = card.findViewById(R.id.projectName);
        TextView meta = card.findViewById(R.id.projectMeta);
        TextView duration = card.findViewById(R.id.projectDuration);
        ImageView thumb = card.findViewById(R.id.projectThumb);

        name.setText(project.name);
        meta.setText(project.clips.size() + " " + EditorActivity.clipsWord(project.clips.size())
                + " · " + project.aspectLabel());
        duration.setText(formatDuration(project.durationMs()));
        duration.setVisibility(project.durationMs() > 0 ? View.VISIBLE : View.GONE);

        String uri = project.clips.isEmpty() ? null : project.clips.get(0).uri;
        Bitmap cached = uri == null ? null : thumbs.get(uri);
        if (cached != null && !cached.isRecycled()) {
            thumb.setImageBitmap(cached);
        } else {
            thumb.setImageBitmap(null);
            loadThumb(thumb, uri);
        }

        card.setOnClickListener(v -> openEditor(project.id));
        card.setOnLongClickListener(v -> {
            showMenu(project);
            return true;
        });
    }

    private void loadThumb(final ImageView view, final String uriString) {
        if (uriString == null || uriString.isEmpty()) {
            return;
        }
        view.setTag(uriString);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                try {
                    bitmap = getContentResolver().loadThumbnail(
                            Uri.parse(uriString), new Size(480, 480), null);
                } catch (Throwable ignored) {
                }
                if (bitmap == null) {
                    return;
                }
                final Bitmap result = bitmap;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        thumbs.put(uriString, result);
                        if (uriString.equals(view.getTag())) {
                            view.setImageBitmap(result);
                        }
                    }
                });
            }
        });
    }

    private void newProject() {
        Project project = ProjectStore.create(this);
        openEditor(project.id);
    }

    private void openEditor(String projectId) {
        Intent intent = new Intent(this, EditorActivity.class);
        intent.putExtra("project_id", projectId);
        startActivity(intent);
    }

    private void showMenu(final Project project) {
        String[] items = {"Открыть", "Переименовать", "Дублировать", "Удалить"};
        new AlertDialog.Builder(this)
                .setTitle(project.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            openEditor(project.id);
                        } else if (which == 1) {
                            rename(project);
                        } else if (which == 2) {
                            duplicate(project);
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

    private void duplicate(Project project) {
        Project copy = Project.fromJson(project.toJson());
        copy.id = java.util.UUID.randomUUID().toString();
        copy.name = project.name + " · копия";
        ProjectStore.save(this, copy);
        refresh();
        Toast.makeText(this, "Проект дублирован", Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(MainActivity.this, "Проект удалён",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000L;
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
