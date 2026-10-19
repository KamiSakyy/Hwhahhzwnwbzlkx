/*
 * JNI-мост к нативному кодеку LS.
 * Ключи AES и RSA живут только здесь, в машинном коде библиотеки.
 */
#include <jni.h>

#include <stdlib.h>
#include <string.h>

#include "codec.h"

static void ls_throw(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("com/lsengine/engine/nativecodec/CodecException");
    if (cls != NULL) {
        env->ThrowNew(cls, msg);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lsengine_engine_nativecodec_Codec_nativeSeal(JNIEnv *env, jclass clazz, jbyteArray input) {
    (void) clazz;
    if (input == NULL) {
        return NULL;
    }
    jsize len = env->GetArrayLength(input);
    jbyte *data = env->GetByteArrayElements(input, NULL);
    if (data == NULL) {
        return NULL;
    }
    uint8_t *out = NULL;
    size_t outLen = 0;
    int res = ls_codec_seal((const uint8_t *) data, (size_t) len, &out, &outLen);
    env->ReleaseByteArrayElements(input, data, JNI_ABORT);
    if (res != 0) {
        ls_throw(env, "не удалось упаковать контейнер .c");
        return NULL;
    }
    jbyteArray result = env->NewByteArray((jsize) outLen);
    if (result != NULL) {
        env->SetByteArrayRegion(result, 0, (jsize) outLen, (const jbyte *) out);
    }
    ls_codec_free(out);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lsengine_engine_nativecodec_Codec_nativeOpen(JNIEnv *env, jclass clazz, jbyteArray input) {
    (void) clazz;
    if (input == NULL) {
        return NULL;
    }
    jsize len = env->GetArrayLength(input);
    jbyte *data = env->GetByteArrayElements(input, NULL);
    if (data == NULL) {
        return NULL;
    }
    uint8_t *out = NULL;
    size_t outLen = 0;
    int res = ls_codec_open((const uint8_t *) data, (size_t) len, &out, &outLen);
    env->ReleaseByteArrayElements(input, data, JNI_ABORT);
    if (res != 0) {
        ls_throw(env, "не удалось распаковать контейнер .c");
        return NULL;
    }
    jbyteArray result = env->NewByteArray((jsize) outLen);
    if (result != NULL) {
        env->SetByteArrayRegion(result, 0, (jsize) outLen, (const jbyte *) out);
    }
    ls_codec_free(out);
    return result;
}

static jbyteArray ls_material(JNIEnv *env, const uint8_t *data, size_t len) {
    jbyteArray result = env->NewByteArray((jsize) len);
    if (result != NULL) {
        env->SetByteArrayRegion(result, 0, (jsize) len, (const jbyte *) data);
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lsengine_engine_nativecodec_Codec_nativePrivateKey(JNIEnv *env, jclass clazz) {
    (void) clazz;
    size_t len = 0;
    const uint8_t *data = ls_signing_private_key(&len);
    return ls_material(env, data, len);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lsengine_engine_nativecodec_Codec_nativeCertificate(JNIEnv *env, jclass clazz) {
    (void) clazz;
    size_t len = 0;
    const uint8_t *data = ls_signing_certificate(&len);
    return ls_material(env, data, len);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lsengine_engine_nativecodec_Codec_nativePublicKey(JNIEnv *env, jclass clazz) {
    (void) clazz;
    size_t len = 0;
    const uint8_t *data = ls_signing_public_key(&len);
    return ls_material(env, data, len);
}
