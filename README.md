# GreenOps

Carbon aware suspension for stateful Apache Flink clusters.

GreenOps watches the carbon intensity of the grid powering your cluster. When
the grid turns dirty it suspends the Flink job, and when the grid comes back
clean it resumes the job from the savepoint taken on the way down. The job
picks up where it left off rather than starting over.

The rule the whole thing is built around: **a job is never suspended unless its
state has been safely captured first.** A failed or timed out savepoint aborts
the suspension and leaves the cluster running.

## Why this exists

Carbon aware scaling is straightforward for stateless and batch workloads,
which is where most existing tooling points. Stream processors are the awkward
case, because stopping one carelessly throws away in flight windowed state and
breaks exactly once processing. GreenOps handles that case.

## How it works

```
Electricity Maps ──▶ Telemetry ──▶ GreenOps operator ──▶ FlinkDeployment
                                          │                     │
                                          │                     ▼
                                          │              Flink JobManager
                                          ▼                     │
                                    Prometheus                  ▼
                                          │              savepoints on S3
                                          ▼
                                      Grafana
```

The operator reconciles every 60 seconds. On a dirty grid it patches
`FlinkDeployment.spec.job.state` to `suspended` and the Flink Kubernetes
Operator takes a savepoint before shutting the cluster down. On a clean grid it
writes the recorded savepoint into `spec.job.initialSavepointPath` and sets the
state back to `running`.

Going through the FlinkDeployment rather than scaling the Deployment directly
matters. The Flink operator owns that Deployment and will undo any replica
change made behind its back.

## Install

With Helm:

```bash
helm install greenops ./charts/greenops --namespace greenops --create-namespace
```

The chart installs the CRD, the operator, the telemetry service, and a
`GreenOpsController` resource pointed at a Flink job named `greenops-flink`.

For a full local environment including Minikube, MinIO, the Flink operator, a
sample Flink job and the monitoring stack:

```bash
./scripts/setup.sh
```

This is safe to re-run. `SKIP_BUILD=1` skips the image builds and `SKIP_TEST=1`
skips the smoke test at the end.

## Configuration

Fields on the `GreenOpsController` resource:

| Field | Default | Description |
| --- | --- | --- |
| `carbonThreshold` | `400` | gCO2/kWh above which the grid counts as dirty |
| `cooperativeSuspension` | `true` | Suspend through the FlinkDeployment rather than by scaling |
| `nodePowerWatts` | `250` | Assumed draw of the freed capacity, used for the carbon estimate |
| `flinkJobName` | | Name of the FlinkDeployment to manage |
| `flinkNamespace` | | Namespace it lives in |
| `telemetryEndpoint` | | Where to read carbon intensity |
| `flinkRestEndpoint` | | Flink REST API, used only in non cooperative mode |
| `savepointDirectory` | `s3://greenops/savepoints` | Where savepoints are written |
| `savepointTimeoutSeconds` | `300` | How long to wait for a savepoint |
| `schedulingPolicy` | `threshold` | `threshold`, `forecast` or `optimising` |
| `costPredictor` | `static` | `static`, `observed` or `regression` |
| `forecastEndpoint` | | Where to read the carbon forecast |
| `jobSelector` | | Labels selecting the FlinkDeployments to manage |
| `breakEvenMultiplier` | `2.0` | How much longer than the round trip a window must be |
| `assumedSavepointSeconds` | `60` | Savepoint duration before anything has been measured |
| `assumedRestartSeconds` | `120` | Restart duration before anything has been measured |
| `maxConcurrentSuspensions` | `0` | How many jobs may be suspended together, 0 for no limit |

Current state is visible without digging through logs:

```bash
kubectl get greenopscontrollers -n greenops
```

```
NAME                        GRID    CARBON   ACTION                 SAVEPOINT   AGE
greenops-flink-controller   DIRTY   850      SUSPEND_REQUESTED      REQUESTED   2h
```

## Scheduling policies

| Policy | Behaviour |
| --- | --- |
| `threshold` | Suspend whenever carbon is above the threshold. |
| `forecast` | Look at how long the dirty stretch lasts and decline windows too short to pay for the savepoint and restart. Start the savepoint slightly before an imminent window. |
| `optimising` | As above, and when more jobs want suspending than `maxConcurrentSuspensions` allows, prefer the ones avoiding most carbon per second of disruption. |

Replayed over fourteen days of a simulated daily grid curve:

| Policy | gCO2 avoided | Suspensions | Wasted |
| --- | --- | --- | --- |
| threshold | 15453 | 92 | 55 |
| forecast | 14277 | 14 | 0 |

A wasted suspension is one shorter than the round trip that paid for it. The
forecast policy keeps most of the saving for a seventh of the disruption.
Reproduce with `EvaluationRunner`.

## Cost prediction

The operator times its own suspends and restarts and keeps a rolling history
on the resource status, so estimates improve as it runs.

| Predictor | Behaviour |
| --- | --- |
| `static` | The configured constants. |
| `observed` | Median and percentiles of what this cluster actually did. Needs three observations. |
| `regression` | Ridge regression on state size and parallelism. Needs eight. |

Mean absolute error against held out observations:

| Scenario | static | observed | regression |
| --- | --- | --- | --- |
| Savepoint duration tracks state size | 65s | 34s | 5s |
| Every job holds the same state | | 4.1s | 4.1s |

The regression is worth having where jobs differ in size. Where they do not,
it matches the median and nothing more, which is the expected result rather
than a disappointing one. Each predictor falls back to the simpler one below
it when there is too little history, so a fresh cluster behaves exactly as it
did before and improves with use.

## Grid data

Set `ELECTRICITY_MAPS_TOKEN` on the telemetry service for real readings from
Electricity Maps. Without a token it stays in simulated mode, which is enough
to exercise the whole cycle:

```bash
./scripts/simulate-dirty-grid.sh
./scripts/simulate-clean-grid.sh
```

## Metrics

The operator serves Prometheus metrics on port 9400.

| Metric | Type | Description |
| --- | --- | --- |
| `greenops_carbon_intensity_gco2_kwh` | gauge | Last reading from telemetry |
| `greenops_carbon_threshold_gco2_kwh` | gauge | Configured threshold |
| `greenops_grid_dirty` | gauge | 1 when the grid is dirty |
| `greenops_job_suspended` | gauge | 1 while the job is suspended |
| `greenops_suspension_seconds_total` | counter | Total time spent suspended |
| `greenops_carbon_avoided_grams_total` | counter | Estimated CO2 avoided |
| `greenops_savepoint_duration_seconds` | histogram | Savepoint timings |
| `greenops_savepoint_total` | counter | Savepoint outcomes by result |
| `greenops_reconcile_total` | counter | Reconciles by action taken |

A Grafana dashboard is included at `grafana/greenops-dashboard.json` and is
provisioned automatically by `setup.sh`.

The carbon figure is an estimate, not a measurement. It multiplies suspended
time by `nodePowerWatts` and the carbon intensity observed during that window.
Treat it as an indication of scale rather than a reported number, and set
`nodePowerWatts` to something reasonable for your hardware.

## Limitations

- Only Apache Flink is supported. The savepoint mechanism is Flink specific.
- The estimate of carbon avoided assumes freed capacity is genuinely idle.
- The policy comparison above is measured on generated grid traces rather than
  a recorded history from a real grid. The shapes are plausible and the traces
  are reproducible from a seed, but they are not production data.
- The regression reads state size from an annotation. Until it is read from
  Flink directly, that feature is only as good as whoever set it, and
  `observed` is the safer default.
- The optimiser ranks jobs greedily. At a few dozen jobs the difference from an
  exact answer is small, but it is not proven optimal.

## Development

```bash
mvn -f operator/pom.xml verify
```

The tests concentrate on the suspension safety rule. Any change to the dirty
grid path should keep them passing.
