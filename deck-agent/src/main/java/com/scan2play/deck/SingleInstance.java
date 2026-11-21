package com.scan2play.deck;

import java.io.IOException;

import java.nio.file.Path;

/** Placeholder to see the test red. */
final class SingleInstance {

    static SingleInstance tryAcquire(Path lockFile) throws IOException {
        return new SingleInstance();
    }

    void release() {
    }
}
