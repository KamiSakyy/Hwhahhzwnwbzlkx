# Правила ProGuard для движка LS.
# Минифицирование отключено, но правила оставлены для совместимости со сборками,
# где shrinker включён: JNI-методы вызываются из native-кода по имени.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.lsengine.engine.nativecodec.Codec { *; }
-keep class com.lsengine.engine.runtime.LsRuntime { *; }
