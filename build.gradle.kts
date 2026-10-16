// Root build script.
// Zero external dependencies on purpose: the app is built only on the Android
// platform SDK, which keeps the release APK around ~1 MB.
plugins {
    id("com.android.application") version "8.13.2" apply false
}
