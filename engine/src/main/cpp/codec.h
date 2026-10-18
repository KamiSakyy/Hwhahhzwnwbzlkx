/*
 * Контейнер LS ".c" — зашифрованный архив с байт-кодом приложения.
 *
 * Формат:
 *   +0   magic  "LSC1"        (4 байта)
 *   +4   version                (1 байт, сейчас 1)
 *   +5   flags                  (1 байт: 1 = LZMA + AES-256-CBC)
 *   +6   reserved               (2 байта)
 *   +8   plainSize              (4 байта, little endian)
 *   +12  iv                     (16 байт)
 *   +28  payload                (AES-256-CBC(LZMA(данные)) с PKCS#7)
 *
 * Ключ AES хранится исключительно в нативной библиотеке (см. lskeys.h).
 */
#ifndef LS_CODEC_H
#define LS_CODEC_H

#include <stddef.h>
#include <stdint.h>

#define LS_CODEC_MAGIC "LSC1"
#define LS_CODEC_VERSION 1
#define LS_CODEC_FLAG_LZMA_AES 1
#define LS_CODEC_HEADER_SIZE 28

#ifdef __cplusplus
extern "C" {
#endif

/* Упаковывает данные в контейнер .c. Возвращает 0 при успехе. */
int ls_codec_seal(const uint8_t *plain, size_t plainLen, uint8_t **out, size_t *outLen);

/* Распаковывает контейнер .c. Возвращает 0 при успехе. */
int ls_codec_open(const uint8_t *blob, size_t blobLen, uint8_t **out, size_t *outLen);

void ls_codec_free(uint8_t *ptr);

/* Сырые ключи подписи APK (PKCS#8 / X.509 / SPKI) — хранятся только здесь. */
const uint8_t *ls_signing_private_key(size_t *len);
const uint8_t *ls_signing_certificate(size_t *len);
const uint8_t *ls_signing_public_key(size_t *len);

#ifdef __cplusplus
}
#endif

#endif /* LS_CODEC_H */
