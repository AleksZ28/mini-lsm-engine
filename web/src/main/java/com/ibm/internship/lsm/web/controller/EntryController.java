package com.ibm.internship.lsm.web.controller;

import com.ibm.internship.lsm.api.StorageEngine;
import com.ibm.internship.lsm.web.dto.EntryRequest;
import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class EntryController {
    private final StorageEngine engine;

    public EntryController(StorageEngine engine) {
        this.engine = engine;
    }

    public void createEntry(Context ctx) throws Exception {
        EntryRequest entryRequest = ctx.bodyAsClass(EntryRequest.class);
        engine.put(entryRequest.key(), entryRequest.value().getBytes(StandardCharsets.UTF_8));
        ctx.status(201);
    }

    public void readEntry(Context ctx) throws Exception {
        String key = ctx.pathParam("key");
        Optional<byte[]> value = engine.get(key);

        if (value.isPresent()) {
            ctx.status(200).result(new String(value.get(), StandardCharsets.UTF_8));
        } else {
            ctx.status(404).result("Entry not found");
        }
    }

    public void deleteEntry(Context ctx) throws Exception {
        String key = ctx.pathParam("key");
        engine.delete(key);
        ctx.status(204);
    }
}