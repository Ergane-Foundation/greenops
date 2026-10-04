#!/usr/bin/env bash
# GreenOps end-to-end bootstrap.
#
# Brings the whole system up on Minikube from a cold start:
#   1. Ensures Minikube is running.
#   2. Builds all four local images into Minikube's docker daemon.
#   3. Installs cert-manager and the Flink Kubernetes Operator, and deploys
#      SeaweedFS as the S3 store for checkpoints and savepoints.
#   4. Creates the bucket Flink writes checkpoints and savepoints to.
#   5. Deploys telemetry, the GreenOps CRD/RBAC/operator/CR, the FlinkDeployment,
#      and the dashboard.
#   6. Waits for every rollout to be Ready.
#   7. Runs the dirty-grid → clean-grid smoke test.
#
# Safe to re-run: every step is idempotent (kubectl apply / helm upgrade --install).
#
# Usage:   ./scripts/setup.sh
# Env:     MINIKUBE_PROFILE (default minikube)
#          MINIKUBE_CPUS, MINIKUBE_MEMORY (default max, i.e. all Docker allows)
#          MINIKUBE_HOME is honoured if set, as minikube itself does
#          SKIP_BUILD=1  → skip all docker builds
#          SKIP_TEST=1   → skip the dirty/clean smoke test at the end

set -euo pipefail


# Config
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

MINIKUBE_PROFILE="${MINIKUBE_PROFILE:-minikube}"
MINIKUBE_CPUS="${MINIKUBE_CPUS:-max}"
MINIKUBE_MEMORY="${MINIKUBE_MEMORY:-max}"
NS="greenops"
BUCKET="greenops"
FLINK_OPERATOR_VERSION="1.14.0"
CERT_MANAGER_VERSION="v1.16.0"

log()  { printf "\033[1;32m[setup]\033[0m %s\n" "$*"; }
warn() { printf "\033[1;33m[warn]\033[0m  %s\n" "$*"; }
die()  { printf "\033[1;31m[fail]\033[0m  %s\n" "$*" >&2; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || die "$1 not found in PATH"; }

for bin in minikube kubectl docker helm mvn; do require "$bin"; done


# 1. Minikube
DOCKER_MEM_MB=$(( $(docker info --format '{{.MemTotal}}') / 1024 / 1024 ))
if (( DOCKER_MEM_MB < 6000 )); then
  warn "Docker has ${DOCKER_MEM_MB}MB of memory; the full demo needs about 6GB and may be unstable"
fi

log "Minikube profile = $MINIKUBE_PROFILE"
if ! minikube -p "$MINIKUBE_PROFILE" status >/dev/null 2>&1; then
  log "Starting Minikube (cpus=$MINIKUBE_CPUS, memory=$MINIKUBE_MEMORY)..."
  minikube start -p "$MINIKUBE_PROFILE" --driver=docker --cpus="$MINIKUBE_CPUS" --memory="$MINIKUBE_MEMORY"
else
  log "Minikube already running."
fi

log "Pointing docker CLI at Minikube's daemon (eval minikube docker-env)"
eval "$(minikube -p "$MINIKUBE_PROFILE" docker-env)"
kubectl config use-context "$MINIKUBE_PROFILE" >/dev/null


# 2. Images
if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
  warn "SKIP_BUILD=1 — skipping docker builds"
else
  log "Building flink-s3:1.19"
  docker build -f Dockerfile.flink -t flink-s3:1.19 .

  log "Building greenops/telemetry:latest"
  docker build -t greenops/telemetry:latest ./telemetry

  log "Building greenops/operator:latest (mvn package + docker build)"
  mvn -q -f operator/pom.xml clean package
  docker build -t greenops/operator:latest ./operator

  log "Building greenops/dashboard:latest"
  docker build -t greenops/dashboard:latest ./dashboard
fi


# 3. Namespace, Flink operator and object store

log "Applying namespace"
kubectl apply -f k8s/namespace.yaml

# SeaweedFS and the Flink pods both read their S3 credentials from this Secret,
# so it has to exist before either starts.
log "Applying object store credentials Secret"
kubectl apply -f k8s/object-store/secret.yaml

helm repo add --force-update "flink-kubernetes-operator-$FLINK_OPERATOR_VERSION" \
  "https://archive.apache.org/dist/flink/flink-kubernetes-operator-$FLINK_OPERATOR_VERSION/" >/dev/null
helm repo update >/dev/null

log "Installing/upgrading cert-manager $CERT_MANAGER_VERSION (Flink operator dep)"
if ! kubectl get ns cert-manager >/dev/null 2>&1; then
  kubectl apply -f "https://github.com/cert-manager/cert-manager/releases/download/$CERT_MANAGER_VERSION/cert-manager.yaml"
  kubectl -n cert-manager wait --for=condition=Available deploy --all --timeout=180s
fi

log "Installing/upgrading flink-kubernetes-operator $FLINK_OPERATOR_VERSION"
helm upgrade --install flink-kubernetes-operator \
  "flink-kubernetes-operator-$FLINK_OPERATOR_VERSION/flink-kubernetes-operator" \
  --version "$FLINK_OPERATOR_VERSION" \
  --namespace "$NS" --values k8s/flink/flink-operator-values.yaml \
  --wait --timeout 5m

log "Deploying SeaweedFS object store"
kubectl apply -f k8s/object-store/seaweedfs.yaml
kubectl -n "$NS" rollout status deployment/seaweedfs --timeout=3m


# 4. Bucket
# Flink cannot create the bucket on demand, so create it before any job starts.
log "Ensuring bucket '$BUCKET' exists"
if kubectl exec -n "$NS" deploy/seaweedfs -- sh -c 'echo "s3.bucket.list" | weed shell' 2>/dev/null \
    | grep -qw "$BUCKET"; then
  log "Bucket '$BUCKET' present"
else
  kubectl exec -n "$NS" deploy/seaweedfs -- sh -c "echo 's3.bucket.create -name $BUCKET' | weed shell" >/dev/null
  log "Bucket '$BUCKET' created"
fi


# 5. Workloads
log "Applying FlinkDeployment"
kubectl apply -f k8s/flink/flink-cluster.yaml

log "Applying telemetry"
kubectl apply -f k8s/telemetry/telemetry-deployment.yaml

log "Applying GreenOps CRD + RBAC + operator"
kubectl apply -f k8s/greenops/greenops-crd.yaml
kubectl apply -f k8s/greenops/greenops-rbac.yaml
kubectl apply -f k8s/greenops/greenops-deployment.yaml

log "Applying dashboard"
kubectl apply -f k8s/dashboard/dashboard-deployment.yaml

log "Applying monitoring stack"
kubectl create configmap greenops-grafana-dashboards \
  --from-file=greenops-dashboard.json=grafana/greenops-dashboard.json \
  -n "$NS" --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f k8s/monitoring/prometheus.yaml
kubectl apply -f k8s/monitoring/grafana.yaml

log "Applying GreenOpsController CR"
kubectl apply -f k8s/greenops/greenops-cr.yaml

# Pick up new images if the deployments already existed
kubectl -n "$NS" rollout restart deployment/greenops-telemetry deployment/greenops-operator deployment/greenops-dashboard >/dev/null


# 6. Wait for rollouts
log "Waiting for rollouts..."
kubectl -n "$NS" rollout status deployment/greenops-telemetry --timeout=3m
kubectl -n "$NS" rollout status deployment/greenops-operator  --timeout=3m
kubectl -n "$NS" rollout status deployment/greenops-dashboard --timeout=3m
kubectl -n "$NS" rollout status deployment/greenops-prometheus --timeout=3m
kubectl -n "$NS" rollout status deployment/greenops-grafana    --timeout=3m
kubectl -n "$NS" rollout status deployment/greenops-flink     --timeout=5m

log "Cluster state:"
kubectl get pods -n "$NS"
kubectl get greenopscontrollers -n "$NS"


# 7. Smoke test: dirty → clean
if [[ "${SKIP_TEST:-0}" == "1" ]]; then
  warn "SKIP_TEST=1 — skipping dirty/clean smoke test"
else
  log "=== SMOKE TEST: DIRTY GRID ==="
  ./scripts/demo.sh dirty
  log "=== SMOKE TEST: CLEAN GRID ==="
  ./scripts/demo.sh clean
fi


# Done
cat <<EOF

\033[1;32m✓ GreenOps is up.\033[0m

Useful next commands:
  # Follow operator logs
  kubectl logs -f deployment/greenops-operator -n $NS

  # Open the dashboard
  kubectl port-forward -n $NS svc/greenops-dashboard 8000:8000 &
  open http://localhost:8000

  # Open Grafana (anonymous viewer is enabled)
  kubectl port-forward -n $NS svc/greenops-grafana 3000:3000 &
  open http://localhost:3000

  # Toggle grid state manually
  ./scripts/simulate-dirty-grid.sh
  ./scripts/simulate-clean-grid.sh

  # Inspect savepoints in SeaweedFS
  kubectl exec -n $NS deploy/seaweedfs -- sh -c 'echo "fs.ls -l /buckets/$BUCKET/savepoints" | weed shell'
EOF
