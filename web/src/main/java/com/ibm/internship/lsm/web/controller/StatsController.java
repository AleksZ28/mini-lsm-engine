package com.ibm.internship.lsm.web.controller;

import com.ibm.internship.lsm.api.StorageEngine;
import com.ibm.internship.lsm.core.MiniLsmEngine;
import com.ibm.internship.lsm.web.dto.EngineStatsResponse;
import io.javalin.http.Context;

import java.util.List;

public class StatsController {
    private final StorageEngine engine;

    public StatsController(StorageEngine engine) {
        this.engine = engine;
    }

    public void getStats(Context ctx) {
        if (engine instanceof MiniLsmEngine lsmEngine) {
            List<String> sstableNames = lsmEngine.getSstables().stream()
                    .map(reader -> reader.getPath().getFileName().toString())
                    .toList();

            EngineStatsResponse response = new EngineStatsResponse(
                    lsmEngine.getMemTableSizeBytes(),
                    lsmEngine.getMemTableEntryCount(),
                    sstableNames.size(),
                    sstableNames
            );

            ctx.json(response);
        } else {
            ctx.status(501).result("Stats not supported for this engine");
        }
    }
}
