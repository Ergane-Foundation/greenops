# Solstice

Carbon aware suspension for stateful Apache Flink clusters.

Solstice watches the carbon intensity of the grid powering your cluster. When
the grid turns dirty it suspends the Flink job, and when the grid comes back
clean it resumes the job from the savepoint taken on the way down. The job
picks up where it left off rather than starting over.

The rule the whole thing is built around: **a job is never suspended unless its
state is captured first, and never resumed from anything but its own
savepoint.** [Safety](#safety) lists exactly how that is enforced.

[Discord](https://discord.gg/gZTJfUujX) ·
[Contributing](CONTRIBUTING.md) ·
[Code of Conduct](CODE_OF_CONDUCT.md) ·
[Security](SECURITY.md)

## Project status

Solstice is an early stage project, maintained by the Ergane Foundation and not
yet released. The suspend and resume lifecycle is built and has been run end to
end on a local cluster. Nothing has run in production. Expect the custom
resource and configuration to change before a first release.

## Why this exists

Carbon aware scaling is straightforward for stateless and batch workloads,
which is where most existing tooling points. Stream processors are the awkward
case, because stopping one carelessly throws away in flight windowed state and
breaks exactly once processing. Solstice handles that case.

## How it works

```
Electricity Maps ──▶ Telemetry ──▶ Solstice operator ──▶ FlinkDeployment
                                          │                     │
                                          │                     ▼
                                          │          Flink Kubernetes Operator
                                          ▼                     │
                                    Prometheus                  ▼
                                          │             savepoints on S3
                                          ▼
                                      Grafana
```

The operator reconciles every 60 seconds. On a dirty grid it patches
`FlinkDeployment.spec.job.state` to `suspended`, and the Flink Kubernetes
Operator takes a savepoint before stopping the job. On a clean grid it writes
that job's savepoint into `spec.job.initialSavepointPath` and sets the state
back to `running`.

Going through the FlinkDeployment rather than scaling the Deployment directly
matters. The Flink operator owns that Deployment and will undo any replica
change made behind its back.

## Safety

| Situation | What Solstice does |
| --- | --- |
| FlinkDeployment's `upgradeMode` is not `savepoint` | Does not suspend it. Flink would stop it without a savepoint. |
| Suspend requested | Reports the savepoint `REQUESTED`, then `COMPLETED` once Flink reports the job suspended, or `FAILED` if Flink reports an error |
| A job has no savepoint of its own | Does not resume it, rather than start it with empty state |
| Telemetry unreachable, erroring or stale | Holds every job where it is, neither suspending nor resuming |
| Non cooperative mode without a savepoint | Does not scale the job down |

## Quickstart

You need Docker with at least 6GB of memory, Minikube, kubectl, Helm, Java 17,
Maven and `make`.

```bash
git clone https://github.com/Ergane-Foundation/solstice.git
cd solstice
make up
```

`make up` creates a Minikube profile called `solstice`, builds the images,
installs cert-manager and the Flink Kubernetes Operator, deploys SeaweedFS as
the S3 store, the Solstice operator, the telemetry service, a sample Flink job,
Prometheus and Grafana, and finishes with a smoke test. The first run takes
around 20 minutes, most of it downloading images.

## Demo

The telemetry service runs in simulated mode unless it has an Electricity Maps
token, so you can drive the grid by hand:

```bash
make demo-dirty
make demo-clean
```

Each command waits until the change has really happened and fails if it does
not:

```
[demo] Grid set to dirty
[demo] Waiting for a new savepoint to complete (up to 300s)
[demo] Waiting for Flink to report the job suspended (up to 300s)
[demo] Suspended with savepoint s3://solstice/savepoints/savepoint-44b668-aeecd7642e07
[demo] Grid set to clean
[demo] Waiting for the job to be running (up to 300s)
[demo] Waiting for Flink to restore from s3://solstice/savepoints/savepoint-44b668-aeecd7642e07 (up to 300s)
[demo] Resumed from savepoint s3://solstice/savepoints/savepoint-44b668-aeecd7642e07
```

The restore is confirmed from Flink's own JobManager log, not from Solstice.
Current state is visible without digging through logs:

```bash
kubectl get solsticecontrollers -n solstice
```

```
NAME                        GRID    CARBON   ACTION              SAVEPOINT   JOBS   AGE
solstice-flink-controller   DIRTY   850      ALREADY_SUSPENDED   COMPLETED   1      4m
```

`make status` adds the Flink job and the pods, and `make help` lists the other
targets.

## Install with Helm

```bash
helm install solstice ./charts/solstice --namespace solstice --create-namespace
```

The chart installs the CRD, the operator, the telemetry service and a
`SolsticeController` pointed at a Flink job named `solstice-flink`. It expects
the Flink Kubernetes Operator, an S3 store and your FlinkDeployments to exist
already.

Images are not published yet. The chart uses `solstice/operator:latest` and
`solstice/telemetry:latest`, which you have to build into your cluster first,
as `make up` does.

## Configuration

Fields on the `SolsticeController` resource:

| Field | Default | Description |
| --- | --- | --- |
| `carbonThreshold` | `400` | gCO2/kWh above which the grid counts as dirty |
| `cooperativeSuspension` | `true` | Suspend through the FlinkDeployment rather than by scaling |
| `nodePowerWatts` | `250` | Assumed draw of the freed capacity, used for the carbon estimate |
| `flinkJobName` | | Name of the FlinkDeployment to manage |
| `flinkNamespace` | | Namespace it lives in |
| `jobSelector` | | Labels selecting the FlinkDeployments to manage, instead of one name |
| `telemetryEndpoint` | | Where to read carbon intensity |
| `forecastEndpoint` | | Where to read the carbon forecast |
| `schedulingPolicy` | `threshold` | `threshold`, `forecast` or `optimising` |
| `costPredictor` | `static` | `static`, `observed` or `regression` |
| `breakEvenMultiplier` | `2.0` | How much longer than the round trip a window must be |
| `assumedSavepointSeconds` | `60` | Savepoint duration before anything has been measured |
| `assumedRestartSeconds` | `120` | Restart duration before anything has been measured |
| `maxConcurrentSuspensions` | `0` | How many jobs may be suspended together, 0 for no limit |
| `flinkRestEndpoint` | | Flink REST API, used only in non cooperative mode |
| `savepointDirectory` | `s3://solstice/savepoints` | Where savepoints go, used only in non cooperative mode |
| `savepointTimeoutSeconds` | `300` | How long to wait for a savepoint, used only in non cooperative mode |

Each managed FlinkDeployment must set `spec.job.upgradeMode: savepoint`. It can
also carry these annotations:

| Annotation | Meaning |
| --- | --- |
| `solstice.io/priority` | Orders the jobs on the status. It does not yet affect which job is suspended. |
| `solstice.io/max-suspension-seconds` | Declines a suspension the forecast says would last longer than this |
| `solstice.io/state-size-bytes` | State size, used by the `regression` predictor |

## Scheduling policies

| Policy | Behaviour |
| --- | --- |
| `threshold` | Suspend whenever carbon is above the threshold. |
| `forecast` | Look at how long the dirty stretch lasts and decline windows too short to pay for the savepoint and restart. Start the savepoint slightly before an imminent window. |
| `optimising` | As above, and when more jobs want suspending than `maxConcurrentSuspensions` allows, prefer the ones avoiding most carbon per second of disruption. |

Replayed over fourteen days of three generated grid traces, with a 5 minute
savepoint and 10 minute restart:

| Trace | Policy | gCO2 avoided | Suspensions | Wasted |
| --- | --- | --- | --- | --- |
| diurnal | threshold | 15453 | 92 | 55 |
| diurnal | forecast | 14277 | 14 | 0 |
| spiky | threshold | 32741 | 72 | 2 |
| spiky | forecast | 32165 | 57 | 0 |
| flickering | threshold | 3529 | 71 | 28 |
| flickering | forecast | 999 | 8 | 0 |

A wasted suspension is one shorter than the round trip that paid for it. Where
the grid flips often, the forecast policy cuts disruption sharply. Where dirty
stretches are long and few, as in `spiky`, it changes little.

These are upper bounds. The traces are generated from a seed rather than
recorded from a real grid, and the forecast policy is given the trace's true
future as its forecast. A real forecast is wrong some of the time and would do
worse. Reproduce with:

```bash
make test
java -cp operator/target/solstice-operator.jar com.solstice.evaluation.EvaluationRunner
```

## Cost prediction

The operator records how long each suspend and restart took on the resource
status, so its estimates can follow the cluster.

| Predictor | Behaviour |
| --- | --- |
| `static` | The configured constants. |
| `observed` | Median and percentiles of what this cluster actually did. Needs three observations. |
| `regression` | Ridge regression on state size and parallelism. Needs eight. |

Each predictor falls back to the simpler one when there is too little history,
so a fresh cluster behaves exactly like `static`.

Mean absolute error against held out observations:

| Scenario | static | observed | regression |
| --- | --- | --- | --- |
| Savepoint duration tracks state size | 65s | 34s | 5s |
| Every job holds the same state | | 4.1s | 4.1s |

These come from synthetic observations, not a live cluster. Reproduce with
`mvn -f operator/pom.xml test -Dtest=PredictorEvaluatorTest`.

On a live cluster the recorded durations are not yet accurate. The timer stops
on the first reconcile after Solstice patches the job, so each observation is
roughly the 60 second reconcile interval rather than the real savepoint or
restart time. Until that is fixed, use `static`.

## Grid data

Without a token the telemetry service runs in simulated mode, which is enough
to exercise the whole cycle. For real readings from Electricity Maps, put the
token in a Secret and point the Helm chart at it with `telemetry.existingSecret`.

A reading older than `STALE_AFTER_SECONDS` (default 600) is reported as
`UNKNOWN`, and the operator holds every job until fresh data arrives.

## Metrics

The operator serves Prometheus metrics on port 9400.

| Metric | Type | Description |
| --- | --- | --- |
| `solstice_carbon_intensity_gco2_kwh` | gauge | Last reading from telemetry |
| `solstice_carbon_threshold_gco2_kwh` | gauge | Configured threshold |
| `solstice_grid_dirty` | gauge | 1 when the grid is dirty |
| `solstice_job_suspended` | gauge | 1 while Flink reports the job suspended |
| `solstice_suspension_seconds_total` | counter | Time each job spent suspended |
| `solstice_carbon_avoided_grams_total` | counter | Estimated CO2 avoided per job |
| `solstice_savepoint_duration_seconds` | histogram | Savepoint timings |
| `solstice_savepoint_total` | counter | Savepoint phase changes by result |
| `solstice_reconcile_total` | counter | Reconciles by action taken |

A Grafana dashboard is included at `grafana/solstice-dashboard.json` and is
provisioned automatically by `make up`.

The carbon figure is an estimate, not a measurement. It multiplies the time a
job is actually suspended by `nodePowerWatts` and the carbon intensity observed
during that time. Treat it as an indication of scale rather than a reported
number, and set `nodePowerWatts` to something reasonable for your hardware.

## Limitations

- Only Apache Flink is supported. The savepoint mechanism is Flink specific.
- With Flink Kubernetes Operator 1.14, a suspended job's JobManager keeps
  running and only its TaskManagers are released. The carbon estimate counts
  whatever `nodePowerWatts` says, so set it for the TaskManager capacity.
- The estimate of carbon avoided assumes freed capacity is genuinely idle.
- The policy comparison uses generated traces and a perfect forecast. See
  [Scheduling policies](#scheduling-policies).
- Recorded suspend and restart durations are not yet accurate. See
  [Cost prediction](#cost-prediction).
- The regression reads state size from an annotation, so it is only as good as
  whoever set it.
- The default `threshold` policy has no hysteresis or minimum suspension time,
  so an intensity hovering at the threshold suspends and resumes repeatedly.
  The `forecast` policy avoids this when a forecast is available.
- The operator's permissions are cluster wide, and a `SolsticeController` can
  point at FlinkDeployments in any namespace. Run it only where everyone who can
  create one is trusted.
- The optimiser ranks jobs greedily. At a few dozen jobs the difference from an
  exact answer is small, but it is not proven optimal.

## Roadmap

Planned or being considered, none of it built yet:

- Published multi arch images, and an end to end test on kind in CI
- Hysteresis and a minimum suspension time for the threshold policy
- Accurate suspend and restart timing from Flink's own status
- Namespace scoped permissions
- Validation against recorded grid history and an imperfect forecast
- Exploratory: a learned scheduling policy using reinforcement learning
- Exploratory: moving suspended state between peers instead of to object storage

Issues labelled `good first issue` are the best place to start.

## Development

```bash
make test
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development setup, conventions
and how pull requests are reviewed. The tests concentrate on the safety rules
above, and any change to the suspend or resume path must keep them passing.

## Maintainers

- Yash Agarwal ([@yash-agarwa-l](https://github.com/yash-agarwa-l))

## Licence

Solstice is licensed under the [Apache License 2.0](LICENSE).

The local demo also runs third party software as separate, unmodified
containers, under their own licences: SeaweedFS (Apache 2.0), the Flink
Kubernetes Operator (Apache 2.0), cert-manager (Apache 2.0), Prometheus
(Apache 2.0) and Grafana (AGPL 3.0).
