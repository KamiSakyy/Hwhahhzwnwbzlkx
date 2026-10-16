import com.sun.source.util.JavacTask;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** JDK parser only. Does not resolve Android symbols, generate classes, dex or APK. */
public final class JavaSyntaxCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected Java source directory");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required");
        List<java.io.File> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get(args[0]))) {
            paths.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> files.add(p.toFile()));
        }
        if (files.isEmpty()) throw new IllegalArgumentException("No Java sources found");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    Arrays.asList("-proc:none", "-source", "8", "-Xlint:-options"), null,
                    manager.getJavaFileObjectsFromFiles(files));
            task.parse();
        }
        boolean errors = false;
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors = true;
                System.err.println(d);
            }
        }
        if (errors) throw new IllegalStateException("Java syntax errors");
        System.out.println("Parsed " + files.size() + " Java source files. Syntax only: Android symbols/runtime/visuals NOT checked.");
    }
}
