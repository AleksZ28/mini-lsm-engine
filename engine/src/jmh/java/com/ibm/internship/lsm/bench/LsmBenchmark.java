package com.ibm.internship.lsm.bench;

import com.ibm.internship.lsm.memtable.MemTable;
import com.ibm.internship.lsm.wal.Wal;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class LsmBenchmark {

    @Param({"1000", "100000"})
    int keys;

    @Param({"64", "1024"})
    int valueSize;

    String[] keyArr;
    byte[] value;
    Path tempDir;
    Wal wal;
    MemTable filled;
    MemTable fresh;
    int idx;

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        keyArr = new String[keys];
        for (int i = 0; i < keys; i++) {
            keyArr[i] = String.format("key-%08d", i);
        }
        value = new byte[valueSize];
        new Random(42).nextBytes(value);

        tempDir = Files.createTempDirectory("lsm-bench");
        wal = new Wal(tempDir.resolve("bench.wal"));

        filled = new MemTable();
        for (String key : keyArr) {
            filled.put(key, value);
        }
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        fresh = new MemTable();
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        wal.close();
        try (Stream<Path> paths = Files.walk(tempDir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    @Benchmark
    public void memTablePut() {
        fresh.put(keyArr[(idx++ & Integer.MAX_VALUE) % keys], value);
    }

    @Benchmark
    public ByteBuffer memTableGet() {
        return filled.get(keyArr[(idx++ & Integer.MAX_VALUE) % keys]);
    }

    @Benchmark
    public void walAppend() throws IOException {
        wal.append(keyArr[(idx++ & Integer.MAX_VALUE) % keys], value, false);
    }
}
