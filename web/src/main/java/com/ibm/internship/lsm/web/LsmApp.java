package com.ibm.internship.lsm.web;

import com.ibm.internship.lsm.api.StorageEngine;
import io.javalin.Javalin;

public class LsmApp {
    private final Javalin app;
    private final StorageEngine engine;

    public LsmApp(StorageEngine engine) {
        this.engine = engine;
        this.app = Javalin.create(config -> {
            config.routes.get("/",  ctx -> {
                ctx.result("Server is working");
            });
        });
    }

    public void start(int port) {
        app.start(port);
    }

    public void stop() {
        app.stop();
    }
}
