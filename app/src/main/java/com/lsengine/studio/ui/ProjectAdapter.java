package com.lsengine.studio.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.lsengine.engine.project.ProjectStore;
import com.lsengine.studio.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Список проектов.
 */
public class ProjectAdapter extends RecyclerView.Adapter<ProjectAdapter.Holder> {

    public interface Listener {
        void onOpen(ProjectStore.Project project);

        void onBuild(ProjectStore.Project project);

        void onMenu(ProjectStore.Project project, View anchor);
    }

    private final List<ProjectStore.Project> items = new ArrayList<>();
    private final Listener listener;

    public ProjectAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<ProjectStore.Project> projects) {
        items.clear();
        items.addAll(projects);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_project, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ProjectStore.Project project = items.get(position);
        holder.name.setText(project.name);
        holder.pkg.setText(project.packageName);
        holder.date.setText(formatDate(project.updatedAt));
        holder.itemView.setOnClickListener(v -> listener.onOpen(project));
        holder.open.setOnClickListener(v -> listener.onOpen(project));
        holder.build.setOnClickListener(v -> listener.onBuild(project));
        holder.menu.setOnClickListener(v -> listener.onMenu(project, v));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static String formatDate(long millis) {
        long diff = System.currentTimeMillis() - millis;
        if (diff < 60_000L) {
            return "только что";
        }
        if (diff < 3_600_000L) {
            return (diff / 60_000L) + " мин назад";
        }
        if (diff < 86_400_000L) {
            return (diff / 3_600_000L) + " ч назад";
        }
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(millis));
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView pkg;
        final TextView date;
        final MaterialButton open;
        final MaterialButton build;
        final ImageButton menu;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.projectName);
            pkg = itemView.findViewById(R.id.projectPackage);
            date = itemView.findViewById(R.id.projectDate);
            open = itemView.findViewById(R.id.openButton);
            build = itemView.findViewById(R.id.buildButton);
            menu = itemView.findViewById(R.id.projectMenu);
        }
    }
}
