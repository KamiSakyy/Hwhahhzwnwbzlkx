package com.lsengine.engine.lang;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Сериализация байт-кода LS в компактный бинарный формат .lsb.
 *
 * Именно этот файл (а не исходный текст) попадает в собранное приложение
 * внутри зашифрованного контейнера .c.
 */
public final class BytecodeFile {

    private static final byte[] MAGIC = {'L', 'S', 'B', '1'};
    private static final int VERSION = 1;

    private BytecodeFile() {
    }

    public static byte[] write(Chunk chunk) throws LsError {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.write(MAGIC, 0, MAGIC.length);
            out.writeByte(VERSION);
            out.writeUTF(chunk.name == null ? "main" : chunk.name);

            out.writeShort(chunk.constants.size());
            for (Object c : chunk.constants) {
                if (c instanceof Double) {
                    out.writeByte(0);
                    out.writeDouble((Double) c);
                } else if (c instanceof Boolean) {
                    out.writeByte(1);
                    out.writeBoolean((Boolean) c);
                } else if (c instanceof String) {
                    out.writeByte(2);
                    out.writeUTF((String) c);
                } else {
                    throw new LsError("Недопустимая константа: " + c);
                }
            }

            out.writeShort(chunk.functions.size());
            for (Chunk.FuncProto f : chunk.functions) {
                out.writeUTF(f.name == null ? "function" : f.name);
                out.writeShort(f.paramCount);
                out.writeShort(f.localCount);
                out.writeShort(f.capturedSlots.length);
                for (int slot : f.capturedSlots) {
                    out.writeShort(slot);
                }
                out.writeInt(f.code.length);
                out.write(f.code, 0, f.code.length);
                out.writeShort(f.upvalues.size());
                for (Chunk.Upval u : f.upvalues) {
                    out.writeBoolean(u.fromParentLocal);
                    out.writeShort(u.index);
                }
            }
            out.writeShort(chunk.entry);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new LsError("Ошибка сериализации байт-кода: " + e.getMessage());
        }
    }

    public static Chunk read(byte[] data) throws LsError {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            for (int i = 0; i < 4; i++) {
                if (magic[i] != MAGIC[i]) {
                    throw new LsError("Файл не является байт-кодом LS");
                }
            }
            int version = in.readByte();
            if (version != VERSION) {
                throw new LsError("Неподдерживаемая версия байт-кода: " + version);
            }
            Chunk chunk = new Chunk();
            chunk.name = in.readUTF();

            int constCount = in.readShort();
            for (int i = 0; i < constCount; i++) {
                int type = in.readByte();
                switch (type) {
                    case 0: chunk.constants.add(in.readDouble()); break;
                    case 1: chunk.constants.add(in.readBoolean()); break;
                    case 2: chunk.constants.add(in.readUTF()); break;
                    default: throw new LsError("Повреждённый байт-код: неизвестная константа");
                }
            }

            int funcCount = in.readShort();
            List<Chunk.FuncProto> protos = new ArrayList<>();
            for (int i = 0; i < funcCount; i++) {
                Chunk.FuncProto f = new Chunk.FuncProto();
                f.name = in.readUTF();
                f.paramCount = in.readShort();
                f.localCount = in.readShort();
                int capturedCount = in.readShort();
                int[] captured = new int[capturedCount];
                for (int j = 0; j < capturedCount; j++) {
                    captured[j] = in.readShort();
                }
                f.capturedSlots = captured;
                int codeLen = in.readInt();
                byte[] code = new byte[codeLen];
                in.readFully(code);
                f.code = code;
                int upvalCount = in.readShort();
                for (int j = 0; j < upvalCount; j++) {
                    boolean fromLocal = in.readBoolean();
                    int index = in.readShort();
                    f.upvalues.add(new Chunk.Upval(fromLocal, index, "up" + j));
                }
                protos.add(f);
            }
            chunk.functions.addAll(protos);
            chunk.entry = in.readShort();
            return chunk;
        } catch (IOException e) {
            throw new LsError("Повреждённый файл байт-кода: " + e.getMessage());
        }
    }
}
