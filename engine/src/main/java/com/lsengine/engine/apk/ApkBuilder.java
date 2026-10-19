package com.lsengine.engine.apk;

import com.lsengine.engine.nativecodec.Codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Offline APK repacker. Application machine code is stored only inside encrypted assets/lsapp.c. */
public final class ApkBuilder {
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");

    public static final class Options {
        public String appName = "LS App";
        public String packageName = "com.lsengine.app0001";
        public String versionName = "1.0.0";
        public int versionCode = 1;
        public String orientation = "portrait";
        public String description = "";
        /** Optional launcher foreground image, normalized PNG bytes. */
        public byte[] iconPng;
    }

    public static final String BUNDLE_ASSET = "assets/lsapp.c";
    public static final String META_ASSET = "assets/lsapp.meta";

    private ApkBuilder() { }

    public static byte[] build(byte[] shellApk, byte[] bundleBytes, Options options) throws Exception {
        if (shellApk == null || shellApk.length == 0 || bundleBytes == null || options == null) {
            throw new IllegalArgumentException("Оболочка, контейнер и параметры обязательны");
        }
        validateOptions(options);

        // Read the shell before writing: icon resources and their ZIP entry path must be replaced together.
        Map<String, byte[]> shell = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(shellApk))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.startsWith("META-INF/") || name.equals(BUNDLE_ASSET) || name.equals(META_ASSET)) continue;
                if (shell.put(name, readAll(zip)) != null) {
                    throw new IOException("В Android shell дублируется ZIP-запись: " + name);
                }
            }
        }

        byte[] manifest = shell.get("AndroidManifest.xml");
        if (manifest == null) throw new IOException("В Android shell отсутствует AndroidManifest.xml");
        shell.put("AndroidManifest.xml", AxmlPatcher.patch(manifest, options.packageName, options.appName));

        byte[] resourceTable = shell.get("resources.arsc");
        if (resourceTable == null) throw new IOException("В Android shell отсутствует resources.arsc");
        if (options.iconPng != null && options.iconPng.length > 0) {
            IconPatcher.Result icon = IconPatcher.patch(resourceTable, options.iconPng);
            if (!shell.containsKey(icon.oldEntry)) {
                throw new IOException("Файл иконки не найден в Android shell: " + icon.oldEntry);
            }
            shell.remove(icon.oldEntry);
            shell.put(icon.newEntry, icon.png);
            resourceTable = icon.resourcesTable;
        }
        // Package name must agree in the binary manifest and the compiled resource table.
        shell.put("resources.arsc", IconPatcher.rewritePackageName(resourceTable, options.packageName));

        List<ZipWriter.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, byte[]> item : shell.entrySet()) {
            String name = item.getKey();
            byte[] data = item.getValue();
            boolean stored = "resources.arsc".equals(name) || (name.startsWith("lib/") && name.endsWith(".so"));
            entries.add(new ZipWriter.Entry(name, data, stored));
        }
        entries.add(new ZipWriter.Entry(BUNDLE_ASSET, bundleBytes));
        entries.add(new ZipWriter.Entry(META_ASSET, meta(options)));

        ZipWriter.Result zip = ZipWriter.build(entries);
        PrivateKey key = loadPrivateKey();
        return ApkSignerV2.sign(zip, key, Codec.certificateDer(), Codec.publicKeyDer());
    }

    private static void validateOptions(Options options) {
        if (options.appName == null || options.appName.trim().isEmpty() || options.appName.length() > 80) {
            throw new IllegalArgumentException("Название приложения должно содержать от 1 до 80 символов");
        }
        if (options.packageName == null || !PACKAGE_NAME.matcher(options.packageName).matches()
                || options.packageName.length() > 255) {
            throw new IllegalArgumentException("Неверный идентификатор пакета Android");
        }
        if (options.versionName == null || options.versionName.trim().isEmpty() || options.versionName.length() > 64) {
            throw new IllegalArgumentException("Неверный номер версии");
        }
        if (options.versionCode < 1) throw new IllegalArgumentException("versionCode должен быть положительным");
    }

    private static byte[] meta(Options options) {
        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(options.appName).append('\n');
        sb.append("package=").append(options.packageName).append('\n');
        sb.append("versionName=").append(options.versionName).append('\n');
        sb.append("versionCode=").append(options.versionCode).append('\n');
        sb.append("orientation=").append(options.orientation).append('\n');
        sb.append("description=").append(options.description).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static PrivateKey loadPrivateKey() throws Exception {
        byte[] der = Codec.privateKeyDer();
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return factory.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static byte[] readAll(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = zip.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }
}
