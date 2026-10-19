package com.lsengine.engine.project;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Хранилище проектов (внутреннее хранилище приложения).
 * Формат — простые файлы без JSON, чтобы не тянуть зависимости.
 */
public final class ProjectStore {

    /** Описание проекта. */
    public static final class Project {
        public String id = UUID.randomUUID().toString().substring(0, 8);
        public String name = "Новый проект";
        public String packageName = "com.lsengine.app";
        public String versionName = "1.0.0";
        public int versionCode = 1;
        public String orientation = "portrait";
        public String description = "";
        public long updatedAt = System.currentTimeMillis();
        /** Имя файла -> содержимое. */
        public final Map<String, String> files = new LinkedHashMap<>();

        public String mainFile() {
            return files.containsKey("main.ls") ? "main.ls" : files.keySet().iterator().next();
        }
    }

    private final File root;

    public ProjectStore(File rootDir) {
        this.root = rootDir;
        if (!root.exists()) {
            root.mkdirs();
        }
    }

    public List<Project> list() {
        List<Project> result = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs == null) {
            return result;
        }
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            Project project = load(dir.getName());
            if (project != null) {
                result.add(project);
            }
        }
        result.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return result;
    }

    public Project load(String id) {
        File dir = new File(root, id);
        File meta = new File(dir, "project.meta");
        if (!meta.exists()) {
            return null;
        }
        Project project = new Project();
        project.id = id;
        try {
            for (String line : readLines(meta)) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq);
                String value = line.substring(eq + 1);
                switch (key) {
                    case "name": project.name = value; break;
                    case "package": project.packageName = value; break;
                    case "versionName": project.versionName = value; break;
                    case "versionCode": project.versionCode = parseInt(value, 1); break;
                    case "orientation": project.orientation = value; break;
                    case "description": project.description = value; break;
                    case "updatedAt": project.updatedAt = parseLong(value, System.currentTimeMillis()); break;
                    default: break;
                }
            }
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && !file.getName().equals("project.meta")) {
                        project.files.put(file.getName(), new String(readAll(file), StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        return project;
    }

    public void save(Project project) throws IOException {
        File dir = new File(root, project.id);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        project.updatedAt = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(project.name == null ? "" : project.name).append('\n');
        sb.append("package=").append(project.packageName == null ? "" : project.packageName).append('\n');
        sb.append("versionName=").append(project.versionName == null ? "" : project.versionName).append('\n');
        sb.append("versionCode=").append(project.versionCode).append('\n');
        sb.append("orientation=").append(project.orientation == null ? "" : project.orientation).append('\n');
        sb.append("description=").append(project.description == null ? "" : project.description).append('\n');
        sb.append("updatedAt=").append(project.updatedAt).append('\n');
        writeAll(new File(dir, "project.meta"), sb.toString().getBytes(StandardCharsets.UTF_8));

        for (Map.Entry<String, String> entry : project.files.entrySet()) {
            writeAll(new File(dir, sanitize(entry.getKey())), entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
    }

    public void delete(String id) {
        File dir = new File(root, id);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        dir.delete();
    }

    /** Уникальное имя пакета для нового приложения. */
    public static String generatePackageName(String projectName) {
        String base = "com.lsengine.";
        String suffix = sanitize(projectName == null ? "app" : projectName)
                .toLowerCase()
                .replaceAll("[^a-z0-9]", "");
        if (suffix.isEmpty()) {
            suffix = "app";
        }
        if (suffix.length() > 7) {
            suffix = suffix.substring(0, 7);
        }
        int number = (int) (Math.abs((long) suffix.hashCode()) % 1000);
        return base + suffix + String.format("%03d", number);
    }

    private static String sanitize(String name) {
        return name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : new String(readAll(file), StandardCharsets.UTF_8).split("\n")) {
            lines.add(line);
        }
        return lines;
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static void writeAll(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data, 0, data.length);
        }
    }
}
