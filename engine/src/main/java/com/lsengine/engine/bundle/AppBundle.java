package com.lsengine.engine.bundle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Содержимое собранного приложения: манифест проекта, исходные файлы
 * и скомпилированный байт-код LS.
 *
 * Упаковывается в зашифрованный контейнер .c и кладётся в assets APK.
 * В dex пользовательского приложения исходников нет — только байт-код.
 */
public final class AppBundle {

    private static final byte[] MAGIC = {'L', 'S', 'B', '1'};
    private static final int VERSION = 2;

    public String appName = "LS App";
    public String packageName = "com.lsengine.app";
    public String versionName = "1.0.0";
    public int versionCode = 1;
    /** portrait | landscape | fullSensor */
    public String orientation = "portrait";
    public String description = "";
    public String entryFile = "main.ls";
    /** Скомпилированный байт-код LS (формат .lsb). */
    public byte[] bytecode = new byte[0];
    /** Машинный код AArch64, хранится только внутри зашифрованного .c. */
    public byte[] nativeCode = new byte[0];
    /** Исходные файлы проекта (для справочных целей и отладки). */
    public Map<String, byte[]> files = new LinkedHashMap<>();

    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.write(MAGIC, 0, MAGIC.length);
            out.writeByte(VERSION);
            out.writeUTF(appName == null ? "" : appName);
            out.writeUTF(packageName == null ? "" : packageName);
            out.writeUTF(versionName == null ? "" : versionName);
            out.writeInt(versionCode);
            out.writeUTF(orientation == null ? "portrait" : orientation);
            out.writeUTF(description == null ? "" : description);
            out.writeUTF(entryFile == null ? "main.ls" : entryFile);
            out.writeInt(bytecode.length);
            out.write(bytecode, 0, bytecode.length);
            out.writeInt(nativeCode == null ? 0 : nativeCode.length);
            if (nativeCode != null) out.write(nativeCode);
            out.writeInt(files.size());
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                out.writeUTF(entry.getKey());
                byte[] data = entry.getValue();
                out.writeInt(data.length);
                out.write(data, 0, data.length);
            }
        }
        return bos.toByteArray();
    }

    public static AppBundle fromBytes(byte[] data) throws IOException {
        AppBundle bundle = new AppBundle();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            for (int i = 0; i < 4; i++) {
                if (magic[i] != MAGIC[i]) {
                    throw new IOException("повреждённый пакет приложения");
                }
            }
            int version = in.readByte();
            if (version != 1 && version != VERSION) {
                throw new IOException("неподдерживаемая версия пакета: " + version);
            }
            bundle.appName = in.readUTF();
            bundle.packageName = in.readUTF();
            bundle.versionName = in.readUTF();
            bundle.versionCode = in.readInt();
            bundle.orientation = in.readUTF();
            bundle.description = in.readUTF();
            bundle.entryFile = in.readUTF();
            int bytecodeLength = in.readInt();
            bundle.bytecode = new byte[bytecodeLength];
            in.readFully(bundle.bytecode);
            if (version >= 2) {
                int nativeCodeLength = in.readInt();
                if (nativeCodeLength < 0 || nativeCodeLength > data.length) throw new IOException("повреждённый AOT-блок");
                bundle.nativeCode = new byte[nativeCodeLength];
                in.readFully(bundle.nativeCode);
            }
            int fileCount = in.readInt();
            for (int i = 0; i < fileCount; i++) {
                String name = in.readUTF();
                int length = in.readInt();
                byte[] fileData = new byte[length];
                in.readFully(fileData);
                bundle.files.put(name, fileData);
            }
        }
        return bundle;
    }
}
