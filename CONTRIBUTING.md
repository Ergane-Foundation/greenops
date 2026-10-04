# Contributing to GreenOps

Thanks for helping. This guide covers how to get set up, how changes are
made, and what happens after you open a pull request.

Questions are welcome on the [GreenOps Discord](https://discord.gg/gZTJfUujX).
Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
Security problems go through [SECURITY.md](SECURITY.md), never a public issue.

## Finding something to work on

Issues labelled `good first issue` are small and self-contained, and say which
files are involved. Comment on an issue before starting so two people do not
pick up the same one. If an issue has had no activity for a week after being
claimed, it is open again.

For anything larger than a bug fix, open an issue first and describe what you
want to change, so the approach can be agreed before you write the code.

## The one rule that matters

GreenOps exists to suspend stateful Flink jobs without losing their state. A
job must never be suspended unless its state has been captured first, and must
never be resumed from anything other than its own savepoint.

Any change to the suspend or resume path needs tests showing that rule still
holds, including the failure cases. Most of the existing tests in
`operator/src/test` are there to protect it.

## Development setup

The repository has four parts:

| Directory | What it is | Language |
| --- | --- | --- |
| `operator/` | The Kubernetes operator | Java 17, Maven |
| `telemetry/` | Carbon intensity service | Python 3.11, FastAPI |
| `dashboard/` | Status dashboard | React with Vite, FastAPI backend |
| `charts/`, `k8s/` | Helm chart and raw manifests | YAML |

### Operator

You need Java 17 and Maven 3.9 or later.

```bash
mvn -f operator/pom.xml verify
mvn -f operator/pom.xml test -Dtest=PlanExecutorTest
mvn -f operator/pom.xml test -Dtest=PlanExecutorTest#resumeIsBlockedWithoutASavepoint
```

### Telemetry service

```bash
cd telemetry
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cd src && uvicorn main:app --port 8080
```

Without `ELECTRICITY_MAPS_TOKEN` it runs in simulated mode.
`/telemetry/status?simulate=dirty` and `?simulate=clean` force a grid state.

### Dashboard

```bash
cd dashboard
npm install
npm run build
```

### Running everything on a local cluster

You need Docker, Minikube, kubectl, Helm, Java 17 and Maven. Give Docker at
least 6GB of memory.

```bash
make up
```

This creates a Minikube profile called `greenops`, builds the images, deploys
the Flink Kubernetes Operator, SeaweedFS, the GreenOps operator and a sample
Flink job, and finishes with a smoke test that suspends the job and resumes
it from its savepoint. The first run takes around 20 minutes.

| Command | What it does |
| --- | --- |
| `make demo` | Suspend the job, then resume it from its savepoint |
| `make demo-dirty` | Suspend the job and wait for its savepoint |
| `make demo-clean` | Resume the job and wait for it to restore |
| `make status` | Show the controller, the Flink job and the pods |
| `make test` | Run the operator test suite |
| `make teardown` | Delete the cluster |

`PROFILE`, `CPUS` and `MEMORY` change the Minikube profile and its size, for
example `make up PROFILE=greenops-dev MEMORY=8g`.

## Making a change

1. Fork the repository and create a branch from `main`. Name it after the
   kind of change, for example `fix/telemetry-timeout`,
   `feat/grafana-savepoint-panel` or `docs/api-reference`.
2. Keep the change focused on one thing. Two unrelated fixes are two pull
   requests.
3. Add or update tests for what you changed, and run them locally.
4. Update the README or docs if you changed behaviour or configuration.

### Commit messages

Commits follow [Conventional Commits](https://www.conventionalcommits.org):

```
type(scope): what the change does, in plain words
```

Types: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`, `ci`.
Scopes follow the part of the code: `operator`, `scheduling`, `cost`,
`forecast`, `inventory`, `evaluation`, `metrics`, `telemetry`, `dashboard`,
`chart`, `k8s`, `scripts`, `monitoring`.

Examples from the history:

```
fix(operator): resume each job only from its own savepoint
feat(scheduling): decide using the forecast rather than the moment
```

Use the body to explain why the change was needed, not just what it does.

## Pull requests

- Open the pull request against `main` and link the issue it addresses.
- Describe what changed, why, and how you tested it.
- CI must pass before review.
- One maintainer approval is needed to merge.
- Pull requests are squash merged, so the title should itself be a valid
  Conventional Commit message.

## What to expect from review

A maintainer will give a first response within 3 working days. If you have
heard nothing after that, a polite nudge on the pull request or on Discord is
welcome.

Review comments are about the code, not about you. If a change is not going to
be accepted, we will say so and explain why rather than leave it open.

## Licence

GreenOps is licensed under the [Apache License 2.0](LICENSE). By contributing,
you agree that your contributions are licensed under the same terms.
