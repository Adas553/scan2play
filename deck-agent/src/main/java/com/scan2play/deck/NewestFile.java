package com.scan2play.deck;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The newest file with an extension in the first of some folders that has one: VirtualDJ's day, Serato's session. */
final class NewestFile {

    private NewestFile() {
    }

    static Optional<Path> in(List<Path> folders, String extension) throws IOException {
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            Path newest = null;
            FileTime newestTime = null;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
                for (Path file : files) {
                    if (!Files.isRegularFile(file) || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)) {
                        continue;
                    }
                    FileTime time = Files.getLastModifiedTime(file);
                    if (newest == null || time.compareTo(newestTime) > 0) {
                        newest = file;
                        newestTime = time;
                    }
                }
            }
            if (newest != null) {
                return Optional.of(newest);
            }
        }
        return Optional.empty();
    }

    static String version(Path file) throws IOException {
        return file + "|" + Files.getLastModifiedTime(file).toMillis() + "|" + Files.size(file);
    }
}
