/* Packaging marker: forces Android's native ABI matching to arm64-v8a only.
 * No JNI, permissions, runtime loading or Telegram native dependencies. */
__attribute__((visibility("default"))) int themestudio_arm64_only(void) { return 64; }
