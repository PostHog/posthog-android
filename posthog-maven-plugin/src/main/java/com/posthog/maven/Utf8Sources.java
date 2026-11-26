package com.posthog.maven;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies source roots with UTF-8 text. posthog-cli reads every source file as UTF-8 and skips a
 * file it cannot decode, so a project whose Java sources use another encoding would upload
 * without them.
 */
final class Utf8Sources {
    private Utf8Sources() {}

    static boolean isUtf8(Charset encoding) {
        return encoding.equals(StandardCharsets.UTF_8) || encoding.equals(StandardCharsets.US_ASCII);
    }

    /**
     * Copies each root into {@code <outputDirectory>/<index of the root>}, with the same relative
     * paths, and returns the copies. Java files are decoded from {@code encoding} and written as
     * UTF-8. Kotlin files are copied unchanged, because the Kotlin compiler reads only UTF-8.
     * Characters and line breaks stay the same, so every line keeps its number.
     */
    static List<File> copy(
            List<File> roots, Charset encoding, File outputDirectory, File buildDirectory)
            throws IOException {
        deleteRecursively(outputDirectory.toPath());
        Path output = outputDirectory.toPath().toAbsolutePath();
        Path build = buildDirectory.toPath().toAbsolutePath();
        List<File> copies = new ArrayList<>();
        for (int index = 0; index < roots.size(); index++) {
            Path root = roots.get(index).toPath().toAbsolutePath();
            Path copyRoot = output.resolve(String.valueOf(index));
            Files.createDirectories(copyRoot);
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attributes) {
                            if (dir.equals(root)) {
                                return FileVisitResult.CONTINUE;
                            }
                            // A root such as the project directory holds the build directory, and
                            // with it these copies.
                            boolean buildOutput = dir.equals(build) && !root.startsWith(build);
                            boolean hidden = dir.getFileName().toString().startsWith(".");
                            return buildOutput || hidden || dir.equals(output)
                                    ? FileVisitResult.SKIP_SUBTREE
                                    : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                                throws IOException {
                            String name = file.getFileName().toString();
                            Path target = copyRoot.resolve(root.relativize(file).toString());
                            if (name.endsWith(".java")) {
                                Files.createDirectories(target.getParent());
                                Files.write(target, decode(Files.readAllBytes(file), encoding));
                            } else if (name.endsWith(".kt")) {
                                Files.createDirectories(target.getParent());
                                Files.copy(file, target);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
            copies.add(copyRoot.toFile());
        }
        return copies;
    }

    // A byte the encoding cannot map becomes U+FFFD, as javac would report it, instead of dropping
    // the file.
    private static byte[] decode(byte[] bytes, Charset encoding) throws IOException {
        String text =
                encoding.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path each : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(each);
            }
        }
    }
}
