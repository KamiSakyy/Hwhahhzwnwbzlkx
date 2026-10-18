/*
 * Хост-тест нативного кодека (запускается в CI на обычной машине):
 *  - проверка AES-256 по эталону FIPS-197;
 *  - проверка CBC;
 *  - упаковка/распаковка контейнера .c.
 */
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <string>
#include <vector>

#include "../engine/src/main/cpp/codec.h"
#include "../engine/src/main/cpp/aes.h"

static int failures = 0;

static void check(bool condition, const char *what) {
    if (condition) {
        std::printf("  [OK]   %s\n", what);
    } else {
        std::printf("  [FAIL] %s\n", what);
        failures++;
    }
}

static void testAesVector() {
    // FIPS-197, AES-256
    const uint8_t key[32] = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f};
    const uint8_t plain[16] = {
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff};
    const uint8_t expected[16] = {
        0x8e, 0xa2, 0xb7, 0xca, 0x51, 0x67, 0x45, 0xbf,
        0xea, 0xfc, 0x49, 0x90, 0x4b, 0x49, 0x60, 0x89};

    LsAesKey k;
    ls_aes256_init(&k, key);
    uint8_t cipher[16];
    ls_aes_encrypt_block(&k, plain, cipher);
    check(std::memcmp(cipher, expected, 16) == 0, "AES-256: эталон FIPS-197 (шифрование)");

    uint8_t back[16];
    ls_aes_decrypt_block(&k, cipher, back);
    check(std::memcmp(back, plain, 16) == 0, "AES-256: эталон FIPS-197 (расшифрование)");
}

static void testCbc() {
    uint8_t key[32];
    for (int i = 0; i < 32; i++) {
        key[i] = (uint8_t) (i * 7 + 3);
    }
    uint8_t iv[16];
    for (int i = 0; i < 16; i++) {
        iv[i] = (uint8_t) (i + 1);
    }
    std::vector<uint8_t> data(64);
    for (size_t i = 0; i < data.size(); i++) {
        data[i] = (uint8_t) (i * 3);
    }
    std::vector<uint8_t> cipher(data.size());
    std::vector<uint8_t> plain(data.size());

    LsAesKey k;
    ls_aes256_init(&k, key);
    ls_aes_cbc_encrypt(&k, iv, data.data(), data.size(), cipher.data());
    ls_aes_cbc_decrypt(&k, iv, cipher.data(), cipher.size(), plain.data());
    check(std::memcmp(plain.data(), data.data(), data.size()) == 0, "AES-256-CBC: roundtrip");
    check(std::memcmp(cipher.data(), data.data(), data.size()) != 0, "AES-256-CBC: данные изменены");
}

static void testContainer() {
    std::string payload;
    for (int i = 0; i < 5000; i++) {
        payload += "LS bytecode line " + std::to_string(i) + " -> function draw(app) end\n";
    }
    uint8_t *blob = nullptr;
    size_t blobLen = 0;
    int res = ls_codec_seal((const uint8_t *) payload.data(), payload.size(), &blob, &blobLen);
    check(res == 0, "контейнер .c: упаковка");
    if (res != 0) {
        return;
    }
    check(blobLen > 0 && blob[0] == 'L', "контейнер .c: магия LSC1");
    check(blobLen < payload.size(), "контейнер .c: сжатие работает");

    uint8_t *plain = nullptr;
    size_t plainLen = 0;
    res = ls_codec_open(blob, blobLen, &plain, &plainLen);
    check(res == 0, "контейнер .c: распаковка");
    if (res == 0) {
        check(plainLen == payload.size(), "контейнер .c: размер совпадает");
        check(std::memcmp(plain, payload.data(), plainLen) == 0, "контейнер .c: данные совпадают");
        ls_codec_free(plain);
    }

    // Порча данных должна приводить к ошибке, а не к «мусору».
    blob[blobLen / 2] = (uint8_t) (blob[blobLen / 2] ^ 0xFF);
    uint8_t *bad = nullptr;
    size_t badLen = 0;
    res = ls_codec_open(blob, blobLen, &bad, &badLen);
    check(res != 0, "контейнер .c: битые данные отвергаются");
    if (res == 0) {
        ls_codec_free(bad);
    }
    ls_codec_free(blob);
}

int main() {
    std::printf("== Тест нативного кодека LS ==\n");
    testAesVector();
    testCbc();
    testContainer();
    if (failures == 0) {
        std::printf("Все проверки пройдены\n");
        return 0;
    }
    std::printf("Провалено проверок: %d\n", failures);
    return 1;
}
