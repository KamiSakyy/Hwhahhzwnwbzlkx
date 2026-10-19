/*
 * Компактная реализация AES-256 (ECB-блок + CBC-режим).
 * Таблицы S-box вычисляются на старте, чтобы исключить ошибки в константах.
 */
#ifndef LS_AES_H
#define LS_AES_H

#include <stddef.h>
#include <stdint.h>

#define LS_AES_BLOCK_SIZE 16
#define LS_AES_KEY256_SIZE 32

typedef struct {
    uint8_t roundKey[240];
    int rounds;
} LsAesKey;

#ifdef __cplusplus
extern "C" {
#endif

void ls_aes256_init(LsAesKey *key, const uint8_t data[LS_AES_KEY256_SIZE]);
void ls_aes_encrypt_block(const LsAesKey *key, const uint8_t in[LS_AES_BLOCK_SIZE], uint8_t out[LS_AES_BLOCK_SIZE]);
void ls_aes_decrypt_block(const LsAesKey *key, const uint8_t in[LS_AES_BLOCK_SIZE], uint8_t out[LS_AES_BLOCK_SIZE]);

/* CBC без padding: длина должна быть кратна 16. */
void ls_aes_cbc_encrypt(const LsAesKey *key, const uint8_t iv[LS_AES_BLOCK_SIZE],
                        const uint8_t *in, size_t len, uint8_t *out);
int ls_aes_cbc_decrypt(const LsAesKey *key, const uint8_t iv[LS_AES_BLOCK_SIZE],
                       const uint8_t *in, size_t len, uint8_t *out);

#ifdef __cplusplus
}
#endif

#endif /* LS_AES_H */
