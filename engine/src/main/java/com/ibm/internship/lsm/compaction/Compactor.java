package com.ibm.internship.lsm.compaction;

import com.ibm.internship.lsm.sstable.SSTableEntry;
import com.ibm.internship.lsm.sstable.SSTableReader;
import com.ibm.internship.lsm.sstable.SSTableWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class Compactor {
    public Compactor() {}

    public SSTableReader compact(
            List<SSTableReader> sstables,
            Path finalPath
    ) throws IOException {
        Objects.requireNonNull(sstables, "SSTables must not be null");
        Objects.requireNonNull(finalPath, "FinalPath must not be null");

        if (sstables.isEmpty()) throw new IllegalArgumentException("SSTables must not be empty");

        MergingIterator iterator = new MergingIterator(sstables);

        boolean ok = false;
        try (SSTableWriter writer = new SSTableWriter(finalPath)) {
            while (iterator.hasNext()) {
                SSTableEntry entry = iterator.next();
                writer.append(entry);
            }

            writer.finish();
            ok = true;
        } finally {
            if (!ok) {
                Files.deleteIfExists(finalPath);
            }
        }

        return SSTableReader.open(finalPath);
    }
}
