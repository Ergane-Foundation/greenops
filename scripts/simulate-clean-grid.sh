#!/usr/bin/env bash
set -euo pipefail

# Forces the operator to see a CLEAN/GREEN grid by patching the CR's
# telemetryEndpoint to include ?simulate=clean.
NS="${NS:-solstice}"
CR="${CR:-solstice-flink-controller}"
BASE="http://solstice-telemetry.solstice.svc.cluster.local:8080/telemetry/status"

kubectl patch solsticecontroller "$CR" -n "$NS" --type=merge \
  -p "{\"spec\":{\"telemetryEndpoint\":\"${BASE}?simulate=clean\"}}"

echo "Patched $CR -> simulate=clean. Watch the operator logs to see scale-up."
