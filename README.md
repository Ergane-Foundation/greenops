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

Current state is visible without digging through logs:

```bash
kubectl get greenopscontrollers -n greenops
```

```
NAME                        GRID    CARBON   ACTION                 SAVEPOINT   AGE
greenops-flink-controller   DIRTY   850      SUSPEND_REQUESTED      REQUESTED   2h
```

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

- One Flink job per controller resource. Managing many jobs means many
  resources, and there is no coordination between them.
- Decisions are reactive. The operator responds to the current reading and does
  not use a forecast, so it cannot avoid suspending just before a dirty window
  ends, or weigh whether a short window is worth the restart cost.
- Only Apache Flink is supported. The savepoint mechanism is Flink specific.
- The estimate of carbon avoided assumes freed capacity is genuinely idle.

## Development

```bash
mvn -f operator/pom.xml verify
```

The tests concentrate on the suspension safety rule. Any change to the dirty
grid path should keep them passing.
