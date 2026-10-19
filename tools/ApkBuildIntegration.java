import com.lsengine.engine.apk.ApkBuilder;
import com.lsengine.engine.bundle.AppBundle;
import com.lsengine.engine.aot.AotModule;
import com.lsengine.engine.lang.BytecodeFile;
import com.lsengine.engine.lang.Chunk;
import com.lsengine.engine.lang.Compiler;
import com.lsengine.engine.nativecodec.Codec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Builds and self-checks a real Android ARM64 package using the same offline builder as LS Studio. */
public final class ApkBuildIntegration {
    private static final byte[] ICON = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGP4DwQACfsD/fteaysAAAAASUVORK5CYII=");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("runtime-shell.apk output.apk");
        byte[] source = "function draw()\n  draw.clear(\"#102030\")\n  draw.text(16, 24, \"ARM64 package test\", 18, \"#ffffff\")\nend\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Chunk chunk = new Compiler().compile(new String(source, java.nio.charset.StandardCharsets.UTF_8));

        AppBundle bundle = new AppBundle();
        bundle.appName = "LS ARM64 Integration";
        bundle.packageName = "com.lsengine.integration";
        bundle.versionName = "1.0.0";
        bundle.versionCode = 1;
        bundle.entryFile = "main.ls";
        bundle.bytecode = BytecodeFile.write(chunk);
        bundle.nativeCode = AotModule.compileArm64(chunk);
        bundle.files.put("main.ls", source);

        byte[] encrypted = Codec.seal(bundle.toBytes());
        AppBundle restored = AppBundle.fromBytes(Codec.open(encrypted));
        if (restored.nativeCode.length < 64 || restored.nativeCode[0] != 0x7f
                || restored.nativeCode[1] != 'E' || restored.nativeCode[2] != 'L' || restored.nativeCode[3] != 'F'
                || (restored.nativeCode[18] & 0xff) != 0xb7) {
            throw new AssertionError("AArch64 ELF was not preserved inside the encrypted .c container");
        }

        ApkBuilder.Options options = new ApkBuilder.Options();
        options.appName = bundle.appName;
        options.packageName = bundle.packageName;
        options.versionName = bundle.versionName;
        options.versionCode = bundle.versionCode;
        options.orientation = "portrait";
        options.iconPng = ICON;
        byte[] shell = Files.readAllBytes(Path.of(args[0]));
        byte[] apk = ApkBuilder.build(shell, encrypted, options);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, apk);

        try (ZipFile zip = new ZipFile(output.toFile())) {
            if (zip.getEntry("assets/lsapp.c") == null) throw new AssertionError("encrypted .c missing");
            if (zip.getEntry("lib/arm64-v8a/liblsrt.so") == null) throw new AssertionError("ARM64 runtime missing");
            if (zip.getEntry("lib/arm64-v8a/liblscodec.so") == null) throw new AssertionError("ARM64 codec missing");
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (entry.getName().startsWith("lib/") && !entry.getName().startsWith("lib/arm64-v8a/")) {
                    throw new AssertionError("generated APK must contain only Android ARM64 native libraries: " + entry.getName());
                }
                if (entry.getName().endsWith("/liblsapp.so")) {
                    throw new AssertionError("user AOT code must only be inside encrypted .c, not a plaintext .so");
                }
            }
            String[] entries = zip.stream().map(ZipEntry::getName).toArray(String[]::new);
            boolean userIcon = false;
            for (String entry : entries) if (entry.matches("res/.+\\.png")) userIcon = true;
            if (!userIcon) throw new AssertionError("selected PNG icon was not installed in the package resources");
        }
        System.out.println("USER_APK=" + output.toAbsolutePath());
        System.out.println("AOT_MACHINE=AArch64 (183); payload encrypted in assets/lsapp.c");
        System.out.println("APK_SIZE=" + apk.length);
    }
}
