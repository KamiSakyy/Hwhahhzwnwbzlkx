#include "codec.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "aes.h"
#include "lskeys.h"

extern "C" {
#include "lzma/7zTypes.h"
#include "lzma/Alloc.h"
#include "lzma/LzmaDec.h"
#include "lzma/LzmaEnc.h"
}

/* ------------------------------------------------------------------ */
/* Ключ AES: собирается из замаскированных частей прямо в машинном коде */
/* ------------------------------------------------------------------ */

static void ls_container_key(uint8_t out[32]) {
    const uint8_t *parts[4] = {LS_AES_PART0, LS_AES_PART1, LS_AES_PART2, LS_AES_PART3};
    for (int j = 0; j < 32; j++) {
        int part = j >> 3;
        int idx = j & 7;
        uint8_t v = (uint8_t) (parts[part][idx] ^ LS_AES_MASK[idx]);
        /* лёгкое дополнительное перемешивание — ключ не читается как есть */
        v = (uint8_t) (v ^ (uint8_t) (0x5A + j * 7));
        out[j] = v;
    }
}

/* ------------------------------------------------------------------ */
/* Случайный IV                                                        */
/* ------------------------------------------------------------------ */

static void ls_random_bytes(uint8_t *out, size_t len) {
    FILE *f = fopen("/dev/urandom", "rb");
    if (f != NULL) {
        size_t read = fread(out, 1, len, f);
        fclose(f);
        if (read == len) {
            return;
        }
    }
    /* запасной источник — время и адрес переменной */
    static unsigned int seed = 0;
    if (seed == 0) {
        seed = (unsigned int) time(NULL) ^ (unsigned int) (uintptr_t) &seed;
    }
    for (size_t i = 0; i < len; i++) {
        seed = seed * 1103515245u + 12345u;
        out[i] = (uint8_t) (seed >> 16);
    }
}

/* ------------------------------------------------------------------ */
/* LZMA                                                                */
/* ------------------------------------------------------------------ */

static int ls_lzma_compress(const uint8_t *src, size_t srcLen, uint8_t **out, size_t *outLen) {
    CLzmaEncProps props;
    LzmaEncProps_Init(&props);
    props.level = 9;              /* максимальное сжатие, как в 7z -9 */
    props.dictSize = 1u << 20;    /* 1 МБ — хватает для байт-кода приложения */
    props.lc = 3;
    props.lp = 0;
    props.pb = 2;
    props.numThreads = 1;

    uint8_t encodedProps[5];
    size_t encodedPropsSize = 5;

    size_t capacity = LzmaEncProps_GetDictSize(&props) + srcLen + srcLen / 2 + 128;
    uint8_t *buffer = (uint8_t *) malloc(capacity);
    if (buffer == NULL) {
        return -1;
    }
    size_t destLen = capacity;
    SRes res = LzmaEncode(buffer, &destLen, src, srcLen, &props, encodedProps,
                          &encodedPropsSize, 0, NULL, &g_Alloc, &g_BigAlloc);
    if (res != SZ_OK) {
        free(buffer);
        return -2;
    }

    uint8_t *result = (uint8_t *) malloc(encodedPropsSize + destLen);
    if (result == NULL) {
        free(buffer);
        return -3;
    }
    memcpy(result, encodedProps, encodedPropsSize);
    memcpy(result + encodedPropsSize, buffer, destLen);
    *out = result;
    *outLen = encodedPropsSize + destLen;
    free(buffer);
    return 0;
}

static int ls_lzma_decompress(const uint8_t *src, size_t srcLen, uint8_t *dst, size_t dstLen) {
    if (srcLen < 5) {
        return -1;
    }
    size_t destLen = dstLen;
    size_t srcRemaining = srcLen - 5;
    ELzmaStatus status;
    /* Первые 5 байт — свойства LZMA, сам поток начинается после них. */
    SRes res = LzmaDecode(dst, &destLen, src + 5, &srcRemaining, src, 5, LZMA_FINISH_END,
                          &status, &g_Alloc);
    if (res != SZ_OK) {
        return -2;
    }
    if (destLen != dstLen) {
        return -3;
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* Контейнер                                                           */
/* ------------------------------------------------------------------ */

int ls_codec_seal(const uint8_t *plain, size_t plainLen, uint8_t **out, size_t *outLen) {
    if (plain == NULL || out == NULL || outLen == NULL) {
        return -1;
    }
    uint8_t *compressed = NULL;
    size_t compressedLen = 0;
    if (ls_lzma_compress(plain, plainLen, &compressed, &compressedLen) != 0) {
        return -2;
    }

    size_t padded = ((compressedLen / 16) + 1) * 16;
    uint8_t *buffer = (uint8_t *) malloc(padded);
    if (buffer == NULL) {
        free(compressed);
        return -3;
    }
    memcpy(buffer, compressed, compressedLen);
    uint8_t pad = (uint8_t) (padded - compressedLen);
    for (size_t i = compressedLen; i < padded; i++) {
        buffer[i] = pad;
    }
    free(compressed);

    uint8_t keyBytes[32];
    ls_container_key(keyBytes);
    LsAesKey key;
    ls_aes256_init(&key, keyBytes);

    uint8_t iv[16];
    ls_random_bytes(iv, sizeof(iv));

    uint8_t *cipher = (uint8_t *) malloc(padded);
    if (cipher == NULL) {
        free(buffer);
        return -4;
    }
    ls_aes_cbc_encrypt(&key, iv, buffer, padded, cipher);
    free(buffer);

    uint8_t *result = (uint8_t *) malloc(LS_CODEC_HEADER_SIZE + padded);
    if (result == NULL) {
        free(cipher);
        return -5;
    }
    memcpy(result, LS_CODEC_MAGIC, 4);
    result[4] = LS_CODEC_VERSION;
    result[5] = LS_CODEC_FLAG_LZMA_AES;
    result[6] = 0;
    result[7] = 0;
    uint32_t plainSize = (uint32_t) plainLen;
    result[8] = (uint8_t) (plainSize & 0xFF);
    result[9] = (uint8_t) ((plainSize >> 8) & 0xFF);
    result[10] = (uint8_t) ((plainSize >> 16) & 0xFF);
    result[11] = (uint8_t) ((plainSize >> 24) & 0xFF);
    memcpy(result + 12, iv, 16);
    memcpy(result + LS_CODEC_HEADER_SIZE, cipher, padded);
    free(cipher);

    *out = result;
    *outLen = LS_CODEC_HEADER_SIZE + padded;
    return 0;
}

int ls_codec_open(const uint8_t *blob, size_t blobLen, uint8_t **out, size_t *outLen) {
    if (blob == NULL || out == NULL || outLen == NULL) {
        return -1;
    }
    if (blobLen < LS_CODEC_HEADER_SIZE + 16) {
        return -2;
    }
    if (memcmp(blob, LS_CODEC_MAGIC, 4) != 0) {
        return -3;
    }
    if (blob[4] != LS_CODEC_VERSION) {
        return -4;
    }
    uint32_t plainSize = (uint32_t) blob[8] | ((uint32_t) blob[9] << 8) |
                         ((uint32_t) blob[10] << 16) | ((uint32_t) blob[11] << 24);
    if (plainSize == 0 || plainSize > (64u * 1024u * 1024u)) {
        return -5;
    }
    size_t payloadLen = blobLen - LS_CODEC_HEADER_SIZE;
    if ((payloadLen % 16) != 0) {
        return -6;
    }

    uint8_t keyBytes[32];
    ls_container_key(keyBytes);
    LsAesKey key;
    ls_aes256_init(&key, keyBytes);

    uint8_t *buffer = (uint8_t *) malloc(payloadLen);
    if (buffer == NULL) {
        return -7;
    }
    if (!ls_aes_cbc_decrypt(&key, blob + 12, blob + LS_CODEC_HEADER_SIZE, payloadLen, buffer)) {
        free(buffer);
        return -8;
    }
    uint8_t pad = buffer[payloadLen - 1];
    if (pad == 0 || pad > 16 || (size_t) pad > payloadLen) {
        free(buffer);
        return -9;
    }
    for (size_t i = payloadLen - pad; i < payloadLen; i++) {
        if (buffer[i] != pad) {
            free(buffer);
            return -10;
        }
    }
    size_t compressedLen = payloadLen - pad;

    uint8_t *plain = (uint8_t *) malloc(plainSize);
    if (plain == NULL) {
        free(buffer);
        return -11;
    }
    if (ls_lzma_decompress(buffer, compressedLen, plain, plainSize) != 0) {
        free(buffer);
        free(plain);
        return -12;
    }
    free(buffer);
    *out = plain;
    *outLen = plainSize;
    return 0;
}

void ls_codec_free(uint8_t *ptr) {
    free(ptr);
}

const uint8_t *ls_signing_private_key(size_t *len) {
    if (len != NULL) {
        *len = sizeof(LS_RSA_PRIVATE_PKCS8);
    }
    return LS_RSA_PRIVATE_PKCS8;
}

const uint8_t *ls_signing_certificate(size_t *len) {
    if (len != NULL) {
        *len = sizeof(LS_RSA_CERTIFICATE);
    }
    return LS_RSA_CERTIFICATE;
}

const uint8_t *ls_signing_public_key(size_t *len) {
    if (len != NULL) {
        *len = sizeof(LS_RSA_PUBLIC_KEY);
    }
    return LS_RSA_PUBLIC_KEY;
}
