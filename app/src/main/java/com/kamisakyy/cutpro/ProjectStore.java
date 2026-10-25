package com.kamisakyy.cutpro;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Reads and writes projects as JSON files in the app's private storage. */
public final class ProjectStore {

    private ProjectStore() {
    }

    private static File dir(Context context) {
        File d = new File(context.getFilesDir(), "projects");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static Project create(Context context) {
        Project p = new Project();
        p.name = "Проект " + new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                .format(new Date());
        save(context, p);
        return p;
    }

    public static void save(Context context, Project project) {
        try {
            project.updatedAt = System.currentTimeMillis();
            File f = new File(dir(context), project.id + ".json");
            FileOutputStream out = new FileOutputStream(f);
            out.write(project.toJson().toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    public static Project load(Context context, String id) {
        try {
            File f = new File(dir(context), id + ".json");
            if (!f.exists()) {
                return null;
            }
            String json = readFile(f);
            return Project.fromJson(new JSONObject(json));
        } catch (Throwable t) {
            return null;
        }
    }

    public static List<Project> list(Context context) {
        List<Project> result = new ArrayList<>();
        File[] files = dir(context).listFiles();
        if (files == null) {
            return result;
        }
        for (File f : files) {
            if (!f.getName().endsWith(".json")) {
                continue;
            }
            try {
                Project p = Project.fromJson(new JSONObject(readFile(f)));
                if (!p.clips.isEmpty()) {
                    result.add(p);
                }
            } catch (Throwable ignored) {
            }
        }
        Collections.sort(result, new Comparator<Project>() {
            @Override
            public int compare(Project a, Project b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return result;
    }

    public static void delete(Context context, Project project) {
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(dir(context), project.id + ".json").delete();
        } catch (Throwable ignored) {
        }
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] data = new byte[(int) f.length()];
        int read = in.read(data);
        in.close();
        if (read <= 0) {
            return "";
        }
        return new String(data, 0, read, StandardCharsets.UTF_8);
    }
}
