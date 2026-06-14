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
