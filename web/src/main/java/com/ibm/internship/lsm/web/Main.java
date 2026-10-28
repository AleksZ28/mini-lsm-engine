package com.ibm.internship.lsm.web;

import com.ibm.internship.lsm.core.MiniLsmEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws IOException {
        int port = System.getenv("PORT") != null
                ? Integer.parseInt(System.getenv("PORT"))
                : 7070;

        Path walPath = Path.of("data", "log.wal");
        if (walPath.getParent() != null) {
            Files.createDirectories(walPath.getParent());
        }

        MiniLsmEngine engine = new MiniLsmEngine(walPath);
        engine.recoverFromWal();

        LsmApp app = new LsmApp(engine);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            try {
                engine.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));

        app.start(port);
    }
}
