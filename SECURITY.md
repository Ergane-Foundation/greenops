# Security Policy

## Supported versions

GreenOps has not had a stable release yet. Security fixes are made on the
`main` branch only.

## Reporting a vulnerability

Please do not report security problems in public issues, pull requests or
the Discord server.

Report them privately through GitHub instead: open the repository's
**Security** tab and choose **Report a vulnerability**. Only the maintainers
can see the report.

A useful report includes:

- what the problem is and which component it affects (operator, telemetry
  service, dashboard, Helm chart or manifests)
- the steps or configuration needed to reproduce it
- what an attacker could do with it
- the commit or version you tested against

## What to expect

- We acknowledge a report within 3 working days.
- We confirm or rule out the problem and tell you what we plan to do.
- Once a fix is ready we publish an advisory, and credit you unless you
  would rather stay anonymous.

## Out of scope

The manifests under `k8s/` and the `scripts/` that set up the local demo use
fixed credentials for the SeaweedFS object store and Grafana on purpose, so
the demo runs with no setup. They are for a local Minikube or kind cluster
only and are not a vulnerability. Never use them anywhere else.
