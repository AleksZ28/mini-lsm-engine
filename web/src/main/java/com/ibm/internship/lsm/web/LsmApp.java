package com.ibm.internship.lsm.web;

import com.ibm.internship.lsm.api.StorageEngine;
import com.ibm.internship.lsm.web.controller.EntryController;
import com.ibm.internship.lsm.web.dto.ErrorResponse;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.javalin.validation.ValidationException;

public class LsmApp {
    private final Javalin app;
    private final StorageEngine engine;

    public LsmApp(StorageEngine engine) {
        this.engine = engine;
        EntryController entryController = new EntryController(engine);

        this.app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson());

            config.routes.exception(IllegalArgumentException.class, (e, ctx) -> {
                ctx.status(400).json(new ErrorResponse(e.getMessage()));
            });

            config.routes.exception(ValidationException.class, (e, ctx) -> {
                ctx.status(400).json(new ErrorResponse(e.getMessage()));
            });

            config.routes.exception(Exception.class, (e, ctx) -> {
                ctx.status(500).json(new ErrorResponse("Internal Server Error: " + e.getMessage()));
            });

            config.routes.get("/",  ctx -> {
                ctx.result("Server is working");
            });

            config.routes.post("/api/entries", entryController::createEntry);
            config.routes.get("/api/entries/{key}", entryController::readEntry);
            config.routes.delete("/api/entries/{key}", entryController::deleteEntry);
        });
    }

    public void start(int port) {
        app.start(port);
    }

    public void stop() {
        app.stop();
    }

    public int port() {
        return app.port();
    }
}
