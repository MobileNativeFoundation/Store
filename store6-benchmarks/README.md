# store6-benchmarks

`store6-benchmarks` is an unpublished JVM harness for Store v6. It measures
METRIC-1, end-to-end collector attachment plus write-to-final-observation under
a controlled schedule against the raw Source of Truth flow, and records
structural-plus-measured evidence for telemetry unset versus configured-noop
overhead. It is neither a published artifact nor a public API.

## Run

From the repository root:

```shell
./gradlew :store6-benchmarks:benchmark
./gradlew :store6-benchmarks:smokeBenchmark
./gradlew :store6-benchmarks:calibrateBenchmark
```

`benchmark` is the default local profile. `smokeBenchmark` is the short,
report-only CI shape. `calibrateBenchmark` uses three forks and is the only
profile whose results may support an NFR-8 target proposal when run on a
documented, quiet, plugged-in machine.

Result JSON is discovered recursively beneath
`store6-benchmarks/build/reports/benchmarks/`. The timestamped layout observed
with `kotlinx-benchmark` 0.4.17 is evidence, not a stable path contract.

Start from a clean report directory. From the repository root, this snippet
requires exactly one non-empty, structurally valid JSON result before
summarizing it. It sorts parameter names before rendering them.

```shell
reports_dir="store6-benchmarks/build/reports/benchmarks"
report_count="$(
  find "$reports_dir" -type f -name '*.json' -print |
    wc -l |
    tr -d ' '
)"
test "$report_count" -eq 1
report="$(find "$reports_dir" -type f -name '*.json' -print)"
jq -e '
  type == "array" and
  length > 0 and
  all(.[];
    (.benchmark | type == "string" and length > 0) and
    (.primaryMetric.score | type == "number") and
    (.primaryMetric.scoreUnit | type == "string" and length > 0)
  )
' "$report" >/dev/null
jq -r '
  .[] |
  [
    .benchmark,
    ((.params // {}) |
      to_entries |
      sort_by(.key) |
      map("\(.key)=\(.value)") |
      join(",")),
    (.primaryMetric.score | tostring),
    .primaryMetric.scoreUnit
  ] |
  @tsv
' "$report"
```

Treat a clean invocation as valid only when it produces one non-empty JSON
array with the expected benchmark inventory and numeric primary metrics.

## What the numbers mean

1. **METRIC-1.** The metric is the `storeStream` / `rawSotFlow` average-time
   ratio for an end-to-end timed invocation. Within each invocation, W=1000
   begins only after every collector receives a public result and observes one
   epoch-unique readiness-marker write. That precondition is outside W but
   inside the timed operation. The score includes collector launch, attachment,
   readiness, the W schedule, and final observation. It is not pure W-only
   latency or per-emission cost. Both sides may conflate intermediate writes.
   `paced=true` cooperatively yields the writer; it is not an acknowledgement or
   a guarantee that all writes are observed. `paced=false` is
   burst/conflation.
2. **Headline and topology boundary.** `collectors=1` is the engine-overhead
   headline because both sides use `FakeSourceOfTruth` with matching reader
   multiplicity and common write cost. `collectors=8` is fan-out/topology data:
   raw opens eight reader chains while Store shares one upstream and fans out.
   It does not isolate engine overhead.
3. **Dispatch hops count.** Store's `Dispatchers.Default` engine hops are part
   of Store cost. The raw side cooperates on `runBlocking`.
4. **FS-10 is structural plus measured.** Structural tests and code establish
   that unset telemetry remains null and allocates no fetch mark.
   `none`-vs-`noop` estimates incremental configured-noop overhead relative to
   unset: non-null branches, the mark, and virtual no-op calls. It does not prove
   literal zero cost or bound total machinery against a telemetry-free engine.
   The ABBA allocation probe covers only the caller-thread resident path; a
   local JMH GC profiler, when available, covers cross-thread allocations.
5. **Hosted CI is smoke-grade.** No hosted number may ratify a target. Only a
   local quiet-machine `calibrate` result may support the NFR proposal.
6. **No numeric CI gate exists yet.** Until Matt ratifies OQ-6, workflows
   validate execution and schema only. They contain no performance threshold.
7. **Invocation isolation.** Every multi-write stream invocation uses
   epoch-unique readiness and sentinel values. Stores close per thread-scoped
   trial; cold stores close per invocation.

The private, ignored first-data record and NFR-8 proposal live at
`docs/v6/decisions/store6-benchmarks-first-data.md`.

## CI boundary

The blocking `:store6-benchmarks:build` step in
`.github/workflows/store6.yml` compiles the harness and executes every benchmark
body once through smoke tests. It is a rot guard, not a performance gate.

`.github/workflows/store6-benchmarks.yml` runs `smokeBenchmark`, validates the
result shape, and uploads the JSON in a non-blocking, report-only measurement
lane. It remains outside the exact-head-green release gate. No workflow may
assert a timing or allocation threshold until OQ-6 is ratified.
