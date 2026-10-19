package com.lsengine.engine.apk;

import java.io.ByteArrayOutputStream;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;

/**
 * Подпись APK по схеме APK Signature Scheme v2 (реализация по спецификации
 * https://source.android.com/docs/security/features/apksigning/v2).
 *
 * Схема состоит из трёх секций, каждая разбивается на куски по 1 МБ:
 *  1) содержимое до блока подписи (заголовки и данные ZIP-записей);
 *  2) центральный каталог;
 *  3) EOCD, в котором смещение каталога трактуется как начало блока подписи.
 */
public final class ApkSignerV2 {

    /** Идентификатор алгоритма подписи RSASSA-PKCS1-v1_5 с SHA-256. */
    private static final int ALGORITHM_RSA_PKCS1_SHA256 = 0x0103;
    /** Идентификатор блока подписи v2. */
    private static final int APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a;
    private static final byte[] APK_SIG_BLOCK_MAGIC =
            "APK Sig Block 42".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int CHUNK_SIZE = 1024 * 1024;

    private ApkSignerV2() {
    }

    /**
     * Подписывает собранный APK.
     *
     * @param zip        результат сборки без подписи
     * @param privateKey RSA-ключ подписи
     * @param certDer    DER-сертификат X.509
     * @param publicKeyDer DER SubjectPublicKeyInfo
     */
    public static byte[] sign(ZipWriter.Result zip, PrivateKey privateKey, byte[] certDer, byte[] publicKeyDer)
            throws Exception {
        byte[] apk = zip.data;
        int cdOffset = zip.centralDirectoryOffset;
        int cdSize = zip.centralDirectorySize;
        int eocdOffset = zip.eocdOffset;

        byte[] beforeCentralDir = Arrays.copyOfRange(apk, 0, cdOffset);
        byte[] centralDir = Arrays.copyOfRange(apk, cdOffset, cdOffset + cdSize);
        byte[] eocd = Arrays.copyOfRange(apk, eocdOffset, apk.length);
        // При подсчёте хэша смещение каталога в EOCD должно указывать на начало блока подписи.
        writeIntLE(eocd, 16, cdOffset);

        byte[] contentDigest = chunkedDigest(beforeCentralDir, centralDir, eocd);

        // --- signed data ---
        ByteArrayOutputStream digestRecord = new ByteArrayOutputStream();
        writeIntLE(digestRecord, ALGORITHM_RSA_PKCS1_SHA256);
        writeLengthPrefixed(digestRecord, contentDigest);
        ByteArrayOutputStream digests = new ByteArrayOutputStream();
        writeLengthPrefixed(digests, digestRecord.toByteArray());

        ByteArrayOutputStream certificates = new ByteArrayOutputStream();
        writeIntLE(certificates, certDer.length);
        certificates.write(certDer, 0, certDer.length);

        ByteArrayOutputStream signedData = new ByteArrayOutputStream();
        writeLengthPrefixed(signedData, digests.toByteArray());
        writeLengthPrefixed(signedData, certificates.toByteArray());
        writeLengthPrefixed(signedData, new byte[0]);   // additional attributes
        byte[] signedDataBytes = signedData.toByteArray();

        // --- подпись ---
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(signedDataBytes);
        byte[] signatureBytes = signature.sign();

        ByteArrayOutputStream signatureRecord = new ByteArrayOutputStream();
        writeIntLE(signatureRecord, ALGORITHM_RSA_PKCS1_SHA256);
        writeLengthPrefixed(signatureRecord, signatureBytes);
        ByteArrayOutputStream signatures = new ByteArrayOutputStream();
        writeLengthPrefixed(signatures, signatureRecord.toByteArray());

        ByteArrayOutputStream signer = new ByteArrayOutputStream();
        writeLengthPrefixed(signer, signedDataBytes);
        writeLengthPrefixed(signer, signatures.toByteArray());
        writeLengthPrefixed(signer, publicKeyDer);
        byte[] signerBytes = signer.toByteArray();

        ByteArrayOutputStream signersSequence = new ByteArrayOutputStream();
        writeLengthPrefixed(signersSequence, signerBytes);
        ByteArrayOutputStream v2BlockValueStream = new ByteArrayOutputStream();
        writeLengthPrefixed(v2BlockValueStream, signersSequence.toByteArray());

        byte[] v2BlockValue = v2BlockValueStream.toByteArray();

        // --- блок подписи ---
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        long pairSize = 4L + v2BlockValue.length;
        writeLongLE(block, pairSize);
        writeIntLE(block, APK_SIGNATURE_SCHEME_V2_BLOCK_ID);
        block.write(v2BlockValue, 0, v2BlockValue.length);
        byte[] blockBytes = block.toByteArray();

        int totalSize = 8 + blockBytes.length + 8 + APK_SIG_BLOCK_MAGIC.length;
        ByteArrayOutputStream signingBlock = new ByteArrayOutputStream();
        writeLongLE(signingBlock, totalSize - 8L);
        signingBlock.write(blockBytes, 0, blockBytes.length);
        writeLongLE(signingBlock, totalSize - 8L);
        signingBlock.write(APK_SIG_BLOCK_MAGIC, 0, APK_SIG_BLOCK_MAGIC.length);
        byte[] signingBlockBytes = signingBlock.toByteArray();

        // --- сборка итогового файла ---
        ByteArrayOutputStream result = new ByteArrayOutputStream(apk.length + signingBlockBytes.length);
        result.write(beforeCentralDir, 0, beforeCentralDir.length);
        result.write(signingBlockBytes, 0, signingBlockBytes.length);
        result.write(centralDir, 0, centralDir.length);
        byte[] finalEocd = eocd.clone();
        writeIntLE(finalEocd, 16, cdOffset + signingBlockBytes.length);
        result.write(finalEocd, 0, finalEocd.length);
        return result.toByteArray();
    }

    /**
     * Хэш содержимого APK по схеме v2: SHA-256 по кускам в 1 МБ.
     */
    private static byte[] chunkedDigest(byte[]... sections) throws Exception {
        long totalChunks = 0;
        for (byte[] section : sections) {
            totalChunks += chunkCount(section.length);
        }
        int chunkCount = (int) totalChunks;

        ByteArrayOutputStream chunkDigests = new ByteArrayOutputStream();
        for (byte[] section : sections) {
            int offset = 0;
            while (offset < section.length) {
                int size = Math.min(CHUNK_SIZE, section.length - offset);
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                md.update((byte) 0xa5);
                md.update(intLE(size));
                md.update(section, offset, size);
                chunkDigests.write(md.digest());
                offset += size;
            }
        }
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        md.update((byte) 0x5a);
        md.update(intLE(chunkCount));
        md.update(chunkDigests.toByteArray());
        return md.digest();
    }

    private static int chunkCount(int length) {
        if (length <= 0) {
            return 0;
        }
        return (length + CHUNK_SIZE - 1) / CHUNK_SIZE;
    }

    private static void writeLengthPrefixed(ByteArrayOutputStream out, byte[] data) {
        writeIntLE(out, data.length);
        out.write(data, 0, data.length);
    }

    private static void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeLongLE(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((value >> (8 * i)) & 0xFF));
        }
    }

    private static void writeIntLE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static byte[] intLE(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)};
    }
}
