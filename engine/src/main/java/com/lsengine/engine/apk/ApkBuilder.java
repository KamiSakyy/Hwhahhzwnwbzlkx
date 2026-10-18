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
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Офлайн-сборщик APK: берёт подготовленную оболочку (shell.apk), подменяет в ней
 * манифест и добавляет зашифрованный контейнер .c с байт-кодом приложения.
 *
 * Ни сети, ни внешних инструментов: всё делается прямо на устройстве.
 */
public final class ApkBuilder {

    /** Параметры собираемого приложения. */
    public static final class Options {
        public String appName = "LS App";
        public String packageName = "com.lsengine.app0001";
        public String versionName = "1.0.0";
        public int versionCode = 1;
        public String orientation = "portrait";
        public String description = "";
    }

    /** Имя файла с контейнером .c внутри собранного APK. */
    public static final String BUNDLE_ASSET = "assets/lsapp.c";
    /** Простой манифест приложения (для быстрого чтения рантаймом). */
    public static final String META_ASSET = "assets/lsapp.meta";

    private ApkBuilder() {
    }

    /**
     * Собирает подписанный APK.
     *
     * @param shellApk    байты оболочки (runtime APK)
     * @param bundleBytes содержимое контейнера .c (уже зашифровано)
     * @param options     параметры приложения
     */
    public static byte[] build(byte[] shellApk, byte[] bundleBytes, Options options) throws Exception {
        List<ZipWriter.Entry> entries = new ArrayList<>();

        // 1. Переносим содержимое оболочки, кроме подписей META-INF.
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(shellApk))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("META-INF/")) {
                    continue;   // старая подпись нам не нужна — подписываем заново
                }
                if (name.equals(BUNDLE_ASSET) || name.equals(META_ASSET)) {
                    continue;
                }
                byte[] data = readAll(zip);
                if (name.equals("AndroidManifest.xml")) {
                    data = AxmlPatcher.patch(data, options.packageName, options.appName);
                }
                entries.add(new ZipWriter.Entry(name, data));
            }
        }

        // 2. Кладём зашифрованный байт-код и метаданные.
        entries.add(new ZipWriter.Entry(BUNDLE_ASSET, bundleBytes));
        entries.add(new ZipWriter.Entry(META_ASSET, meta(options)));

        // 3. Собираем ZIP и подписываем по схеме v2.
        ZipWriter.Result zip = ZipWriter.build(entries);
        PrivateKey key = loadPrivateKey();
        return ApkSignerV2.sign(zip, key, Codec.certificateDer(), Codec.publicKeyDer());
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
        while ((read = zip.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
