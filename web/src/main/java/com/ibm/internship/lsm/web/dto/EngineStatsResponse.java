package com.ibm.internship.lsm.web.dto;

import java.util.List;

public record EngineStatsResponse(
        long memTableSizeBytes,
        int memTableEntryCount,
        int sstableCount,
        List<String> sstables
) {
}
