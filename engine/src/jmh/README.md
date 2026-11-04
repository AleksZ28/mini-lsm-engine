# Benchmarking the engine with JMH and JFR

This directory holds JMH micro-benchmarks for the engine (`LsmBenchmark`: `memTablePut`,
`memTableGet`, `walAppend`). This page explains how to build and run them, what every
flag does, and how to turn a recording into a flamegraph or a before/after comparison.

You need Java 21 on your `PATH` (`sdk install java 21.0.9-tem`). Gradle itself needs 17+,
the project toolchain is pinned to 21.

## 1. Build the benchmark jar

```bash
./gradlew :engine:jmhJar -q
```

Produces `engine/build/libs/engine-1.0-SNAPSHOT-jmh.jar`, a self-contained jar with the
engine classes, the benchmarks and the JMH runner. Rebuild it after every code change,
otherwise you benchmark stale classes.

## 2. Run with a CPU + memory recording

```bash
mkdir -p engine/build/jfr
java -jar engine/build/libs/engine-1.0-SNAPSHOT-jmh.jar LsmBenchmark \
    -f 1 -wi 3 -i 5 -w 1s -r 1s \
    -prof jfr:dir=engine/build/jfr \
    -prof gc \
    -rf json -rff engine/build/jfr/results.json
```

| Flag | Meaning |
|---|---|
| `LsmBenchmark` | Regex matched against benchmark names. `walAppend` runs one method, `LsmBenchmark` runs all three, nothing runs everything. `-l` lists what is available. |
| `-f 1` | Number of forked JVMs per benchmark. Each fork is a fresh JVM so JIT state does not leak between benchmarks. `-f 0` runs in the host JVM (handy for debugging, useless for numbers). |
| `-wi 3 -w 1s` | 3 warm-up iterations of 1 second each. Warm-up lets the JIT compile the hot path. Results from warm-up are discarded. |
| `-i 5 -r 1s` | 5 measured iterations of 1 second each. The score is the mean over these, the error is the 99.9% confidence interval. |
| `-p keys=1000 -p valueSize=64` | Override an `@Param`. Without it JMH runs the full matrix of all declared values. |
| `-prof jfr:dir=DIR` | Start a JDK Flight Recorder recording inside the forked JVM, using the JDK `profile` settings: CPU samples every 10 ms (`jdk.ExecutionSample`) plus allocation samples (`jdk.ObjectAllocationSample`). One `profile.jfr` per benchmark+params lands under `DIR/<benchmark>-<params>/`. Options: `configName=default` for a lighter recording, `stackDepth=128` for deeper stacks. |
| `-prof gc` | Prints allocation rate (MB/s), bytes allocated per operation (`gc.alloc.rate.norm`) and GC counts next to the score. This is the number to watch for memory regressions. |
| `-rf json -rff FILE` | Write the result table as JSON. Needed for comparisons (section 4). |
| `-bm avgt` / `-bm thrpt` | Benchmark mode: average time per operation, or operations per second. The class sets `avgt`. |
| `-tu us` | Time unit for the output (ns, us, ms, s). |
| `-jvmArgsAppend "-Xmx1g"` | Extra JVM flags for the forks, e.g. heap size or `-XX:+UseParallelGC`. |

Always check that the result table has rows. A failed fork prints an empty table.

### What the numbers mean

```
Benchmark                                    (keys) (valueSize) Mode Cnt    Score   Error Units
LsmBenchmark.memTablePut                     100000          64 avgt   5    0.125 ± 0.004 us/op
LsmBenchmark.memTablePut:gc.alloc.rate.norm  100000          64 avgt   5  168.209 ± 0.100  B/op
```

`Score` is the cost of one call. `B/op` is how many bytes one call allocates. If the error
is a large fraction of the score, the run is noisy: close other programs, raise `-i`, and
never run two benchmarks at once.

## 3. Turn the recording into a flamegraph

Two tools are involved:

- `jfr`, which ships with the JDK, dumps a recording as text.
- `jfrconv`, from [async-profiler](https://github.com/async-profiler/async-profiler/releases)
  (`bin/jfrconv` in the download), renders interactive HTML flamegraphs.

`jfrconv` reads allocation samples straight from a JDK recording, but it reads **no CPU
samples** from one (it only understands its own CPU events). For CPU we go through
`jfr print` and a tiny script, `engine/tools/jfr2collapsed.py`, that folds the text into
the standard "collapsed stacks" format (`frame;frame;frame count`, one line per stack).

```bash
F=engine/build/jfr/<benchmark-dir>/profile.jfr

# CPU flamegraph
jfr print --stack-depth 64 --events jdk.ExecutionSample "$F" > cpu.txt
python3 engine/tools/jfr2collapsed.py cpu.txt cpu.collapsed
jfrconv -o html cpu.collapsed cpu.html

# Allocation flamegraph (bytes)
jfrconv --alloc -o html "$F" alloc.html
```

Open the `.html` in a browser. Width is share of samples (or bytes); click a frame to
zoom, use the search box to highlight a regex. Useful `jfrconv` switches: `--simple`
(short class names), `--reverse` (flip so leaves are on top), `-I REGEX` / `-X REGEX`
(keep or drop stacks containing a frame), `--title`.

The `.collapsed` file is also greppable:

```bash
# hottest leaf frames
awk '{n=split($0,a,";"); leaf=a[n]; sub(/ [0-9]+$/,"",leaf); c[leaf]+=$NF}
     END {for (k in c) print c[k], k}' cpu.collapsed | sort -rn | head
```

Caveats:

- Native work is invisible to `jdk.ExecutionSample`. `walAppend` spends its time in
  `FileChannel.write`, so its CPU profile is almost empty. Use
  `--events jdk.NativeMethodSample` for that, or trust `gc` and the score instead.
- The recording starts before warm-up, so `@Setup` and warm-up iterations are in it.
  Keep `-i` large relative to `-wi` when reading a profile.
- Percentages are shares of that one run. They say where time goes, not how much it is.

## 4. Before/after comparisons

**Scores.** Run the same command on both versions with different `-rff` names, then diff
the JSON:

```bash
# on the old code
java -jar ... -rf json -rff before.json
# on the new code (rebuild the jar first!)
java -jar ... -rf json -rff after.json

jq -r '.[] | [.benchmark, (.params|tostring), .primaryMetric.score] | @tsv' before.json > b.tsv
jq -r '.[] | [.benchmark, (.params|tostring), .primaryMetric.score] | @tsv' after.json  > a.tsv
paste b.tsv a.tsv | awk -F'\t' '{printf "%-45s %-40s %10.3f -> %10.3f  (%+.1f%%)\n", $1, $2, $3, $6, ($6-$3)*100/$3}'
```

Compare `gc.alloc.rate.norm` the same way from `.secondaryMetrics`. Same machine, same
flags, same params on both sides, or the comparison is meaningless.

**Profiles.** `jfrconv` draws a differential flamegraph from two collapsed files. Frames
that got more expensive are red, cheaper ones blue:

```bash
jfrconv --diff -o html before.collapsed after.collapsed diff.html
```

Build the two `.collapsed` inputs with the CPU recipe in section 3 (one per version). For
allocations, first convert each recording: `jfrconv --alloc -o collapsed "$F" alloc.collapsed`.

## 5. Adding a benchmark

Add a `public` method annotated `@Benchmark` to `LsmBenchmark`, or a new class in this
source set. Return the value you compute or take a `Blackhole` parameter and
`consume()` it, otherwise the JIT removes the work. Put expensive preparation in
`@Setup(Level.Trial)`, not in the benchmark body. Rebuild the jar and run with `-l` to
see the new name.
