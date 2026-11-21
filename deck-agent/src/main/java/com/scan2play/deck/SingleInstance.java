package com.scan2play.deck;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One running copy of the program per user: two would read the same history, send every song twice and show two tray icons. The
 * guard is a lock held on {@code ~/.scan2play/zagrane.lock} for the program's whole life. The system drops the lock when the process
 * ends however it ends (a crash, "Zakończ", a shutdown), so a file left behind never blocks the next start; the file's presence
 * means nothing, only the lock.
 */
final class SingleInstance {

    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstance(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static Path forThisUser() {
        return Path.of(System.getProperty("user.home"), ".scan2play", "zagrane.lock");
    }

    /** The lock, or null when another copy holds it (another process, or this one: the JVM reports that as an overlap). */
    static SingleInstance tryAcquire(Path lockFile) throws IOException {
        Files.createDirectories(lockFile.toAbsolutePath().getParent());
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return new SingleInstance(channel, lock);
            }
        } catch (OverlappingFileLockException e) {
            // held in this JVM already
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        channel.close();
        return null;
    }

    void release() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // the channel's close below drops it too
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // nothing left to do: the process' end drops the lock anyway
        }
    }
}
